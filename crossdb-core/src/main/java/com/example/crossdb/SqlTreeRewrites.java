package com.example.crossdb;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlWithinGroupOperator;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.example.crossdb.LocalOperators.*;

/** 解析树改写：表达式级 shuttle（方言函数 → 本地 UDF 挂载 / 等价标准形态）+ USING
 * 展开、别名列清单的 FROM 级改写（FromRewriter）。本地操作符常量定义见
 * LocalOperators。 */
final class SqlTreeRewrites {
  private SqlTreeRewrites() {}

  /** 改写入口：表达式级 shuttle 改写 + USING / 别名列清单的 FROM 级改写。
   * hints 为已注册源库的列类型目录（可为 null，等价空目录）。 */
  static SqlNode rewrite(SqlNode parsed, SchemaPlus root, JavaTypeFactory typeFactory,
      ColumnHints hints) {
    ColumnHints h = hints == null ? ColumnHints.EMPTY : hints;
    parsed = parsed.accept(rewriter(h));
    return new FromRewriter(root, typeFactory).expand(parsed);
  }

  private static SqlShuttle rewriter(ColumnHints hints) {
    return new SqlShuttle() {
      @Override public SqlNode visit(SqlCall call) {
        if (call.getKind().belongsTo(SqlKind.DDL) || call.getKind().belongsTo(SqlKind.DML)) {
          // DDL/DML 仅会被只读硬化拒绝，且其专用节点（SqlCreate 等）不能按
          // 通用 createCall 重建（shuttle 深入会崩），直接原样返回
          return call;
        }
        call = (SqlCall) super.visit(call);
        if (call.getOperator() == SqlStdOperatorTable.SIMILAR_TO
            || call.getOperator() == SqlStdOperatorTable.NOT_SIMILAR_TO) {
          boolean negative = call.getOperator() == SqlStdOperatorTable.NOT_SIMILAR_TO;
          List<SqlNode> operands = call.getOperandList();
          SqlBasicCall similar = new SqlBasicCall(
              operands.size() >= 3 ? SIMILAR_FN_3 : SIMILAR_FN_2, operands,
              call.getParserPosition());
          return negative
              ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(similar),
                  call.getParserPosition())
              : similar;
        }
        String name = call.getOperator().getName();
        String upper = name.toUpperCase();
        if (upper.equals("RLIKE") || upper.equals("NOT RLIKE")) {
          return rewriteRlike(call);
        }
        if (call.getKind() == SqlKind.TIMESTAMP_DIFF || upper.equals("TIMESTAMPDIFF")) {
          return rewriteTimestampDiff(call);
        }
        if (upper.equals("STRING_AGG") || upper.equals("GROUP_CONCAT")) {
          return rewriteListAgg(call);
        }
        if (upper.equals("LISTAGG")) {
          // 注意：解析器构造的 LISTAGG 是 SqlListaggAggFunction 专有实例，
          // 不能按操作符实例判等，须按名匹配
          return rewriteListaggDistinct(call);
        }
        if (upper.equals("GREATEST") || upper.equals("LEAST")) {
          return rewriteExtremum(call);
        }
        if (upper.equals("DECODE")) {
          return rewriteDecode(call);
        }
        if (upper.equals("MEDIAN")) {
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 1
              ? new SqlBasicCall(MEDIAN_FN, ops, call.getParserPosition())
              : call;
        }
        if (upper.equals("IIF")) {
          return rewriteIif(call);
        }
        if (upper.equals("ISNULL")) {
          return rewriteIsnull(call);
        }
        if (upper.equals("NVL") && call.getOperandList().size() == 2) {
          // Oracle NVL(a, b) ≡ COALESCE(a, b)（NULL 字面量首参时 UDF 的 ARG0
          // 返回类型推导为 Void 会在运行期抛类型转换异常，改写挂标准 COALESCE）
          return new SqlBasicCall(SqlStdOperatorTable.COALESCE, call.getOperandList(),
              call.getParserPosition());
        }
        if (upper.equals("ILIKE") || upper.equals("NOT ILIKE")) {
          return rewriteIlike(call);
        }
        if (upper.equals("ANY_VALUE") || upper.equals("MODE")) {
          // MySQL ANY_VALUE / Oracle MODE：本地 UDAF（ANY_VALUE 取首见非 NULL，
          // MODE 取众数、并列取最小值）
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 1
              ? new SqlBasicCall(upper.equals("ANY_VALUE") ? ANY_VALUE_FN : MODE_FN,
                  ops, call.getParserPosition())
              : call;
        }
        if (upper.equals("NVL2") && call.getOperandList().size() == 3) {
          // NVL2(e, a, b)（Oracle）→ CASE WHEN e IS NOT NULL THEN a ELSE b END
          List<SqlNode> ops = call.getOperandList();
          SqlParserPos pos = call.getParserPosition();
          SqlNodeList whens = new SqlNodeList(pos);
          whens.add(new SqlBasicCall(SqlStdOperatorTable.IS_NOT_NULL,
              List.of(ops.get(0)), pos));
          SqlNodeList thens = new SqlNodeList(pos);
          thens.add(ops.get(1));
          return new SqlCase(pos, null, whens, thens, ops.get(2));
        }
        if (upper.equals("INSTR")
            && (call.getOperandList().size() == 2 || call.getOperandList().size() == 3)) {
          // INSTR(str, substr[, start])（MySQL/Oracle）→ 本地 UDF（参数序与 LOCATE 相反）
          return new SqlBasicCall(
              call.getOperandList().size() == 2 ? INSTR2_FN : INSTR3_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("LENGTH") && call.getOperandList().size() == 1) {
          // LENGTH（MySQL/PG 常用名，Calcite 仅注册 CHAR_LENGTH）→ CHAR_LENGTH
          return new SqlBasicCall(SqlStdOperatorTable.CHAR_LENGTH,
              call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("CONCAT_WS") && call.getOperandList().size() >= 4
            && call.getOperandList().size() <= 5) {
          // CONCAT_WS 可变参：已注册元数仅 2/3，4/5 参按元数挂本地实现
          return new SqlBasicCall(
              call.getOperandList().size() == 4 ? CONCAT_WS4_FN : CONCAT_WS5_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("SUBSTRING_INDEX") && call.getOperandList().size() == 3) {
          return new SqlBasicCall(SUBSTRING_INDEX_FN, call.getOperandList(),
              call.getParserPosition());
        }
        if (upper.equals("DATE_FORMAT") && call.getOperandList().size() == 2) {
          return new SqlBasicCall(DATE_FORMAT_FN, call.getOperandList(),
              call.getParserPosition());
        }
        if (upper.equals("REGEXP_REPLACE") && call.getOperandList().size() == 3) {
          // MySQL 三参全局替换形态（Calcite 未注册）→ 本地 UDF；其余元数保留原样
          return new SqlBasicCall(REGEXP_REPLACE3_FN, call.getOperandList(),
              call.getParserPosition());
        }
        if (upper.equals("TO_CHAR")) {
          List<SqlNode> ops = call.getOperandList();
          SqlParserPos pos = call.getParserPosition();
          if (ops.size() == 1) {
            // TO_CHAR(x) 单参（Oracle 隐式格式）→ CAST AS VARCHAR
            return new SqlBasicCall(SqlStdOperatorTable.CAST, List.of(ops.get(0),
                new SqlDataTypeSpec(
                    new SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, pos), pos)), pos);
          }
          if (ops.size() == 2) {
            return new SqlBasicCall(TO_CHAR2_FN, ops, pos);
          }
          return call;
        }
        if (upper.equals("NEXT_DAY") && call.getOperandList().size() == 2) {
          return new SqlBasicCall(NEXT_DAY_FN, call.getOperandList(),
              call.getParserPosition());
        }
        if ((upper.equals("ARG_MIN") || upper.equals("ARG_MAX"))
            && call.getOperandList().size() == 2) {
          // ARG_MIN/ARG_MAX(v, o)（DuckDB/Trino）→ 本地 UDAF（标准表 1.42 起有
          // SqlBasicAggFunction 注册，但 Enumerable 无实现，统一挂本地）
          return new SqlBasicCall(upper.equals("ARG_MIN") ? ARG_MIN_FN : ARG_MAX_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if (call.getKind() == SqlKind.SAFE_CAST) {
          // TRY_CAST(x AS type)（解析器按 SAFE_CAST kind 产出）：校验器对字符→数值
          // 目标类型检查过严且 Enumerable 无安全转换实现 → 按目标类型挂本地 UDF
          // （失败得 NULL）；字符目标退化为恒成功的 CAST
          return rewriteTryCast(call);
        }
        if (upper.equals("ARRAY_AGG")) {
          return rewriteArrayAgg(call);
        }
        if (upper.equals("TRANSLATE") || upper.equals("TRANSLATE3")) {
          // Oracle/PostgreSQL 三参 TRANSLATE：与标准表 SqlTranslateFunction 同名
          // 重载消解冲突（解析器挂 TRANSLATE3 名），统一按名改挂本地实现
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 3
              ? new SqlBasicCall(TRANSLATE_FN, ops, call.getParserPosition())
              : call;
        }
        if (upper.equals("LOG")) {
          return rewriteLog(call);
        }
        if (upper.equals("SPACE") && call.getOperandList().size() == 1) {
          // MySQL SPACE(n) → REPEAT(' ', n)（复用本地 UDF）
          SqlParserPos pos = call.getParserPosition();
          return new SqlBasicCall(REPEAT_FN, List.of(
              SqlLiteral.createCharString(" ", pos), call.getOperandList().get(0)), pos);
        }
        if (upper.equals("CHAR") && call.getOperandList().size() == 1) {
          // MySQL CHAR(n) → CHR(n)（复用本地 UDF）
          return new SqlBasicCall(CHR_FN, call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("STRCMP")) {
          return rewriteStrcmp(call);
        }
        if ((upper.equals("LEFT") || upper.equals("RIGHT"))
            && call.getOperandList().size() == 2) {
          // MySQL/SQL Server LEFT/RIGHT(s, n) → 本地 UDF
          return new SqlBasicCall(upper.equals("LEFT") ? LEFT_FN : RIGHT_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("LOCATE")
            && (call.getOperandList().size() == 2 || call.getOperandList().size() == 3)) {
          // MySQL/PostgreSQL LOCATE(substr, str[, start]) → 本地 UDF
          return new SqlBasicCall(
              call.getOperandList().size() == 2 ? LOCATE2_FN : LOCATE3_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if ((call.getOperator() == SqlStdOperatorTable.MOD || upper.equals("MOD"))
            && modFloatInvolved(call, hints)) {
          // MOD 浮点语义修正：仅操作数含浮点（字面量或目录已知浮点列）时挂本地
          // 实现（Java % 语义）；整数 MOD 保持原生路径（可下推源库）。
          // MOD(a,b) 函数形与 a MOD b / a % b 操作符形同名同实例，按名兜底匹配
          return new SqlBasicCall(MOD_FN, call.getOperandList(), call.getParserPosition());
        }
        if (call.getKind() == SqlKind.CAST) {
          return rewriteCastBoolean(call, hints);
        }
        if (call.getKind() == SqlKind.PLUS || call.getKind() == SqlKind.MINUS) {
          return rewriteDateArith(call, hints);
        }
        if (call.getKind() == SqlKind.MATCH_RECOGNIZE) {
          // SQL:2011 标准默认 AFTER MATCH SKIP PAST LAST ROW——Calcite 解析器/
          // 转换器默认给 SKIP TO NEXT ROW（与标准及 Oracle/PostgreSQL 不一致），
          // 未显式指定 AFTER 时补上标准默认字面量
          org.apache.calcite.sql.SqlMatchRecognize mr =
              (org.apache.calcite.sql.SqlMatchRecognize) call;
          if (mr.getAfter() == null) {
            mr.setOperand(org.apache.calcite.sql.SqlMatchRecognize.OPERAND_AFTER,
                SqlLiteral.createSymbol(
                    org.apache.calcite.sql.SqlMatchRecognize.AfterOption.SKIP_PAST_LAST_ROW,
                    mr.getParserPosition()));
          }
          return call;
        }
        if (call.getKind() == SqlKind.WITHIN_GROUP) {
          return rewriteWithinGroup(call);
        }
        if (call.getKind() == SqlKind.OTHER_FUNCTION) {
          return switch (upper) {
            case "BOOL_AND", "EVERY" -> new SqlBasicCall(BOOL_AND_FN, call.getOperandList(),
                call.getParserPosition());
            case "BOOL_OR" -> new SqlBasicCall(BOOL_OR_FN, call.getOperandList(),
                call.getParserPosition());
            case "INITCAP" -> new SqlBasicCall(INITCAP_FN, call.getOperandList(),
                call.getParserPosition());
            case "OVERLAY" -> {
              List<SqlNode> ops = call.getOperandList();
              yield ops.size() == 3 || ops.size() == 4
                  ? new SqlBasicCall(ops.size() == 3 ? OVERLAY_FN_3 : OVERLAY_FN_4,
                      ops, call.getParserPosition())
                  : call;
            }
            case "STRING_AGG", "GROUP_CONCAT" -> rewriteListAgg(call);
            case "VAR_POP", "VAR_SAMP", "STDDEV_POP", "STDDEV_SAMP" -> {
              List<SqlNode> ops = call.getOperandList();
              yield ops.size() == 1
                  ? new SqlBasicCall(call.getOperator(),
                      List.of(castDouble(ops.get(0), call.getParserPosition())),
                      call.getParserPosition())
                  : call;
            }
            default -> call;
          };
        }
        if (call.getKind() == SqlKind.FLOOR || call.getKind() == SqlKind.CEIL) {
          // FLOOR/CEIL(x TO unit)：datetime 截断语法下推源库必错，改本地 UDF
          List<SqlNode> ops = call.getOperandList();
          if (ops.size() == 2 && ops.get(1) instanceof SqlIntervalQualifier q
              && q.getUnit() != null) {
            return new SqlBasicCall(
                call.getKind() == SqlKind.FLOOR ? FLOOR_UNIT_FN : CEIL_UNIT_FN,
                List.of(ops.get(0), SqlLiteral.createCharString(q.getUnit().name(),
                    call.getParserPosition())),
                call.getParserPosition());
          }
          return call;
        }
        if (switch (call.getKind()) {
          case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> true;
          default -> false;
        }) {
          // 行构造器不等比较：Enumerable 运行时不实现 ROW 类型排序比较，
          // 展开为字典序等价的标量比较组合
          return rewriteRowComparison(call);
        }
        if (call.getKind() == SqlKind.OVER) {
          return rewriteOver(call);
        }
        return call;
      }
    };
  }

  /** TRY_CAST(x AS T)（SAFE_CAST kind）→ 按目标类型挂本地安全转换 UDF：
   * 数值目标族（失败/溢出得 NULL）；VARCHAR/CHAR 目标 CAST 恒成功退化为 CAST；
   * 其余目标（日期等）保留原样交校验器报错（待支持）。 */
  private static SqlNode rewriteTryCast(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !(ops.get(1) instanceof SqlDataTypeSpec spec)
        || !(spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec basic)) {
      return call;
    }
    SqlTypeName target = SqlTypeName.get(basic.getTypeName().getSimple());
    SqlUserDefinedFunction fn = switch (target) {
      case TINYINT, SMALLINT, INTEGER -> TRY_INT_FN;
      case BIGINT -> TRY_BIGINT_FN;
      case FLOAT, REAL, DOUBLE -> TRY_DOUBLE_FN;
      case DECIMAL -> TRY_DECIMAL_FN;
      default -> null;
    };
    if (fn != null) {
      return new SqlBasicCall(fn, List.of(ops.get(0)), call.getParserPosition());
    }
    if (target == SqlTypeName.VARCHAR || target == SqlTypeName.CHAR) {
      return new SqlBasicCall(SqlStdOperatorTable.CAST, ops, call.getParserPosition());
    }
    return call;
  }

  /** LOG(x)（PostgreSQL/MySQL 单参 = 自然对数，Calcite 仅注册 LN）→ LN(x)；
   * LOG(b, x)（对数底 b）→ LN(x) / LN(b)。其余元数保留原样交校验器报错。 */
  private static SqlNode rewriteLog(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    SqlParserPos pos = call.getParserPosition();
    if (ops.size() == 1) {
      return new SqlBasicCall(SqlStdOperatorTable.LN, ops, pos);
    }
    if (ops.size() == 2) {
      return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
          new SqlBasicCall(SqlStdOperatorTable.LN, List.of(ops.get(1)), pos),
          new SqlBasicCall(SqlStdOperatorTable.LN, List.of(ops.get(0)), pos)), pos);
    }
    return call;
  }

  /** STRCMP(a, b)（MySQL）→ CASE：任一 NULL 得 NULL；a=b 得 0、a<b 得 -1、
   * 其余（a>b）得 1。比较语义由校验器按操作数类型推导。 */
  private static SqlNode rewriteStrcmp(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlNode a = ops.get(0);
    SqlNode b = ops.get(1);
    SqlParserPos pos = call.getParserPosition();
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(new SqlBasicCall(SqlStdOperatorTable.OR, List.of(
        new SqlBasicCall(SqlStdOperatorTable.IS_NULL, List.of(a), pos),
        new SqlBasicCall(SqlStdOperatorTable.IS_NULL, List.of(b), pos)), pos));
    whens.add(new SqlBasicCall(SqlStdOperatorTable.EQUALS, List.of(a, b), pos));
    whens.add(new SqlBasicCall(SqlStdOperatorTable.LESS_THAN, List.of(a, b), pos));
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(SqlLiteral.createNull(pos));
    thens.add(SqlLiteral.createExactNumeric("0", pos));
    thens.add(SqlLiteral.createExactNumeric("-1", pos));
    return new SqlCase(pos, null, whens, thens,
        SqlLiteral.createExactNumeric("1", pos));
  }

  private static final java.util.Set<SqlTypeName> NUMERIC_TYPE_NAMES = java.util.Set.of(
      SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT,
      SqlTypeName.DECIMAL, SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE);

  /** CAST(布尔 AS 数值)（MySQL 布尔即 tinyint 语义，Calcite 校验器类型系统拒绝）→
   * 等价改写：TRUE/FALSE 字面量直接换 1/0；布尔列（列类型目录判定）换
   * CASE WHEN x THEN 1 WHEN NOT x THEN 0 END（缺省 ELSE NULL，NULL→NULL）。 */
  private static SqlNode rewriteCastBoolean(SqlCall call, ColumnHints hints) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !(ops.get(1) instanceof SqlDataTypeSpec spec)
        || !(spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec basic)) {
      return call;
    }
    // SqlBasicTypeNameSpec.getTypeName() 返回 SqlIdentifier（如 INT 归一为
    // INTEGER），经 SqlTypeName.get 映射回枚举判定数值目标类型
    SqlTypeName target = SqlTypeName.get(basic.getTypeName().getSimple());
    if (target == null || !NUMERIC_TYPE_NAMES.contains(target)) {
      return call;
    }
    SqlNode operand = ops.get(0);
    SqlParserPos pos = call.getParserPosition();
    if (operand instanceof SqlLiteral lit && lit.getTypeName() == SqlTypeName.BOOLEAN) {
      return SqlLiteral.createExactNumeric(
          Boolean.TRUE.equals(lit.getValue()) ? "1" : "0", pos);
    }
    if (operand instanceof SqlIdentifier id && !id.names.isEmpty()
        && hints.isBooleanColumn(id.names.get(id.names.size() - 1))) {
      SqlNodeList whens = new SqlNodeList(pos);
      whens.add(operand);
      whens.add(new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(operand), pos));
      SqlNodeList thens = new SqlNodeList(pos);
      thens.add(SqlLiteral.createExactNumeric("1", pos));
      thens.add(SqlLiteral.createExactNumeric("0", pos));
      return new SqlCase(pos, null, whens, thens, SqlLiteral.createNull(pos));
    }
    return call;
  }

  /** DATE 列 ± 整数（Oracle 语义：日加减，Calcite 类型系统原生不支持 DATE 与
   * 数值直接加减）→ DATE ± INTERVAL 'n' DAY（结果仍为 DATE，天精度区间不加带
   * 时间部分）。仅当裸列名在已注册库目录中无歧义地为 DATE/TIMESTAMP 时改写；
   * n + date 仅加法交换后改写。 */
  private static SqlNode rewriteDateArith(SqlCall call, ColumnHints hints) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    boolean plus = call.getKind() == SqlKind.PLUS;
    SqlNode date;
    SqlNode other;
    if (isDateColumn(ops.get(0), hints)) {
      date = ops.get(0);
      other = ops.get(1);
    } else if (plus && isDateColumn(ops.get(1), hints)) {
      date = ops.get(1);
      other = ops.get(0);
    } else {
      return call;
    }
    Long days = integerLiteral(other);
    if (days == null) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    org.apache.calcite.avatica.util.TimeUnit dayUnit =
        org.apache.calcite.avatica.util.TimeUnit.DAY;
    long magnitude = Math.abs(days);
    SqlNode interval = SqlLiteral.createInterval(days < 0 ? -1 : 1,
        Long.toString(magnitude), new SqlIntervalQualifier(dayUnit, null, pos), pos);
    return new SqlBasicCall(plus ? SqlStdOperatorTable.PLUS : SqlStdOperatorTable.MINUS,
        List.of(date, interval), pos);
  }

  /** DATE/TIMESTAMP 列判定（可带限定符：p.made 按裸列名查目录——限定符不影响
   * 类型归属，目录侧同名异类列已被剔除）。 */
  private static boolean isDateColumn(SqlNode node, ColumnHints hints) {
    if (!(node instanceof SqlIdentifier id) || id.names.isEmpty()) {
      return false;
    }
    return hints.isDateColumn(id.names.get(id.names.size() - 1));
  }

  /** 整数字面量（含一元负号形态）→ Long；非整数字面量返回 null。 */
  private static Long integerLiteral(SqlNode node) {
    if (node instanceof SqlLiteral lit && lit.getValue() instanceof BigDecimal bd
        && bd.scale() <= 0) {
      try {
        return bd.longValueExact();
      } catch (ArithmeticException e) {
        return null;
      }
    }
    if (node instanceof SqlBasicCall neg
        && neg.getOperator() == SqlStdOperatorTable.UNARY_MINUS
        && neg.getOperandList().size() == 1) {
      Long v = integerLiteral(neg.getOperandList().get(0));
      return v == null ? null : -v;
    }
    return null;
  }

  /** MOD 操作数是否含浮点成分：浮点/带标度 DECIMAL 字面量，或列类型目录中的
   * 浮点列（裸列名）。整数 MOD 不改写，保持原生下推路径。 */
  private static boolean modFloatInvolved(SqlCall mod, ColumnHints hints) {
    for (SqlNode op : mod.getOperandList()) {
      if (isFloatLiteral(op) || (op instanceof SqlIdentifier id && !id.names.isEmpty()
          && hints.isFloatColumn(id.names.get(id.names.size() - 1)))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFloatLiteral(SqlNode node) {
    if (!(node instanceof SqlLiteral lit)) {
      return false;
    }
    return switch (lit.getTypeName()) {
      case DOUBLE, FLOAT, REAL -> true;
      case DECIMAL -> lit.getValue() instanceof BigDecimal bd
          && bd.stripTrailingZeros().scale() > 0;
      default -> false;
    };
  }

  /** {@code a RLIKE / NOT RLIKE pattern}（校验器未注册的 SqlLikeOperator）→
   * {@code CROSSDB_REGEXP(a, pattern)} 本地求值；转义子句形态不支持，保留原样。 */
  private static SqlNode rewriteRlike(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlBasicCall regexp = new SqlBasicCall(REGEXP_FN, ops, pos);
    return call.getOperator().getName().equalsIgnoreCase("NOT RLIKE")
        ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(regexp), pos)
        : regexp;
  }

  /** IIF(cond, t, f)（SQL Server 方言，未注册于标准操作符表）→ CASE WHEN cond
   * THEN t ELSE f END（语义等价）。 */
  private static SqlNode rewriteIif(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 3) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(ops.get(0));
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(ops.get(1));
    return new SqlCase(pos, null, whens, thens, ops.get(2));
  }

  /** ISNULL(a, b)（SQL Server 方言）→ COALESCE(a, b)（NULL 语义一致）。 */
  private static SqlNode rewriteIsnull(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    return new SqlBasicCall(SqlStdOperatorTable.COALESCE, ops, call.getParserPosition());
  }

  /** {@code a ILIKE p / a NOT ILIKE p}（PostgreSQL 大小写不敏感 LIKE，校验器未注册）→
   * {@code LOWER(a) [NOT] LIKE LOWER(p)}（等价：两侧同折叠后匹配）。 */
  private static SqlNode rewriteIlike(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode like = new SqlBasicCall(SqlStdOperatorTable.LIKE, List.of(
        new SqlBasicCall(SqlStdOperatorTable.LOWER, List.of(ops.get(0)), pos),
        new SqlBasicCall(SqlStdOperatorTable.LOWER, List.of(ops.get(1)), pos)), pos);
    return call.getOperator().getName().equalsIgnoreCase("NOT ILIKE")
        ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(like), pos)
        : like;
  }

  /** ARRAY_AGG(x [ORDER BY o])（引擎不支持 ARRAY 值类型透出）→ CROSSDB_ARRAY_AGG(x)
   * （{@code "[v1, v2, ...]"} 字符串渲染、按值升序保证确定性）；ORDER BY 与取值
   * 表达式一致时升序内置于 UDAF、直接去包装，其余排序变体保留 WITHIN GROUP 形态
   * （Calcite 对 UDAF 不强制输入有序，排序尽力而为）。 */
  private static SqlNode rewriteArrayAgg(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode value = ops.get(0);
    SqlNodeList order = ops.size() > 1 && ops.get(1) instanceof SqlNodeList list
        ? list : null;
    SqlNode agg = new SqlBasicCall(ARRAY_AGG_FN, List.of(value), pos);
    // SqlNode.equals 为恒等语义，排序项与取值表达式的一致性按打印形态比较
    if (order == null || (order.size() == 1
        && order.get(0).toString().equalsIgnoreCase(value.toString()))) {
      return agg;
    }
    return new SqlBasicCall(new SqlWithinGroupOperator(), List.of(agg, order), pos);
  }

  /** AGG(x) FILTER (WHERE c) → AGG(CASE WHEN c THEN x END)：跳 NULL 语义的聚合上
   * 等价（SUM/COUNT/AVG/MIN/MAX 及本地 UDAF）；COUNT(*) 的星号操作数换为常量 1；
   * DISTINCT 量化符保留（COUNT 的去重变体直接换挂本地 CROSSDB_COUNT_DISTINCT）。
   * 多操作数（LISTAGG 等）形态返回 null，保留原样交校验器报错。 */
  private static SqlNode filterToCase(SqlCall agg, SqlNode cond, SqlParserPos pos) {
    List<SqlNode> ops = agg.getOperandList();
    SqlNode arg;
    if (ops.size() == 1 && ops.get(0) instanceof SqlIdentifier id && id.isStar()) {
      arg = SqlLiteral.createExactNumeric("1", pos);
    } else if (ops.size() == 1) {
      arg = ops.get(0);
    } else {
      return null;
    }
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(cond);
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(arg);
    SqlNode wrapped =
        new SqlCase(pos, null, whens, thens, SqlLiteral.createNull(pos));
    SqlLiteral quantifier =
        agg instanceof SqlBasicCall basic ? basic.getFunctionQuantifier() : null;
    if (quantifier != null && agg.getOperator().getName().equalsIgnoreCase("COUNT")
        && org.apache.calcite.sql.SqlSelectKeyword.DISTINCT
            == quantifier.getValueAs(org.apache.calcite.sql.SqlSelectKeyword.class)) {
      return new SqlBasicCall(COUNT_DISTINCT_FN, List.of(wrapped), pos);
    }
    return new SqlBasicCall(agg.getOperator(), List.of(wrapped), pos, quantifier);
  }

  /** 行构造器不等比较（Enumerable 运行时不实现 ROW 类型排序比较）→ 按标准 SQL
   * 行值比较的展开定义改写为标量比较组合：
   * {@code (a1..an) OP (b1..bn) ≡ OR_i ( AND_{j<i} a_j = b_j AND a_i OP b_i )}
   * （NULL 三值逻辑与展开定义一致）。任一侧非行构造器、字段数不符或为空时保留原样。 */
  private static SqlNode rewriteRowComparison(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !isRowCall(ops.get(0)) || !isRowCall(ops.get(1))) {
      return call;
    }
    List<SqlNode> left = ((SqlCall) ops.get(0)).getOperandList();
    List<SqlNode> right = ((SqlCall) ops.get(1)).getOperandList();
    if (left.size() != right.size() || left.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    org.apache.calcite.sql.SqlOperator cmpOp = switch (call.getKind()) {
      case LESS_THAN -> SqlStdOperatorTable.LESS_THAN;
      case LESS_THAN_OR_EQUAL -> SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
      case GREATER_THAN -> SqlStdOperatorTable.GREATER_THAN;
      default -> SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
    };
    SqlNode out = null;
    for (int i = 0; i < left.size(); i++) {
      SqlNode term = new SqlBasicCall(cmpOp, List.of(left.get(i), right.get(i)), pos);
      for (int j = i - 1; j >= 0; j--) {
        term = new SqlBasicCall(SqlStdOperatorTable.AND, List.of(
            new SqlBasicCall(SqlStdOperatorTable.EQUALS,
                List.of(left.get(j), right.get(j)), pos), term), pos);
      }
      out = out == null ? term
          : new SqlBasicCall(SqlStdOperatorTable.OR, List.of(out, term), pos);
    }
    return out;
  }

  private static boolean isRowCall(SqlNode node) {
    return node instanceof SqlCall c && c.getKind() == SqlKind.ROW;
  }

  /** STRING_AGG(x[, sep][, ORDER BY ..]) / GROUP_CONCAT(x[, ORDER BY ..][, SEPARATOR sep])
   * → LISTAGG(x, sep) [WITHIN GROUP (ORDER BY ..)]。 */
  private static SqlNode rewriteListAgg(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode value = ops.get(0);
    SqlNode sep = SqlLiteral.createCharString(",", pos);
    SqlNodeList order = null;
    for (int i = 1; i < ops.size(); i++) {
      SqlNode op = ops.get(i);
      if (op instanceof SqlNodeList list) {
        order = list;
      } else if (op instanceof SqlCall sepCall && sepCall.getKind() == SqlKind.SEPARATOR) {
        sep = sepCall.operand(0);
      } else if (op instanceof SqlLiteral lit && lit.getTypeName() == SqlTypeName.VARCHAR) {
        sep = op;
      }
    }
    // 原调用带 DISTINCT 量化符（STRING_AGG(DISTINCT ..) / GROUP_CONCAT(DISTINCT ..)）：
    // 直接换成本地去重 UDAF，避免静默丢失去重语义
    boolean distinct = call instanceof SqlBasicCall basic && basic.getFunctionQuantifier() != null;
    SqlNode rewritten = distinct
        ? new SqlBasicCall(LISTAGG_DISTINCT_FN, List.of(value, sep), pos)
        : new SqlBasicCall(SqlStdOperatorTable.LISTAGG, List.of(value, sep), pos);
    return order == null ? rewritten
        : new SqlBasicCall(new SqlWithinGroupOperator(), List.of(rewritten, order), pos);
  }

  /** DECODE(e, s1, r1[, s2, r2...][, default]) → 等价 searched CASE：
   * {@code CASE WHEN e IS NOT DISTINCT FROM s1 THEN r1 ... [ELSE default] END}
   * （Oracle 语义：NULL 与 NULL 视为相等，由 IS NOT DISTINCT FROM 承载；
   * 无缺省分支回落 NULL；元数 ≥3 的任意形态均可改写，否则原样保留交由校验器报错）。 */
  private static SqlNode rewriteDecode(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() < 3) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode target = ops.get(0);
    SqlNodeList whens = new SqlNodeList(pos);
    SqlNodeList thens = new SqlNodeList(pos);
    for (int i = 1; i + 1 < ops.size(); i += 2) {
      whens.add(new SqlBasicCall(SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
          List.of(target, ops.get(i)), pos));
      thens.add(ops.get(i + 1));
    }
    SqlNode elseNode = ops.size() % 2 == 0
        ? ops.get(ops.size() - 1)        // 末位缺省分支
        : SqlLiteral.createNull(pos);    // 无缺省回落 NULL
    return new SqlCase(pos, null, whens, thens, elseNode);
  }

  /** GREATEST/LEAST(a, b, ...) → 按元数挂载对应本地 UDF（跳过 NULL 取极值）。 */
  private static SqlNode rewriteExtremum(SqlCall call) {
    int n = call.operandCount();
    if (n < 2 || n > 4) {
      return call;
    }
    boolean greatest = call.getOperator().getName().equalsIgnoreCase("GREATEST");
    SqlUserDefinedFunction fn = switch ((greatest ? "G" : "L") + n) {
      case "G2" -> GREATEST2;
      case "G3" -> GREATEST3;
      case "G4" -> GREATEST4;
      case "L2" -> LEAST2;
      case "L3" -> LEAST3;
      default -> LEAST4;
    };
    return new SqlBasicCall(fn, call.getOperandList(), call.getParserPosition());
  }

  /** TIMESTAMPDIFF(unit, a, b)（Calcite SqlTimestampDiffFunction，下推源库普遍无此
   * 函数）→ CROSSDB_TIMESTAMPDIFF('unit', a, b) 本地求值。兼容 BigQuery 的
   * 「时间在前、unit 在后」参数顺序。无法安全改写的形态原样保留。 */
  private static SqlNode rewriteTimestampDiff(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 3) {
      return call;
    }
    SqlIntervalQualifier unit = null;
    List<SqlNode> times = new ArrayList<>();
    for (SqlNode op : ops) {
      if (op instanceof SqlIntervalQualifier q && unit == null) {
        unit = q;
      } else {
        times.add(op);
      }
    }
    if (unit == null || unit.getStartUnit() == null || times.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    return new SqlBasicCall(TIMESTAMPDIFF_FN, List.of(
        SqlLiteral.createCharString(unit.getStartUnit().toString(), pos),
        times.get(0), times.get(1)), pos);
  }

  /** WITHIN GROUP 包装改写：
   * <ul>
   *   <li>PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x) → CROSSDB_PERCENTILE_CONT(x, p)
   *   （Enumerable 无原生实现）；</li>
   *   <li>PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY x) → CROSSDB_PERCENTILE_DISC(x, p)
   *   （离散百分位，Enumerable 无原生实现）；</li>
   *   <li>LISTAGG(DISTINCT x, sep) WITHIN GROUP (ORDER BY x) → CROSSDB_LISTAGG(x, sep)
   *   （原生 LISTAGG DISTINCT 计划期 AIOOBE；排序内置为按值升序，故要求 ORDER BY
   *   与取值表达式一致，否则保留原形态交由校验器报错）；</li>
   * </ul> */
  private static SqlNode rewriteWithinGroup(SqlCall within) {
    List<SqlNode> ops = within.getOperandList();
    if (ops.size() != 2 || !(ops.get(0) instanceof SqlCall agg)
        || !(ops.get(1) instanceof SqlNodeList order) || order.size() != 1) {
      return within;
    }
    SqlParserPos pos = within.getParserPosition();
    if (agg.getOperator() == PERCENTILE_CONT_FN || agg.getKind() == SqlKind.PERCENTILE_CONT
        || agg.getOperator().getName().equalsIgnoreCase("PERCENTILE_CONT")) {
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 1) {
        return new SqlBasicCall(PERCENTILE_CONT_FN,
            List.of(order.get(0), aggOps.get(0)), pos);
      }
      return within;
    }
    if (agg.getOperator() == PERCENTILE_DISC_FN || agg.getKind() == SqlKind.PERCENTILE_DISC
        || agg.getOperator().getName().equalsIgnoreCase("PERCENTILE_DISC")) {
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 1) {
        return new SqlBasicCall(PERCENTILE_DISC_FN,
            List.of(order.get(0), aggOps.get(0)), pos);
      }
      return within;
    }
    if (agg.getOperator() == LISTAGG_DISTINCT_FN) {
      // 内层 LISTAGG(DISTINCT ..) 已被改写；仅当排序表达式与取值一致时去掉包装
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 2 && order.get(0).equals(aggOps.get(0))) {
        return agg;
      }
    }
    return within;
  }

  /** LISTAGG(DISTINCT x, sep) → CROSSDB_LISTAGG(x, sep)（DISTINCT 量化符剥离，
   * 去重 + 按值升序由本地 UDAF 实现；排序语义由 WITHIN GROUP 包装层收口）。 */
  private static SqlNode rewriteListaggDistinct(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !(call instanceof SqlBasicCall basic)
        || basic.getFunctionQuantifier() == null) {
      return call;
    }
    return new SqlBasicCall(LISTAGG_DISTINCT_FN, ops, call.getParserPosition());
  }

  /** 窗口聚合改写：CUME_DIST/PERCENT_RANK 用 RANK/COUNT(*) 等价表达；
   * NTH_VALUE 换成本地窗口聚合（帧内第 n 行）；
   * FILTER（与 OVER 组合时校验器拒绝「OVER must be applied to aggregate function」）→
   * 等价 CASE 包参改写；IGNORE NULLS（FIRST_VALUE/LAST_VALUE）→ 本地非 NULL 端点
   * 窗口聚合；COUNT(DISTINCT x)（EnumerableWindow 静默丢弃 DISTINCT 量化符）→
   * 本地去重计数窗口聚合；其余原样。 */
  private static SqlNode rewriteOver(SqlCall over) {
    List<SqlNode> ops = over.getOperandList();
    if (ops.size() != 2 || !(ops.get(0) instanceof SqlCall agg)
        || !(ops.get(1) instanceof org.apache.calcite.sql.SqlWindow w)) {
      return over;
    }
    SqlParserPos pos = over.getParserPosition();
    // AGG(x) FILTER (WHERE c) OVER w → AGG(CASE WHEN c THEN x END) OVER w：
    // 跳 NULL 语义的聚合（SUM/COUNT/AVG/MIN/MAX 及本地 UDAF）上两者等价。
    // 注意解析树上 std 聚合尚未绑定（kind=OTHER_FUNCTION），isAggregator() 为
    // false，须按名白名单 + 已绑定操作符共同判定
    if (agg.getKind() == SqlKind.FILTER) {
      List<SqlNode> fops = agg.getOperandList();
      if (fops.size() == 2 && fops.get(0) instanceof SqlCall inner) {
        String innerName = inner.getOperator().getName().toUpperCase();
        if (inner.getOperator().isAggregator() || innerName.equals("SUM")
            || innerName.equals("COUNT") || innerName.equals("AVG")
            || innerName.equals("MIN") || innerName.equals("MAX")) {
          SqlNode rewritten = filterToCase(inner, fops.get(1), pos);
          if (rewritten != null) {
            return over(rewritten, w, pos);
          }
        }
      }
      return over;   // 无法安全改写的形态保留，交校验器报真实错误
    }
    // FIRST_VALUE/LAST_VALUE(x) IGNORE NULLS OVER w → 本地首/末非 NULL 端点聚合；
    // 仅在 OVER 语境下改写（裸 IGNORE NULLS 无窗口本就非法）。LEAD/LAG 等保留。
    if (agg.getKind() == SqlKind.IGNORE_NULLS && agg.getOperandList().size() == 1
        && agg.getOperandList().get(0) instanceof SqlCall inner
        && inner.getOperandList().size() == 1) {
      String name = inner.getOperator().getName().toUpperCase();
      if (name.equals("FIRST_VALUE") || name.equals("LAST_VALUE")) {
        return over(new SqlBasicCall(
            name.equals("FIRST_VALUE") ? FIRST_VALUE_NN_FN : LAST_VALUE_NN_FN,
            inner.getOperandList(), inner.getParserPosition()), w, pos);
      }
      return over;
    }
    // COUNT(DISTINCT x) OVER w：EnumerableWindow 静默丢弃 DISTINCT（得到 COUNT(*)
    // 语义的错误结果），改挂本地去重计数 UDAF 修正
    if (agg instanceof SqlBasicCall basic
        && basic.getFunctionQuantifier() != null
        && org.apache.calcite.sql.SqlSelectKeyword.DISTINCT
            == basic.getFunctionQuantifier()
                .getValueAs(org.apache.calcite.sql.SqlSelectKeyword.class)
        && agg.getOperator().getName().equalsIgnoreCase("COUNT")
        && agg.getOperandList().size() == 1
        && !(agg.getOperandList().get(0) instanceof SqlIdentifier id && id.isStar())) {
      return over(new SqlBasicCall(COUNT_DISTINCT_FN, agg.getOperandList(),
          agg.getParserPosition()), w, pos);
    }
    switch (agg.getOperator().getName().toUpperCase()) {
      case "CUME_DIST" -> {        // CUME_DIST() OVER w == RANK() OVER w / COUNT(*) OVER (同分区、无排序)
        return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
            castDouble(over(rank(pos), w, pos), pos),
            over(count(pos), partitionOnly(w), pos)), pos);
      }
      case "PERCENT_RANK" -> {
        // PERCENT_RANK() OVER w == (RANK() OVER w - 1) / (COUNT(*) OVER (同分区) - 1)
        return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
            new SqlBasicCall(SqlStdOperatorTable.MINUS, List.of(
                castDouble(over(rank(pos), w, pos), pos),
                SqlLiteral.createExactNumeric("1", pos)), pos),
            new SqlBasicCall(SqlStdOperatorTable.MINUS, List.of(
                over(count(pos), partitionOnly(w), pos),
                SqlLiteral.createExactNumeric("1", pos)), pos)), pos);
      }
      case "NTH_VALUE" -> {
        // NTH_VALUE(x, n) → CROSSDB_NTH_VALUE{n}(x)：本地窗口聚合按「帧内第 n 行」
        // 取值（Calcite 内建与源库均按整分区取值、忽略帧）。常量 n 不进操作数、
        // 编码进函数名，绕开窗口聚合常量参数被输入投影裁剪的缺陷；n 不在 1..4
        // 时保留原样。
        List<SqlNode> aggOps = agg.getOperandList();
        if (aggOps.size() == 2 && aggOps.get(1) instanceof SqlLiteral lit
            && lit.getValue() instanceof Number num
            && num.intValue() >= 2 && num.intValue() <= 4) {
          SqlUserDefinedAggFunction fn = switch (num.intValue()) {
            case 2 -> NTH_VALUE2_FN;
            case 3 -> NTH_VALUE3_FN;
            default -> NTH_VALUE4_FN;
          };
          return over(new SqlBasicCall(fn, List.of(aggOps.get(0)), agg.getParserPosition()),
              w, pos);
        }
        return over;
      }
      default -> {
        return over;
      }
    }
  }

  private static SqlNode rank(SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.RANK, new SqlNode[0], pos);
  }

  private static SqlNode count(SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.COUNT, List.of(SqlIdentifier.STAR), pos);
  }

  private static SqlNode over(SqlNode aggCall, org.apache.calcite.sql.SqlWindow w, SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.OVER, List.of(aggCall, w), pos);
  }

  /** 同分区、无排序的窗口副本：COUNT(*) OVER (PARTITION BY ..) 给出分区总行数。
   * 在克隆上改写，不影响原窗口（RANK 仍需其 ORDER BY）。 */
  @SuppressWarnings("unchecked")
  private static org.apache.calcite.sql.SqlWindow partitionOnly(
      org.apache.calcite.sql.SqlWindow w) {
    org.apache.calcite.sql.SqlWindow copy = (org.apache.calcite.sql.SqlWindow) w.clone(w.getParserPosition());
    List<SqlNode> operands = copy.getOperandList();
    for (int i = 0; i < operands.size(); i++) {
      if (operands.get(i) == copy.getOrderList()) {
        copy.setOperand(i, new SqlNodeList(w.getParserPosition()));
        return copy;
      }
    }
    return copy;
  }

  private static SqlNode castDouble(SqlNode expr, SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.CAST, List.of(expr,
        new SqlDataTypeSpec(new SqlBasicTypeNameSpec(SqlTypeName.DOUBLE, pos), pos)), pos);
  }
}
