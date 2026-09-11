package com.example.crossdb;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedTest {

  @Test void limitAllowsExactlyThreshold() throws Exception {
    try (Connection c = Fixtures.USERS.getConnection();
        Statement s = c.createStatement();
        ResultSet raw = s.executeQuery("SELECT id FROM users")) {
      ResultSet rs = Guarded.limit(raw, 3);
      int n = 0;
      while (rs.next()) {
        n++;
      }
      assertEquals(3, n);
    }
  }

  @Test void limitThrowsPastThreshold() throws Exception {
    try (Connection c = Fixtures.USERS.getConnection();
        Statement s = c.createStatement();
        ResultSet raw = s.executeQuery("SELECT id FROM users")) {
      ResultSet rs = Guarded.limit(raw, 2);
      assertTrue(rs.next());
      assertTrue(rs.next());
      SQLException e = assertThrows(SQLException.class, rs::next);
      assertTrue(e.getMessage().contains("熔断"), e.getMessage());
    }
  }

  @Test void limitPassesThroughNonNextMethods() throws Exception {
    try (Connection c = Fixtures.USERS.getConnection();
        Statement s = c.createStatement();
        ResultSet raw = s.executeQuery("SELECT id FROM users WHERE id = 1")) {
      ResultSet rs = Guarded.limit(raw, 2);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertFalse(rs.next());
    }
  }

  @Test void wrapConfiguresStatementsAndGuardsRows() throws Exception {
    DataSource ds = Guarded.wrap(Fixtures.USERS, 7, 2);
    try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
      assertEquals(7, s.getFetchSize());
      assertEquals(3, s.getMaxRows());
      ResultSet rs = s.executeQuery("SELECT id FROM users");
      assertTrue(rs.next());
      assertTrue(rs.next());
      SQLException e = assertThrows(SQLException.class, rs::next);
      assertTrue(e.getMessage().contains("熔断"), e.getMessage());
    }
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE id = ?")) {
      assertEquals(7, ps.getFetchSize());
      assertEquals(3, ps.getMaxRows());
      ps.setInt(1, 1);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertFalse(rs.next());
      }
    }
  }

  @Test void statementsRegisterForCancelAndUnregisterOnClose() throws Exception {
    Stats stats = new Stats();
    DataSource ds = Guarded.wrap(Fixtures.USERS, 100, 10, "userdb", stats, 0);
    try (Connection c = ds.getConnection();
        PreparedStatement s = c.prepareStatement("SELECT id FROM users WHERE id = ?")) {
      assertEquals(1, stats.live.size(), "语句创建即注册供级联取消");
      s.setInt(1, 1);
      try (ResultSet rs = s.executeQuery()) {
        assertTrue(rs.next());
      }
      stats.cancelAll(); // 对已完成语句 cancel 是无害空操作
      assertEquals(1, stats.live.size());
    }
    assertTrue(stats.live.isEmpty(), "语句关闭后应自动注销");
  }

  @Test void wrapPropagatesTimeoutAndRecordsStats() throws Exception {
    Stats stats = new Stats();
    DataSource ds = Guarded.wrap(Fixtures.USERS, 7, 100, "userdb", stats, 5);
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE id = ?")) {
      assertEquals(5, ps.getQueryTimeout());
      ps.setInt(1, 1);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertFalse(rs.next());
      }
    }
    assertEquals(1, stats.schemas.size(), "应记录 userdb 统计");
    Stats.Schema schema = stats.schemas.get("userdb");
    assertEquals(1, schema.rows.sum(), "应记录拉取行数 1");
    assertEquals(1, schema.sqls.size(), "应记录下发 SQL");
    assertTrue(schema.sqls.iterator().next().contains("users"),
        String.valueOf(schema.sqls));
  }

  /** Oracle 同义词元数据：getConnection 取到的 Oracle 连接须开启
   * includeSynonyms（默认 false 时 getColumns 对同义词返回空列集，Calcite 会
   * 注册出零列行型的表——SELECT * 透传可跑、列引用校验失败）。 */
  @Test void oracleConnectionEnablesSynonymMetadata() throws Exception {
    java.util.List<Boolean> synonyms = new java.util.ArrayList<>();
    Connection oracleLike = (Connection) Proxy.newProxyInstance(
        GuardedTest.class.getClassLoader(),
        new Class<?>[]{Connection.class, oracle.jdbc.OracleConnection.class},
        (p, m, a) -> {
          if (m.getName().equals("setIncludeSynonyms")) {
            synonyms.add((Boolean) a[0]);
            return null;
          }
          return defaultValue(m.getReturnType());
        });
    DataSource oracleDs = (DataSource) Proxy.newProxyInstance(
        GuardedTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
        (p, m, a) -> m.getName().equals("getConnection")
            ? oracleLike : defaultValue(m.getReturnType()));
    DataSource wrapped = Guarded.wrap(oracleDs, 100, 10, "oradb", new Stats(), 0);
    try (Connection c = wrapped.getConnection()) {
      assertFalse(c.isClosed());
    }
    assertEquals(java.util.List.of(Boolean.TRUE), synonyms,
        "Oracle 连接获取时应开启 includeSynonyms");
  }

  /** 非 Oracle 连接（H2）不受反射开关影响：现有 wrap 系列用例均以 H2 走通同一
   * 代码路径（classpath 存在测试桩 oracle.jdbc.OracleConnection 时 isInstance
   * 拦下），此处显式断言一次开关未被误开。 */
  @Test void nonOracleConnectionSkipsSynonymToggle() throws Exception {
    Connection h2 = Fixtures.USERS.getConnection();
    DataSource ds = (DataSource) Proxy.newProxyInstance(
        GuardedTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
        (p, m, a) -> m.getName().equals("getConnection")
            ? h2 : defaultValue(m.getReturnType()));
    DataSource wrapped = Guarded.wrap(ds, 100, 10, "userdb", new Stats(), 0);
    try (Connection c = wrapped.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM users WHERE id = 1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0f;
    }
    if (type == double.class) {
      return 0d;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
