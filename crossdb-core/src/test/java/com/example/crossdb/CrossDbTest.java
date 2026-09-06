package com.example.crossdb;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CrossDbTest {

  private static final String JOIN_SQL =
      "SELECT u.name, COUNT(*) AS cnt, SUM(o.amount) AS total "
      + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
      + "GROUP BY u.name ORDER BY u.name";

  private static Map<String, String> run(CrossDb db, String sql) throws SQLException {
    try (ResultSet rs = db.register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS).query(sql)) {
      Map<String, String> m = new LinkedHashMap<>();
      while (rs.next()) {
        m.put(rs.getString(1), rs.getInt(2) + "," + rs.getInt(3));
      }
      return m;
    }
  }

  @Test void crossDbJoinGroupBy() throws Exception {
    try (CrossDb db = new CrossDb()) {
      Map<String, String> m = run(db, JOIN_SQL);
      assertEquals("2,15", m.get("alice"));
      assertEquals("2,21", m.get("bob"));
      assertFalse(m.containsKey("carol"));
    }
  }

  @Test void bindJoinPushesInInsteadOfInnerFullScan() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    Map<String, String> m;
    try (CrossDb db = new CrossDb(1000, 1_000_000L, 500, 2)) {
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders)
          .query(JOIN_SQL)) {
        m = new LinkedHashMap<>();
        while (rs.next()) {
          m.put(rs.getString(1), rs.getInt(2) + "," + rs.getInt(3));
        }
      }
    }
    assertEquals(Map.of("alice", "2,15", "bob", "2,21"), m);
    long inCount = sqls.stream().filter(s -> s.contains("IN (")).count();
    assertEquals(1, inCount, "应有且仅有 1 条 IN 下推: " + sqls);
    assertTrue(sqls.stream().anyMatch(s -> s.contains("IN (?,?)")),
        "两个 key 应合并进一个 IN 批次: " + sqls);
    assertEquals(2, sqls.size(), "内表 IN 查询 + 驱动侧全量拉取各一条: " + sqls);
  }

  @Test void compositeJoinConditionFallsBackToNative() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      Map<String, String> m = new LinkedHashMap<>();
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders).query(
          "SELECT u.name, COUNT(*) AS cnt FROM userdb.users u JOIN orderdb.orders o "
          + "ON o.user_id = u.id AND o.amount > u.id GROUP BY u.name ORDER BY u.name")) {
        while (rs.next()) {
          m.put(rs.getString(1), rs.getString(2));
        }
      }
      assertEquals(Map.of("alice", "2", "bob", "1"), m);
      assertTrue(sqls.stream().noneMatch(s -> s.contains("IN (")),
          "复合条件不应触发 Bind Join: " + sqls);
    }
  }

  @Test void crossDbLeftJoinKeepsUnmatchedAndBindsInner() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      Map<String, String> m = new LinkedHashMap<>();
      try (ResultSet rs = db.register("userdb", users)
          .register("orderdb", orders).query(
          "SELECT u.name, COUNT(o.id) AS cnt FROM userdb.users u "
          + "LEFT JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name ORDER BY u.name")) {
        while (rs.next()) {
          m.put(rs.getString(1), rs.getString(2));
        }
      }
      assertEquals(Map.of("alice", "2", "bob", "2", "carol", "0"), m);
      long inCount = sqls.stream().filter(s -> s.contains(" IN (")).count();
      long fullCount = sqls.stream().filter(s -> !s.contains(" IN (")).count();
      assertEquals(1, inCount, "LEFT JOIN 内表应走 IN 下推: " + sqls);
      assertEquals(1, fullCount, "只有驱动侧一条拉取，内表不应全量拉取: " + sqls);
    }
  }

  @Test void whereClauseOnCrossDbJoinWorksAndPushesDown() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders).query(
          "SELECT u.name FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
          + "WHERE u.name = 'alice'")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      assertEquals(List.of("alice", "alice"), names);
      assertTrue(sqls.stream().anyMatch(s -> s.contains("'alice'")),
          "过滤条件应下推到源库: " + sqls);
    }
  }

  @Test void transitivePredicatePushedToBothSides() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      int n = 0;
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders).query(
          "SELECT u.name FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
          + "WHERE u.id = 1")) {
        while (rs.next()) {
          n++;
        }
      }
      assertEquals(2, n);
      // WHERE u.id = 1 沿 join key 传递：orders 侧 SQL 应包含 user_id = 1
      assertTrue(sqls.stream().anyMatch(s -> s.contains("ORDERS") && s.contains("= 1")),
          "传递谓词应下推到 orders 侧: " + sqls);
    }
  }

  @Test void topNPushesOrderAndLimitIntoDriverSql() throws Exception {
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<Integer> ids = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS).register("orderdb", orders)
          .query("SELECT o.id FROM orderdb.orders o JOIN userdb.users u ON o.user_id = u.id "
              + "ORDER BY o.id DESC LIMIT 2")) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
      assertEquals(List.of(103, 102), ids);
      String ordersSql = sqls.stream().filter(s -> s.contains("ORDERS")).findFirst().orElse("");
      assertTrue(ordersSql.contains("ORDER BY"), "驱动侧应带 ORDER BY: " + sqls);
      assertTrue(ordersSql.contains("LIMIT") || ordersSql.contains("FETCH"),
          "驱动侧应带 LIMIT/FETCH: " + sqls);
    }
  }

  @Test void compositeKeyBindJoinAcrossDbs() throws Exception {
    try (CrossDb db = new CrossDb()) {
      Map<String, String> m = new LinkedHashMap<>();
      try (ResultSet rs = db.register("credsdb", Fixtures.CREDS)
          .register("quotasdb", Fixtures.QUOTAS).query(
          "SELECT c.login, q.quota FROM credsdb.creds c JOIN quotasdb.quotas q "
          + "ON c.user_id = q.user_id AND c.tenant_id = q.tenant_id ORDER BY c.login")) {
        while (rs.next()) {
          m.put(rs.getString(1), rs.getString(2));
        }
      }
      assertEquals(Map.of("a1", "10", "a2", "5", "b1", "20"), m);
    }
  }

  @Test void safeModeRejectsBareScanButAllowsFiltered() throws Exception {
    try (CrossDb db = new CrossDb().safeMode()) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      assertThrows(CrossDbUnsafeQueryException.class,
          () -> db.query("SELECT id FROM userdb.users"));
      assertThrows(CrossDbUnsafeQueryException.class,
          () -> db.query("SELECT o.id FROM orderdb.orders o JOIN userdb.users u "
              + "ON o.user_id = u.id"));
      try (ResultSet rs = db.query("SELECT name FROM userdb.users WHERE id = 1")) {
        assertTrue(rs.next());
        assertEquals("alice", rs.getString(1));
      }
    }
  }

  @Test void queryTimeoutPropagatesToSources() throws Exception {
    List<String> props = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, new ArrayList<>(), props);
    try (CrossDb db = new CrossDb(1000, 1_000_000L, 500, 2, 3)) {
      try (ResultSet rs = db.register("userdb", users).register("orderdb", Fixtures.ORDERS)
          .query("SELECT id FROM userdb.small")) {
        assertTrue(rs.next());
      }
    }
    assertTrue(props.contains("setQueryTimeout(3)"), "应设置 queryTimeout=3: " + props);
  }

  @Test void analyzeReportsSourceSqlAndNetworkRows() throws Exception {
    try (CrossDb db = new CrossDb()) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      String report = db.analyze(JOIN_SQL);
      assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
      assertTrue(report.contains("networkRows"), report);
      assertTrue(report.contains("SELECT"), report);
      assertTrue(report.contains("bindJoin"), report);
    }
  }

  @Test void explainReturnsPlan() throws Exception {
    try (CrossDb db = new CrossDb()) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      assertTrue(db.explain(JOIN_SQL).contains("BindJoin"),
          "explain 应含 BindJoin 算子");
    }
  }

  @Test void sameDbJoinStillWorks() throws Exception {
    try (CrossDb db = new CrossDb()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query(
          "SELECT u.name FROM userdb.users u JOIN userdb.small s ON s.id = u.id "
          + "ORDER BY u.name")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      assertEquals(List.of("alice", "bob"), names);
    }
  }

  @Test void rowLimitExactlyAtThresholdPasses() throws Exception {
    try (CrossDb db = new CrossDb(1000, 2, 1, 1)) {
      List<Integer> ids = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query("SELECT id FROM userdb.small")) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
      assertEquals(List.of(1, 2), ids);
    }
  }

  @Test void rowLimitExceededRejected() throws Exception {
    try (CrossDb db = new CrossDb(1000, 2, 1, 1)) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      try {
        try (ResultSet rs = db.query("SELECT id FROM userdb.users")) {
          while (rs.next()) {
            // 只消费不读列；熔断应发生在第 3 次 next()
          }
        }
        fail("超过阈值必须拒绝执行");
      } catch (Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("熔断"),
            "根因应是熔断信息: " + root);
      }
    }
  }

  @Test void fetchSizePropagatesToSources() throws Exception {
    List<String> props = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, new ArrayList<>(), props);
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, new ArrayList<>(), props);
    try (CrossDb db = new CrossDb(7, 1_000_000L, 500, 2)) {
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders)
          .query(JOIN_SQL)) {
        assertTrue(rs.next());
      }
    }
    assertTrue(props.contains("setFetchSize(7)"), "源库语句应设置 fetchSize=7: " + props);
  }

  @Test void rightJoinBindsAcrossDbsAndKeepsAllRightRows() throws Exception {
    List<String> userSqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, userSqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> rows = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", users)
          .register("eventdb", Fixtures.EVENTS).query(
          "SELECT u.name, e.id FROM userdb.users u RIGHT JOIN eventdb.events e "
          + "ON e.user_id = u.id ORDER BY e.id")) {
        while (rs.next()) {
          rows.add(rs.getString(1) + "," + rs.getInt(2));
        }
      }
      // 订单侧（右表）全保留：alice 匹配事件 1；事件 2 无用户，补 NULL
      assertEquals(List.of("alice,1", "null,2"), rows);
      assertEquals(1, userSqls.size(), "users 侧应只收到 1 条 IN 查询: " + userSqls);
      assertTrue(userSqls.get(0).contains(" IN ("), "users 侧应走 IN 下推: " + userSqls);
    }
  }

  @Test void fullJoinAddsUnmatchedFromBothSides() throws Exception {
    List<String> eventSqls = Collections.synchronizedList(new ArrayList<>());
    DataSource events = Recording.dataSource(Fixtures.EVENTS, eventSqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> rows = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("eventdb", events).query(
          "SELECT u.name, e.id FROM userdb.users u FULL JOIN eventdb.events e "
          + "ON e.user_id = u.id "
          + "ORDER BY COALESCE(e.id, 99), u.name NULLS LAST")) {
        while (rs.next()) {
          rows.add(rs.getString(1) + "," + (rs.getObject(2) == null ? "-" : rs.getInt(2)));
        }
      }
      // 匹配 (alice,1)；右侧未匹配 (null,2)；左侧未匹配 bob/carol
      assertEquals(List.of("alice,1", "null,2", "bob,-", "carol,-"), rows);
      assertTrue(eventSqls.stream().anyMatch(s -> s.contains(" IN (")),
          "events 侧（内表）应有 IN 下推: " + eventSqls);
      assertTrue(eventSqls.stream().anyMatch(s -> s.contains("NOT (")),
          "events 侧应有 FULL 反连接 NOT IN: " + eventSqls);
      assertEquals(2, eventSqls.size(), "内表应只收 IN + NOT IN 各一条: " + eventSqls);
    }
  }

  @Test void semiJoinExistsPushesInInsteadOfFullScan() throws Exception {
    List<String> orderSqls = Collections.synchronizedList(new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, orderSqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", orders).query(
          "SELECT u.name FROM userdb.users u WHERE EXISTS "
          + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
          + "ORDER BY u.name")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      assertEquals(List.of("alice", "bob"), names, "EXISTS 半连接应只保留有订单的用户");
      assertTrue(orderSqls.stream().anyMatch(s -> s.contains(" IN (")),
          "orders 侧应收到 IN 下推: " + orderSqls);
      assertEquals(1, orderSqls.size(), "orders 侧不应全量拉取: " + orderSqls);
    }
  }

  @Test void antiJoinNotExistsBindsInnerInsteadOfKeyPull() throws Exception {
    // NOT EXISTS 去相关为「LEFT JOIN + 常量标记列 IS NULL」，AntiBindJoinFilterRule
    // 改写为 ANTI Bind Join：内表按外表 key 分批 IN 下推，只回传命中行
    List<String> orderSqls = Collections.synchronizedList(new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, orderSqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", orders).query(
          "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
          + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
          + "ORDER BY u.name")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      assertEquals(List.of("carol"), names, "NOT EXISTS 反连接应只保留无订单的用户");
      assertTrue(orderSqls.stream().anyMatch(s -> s.contains(" IN (")),
          "orders 侧应收到 IN 下推: " + orderSqls);
      assertEquals(1, orderSqls.size(), "orders 侧应只收到 1 条 IN 查询: " + orderSqls);
    }
  }

  @Test void notInSubqueryFallsBackToNativeAndWorks() throws Exception {
    // NOT IN 被 Calcite 展开为「计数聚合 + LEFT JOIN」形态：无等值连接对，
    // Bind Join 不改写（曾有误改写生成 WHERE () 空条件的回归），走原生计划
    try (CrossDb db = new CrossDb()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query(
          "SELECT u.name FROM userdb.users u WHERE u.id NOT IN "
          + "(SELECT o.user_id FROM orderdb.orders o) ORDER BY u.name")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      assertEquals(List.of("carol"), names);
    }
  }

  @Test void crossDbCartesianFallsBackToNative() throws Exception {
    // 跨库笛卡尔积（无连接条件）不能进 Bind Join（无 key 可 IN），走原生计划
    try (CrossDb db = new CrossDb()) {
      int n = 0;
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query(
          "SELECT u.name FROM userdb.users u CROSS JOIN orderdb.orders o")) {
        while (rs.next()) {
          n++;
        }
      }
      assertEquals(12, n, "3 用户 × 4 订单 = 12 行");
    }
  }

  @Test void readOnlyRejectsDmlButAllowsSelectAndCte() throws Exception {
    try (CrossDb db = new CrossDb()) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      SQLException e = assertThrows(SQLException.class,
          () -> db.query("DELETE FROM userdb.users WHERE id = 1"));
      assertTrue(e.getMessage().contains("只读"), e.getMessage());
      assertThrows(SQLException.class,
          () -> db.query("INSERT INTO userdb.users VALUES (9, 'eve')"));
      assertThrows(SQLException.class, () -> db.explain("UPDATE userdb.users SET id = 1"));
      // SELECT 与 WITH CTE 形态放行
      try (ResultSet rs = db.query("SELECT COUNT(*) AS c FROM userdb.users")) {
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
      }
      try (ResultSet rs = db.query(
          "WITH big AS (SELECT id FROM userdb.users WHERE id >= 2) "
          + "SELECT COUNT(*) AS c FROM big")) {
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
      }
    }
  }

  @Test void shardTopNPushesOrderAndLimitIntoEachUnionBranch() throws Exception {
    // UNION ALL 跨库合并 + ORDER BY + LIMIT：ShardTopNRule 把排序+裁剪下推到
    // 每个分支源库（每库只回 offset+fetch 行），本地归并保持语义
    List<String> sqls = Collections.synchronizedList(new ArrayList<>());
    DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
    DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<Integer> ids = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", users).register("orderdb", orders).query(
          "SELECT id FROM (SELECT id FROM userdb.users UNION ALL "
          + "SELECT id FROM orderdb.orders) t ORDER BY id DESC LIMIT 3")) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
      assertEquals(List.of(103, 102, 101), ids);
      long pushed = sqls.stream()
          .filter(s -> s.contains("ORDER BY") && (s.contains("LIMIT") || s.contains("FETCH")))
          .count();
      assertEquals(2, pushed, "两个分支源库 SQL 都应下推 ORDER BY + LIMIT/FETCH: " + sqls);
    }
  }

  @Test void shardTopNWithOffsetReturnsCorrectWindow() throws Exception {
    // 带 OFFSET 的分片 Top-N：分支取前 offset+fetch 行，本地按原 offset/fetch 裁剪
    try (CrossDb db = new CrossDb()) {
      List<Integer> ids = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query(
          "SELECT id FROM (SELECT id FROM userdb.users UNION ALL "
          + "SELECT id FROM orderdb.orders) t ORDER BY id DESC LIMIT 2 OFFSET 2")) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
      // 全体 id 降序: 103,102,101,100,3,2,1 → 跳过 2 行取 2 行
      assertEquals(List.of(101, 100), ids);
    }
  }

  @Test void crossDbUnionAllWithoutTopNStillWorks() throws Exception {
    // 不带 ORDER BY/LIMIT 的 UNION ALL：各分支整条 SQL 下发（原生即覆盖），
    // 最终结果仍受行数熔断封顶（users 3 行 + orders 4 行 > 阈值 6）
    try (CrossDb db = new CrossDb(1000, 6, 500, 2)) {
      int n = 0;
      try {
        try (ResultSet rs = db.register("userdb", Fixtures.USERS)
            .register("orderdb", Fixtures.ORDERS).query(
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders")) {
          while (rs.next()) {
            n++;
          }
        }
        fail("合并输出 7 行超过阈值 6，应触发熔断");
      } catch (Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("熔断"),
            "根因应是熔断信息: " + root);
      }
      assertEquals(6, n, "前 6 行流式通过，第 7 行触发熔断");
    }
  }

  @Test void cancelIsSafeBeforeDuringAndAfterQuery() throws Exception {
    try (CrossDb db = new CrossDb()) {
      db.register("userdb", Fixtures.USERS).register("orderdb", Fixtures.ORDERS);
      db.cancel(); // 无在途语句：空操作
      try (ResultSet rs = db.query(JOIN_SQL)) {
        assertTrue(rs.next());
        db.cancel(); // 在途：对已完成的拉取无害
      }
      db.cancel();
      int n = 0;
      try (ResultSet rs = db.query("SELECT id FROM userdb.small")) {
        while (rs.next()) {
          n++;
        }
      }
      assertEquals(2, n, "cancel 后新查询应正常执行");
    }
  }

  @Test void topNOnJoinKeyEnablesWindowEviction() throws Exception {
    try (CrossDb db = new CrossDb(1000, 1_000_000L, 2, 1)) {
      List<Integer> ids = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS).query(
          "SELECT o.id FROM orderdb.orders o JOIN userdb.users u ON o.user_id = u.id "
          + "ORDER BY o.user_id, o.id LIMIT 4")) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
      // 排序列含 join key：驱动侧 SQL 带 ORDER BY user_id → 哈希按窗口淘汰；
      // 多窗口下所有匹配仍须完整（user 1 的行在窗口 1，淘汰后不得丢失）
      assertEquals(List.of(100, 102, 101, 103), ids);
    }
  }

  @Test void invalidConfigRejected() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> new CrossDb(0, 10, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CrossDb(1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CrossDb(1, 10, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new CrossDb(1, 10, 1, 0));
  }

  @Test void invalidSqlRejected() throws Exception {
    try (CrossDb db = new CrossDb()) {
      assertThrows(SQLException.class, () -> db.query("SELEC 1"));
      assertThrows(SQLException.class,
          () -> db.query("SELECT * FROM nosuchdb.nosuchtable"));
    }
  }

  @Test void fullJoinKeepsInnerNullKeyRows() throws Exception {
    // FULL 反连接的补 OR key IS NULL 回归：内表 user_id=9 与 user_id=NULL 的行
    // 都是无匹配行，三值逻辑下 NOT IN 会丢掉 NULL key 行，原生 FULL 语义不允许
    List<String> pingSqls = Collections.synchronizedList(new ArrayList<>());
    DataSource pings = Recording.dataSource(Fixtures.PINGS, pingSqls, new ArrayList<>());
    try (CrossDb db = new CrossDb()) {
      List<String> rows = new ArrayList<>();
      try (ResultSet rs = db.register("userdb", Fixtures.USERS)
          .register("pingdb", pings).query(
          "SELECT u.name, p.id, p.user_id FROM userdb.users u FULL JOIN pingdb.pings p "
          + "ON p.user_id = u.id "
          + "ORDER BY COALESCE(p.id, 99), u.name NULLS LAST")) {
        while (rs.next()) {
          rows.add(rs.getString(1) + "," + rs.getObject(2) + "," + rs.getObject(3));
        }
      }
      // alice 匹配 p1；p2(9) 与 p3(NULL) 反连接补出左侧 NULL；bob/carol 右侧补 NULL
      assertEquals(List.of("alice,1,1", "null,2,9", "null,3,null", "bob,null,null",
          "carol,null,null"), rows);
      assertTrue(pingSqls.stream().anyMatch(s -> s.contains("IS NULL")),
          "反连接 SQL 应带 key IS NULL: " + pingSqls);
    }
  }

  @Test void resultSetTypedGettersWasNullAndMetadata() throws Exception {
    try (CrossDb db = new CrossDb()) {
      db.register("pingdb", Fixtures.PINGS);
      try (ResultSet rs = db.query("SELECT id AS `ID`, user_id AS `USER_ID`, "
          + "note AS `NOTE`, amount AS `AMOUNT`, ts AS `TS`, flag AS `FLAG` "
          + "FROM pingdb.pings ORDER BY id")) {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        assertEquals(6, md.getColumnCount());
        assertEquals("ID", md.getColumnName(1));
        assertEquals("USER_ID", md.getColumnLabel(2));
        assertEquals(java.sql.Types.INTEGER, md.getColumnType(1));
        assertEquals(java.sql.Types.VARCHAR, md.getColumnType(3));
        assertEquals(java.sql.Types.DECIMAL, md.getColumnType(4));
        assertEquals("TIMESTAMP", md.getColumnTypeName(5));
        assertEquals(java.sql.Types.TIMESTAMP, md.getColumnType(5));
        assertEquals(java.sql.Types.BOOLEAN, md.getColumnType(6));
        // JDBC 契约：className 与 getObject 返回类型一致——Calcite 内部把 TIMESTAMP
        // 承载为 epoch millis（Long），getObject 返回 Long，typed getter 负责转时间型
        assertEquals("java.lang.Long", md.getColumnClassName(5));
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("ID"));
        assertFalse(rs.wasNull());
        assertEquals("a", rs.getString("NOTE"));
        assertEquals(new java.math.BigDecimal("1.25"), rs.getBigDecimal("AMOUNT"));
        assertEquals(java.sql.Timestamp.valueOf("2026-01-02 03:04:05"), rs.getTimestamp("TS"));
        assertEquals(java.sql.Date.valueOf("2026-01-02"), rs.getDate("TS"));
        assertTrue(rs.getBoolean("FLAG"));
        assertEquals(1.25, rs.getDouble("AMOUNT"), 0);
        assertEquals(1L, rs.getLong("ID"));
        assertEquals((short) 1, rs.getShort("ID"));
        assertEquals((byte) 1, rs.getByte("ID"));

        assertTrue(rs.next());
        assertEquals(9, rs.getInt("USER_ID"));
        assertFalse(rs.wasNull());
        assertNull(rs.getBigDecimal("AMOUNT"));
        assertTrue(rs.wasNull());
        assertNull(rs.getTimestamp("TS"));
        assertEquals(0.0, rs.getDouble("AMOUNT"), 0);
        assertTrue(rs.wasNull());
        assertFalse(rs.getBoolean("FLAG"));
        assertTrue(rs.wasNull());

        assertTrue(rs.next());
        // 内表 NULL key 行：getInt 返回 0 且 wasNull 可区分（此前恒 false 且 NPE）
        assertEquals(0, rs.getInt("USER_ID"));
        assertTrue(rs.wasNull());
        assertNull(rs.getString("NOTE"));
        // DECIMAL(10,2) 定标返回 3.50，按数值比较
        assertEquals(0, rs.getBigDecimal("AMOUNT").compareTo(new java.math.BigDecimal("3.5")));
        assertEquals(java.sql.Timestamp.valueOf("2026-02-03 04:05:06"), rs.getTimestamp("TS"));
        assertFalse(rs.getBoolean("FLAG"));
        assertFalse(rs.wasNull());
        assertFalse(rs.next());
      }
    }
  }

  @Test void safeModeRejectsDegenerateFullJoinAntiJoin() throws Exception {
    // 外表过滤为空集 → 反连接 queued 为空 → 运行期将无 WHERE 全表拉取内表；
    // safeMode 承诺零容忍全表拉取：Bind Join 计划在执行期拦截，原生回退计划
    // （两侧裸拉取）在计划期拦截——两种路径根因都应是 CrossDbUnsafeQueryException
    try (CrossDb db = new CrossDb().safeMode()) {
      db.register("userdb", Fixtures.USERS).register("pingdb", Fixtures.PINGS);
      try {
        try (ResultSet rs = db.query(
            "SELECT u.name, p.id FROM (SELECT id, name FROM userdb.users WHERE id = 999) u "
            + "FULL JOIN pingdb.pings p ON p.user_id = u.id")) {
          while (rs.next()) {
            // 消费触发反连接
          }
        }
        fail("外表零行时 FULL 反连接将全表拉取内表，safeMode 应拒绝");
      } catch (Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(root instanceof CrossDbUnsafeQueryException,
            "根因应是 safeMode 拦截: " + root);
      }
    }
  }

  @Test void registerRejectsBlankAndDuplicateSchema() throws Exception {
    try (CrossDb db = new CrossDb()) {
      assertThrows(IllegalArgumentException.class, () -> db.register(null, Fixtures.USERS));
      assertThrows(IllegalArgumentException.class, () -> db.register(" ", Fixtures.USERS));
      db.register("userdb", Fixtures.USERS);
      assertThrows(IllegalArgumentException.class,
          () -> db.register("userdb", Fixtures.ORDERS));
    }
  }
}
