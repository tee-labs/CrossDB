package com.example.crossdb;

import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 共享内存 H2 夹具：每个 JVM 初始化一次，数据只读。 */
final class Fixtures {
  static final JdbcDataSource USERS = init("users", """
      CREATE TABLE IF NOT EXISTS users(id INT PRIMARY KEY, name VARCHAR(50));
      CREATE TABLE IF NOT EXISTS small(id INT PRIMARY KEY);
      INSERT INTO users VALUES (1,'alice'),(2,'bob'),(3,'carol');
      INSERT INTO small VALUES (1),(2);
      """);
  static final JdbcDataSource ORDERS = init("orders", """
      CREATE TABLE IF NOT EXISTS orders(id INT PRIMARY KEY, user_id INT, amount INT);
      INSERT INTO orders VALUES (100,1,10),(101,2,20),(102,1,5),(103,2,1);
      """);
  /** 复合键夹具：(user_id, tenant_id) 两列联合 join。 */
  static final JdbcDataSource CREDS = init("creds", """
      CREATE TABLE IF NOT EXISTS creds(user_id INT, tenant_id INT, login VARCHAR(20));
      INSERT INTO creds VALUES (1,100,'a1'),(2,100,'b1'),(1,200,'a2'),(3,100,'c1');
      """);
  static final JdbcDataSource QUOTAS = init("quotas", """
      CREATE TABLE IF NOT EXISTS quotas(tenant_id INT, user_id INT, quota INT);
      INSERT INTO quotas VALUES (100,1,10),(100,2,20),(200,1,5);
      """);
  /** RIGHT/FULL 夹具：id=2 的事件无对应用户（user_id=9），验证补 NULL 与 FULL 反连接。 */
  static final JdbcDataSource EVENTS = init("events", """
      CREATE TABLE IF NOT EXISTS events(id INT PRIMARY KEY, user_id INT);
      INSERT INTO events VALUES (1,1),(2,9);
      """);
  /** 内表 NULL key + 常用类型列夹具：user_id 含 NULL（验证 FULL 反连接不丢 NULL key 行），
   * note/amount/ts/flag 含 NULL（验证 ResultSet 取值 API 与 wasNull）。 */
  static final JdbcDataSource PINGS = init("pings", """
      CREATE TABLE IF NOT EXISTS pings(id INT PRIMARY KEY, user_id INT, note VARCHAR(20),
        amount DECIMAL(10,2), ts TIMESTAMP, flag BOOLEAN);
      INSERT INTO pings VALUES
        (1, 1, 'a', 1.25, TIMESTAMP '2026-01-02 03:04:05', TRUE),
        (2, 9, 'b', NULL, NULL, NULL),
        (3, NULL, NULL, 3.5, TIMESTAMP '2026-02-03 04:05:06', FALSE);
      """);

  private Fixtures() {}

  private static JdbcDataSource init(String name, String ddl) {
    JdbcDataSource d = new JdbcDataSource();
    d.setURL("jdbc:h2:mem:crossdb_" + name + ";DB_CLOSE_DELAY=-1");
    try (Connection c = d.getConnection(); Statement s = c.createStatement()) {
      for (String part : ddl.split(";")) {
        if (!part.isBlank()) {
          s.execute(part);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("夹具初始化失败: " + name, e);
    }
    return d;
  }
}
