package com.example.crossdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 列类型目录：供解析期「类型感知改写」（DATE±整数、CAST(布尔 AS 数值)、MOD
 * 浮点修正、SELECT * EXCLUDE 列展开）按裸列名/表名判定列类型与列清单。经 JDBC
 * DatabaseMetaData 扫描各已注册源库构建；跨库同名但类别不一致的列名一律剔除
 * （防误改写）；扫描失败降级为空目录（相关改写不生效，维持原生报错路径）。
 * 类别仅保留粗粒度：date / bool / float / int / other。 */
final class ColumnHints {
  static final ColumnHints EMPTY = new ColumnHints(Map.of(), Map.of());
  private final Map<String, String> categories;
  /** 表（schema.table 或裸 table，小写键）→ 有序列名清单（目录原大小写）。 */
  private final Map<String, List<String>> tableColumns;

  private ColumnHints(Map<String, String> categories, Map<String, List<String>> tableColumns) {
    this.categories = categories;
    this.tableColumns = tableColumns;
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

  /** 表的有序列清单：优先 schema 限定名，回退裸表名（跨库同名表列清单一致时）；
   * 未知或同名冲突返回 null。 */
  List<String> columnsOf(String tableRef) {
    String ref = tableRef == null ? "" : tableRef.toLowerCase(java.util.Locale.ROOT).trim();
    List<String> cols = tableColumns.get(ref);
    if (cols != null) {
      return cols;
    }
    int dot = ref.lastIndexOf('.');
    return dot < 0 ? null : tableColumns.get(ref.substring(dot + 1));
  }

  private static String key(String name) {
    return name.toLowerCase(java.util.Locale.ROOT);
  }

  static ColumnHints scan(java.util.Collection<javax.sql.DataSource> sources) {
    // 无 schema 名的兼容入口：裸表名目录退化为空（仅类别目录可用）
    return scanSources(sources.stream().map(s -> Map.entry("", s)).toList());
  }

  /** 按 schema→DataSource 扫描（schema 名作为表目录限定段；空名 schema 的表
   * 不进表目录）。系统 schema（information_schema/pg_catalog 等）的表不进目录，
   * 防止同名系统表污染列清单与类别合并。 */
  static ColumnHints scanSources(java.util.List<Map.Entry<String, javax.sql.DataSource>> named) {
    Map<String, String> cats = new LinkedHashMap<>();
    Map<String, List<String>> tables = new LinkedHashMap<>();
    java.util.Set<String> dropped = new java.util.HashSet<>();
    for (Map.Entry<String, javax.sql.DataSource> e : named) {
      try (java.sql.Connection c = e.getValue().getConnection();
           java.sql.ResultSet rs = c.getMetaData().getColumns(null, null, "%", "%")) {
        Map<String, List<String[]>> byTable = new LinkedHashMap<>();
        while (rs.next()) {
          String tableSchema = rs.getString("TABLE_SCHEM");
          if (tableSchema != null && isSystemSchema(tableSchema)) {
            continue;
          }
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
          if (!e.getKey().isEmpty()) {
            byTable.computeIfAbsent(
                e.getKey() + "." + rs.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT),
                k -> new ArrayList<>())
                .add(new String[]{col, String.valueOf(rs.getInt("ORDINAL_POSITION"))});
          }
        }
        for (Map.Entry<String, List<String[]>> t : byTable.entrySet()) {
          t.getValue().sort((a, b) -> Integer.compare(
              Integer.parseInt(a[1]), Integer.parseInt(b[1])));
          List<String> cols = new ArrayList<>();
          t.getValue().forEach(a -> cols.add(a[0]));
          List<String> prevQualified = tables.get(t.getKey());
          if (prevQualified != null && !prevQualified.equals(cols)) {
            continue;   // 同名表限定键冲突（异常形态）：保留首见
          }
          String bare = t.getKey().substring(t.getKey().lastIndexOf('.') + 1);
          List<String> prevBare = tables.get(bare);
          if (prevBare == null || prevBare.equals(cols)) {
            tables.put(bare, cols);
          }
          tables.put(t.getKey(), cols);
        }
      } catch (java.sql.SQLException ex) {
        return EMPTY;   // 元数据不可得：降级为空目录，改写安全旁路
      }
    }
    dropped.forEach(cats::remove);
    return new ColumnHints(cats, tables);
  }

  /** 系统目录 schema 判定（H2/MySQL information_schema、PG pg_*、部分库的 builtin）。 */
  private static boolean isSystemSchema(String schema) {
    String u = schema.toUpperCase(java.util.Locale.ROOT);
    return u.equals("INFORMATION_SCHEMA") || u.startsWith("PG_")
        || u.equals("BUILTIN") || u.equals("SYSTEM");
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
