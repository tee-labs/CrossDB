package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 计划直接执行：用 {@link EnumerableInterpretable#toBindable} 跑优化后的 Enumerable 树，
 * 绕过 RelRunner 的二次 prepare。
 *
 * <p>修复：二次 prepare 会对已优化的树重新做字段裁剪与约定推导，含 WHERE/LIMIT 的
 * 跨库查询会抛 CannotPlanException / EnumerableLimit 断言失败。执行结果以 ResultSet
 * 动态代理暴露（见 {@link #resultSet}：取值方法全集合 + wasNull + 最小元数据）。
 */
final class Exec {
  private Exec() {}

  static ResultSet query(CalciteConnection connection, RelNode plan) throws SQLException {
    RelNode rel = unwrap(plan);
    // params 承接计划生成期暂存的对象（如递归 CTE 的 TransientTable）：生成的代码
    // 在 bind 期通过 DataContext.get("vNstashed") 取回，context() 必须按名供给
    Map<String, Object> params = new HashMap<>();
    Bindable bindable = EnumerableInterpretable.toBindable(params, spark(),
        (EnumerableRel) rel, EnumerableRel.Prefer.ARRAY);
    Enumerable<Object[]> rows;
    try {
      rows = (Enumerable<Object[]>) bindable.bind(context(connection, params));
    } catch (SQLException e) {
      throw e;
    } catch (RuntimeException e) {
      // 计划期已定的执行形态（如 SINGLE_VALUE 多行标量子查询校验）以受检异常统一暴露
      throw new SQLException("crossdb: 查询执行失败: " + e.getMessage(), e);
    }
    return resultSet(rows, rel.getRowType(), connection.getTypeFactory());
  }

  private static RelNode unwrap(RelNode n) {
    while (n instanceof RelSubset s) {
      n = s.getBestOrOriginal();
    }
    return n;
  }

  private static CalcitePrepare.SparkHandler spark() {
    return new CalcitePrepare.SparkHandler() {
      @Override public RelNode flattenTypes(RelOptPlanner planner, RelNode rel, boolean b) {
        return rel;
      }

      @Override public void registerRules(RuleSetBuilder builder) {
      }

      @Override public boolean enabled() {
        return false;
      }

      @Override public org.apache.calcite.runtime.ArrayBindable compile(
          org.apache.calcite.linq4j.tree.ClassDeclaration expr, String s) {
        throw new UnsupportedOperationException();
      }

      @Override public Object sparkContext() {
        return null;
      }
    };
  }

  private static org.apache.calcite.DataContext context(CalciteConnection connection,
      Map<String, Object> stashed) throws SQLException {
    JavaTypeFactory typeFactory = connection.getTypeFactory();
    SchemaPlus rootSchema = connection.getRootSchema();
    return new org.apache.calcite.DataContext() {
      @Override public SchemaPlus getRootSchema() {
        return rootSchema;
      }

      @Override public JavaTypeFactory getTypeFactory() {
        return typeFactory;
      }

      @Override public org.apache.calcite.linq4j.QueryProvider getQueryProvider() {
        return null;
      }

      @Override public Object get(String name) {
        return stashed.get(name);
      }
    };
  }

  /** 结果集动态代理：next/close/wasNull/getInt/getLong/getString/getObject/findColumn
   * 之外，补齐常用类型化取值（getBigDecimal/getTimestamp/getDate/getTime/getBoolean/
   * getDouble/getFloat/getByte/getShort/getBytes）与 getMetaData（按 rowType 生成
   * 最小元数据），让 Spring JdbcTemplate / MyBatis 等依赖元数据的框架可用。
   * getXxx 后可查询 wasNull；NULL 的数值取 0（JDBC 语义）。 */
  private static ResultSet resultSet(Enumerable<Object[]> rows, RelDataType rowType,
      JavaTypeFactory typeFactory) {
    Iterator<Object[]> it = rows.iterator();
    Object[] current = {null};
    boolean[] wasNull = {false};
    List<String> names = rowType.getFieldNames();
    // 元数据按 rowType 一次性物化，代理只做查表
    List<RelDataTypeField> fields = rowType.getFieldList();
    int n = fields.size();
    int[] jdbcTypes = new int[n];
    String[] typeNames = new String[n];
    int[] precisions = new int[n];
    int[] scales = new int[n];
    boolean[] nullables = new boolean[n];
    String[] classNames = new String[n];
    for (int i = 0; i < n; i++) {
      RelDataType t = fields.get(i).getType();
      SqlTypeName st = t.getSqlTypeName();
      jdbcTypes[i] = st.getJdbcOrdinal();
      typeNames[i] = st.getName();
      precisions[i] = Math.max(t.getPrecision(), 0);
      scales[i] = t.getScale();
      nullables[i] = t.isNullable();
      try {
        java.lang.reflect.Type jtype = typeFactory.getJavaClass(t);
        classNames[i] = jtype instanceof Class<?> c ? c.getName() : String.valueOf(jtype);
      } catch (Exception e) {
        classNames[i] = Object.class.getName();
      }
    }
    return (ResultSet) Proxy.newProxyInstance(Exec.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        (proxy, method, args) -> {
          Object[] cur = current[0] instanceof Object[] a ? a : null;
          return switch (method.getName()) {
            case "next" -> {
              boolean hasNext;
              try {
                hasNext = it.hasNext();
                if (hasNext) {
                  Object r = it.next();
                  // 单列行的 Enumerable 用裸标量表示，统一归一为 Object[]
                  current[0] = r instanceof Object[] arr ? arr : new Object[]{r};
                } else {
                  current[0] = null;
                }
              } catch (RuntimeException e) {
                // 迭代期执行失败（源库错误、Bind Join 批次、熔断等）统一以
                // SQLException 暴露，根因挂 cause 链保留
                throw new SQLException("crossdb: 结果集迭代失败: " + e.getMessage(), e);
              }
              yield hasNext;
            }
            case "close" -> {
              if (it instanceof AutoCloseable closeable) {
                try {
                  closeable.close();
                } catch (Exception ignored) {
                  // 底层迭代器关闭失败不向上传播
                }
              }
              yield null;
            }
            case "wasNull" -> wasNull[0];
            case "isClosed" -> false;
            case "getInt" -> num(cur, wasNull, names, args).intValue();
            case "getLong" -> num(cur, wasNull, names, args).longValue();
            case "getByte" -> num(cur, wasNull, names, args).byteValue();
            case "getShort" -> num(cur, wasNull, names, args).shortValue();
            case "getFloat" -> (float) num(cur, wasNull, names, args).doubleValue();
            case "getDouble" -> num(cur, wasNull, names, args).doubleValue();
            case "getBoolean" -> booleanOf(cur, wasNull, names, args);
            case "getBigDecimal" -> decimalOf(cur, wasNull, names, args);
            case "getTimestamp" -> temporal(cur, wasNull, names, args, Timestamp.class);
            case "getDate" -> temporal(cur, wasNull, names, args, Date.class);
            case "getTime" -> temporal(cur, wasNull, names, args, Time.class);
            case "getBytes" -> {
              Object v = val(cur, wasNull, names, args);
              if (v != null && !(v instanceof byte[])) {
                throw new SQLException("crossdb: 列值不是 byte[]（实际 "
                    + v.getClass().getName() + "），getBytes 不支持");
              }
              yield v;
            }
            case "getString" -> {
              Object v = val(cur, wasNull, names, args);
              yield v == null ? null : String.valueOf(v);
            }
            case "getObject" -> val(cur, wasNull, names, args);
            case "findColumn" -> findColumn(names, String.valueOf(args[0]));
            case "getMetaData" -> metadataProxy(n, names, jdbcTypes, typeNames, precisions,
                scales, nullables, classNames);
            default -> throw new SQLException("crossdb: ResultSet 不支持 " + method.getName());
          };
        });
  }

  /** 列定位：args[0] 为下标（getXxx(int)）或列名（getXxx(String)）。 */
  private static int colIdx(Object[] args, List<String> names) throws SQLException {
    if (args[0] instanceof Number num) {
      return num.intValue() - 1;
    }
    return findColumn(names, String.valueOf(args[0])) - 1;
  }

  /** 取当前行列值并记录 wasNull；未 next() 就取列时报错。 */
  private static Object val(Object[] cur, boolean[] wasNull, List<String> names, Object[] args)
      throws SQLException {
    if (cur == null) {
      throw new SQLException("crossdb: 无当前行，请先调用 next()");
    }
    Object v = cur[colIdx(args, names)];
    wasNull[0] = v == null;
    return v;
  }

  private static Number num(Object[] cur, boolean[] wasNull, List<String> names, Object[] args)
      throws SQLException {
    Object v = val(cur, wasNull, names, args);
    if (v == null) {
      return 0; // JDBC 语义：NULL 的数值型取 0，由 wasNull() 区分
    }
    if (v instanceof Number num) {
      return num;
    }
    throw new SQLException("crossdb: 列值不是数值（实际 " + v.getClass().getName() + "）");
  }

  private static boolean booleanOf(Object[] cur, boolean[] wasNull, List<String> names,
      Object[] args) throws SQLException {
    Object v = val(cur, wasNull, names, args);
    return switch (v) {
      case null -> false;
      case Boolean b -> b;
      case Number num -> num.doubleValue() != 0;
      case String s -> Boolean.parseBoolean(s);
      default -> throw new SQLException("crossdb: 列值无法转 boolean（实际 "
          + v.getClass().getName() + "）");
    };
  }

  private static BigDecimal decimalOf(Object[] cur, boolean[] wasNull, List<String> names,
      Object[] args) throws SQLException {
    Object v = val(cur, wasNull, names, args);
    if (v == null) {
      return null;
    }
    BigDecimal d = switch (v) {
      case BigDecimal bd -> bd;
      case Float f -> BigDecimal.valueOf(f.doubleValue());
      case Double dbl -> BigDecimal.valueOf(dbl);
      case Number num -> new BigDecimal(num.toString());
      case String s -> new BigDecimal(s);
      default -> throw new SQLException("crossdb: 列值无法转 BigDecimal（实际 "
          + v.getClass().getName() + "）");
    };
    // JDBC 语义：指定 scale 收窄精度时按 HALF_UP 舍入（1.25 → scale 1 → 1.3）
    return args.length > 1
        ? d.setScale(((Number) args[1]).intValue(), java.math.RoundingMode.HALF_UP) : d;
  }

  /** 时间型统一转换：target 为 Timestamp/Date/Time 之一，NULL 返回 null；
   * 数值（Calcite 内部 epoch millis）按时间戳处理。 */
  private static Object temporal(Object[] cur, boolean[] wasNull, List<String> names,
      Object[] args, Class<?> target) throws SQLException {
    Object v = val(cur, wasNull, names, args);
    if (v == null) {
      return null;
    }
    try {
      if (target == Timestamp.class) {
        return switch (v) {
          case Timestamp t -> t;
          case java.util.Date d -> new Timestamp(d.getTime());
          // Calcite 内部以 epoch millis（Long）承载 TIMESTAMP
          case Number n -> new Timestamp(n.longValue());
          case LocalDateTime dt -> Timestamp.valueOf(dt);
          case String s -> Timestamp.valueOf(s);
          default -> null;
        };
      }
      if (target == Date.class) {
        return switch (v) {
          case Date d -> d;
          case Timestamp t -> dayOf(t);
          case java.util.Date d -> dayOf(d);
          case Number n -> dayOf(new java.util.Date(n.longValue()));
          case LocalDate d -> Date.valueOf(d);
          case String s -> Date.valueOf(s);
          default -> null;
        };
      }
      return switch (v) {
        case Time t -> t;
        case Timestamp t -> timeOf(t);
        case java.util.Date d -> timeOf(d);
        case Number n -> timeOf(new java.util.Date(n.longValue()));
        case LocalTime t -> Time.valueOf(t);
        case String s -> Time.valueOf(s);
        default -> null;
      };
    } catch (IllegalArgumentException e) {
      throw new SQLException("crossdb: 列值无法按 JDBC 时间格式解析（实际 "
          + v.getClass().getName() + "）");
    }
  }

  /** 截到当日 0 点（JDBC getDate 对时间戳的语义：保留日期部分）。 */
  @SuppressWarnings("deprecation")
  private static Date dayOf(java.util.Date d) {
    return new Date(d.getYear(), d.getMonth(), d.getDate());
  }

  /** 截到当日时分秒（JDBC getTime 对时间戳的语义：保留时间部分）。 */
  @SuppressWarnings("deprecation")
  private static Time timeOf(java.util.Date d) {
    return new Time(d.getHours(), d.getMinutes(), d.getSeconds());
  }

  private static int findColumn(List<String> names, String column) throws SQLException {
    int i = names.indexOf(column);
    if (i < 0) {
      // 列名按校验行型保留书写形态（别名/小写），查找做大小写不敏感回退
      i = names.indexOf(column.toLowerCase());
    }
    if (i < 0) {
      for (int j = 0; j < names.size(); j++) {
        if (names.get(j).equalsIgnoreCase(column)) {
          i = j;
          break;
        }
      }
    }
    if (i < 0) {
      throw new SQLException("crossdb: 列不存在 " + column + "（可用列 " + names + "）");
    }
    return i + 1;
  }

  /** 最小 ResultSetMetaData：列数/名称/类型/精度/可空等框架常用项；
   * 其余方法显式抛「不支持」，不静默返回假值。 */
  private static ResultSetMetaData metadataProxy(int count, List<String> names,
      int[] jdbcTypes, String[] typeNames, int[] precisions, int[] scales,
      boolean[] nullables, String[] classNames) {
    return (ResultSetMetaData) Proxy.newProxyInstance(Exec.class.getClassLoader(),
        new Class<?>[]{ResultSetMetaData.class},
        (proxy, method, args) -> {
          int i = args != null && args.length > 0 && args[0] instanceof Number num
              ? num.intValue() - 1 : -1;
          return switch (method.getName()) {
            case "getColumnCount" -> count;
            case "getColumnName", "getColumnLabel" -> names.get(i);
            case "getColumnType" -> jdbcTypes[i];
            case "getColumnTypeName" -> typeNames[i];
            case "getColumnClassName" -> classNames[i];
            case "getPrecision" -> precisions[i];
            case "getScale" -> scales[i];
            case "isNullable" -> nullables[i] ? ResultSetMetaData.columnNullable
                : ResultSetMetaData.columnNoNulls;
            case "isAutoIncrement", "isCurrency", "isWritable", "isDefinitelyWritable" -> false;
            case "isSigned" -> jdbcTypes[i] == Types.NUMERIC || jdbcTypes[i] == Types.DECIMAL
                || jdbcTypes[i] == Types.TINYINT || jdbcTypes[i] == Types.SMALLINT
                || jdbcTypes[i] == Types.INTEGER || jdbcTypes[i] == Types.BIGINT
                || jdbcTypes[i] == Types.REAL || jdbcTypes[i] == Types.FLOAT
                || jdbcTypes[i] == Types.DOUBLE;
            case "isCaseSensitive" -> false;
            case "isSearchable" -> true;
            case "isReadOnly" -> true;
            case "getColumnDisplaySize" -> Math.max(precisions[i], 1);
            default -> throw new SQLException("crossdb: ResultSetMetaData 不支持 "
                + method.getName() + "（仅支持列数/名称/类型/精度/可空等基础元数据）");
          };
        });
  }
}
