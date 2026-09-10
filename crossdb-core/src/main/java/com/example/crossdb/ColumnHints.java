package com.example.crossdb;

import java.util.LinkedHashMap;
import java.util.Map;

/** 列类型目录：供解析期「类型感知改写」（DATE±整数、CAST(布尔 AS 数值)、MOD
 * 浮点修正）按裸列名判定列类型。经 JDBC DatabaseMetaData 扫描各已注册源库构建；
 * 跨库同名但类别不一致的列名一律剔除（防误改写）；扫描失败降级为空目录
 * （相关改写不生效，维持原生报错路径）。类别仅保留粗粒度：date / bool /
 * float / int / other。 */
final class ColumnHints {
  static final ColumnHints EMPTY = new ColumnHints(Map.of());
  private final Map<String, String> categories;

  private ColumnHints(Map<String, String> categories) {
    this.categories = categories;
  }

  boolean isDateColumn(String name) {
    return "date".equals(categories.get(key(name)));
  }

  boolean isBooleanColumn(String name) {
    return "bool".equals(categories.get(key(name)));
  }

  boolean isFloatColumn(String name) {
    return "float".equals(categories.get(key(name)));
  }

  private static String key(String name) {
    return name.toLowerCase(java.util.Locale.ROOT);
  }

  static ColumnHints scan(java.util.Collection<javax.sql.DataSource> sources) {
    Map<String, String> cats = new LinkedHashMap<>();
    java.util.Set<String> dropped = new java.util.HashSet<>();
    for (javax.sql.DataSource ds : sources) {
      try (java.sql.Connection c = ds.getConnection();
           java.sql.ResultSet rs = c.getMetaData().getColumns(null, null, "%", "%")) {
        while (rs.next()) {
          String col = rs.getString("COLUMN_NAME");
          int scale = rs.getInt("DECIMAL_DIGITS");
          String cat = category(rs.getInt("DATA_TYPE"), rs.wasNull() ? -1 : scale);
          String key = key(col);
          String prev = cats.get(key);
          if (prev == null) {
            cats.put(key, cat);
          } else {
            cats.put(key, mergeCategory(prev, cat, key, dropped));
          }
        }
      } catch (java.sql.SQLException e) {
        return EMPTY;   // 元数据不可得：降级为空目录，改写安全旁路
      }
    }
    dropped.forEach(cats::remove);
    return new ColumnHints(cats);
  }

  /** 同名列类别合并：数值类内部以「更宽」者为准（int 遇 float → float：按浮点
   * 语义改写 MOD 等，正确性优先，代价仅是该列 MOD 不再下推）；跨大类冲突
   * （date/bool 与其他）无法安全裁决 → 剔除该列。 */
  private static String mergeCategory(String a, String b, String key,
      java.util.Set<String> dropped) {
    if (a.equals(b)) {
      return a;
    }
    java.util.Set<String> s = java.util.Set.of(a, b);
    if (s.equals(java.util.Set.of("int", "float"))) {
      return "float";
    }
    dropped.add(key);
    return a;
  }

  private static String category(int jdbcType, int scale) {
    return switch (jdbcType) {
      case java.sql.Types.DATE, java.sql.Types.TIMESTAMP -> "date";
      case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "bool";
      case java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL -> "float";
      case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> scale > 0 ? "float" : "int";
      case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
          java.sql.Types.BIGINT -> "int";
      default -> "other";
    };
  }
}
