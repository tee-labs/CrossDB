package com.example.crossdb;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.H2SqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** wrapInner 派生表别名的方言兼容：Oracle 表别名不允许 AS 关键字（ORA-00933
 * SQL 命令未正确结束，仅列别名可用 AS），其余方言保留 AS。 */
class BindJoinRuleTest {

  @Test void h2DerivedTableAliasKeepsAs() {
    assertEquals("SELECT * FROM (SELECT 1) AS \"T\"",
        BindJoinRule.wrapInner(H2SqlDialect.DEFAULT, "SELECT 1"));
  }

  @Test void oracleDerivedTableAliasOmitsAs() {
    assertEquals("SELECT * FROM (SELECT 1) \"T\"",
        BindJoinRule.wrapInner(OracleSqlDialect.DEFAULT, "SELECT 1"));
  }

  @Test void mysqlPostgresDerivedTableAliasKeepsAs() {
    for (SqlDialect d : new SqlDialect[]{MysqlSqlDialect.DEFAULT,
        PostgresqlSqlDialect.DEFAULT}) {
      String sql = BindJoinRule.wrapInner(d, "SELECT 1");
      assertTrue(sql.startsWith("SELECT * FROM (SELECT 1) AS "), sql);
      assertFalse(sql.endsWith(" AS "), sql);
    }
  }
}
