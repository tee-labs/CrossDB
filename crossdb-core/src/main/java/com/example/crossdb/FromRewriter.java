package com.example.crossdb;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 遍历每个 SELECT 作用域的 FROM 树：USING 连接展开为 ON 并记录共享列；
 * 表别名列名清单展开为派生表；随后把本层裸引用的共享列替换为 COALESCE。 */
final class FromRewriter {
  private final SchemaPlus root;
  private final JavaTypeFactory typeFactory;

  FromRewriter(SchemaPlus root, JavaTypeFactory typeFactory) {
    this.root = root;
    this.typeFactory = typeFactory;
  }

  SqlNode expand(SqlNode stmt) {
    switch (stmt) {
      case SqlOrderBy ob -> expand(ob.query);
      case org.apache.calcite.sql.SqlWith with -> expand(with.body);
      case SqlCall set when set.getKind() == SqlKind.UNION
          || set.getKind() == SqlKind.INTERSECT || set.getKind() == SqlKind.EXCEPT -> {
        for (SqlNode op : set.getOperandList()) {
          if (op != null) {
            expand(op);
          }
        }
      }
      case SqlSelect select -> expandSelect(select);
      default -> {
      }
    }
    return stmt;
  }

  private void expandSelect(SqlSelect select) {
    if (select.getFrom() == null) {
      return;
    }
    Map<String, SqlNode[]> coalesce = new LinkedHashMap<>();
    select.setFrom(rewriteFrom(select.getFrom(), coalesce));
    if (!coalesce.isEmpty()) {
      substituteBare(select, coalesce);
    }
  }

  /** 递归改写 FROM 树，返回改写后的节点（可能为原实例）。 */
  private SqlNode rewriteFrom(SqlNode node, Map<String, SqlNode[]> coalesce) {
    if (node instanceof SqlJoin join) {
      SqlNode left = rewriteFrom(join.getLeft(), coalesce);
      SqlNode right = rewriteFrom(join.getRight(), coalesce);
      join.setLeft(left);
      join.setRight(right);
      if (join.getConditionType() == JoinConditionType.USING
          && join.getCondition() instanceof SqlNodeList cols) {
        String ln = itemName(join.getLeft());
        String rn = itemName(join.getRight());
        if (ln != null && rn != null) {
          // USING (c..) → ON l.c = r.c AND ...（INNER/OUTER 语义均与 COALESCE 合并列一致）
          SqlParserPos pos = node.getParserPosition();
          SqlNode on = null;
          for (SqlNode colNode : cols) {
            String col = ((SqlIdentifier) colNode).getSimple();
            SqlNode eq = new SqlBasicCall(SqlStdOperatorTable.EQUALS,
                List.of(qualify(ln, col, pos), qualify(rn, col, pos)), pos);
            on = on == null ? eq
                : new SqlBasicCall(SqlStdOperatorTable.AND, List.of(on, eq), pos);
            coalesce.putIfAbsent(col, new SqlNode[]{qualify(ln, col, pos),
                qualify(rn, col, pos)});
          }
          // operand 布局：[left, natural, joinType, right, conditionType, condition]
          join.setOperand(4, SqlLiteral.createSymbol(JoinConditionType.ON, pos));
          join.setOperand(5, on);
        }
      }
      return join;
    }
    if (node instanceof SqlBasicCall as && as.getKind() == SqlKind.AS
        && as.operandCount() >= 3
        && as.getOperandList().stream().skip(2).allMatch(o -> o instanceof SqlIdentifier)) {
      // t(a, b) 列名清单 → 派生表列重命名
      SqlNode inner = rewriteFrom(as.operand(0), coalesce);
      SqlNode derived = deriveWithColumns(inner, as);
      return derived != null
          ? new SqlBasicCall(SqlStdOperatorTable.AS, List.of(derived, as.operand(1)),
              node.getParserPosition())
          : as;
    }
    return node;
  }

  /** 仅当底层是 schema 可解析字段名的表引用时，生成等价派生表，否则 null。 */
  private SqlNode deriveWithColumns(SqlNode table, SqlBasicCall as) {
    if (!(table instanceof SqlIdentifier ident)) {
      return null;
    }
    List<String> fields = resolveFields(ident);
    if (fields == null || fields.size() < as.operandCount() - 2) {
      return null;
    }
    SqlParserPos pos = as.getParserPosition();
    SqlNodeList selectList = new SqlNodeList(pos);
    List<String> cols = as.getOperandList().stream().skip(2)
        .map(o -> ((SqlIdentifier) o).getSimple()).toList();
    for (int i = 0; i < cols.size(); i++) {
      selectList.add(new SqlBasicCall(SqlStdOperatorTable.AS, List.of(
          new SqlIdentifier(fields.get(i), pos), new SqlIdentifier(cols.get(i), pos)), pos));
    }
    // (pos, keywords, selectList, from, where, groupBy, having, windowDecls, orderBy,
    //  offset, fetch, hints)
    return new SqlSelect(pos, new SqlNodeList(pos), selectList, ident, null, null, null,
        new SqlNodeList(pos), null, null, null, new SqlNodeList(pos));
  }

  private List<String> resolveFields(SqlIdentifier ident) {
    List<String> names = ident.names;
    SchemaPlus s = root;
    for (int i = 0; i < names.size() - 1; i++) {
      s = findSchema(s, names.get(i));
      if (s == null) {
        return null;
      }
    }
    Table table = findTable(s, names.get(names.size() - 1));
    return table == null ? null : table.getRowType(typeFactory).getFieldNames();
  }

  private SchemaPlus findSchema(SchemaPlus parent, String name) {
    for (String n : parent.getSubSchemaNames()) {
      if (n.equalsIgnoreCase(name)) {
        return parent.getSubSchema(n);
      }
    }
    return null;
  }

  private Table findTable(SchemaPlus schema, String name) {
    for (String n : schema.getTableNames()) {
      if (n.equalsIgnoreCase(name)) {
        return schema.getTable(n);
      }
    }
    return null;
  }

  /** FROM 项的可见名：表名或别名；派生表等无名字形态返回 null。 */
  private static String itemName(SqlNode node) {
    if (node instanceof SqlIdentifier id) {
      return id.names.get(id.names.size() - 1);
    }
    if (node instanceof SqlBasicCall as && as.getKind() == SqlKind.AS
        && as.operandCount() >= 2 && as.operand(1) instanceof SqlIdentifier alias) {
      return alias.getSimple();
    }
    return null;
  }

  private static SqlIdentifier qualify(String base, String col, SqlParserPos pos) {
    return new SqlIdentifier(List.of(base, col), pos);
  }

  /** 本层 SELECT 的裸引用（单段名命中 USING 共享列）替换为 COALESCE(l.c, r.c)；
   * 不下钻嵌套子查询（新作用域）。 */
  private static void substituteBare(SqlSelect select, Map<String, SqlNode[]> coalesce) {
    substituteList(select.getSelectList(), coalesce);
    if (select.getWhere() != null) {
      select.setWhere(substitute(select.getWhere(), coalesce));
    }
    if (select.getGroup() != null) {
      substituteList(select.getGroup(), coalesce);
    }
    if (select.getHaving() != null) {
      select.setHaving(substitute(select.getHaving(), coalesce));
    }
  }

  private static void substituteList(SqlNodeList list, Map<String, SqlNode[]> coalesce) {
    for (int i = 0; i < list.size(); i++) {
      list.set(i, substitute(list.get(i), coalesce));
    }
  }

  private static SqlNode substitute(SqlNode node, Map<String, SqlNode[]> coalesce) {
    if (node == null) {
      return null;
    }
    if (node instanceof SqlIdentifier id && id.names.size() == 1) {
      SqlNode[] pair = coalesce.get(id.getSimple());
      return pair == null ? node
          : new SqlBasicCall(SqlStdOperatorTable.COALESCE, List.of(pair[0], pair[1]),
              id.getParserPosition());
    }
    if (node instanceof SqlSelect || node instanceof org.apache.calcite.sql.SqlWith) {
      return node;
    }
    if (node instanceof SqlBasicCall call) {
      List<SqlNode> ops = call.getOperandList();
      List<SqlNode> newOps = new ArrayList<>(ops.size());
      boolean changed = false;
      for (SqlNode op : ops) {
        SqlNode nn = op == null ? null : substitute(op, coalesce);
        newOps.add(nn);
        changed |= nn != op;
      }
      return changed
          ? new SqlBasicCall(call.getOperator(), newOps, call.getParserPosition())
          : call;
    }
    return node;
  }
}
