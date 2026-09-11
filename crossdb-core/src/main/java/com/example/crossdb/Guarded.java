package com.example.crossdb;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 危险 SQL 熔断 + fetchSize 流式拉取 + queryTimeout 超时传播 + 执行统计。
 *
 * <p>注册进 Calcite 的 DataSource 会被包一层代理：每条语句带上
 * fetchSize（流式拉取）、maxRows=上限+1（驱动侧封顶）与 queryTimeout（超时传播到
 * 源库，掐断慢查询防连接池耗尽），并给所有 ResultSet 加计数代理，拉到的行一旦
 * 超过阈值就抛异常拒绝执行；同时把实际下发的 SQL 与拉取行数记入 {@link Stats}。
 */
final class Guarded {
  private Guarded() {}

  static DataSource wrap(DataSource delegate, int fetchSize, long maxRows) {
    return wrap(delegate, fetchSize, maxRows, null, null, 0);
  }

  static DataSource wrap(DataSource delegate, int fetchSize, long maxRows, String schema,
      Stats stats, int queryTimeout) {
    return (DataSource) Proxy.newProxyInstance(Guarded.class.getClassLoader(),
        new Class<?>[]{DataSource.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConnection")) {
            return connectionProxy((Connection) invoke(method, delegate, args),
                fetchSize, maxRows, schema, stats, queryTimeout);
          }
          return invoke(method, delegate, args);
        });
  }

  private static Connection connectionProxy(Connection target, int fetchSize, long maxRows,
      String schema, Stats stats, int queryTimeout) {
    enableOracleSynonyms(target);
    return (Connection) Proxy.newProxyInstance(Guarded.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (proxy, method, args) -> {
          Object result = invoke(method, target, args);
          String name = method.getName();
          if (result instanceof Statement
              && (name.equals("createStatement") || name.startsWith("prepareStatement")
                  || name.startsWith("prepareCall"))) {
            Statement st = (Statement) result;
            st.setFetchSize(fetchSize);
            st.setMaxRows((int) Math.min(maxRows + 1, Integer.MAX_VALUE));
            if (queryTimeout > 0) {
              st.setQueryTimeout(queryTimeout);
            }
            if (stats != null && name.startsWith("prepare") && args != null && args.length > 0) {
              stats.sql(schema, String.valueOf(args[0]));
            }
            Class<?> iface =
                name.equals("createStatement") ? Statement.class : PreparedStatement.class;
            return statementProxy(st, iface, maxRows, schema, stats);
          }
          return result;
        });
  }

  private static Statement statementProxy(Statement target, Class<?> iface, long maxRows,
      String schema, Stats stats) {
    if (stats != null) {
      stats.live.add(target);
    }
    return (Statement) Proxy.newProxyInstance(Guarded.class.getClassLoader(),
        new Class<?>[]{iface},
        (proxy, method, args) -> {
          Object result = invoke(method, target, args);
          String name = method.getName();
          if (stats != null && name.equals("close")) {
            stats.live.remove(target);
          }
          if (stats != null
              && (name.equals("executeQuery") || name.equals("execute"))
              && args != null && args.length > 0 && args[0] instanceof String sql) {
            stats.sql(schema, sql);
          }
          return result instanceof ResultSet
              ? limit((ResultSet) result, maxRows, schema, stats) : result;
        });
  }

  static ResultSet limit(ResultSet target, long maxRows) {
    return limit(target, maxRows, null, null);
  }

  static ResultSet limit(ResultSet target, long maxRows, String schema, Stats stats) {
    long[] seen = {0};
    return (ResultSet) Proxy.newProxyInstance(Guarded.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        (proxy, method, args) -> {
          Object result = invoke(method, target, args);
          if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
            if (stats != null && schema != null) {
              stats.rows(schema, 1);
            }
            if (++seen[0] > maxRows) {
              throw new SQLException("crossdb: 拉取行数超过阈值 " + maxRows
                  + "，拒绝继续执行（危险 SQL 熔断）");
            }
          }
          return result;
        });
  }

  /** Oracle 同义词元数据开关（反射解析 {@code oracle.jdbc.OracleConnection#
   * setIncludeSynonyms}，不引入 ojdbc 依赖；classpath 无 ojdbc 时为 null）。
   * Oracle JDBC 默认 includeSynonyms=false：getColumns 对同义词名返回空列集，而
   * Calcite 的 JdbcSchema 枚举表不过滤表类型（types=null），会把 getTables 列出的
   * 同义词注册成零列行型的表——SELECT * 恰好因「空投影恒等 star」透传可执行，
   * 但任何列引用（WHERE/SELECT 列清单）都校验失败 Column not found。对 CrossDb
   * 经由此 DataSource 拿到的 Oracle 连接统一开启，使同义词取得与真实表一致的
   * 列元数据。连接池返回的代理未实现 OracleConnection 接口时静默跳过（此时可在
   * 池配置中直接设 includeSynonyms=true）。 */
  private static final Method ORACLE_INCLUDE_SYNONYMS = lookupOracleIncludeSynonyms();

  private static Method lookupOracleIncludeSynonyms() {
    try {
      return Class.forName("oracle.jdbc.OracleConnection")
          .getMethod("setIncludeSynonyms", boolean.class);
    } catch (ReflectiveOperationException e) {
      return null;   // classpath 无 ojdbc：非 Oracle 源库
    }
  }

  private static void enableOracleSynonyms(Connection target) {
    if (ORACLE_INCLUDE_SYNONYMS == null
        || !ORACLE_INCLUDE_SYNONYMS.getDeclaringClass().isInstance(target)) {
      return;
    }
    try {
      ORACLE_INCLUDE_SYNONYMS.invoke(target, true);
    } catch (ReflectiveOperationException | RuntimeException e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("Guarded: 开启 Oracle includeSynonyms 失败: " + e);
      }
    }
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }
}
