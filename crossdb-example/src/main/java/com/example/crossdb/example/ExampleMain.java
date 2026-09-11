package com.example.crossdb.example;

import com.example.crossdb.CrossDb;
import com.example.crossdb.CrossDbUnsafeQueryException;
import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * 验证用的示例入口：连接两个库、跑几类典型查询，观察下推与跨库行为。
 *
 * <p>默认用两个内存 H2 当源库（零环境依赖，直接可跑）；换成真实库时改
 * {@link #userdb()} / {@link #orderdb()} 两个方法即可（见 realDataSource 模板）。
 *
 * <p>运行（WSL，参考根 README/AGENTS 的构建约定）：
 * <pre>
 *   mvn -q -DskipTests install                  # 先把 crossdb 装进本地仓库（一次）
 *   mvn -q -pl crossdb-example compile exec:java                # 跑本入口（exec 不触发编译，改完源码须带 compile）
 *   mvn -q -pl crossdb-example compile exec:java -Dcrossdb.debug=true   # 附物理计划
 * </pre>
 *
 * <p>注意：一个 {@link CrossDb} 实例同一时刻只支持一个在途查询（ResultSet 流式单遍）。
 */
public final class ExampleMain {

  public static void main(String[] args) throws Exception {
    // 源库只建一次（命名内存 H2 在 JVM 内存活，重复建表会冲突），多个 CrossDb 实例可复用
    DataSource userdb = userdb();
    DataSource orderdb = orderdb();

    // 构造参数（可选）：new CrossDb(fetchSize, rowLimit, bindBatchSize, bindParallelism[, queryTimeoutSeconds])
    // fetchSize=源库流式拉取批大小；rowLimit=行数熔断（超过即拒绝）；
    // bindBatchSize/bindParallelism=Bind Join 内表 IN 批大小与并发。
    try (CrossDb db = new CrossDb()) {
      db.register("userdb", userdb)
        .register("orderdb", orderdb);

      // 1) 单库点查：WHERE 谓词应整体下推到 userdb
      run(db, "单库点查",
          "SELECT name, city FROM userdb.users WHERE id = 1");

      // 2) 跨库 INNER JOIN：Batched IN Bind Join，内表按 join key 分批拉取
      run(db, "跨库 INNER JOIN",
          "SELECT u.name, o.id, o.amount FROM userdb.users u "
          + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id");

      // 3) 跨库 LEFT JOIN：orderdb 里 user_id=9 无对应用户，应补 NULL
      run(db, "跨库 LEFT JOIN",
          "SELECT o.id, u.name, o.amount FROM orderdb.orders o "
          + "LEFT JOIN userdb.users u ON u.id = o.user_id ORDER BY o.id");

      // 4) 跨库聚合：GROUP BY 在本地合并，两侧单列/单表拉取
      run(db, "跨库聚合",
          "SELECT u.city, COUNT(*) AS cnt, SUM(o.amount) AS total "
          + "FROM orderdb.orders o JOIN userdb.users u ON o.user_id = u.id "
          + "GROUP BY u.city ORDER BY u.city");

      // 5) 跨库 UNION ALL + Top-N：LIMIT 归并下推进每个分支
      run(db, "跨库 UNION ALL Top-N",
          "SELECT id FROM (SELECT id FROM userdb.users "
          + "UNION ALL SELECT id FROM orderdb.orders) t "
          + "ORDER BY id DESC LIMIT 3");

      // 6) explain：物理计划（跨库 JOIN 应能看到 BindJoin 算子）
      System.out.println("== explain: 跨库 JOIN 物理计划 ==");
      System.out.println(db.explain(
          "SELECT u.name, SUM(o.amount) FROM userdb.users u "
          + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name"));

      // 7) analyze：各源库实际收到的 SQL、网络行数、Bind Join 批次
      System.out.println("== analyze: 各源库实际下发 SQL ==");
      System.out.println(db.analyze(
          "SELECT u.name, SUM(o.amount) FROM userdb.users u "
          + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name"));

      // 8) safeMode：拒绝无过滤的跨库全表拉取，带 WHERE 的放行
      System.out.println("== safeMode: 全表拉取拦截 ==");
      try (CrossDb safe = new CrossDb().safeMode()) {
        safe.register("userdb", userdb).register("orderdb", orderdb);
        try {
          safe.query("SELECT id FROM userdb.users");
          System.out.println("   [异常] 裸全表扫描未被拦截");
        } catch (CrossDbUnsafeQueryException e) {
          System.out.println("   拦截: " + e.getMessage());
        }
        run(safe, "safeMode 点查放行", "SELECT name FROM userdb.users WHERE id = 2");
      }

      // TODO: 在下面加自己的验证 SQL（改完直接重跑 exec:java）
    }
  }

  // ---------- 源库构建（默认内存 H2，换真实库改这里） ----------

  /** 用户库：用户主数据。 */
  private static DataSource userdb() throws SQLException {
    return h2("example_users", new String[] {
        "CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(50), city VARCHAR(20))",
        "INSERT INTO users VALUES (1,'alice','北京'),(2,'bob','上海'),(3,'carol','深圳')",
    });
  }

  /** 订单库：外键指向用户库；user_id=9 故意无对应用户（验证外连接补 NULL）。 */
  private static DataSource orderdb() throws SQLException {
    return h2("example_orders", new String[] {
        "CREATE TABLE orders(id INT PRIMARY KEY, user_id INT, amount DECIMAL(10,2), made DATE)",
        "INSERT INTO orders VALUES"
            + " (100,1,99.90, DATE '2026-01-15'),"
            + " (101,1,25.00, DATE '2026-02-20'),"
            + " (102,2,120.50, DATE '2026-03-10'),"
            + " (103,9,7.30, DATE '2026-04-01')",
    });
  }

  /** 换真实库模板（MySQL/PostgreSQL 驱动已随 crossdb 传递引入，Oracle 需自行加 ojdbc11）：
   *
   * <pre>
   * MySQL:
   *   com.mysql.cj.jdbc.MysqlDataSource ds = new com.mysql.cj.jdbc.MysqlDataSource();
   *   ds.setURL("jdbc:mysql://host:3306/db?useSSL=false&amp;serverTimezone=UTC");
   *   ds.setUser("u"); ds.setPassword("p");
   * PostgreSQL:
   *   org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
   *   ds.setURL("jdbc:postgresql://host:5432/db");
   *   ds.setUser("u"); ds.setPassword("p");
   * </pre>
   */
  private static DataSource realDataSource() {
    throw new UnsupportedOperationException("模板方法：按注释替换后使用");
  }

  /** 建一个内存 H2 并执行初始化 DDL（按分号拆分逐条执行）。 */
  private static DataSource h2(String name, String[] ddl) throws SQLException {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
      for (String stmt : ddl) {
        s.execute(stmt);
      }
    }
    return ds;
  }

  // ---------- 输出工具 ----------

  /** 执行查询并按「列 1 | 列 2 | ...」打印每一行。 */
  private static void run(CrossDb db, String title, String sql) throws SQLException {
    System.out.println("== " + title + " ==");
    System.out.println("SQL: " + sql);
    try (ResultSet rs = db.query(sql)) {
      ResultSetMetaData md = rs.getMetaData();
      int n = md.getColumnCount();
      int rows = 0;
      while (rs.next()) {
        StringBuilder line = new StringBuilder("   ");
        for (int i = 1; i <= n; i++) {
          if (i > 1) {
            line.append(" | ");
          }
          line.append(rs.getObject(i));
        }
        System.out.println(line);
        rows++;
      }
      System.out.println("   共 " + rows + " 行");
    }
  }

  private ExampleMain() {}
}
