/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.plugins.jdbc.postgresql;

import java.util.List;
import java.util.Locale;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;

/**
 * PostgreSQL SQL rendering dialect used by the Dremio JDBC connector.
 *
 * <p>Extends {@link PostgresqlSqlDialect} with an {@link #unparseCall} override that intercepts
 * the three pgvector distance functions registered in {@link VectorDistanceFunctions} and translates
 * them to pgvector infix operators:
 *
 * <ul>
 *   <li>{@code l2_distance(col, ARRAY[...])}      renders as {@code col <-> '[...]'}
 *   <li>{@code cosine_distance(col, ARRAY[...])}   renders as {@code col <=> '[...]'}
 *   <li>{@code inner_product(col, ARRAY[...])}     renders as {@code col <#> '[...]'}
 * </ul>
 *
 * <p>The {@code ARRAY[val1, val2, ...]} second operand is rendered as a pgvector text literal
 * {@code '[val1,val2,...]'} so that PostgreSQL can apply an implicit text→vector cast. pgvector's
 * HNSW and IVFFlat indexes only activate with the infix operator form; function-call syntax is not
 * index-accelerated.
 *
 * <p>Non-distance function calls (e.g. {@code UPPER}, {@code ROUND}, {@code CAST}) are delegated
 * to {@code super.unparseCall()} without modification.
 *
 * <p>The singleton {@link #INSTANCE} is wired via {@link PostgresConf#newPlugin}'s
 * {@code createDialect()} override so all PostgreSQL sources use this dialect.
 */
public final class DremioPostgresDialect extends PostgresqlSqlDialect {

  /** Shared singleton. All PostgreSQL JDBC sources use this instance. */
  public static final DremioPostgresDialect INSTANCE =
      new DremioPostgresDialect(PostgresqlSqlDialect.DEFAULT_CONTEXT);

  /**
   * Creates a new dialect with the given Calcite context.
   *
   * @param context the SQL dialect context (quoting style, null collation, etc.)
   */
  public DremioPostgresDialect(SqlDialect.Context context) {
    super(context);
  }

  /**
   * Overrides SQL rendering for pgvector distance function calls.
   *
   * <p>Intercepts {@code l2_distance}, {@code cosine_distance}, and {@code inner_product} and
   * renders them as pgvector infix operators. For any other function, delegates to the parent class.
   *
   * @param writer the SQL writer
   * @param call the SQL call to render
   * @param leftPrec left precedence
   * @param rightPrec right precedence
   */
  @Override
  public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    String pgOp = getPgvectorOp(call.getOperator().getName());
    if (pgOp == null) {
      super.unparseCall(writer, call, leftPrec, rightPrec);
      return;
    }
    // Render: left <OP> right
    SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.SIMPLE, "", "");
    call.operand(0).unparse(writer, leftPrec, rightPrec);
    writer.print(" " + pgOp + " ");
    renderVectorOperand(writer, call.operand(1), leftPrec, rightPrec);
    writer.endList(frame);
  }

  /**
   * Maps a distance function name to its pgvector infix operator, or {@code null} if the function
   * is not a pgvector distance function.
   *
   * @param funcName the function name (case-insensitive)
   * @return the pgvector operator string, or {@code null}
   */
  private static String getPgvectorOp(String funcName) {
    if (funcName == null) {
      return null;
    }
    switch (funcName.toLowerCase(Locale.ROOT)) {
      case "l2_distance":     return "<->";
      case "cosine_distance": return "<=>";
      case "inner_product":   return "<#>";
      default:                return null;
    }
  }

  /**
   * Renders the right-hand operand to a pgvector distance operator.
   *
   * <p>If the operand is an {@code ARRAY[lit1, lit2, ...]} constructor, renders it as
   * {@code '[lit1,lit2,...]'} — the pgvector text literal format. Numeric values are extracted
   * via {@link BigDecimal#toPlainString()} to avoid scientific notation (e.g. {@code 1E2} renders
   * as {@code 100}).
   *
   * <p>For column references and all other operand forms, falls through to normal
   * {@link SqlNode#unparse} rendering.
   *
   * @param writer the SQL writer
   * @param operand the operand to render
   * @param lp left precedence
   * @param rp right precedence
   */
  private void renderVectorOperand(SqlWriter writer, SqlNode operand, int lp, int rp) {
    if (operand instanceof SqlCall) {
      SqlCall argCall = (SqlCall) operand;
      if (argCall.getKind() == SqlKind.ARRAY_VALUE_CONSTRUCTOR) {
        // Render ARRAY[1.0, 2.0, 3.0] as '[1.0,2.0,3.0]'
        StringBuilder sb = new StringBuilder("'[");
        List<SqlNode> elems = argCall.getOperandList();
        for (int i = 0; i < elems.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          SqlNode elem = elems.get(i);
          if (elem instanceof SqlNumericLiteral) {
            // Use BigDecimal to avoid scientific notation (e.g. 1E2 → 100)
            java.math.BigDecimal val =
                ((SqlNumericLiteral) elem).getValueAs(java.math.BigDecimal.class);
            sb.append(val != null ? val.toPlainString() : elem.toString());
          } else if (elem instanceof SqlLiteral) {
            java.math.BigDecimal val =
                ((SqlLiteral) elem).getValueAs(java.math.BigDecimal.class);
            sb.append(val != null ? val.toPlainString() : elem.toString());
          } else {
            sb.append(elem.toString());
          }
        }
        sb.append("]'");
        writer.print(sb.toString());
        return;
      }
    }
    // Column reference or other expression — unparse normally
    operand.unparse(writer, lp, rp);
  }
}
