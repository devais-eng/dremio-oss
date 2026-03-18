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
package com.dremio.plugins.jdbc.planning;

import com.google.common.collect.ImmutableSet;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;

/**
 * Standard SQL function whitelist valid for both PostgreSQL and Oracle (and most ANSI SQL databases).
 *
 * <p>Functions are identified by either their {@link SqlKind} (preferred — exact match, no string
 * comparison overhead) or their operator name for {@link SqlKind#OTHER_FUNCTION} operators
 * (identified by name only).
 *
 * <p>Whitelisted by kind:
 * <ul>
 *   <li>{@code CAST(col AS type)}</li>
 *   <li>{@code FLOOR(x)}</li>
 *   <li>{@code CEIL(x)}</li>
 *   <li>{@code TRIM(x)}</li>
 *   <li>{@code LTRIM(x)}</li>
 *   <li>{@code RTRIM(x)}</li>
 *   <li>{@code EXTRACT(YEAR FROM date)}</li>
 *   <li>{@code COALESCE(a, b, ...)}</li>
 *   <li>{@code NULLIF(a, b)}</li>
 * </ul>
 *
 * <p>Whitelisted by name (kind = {@link SqlKind#OTHER_FUNCTION}):
 * <ul>
 *   <li>{@code UPPER}, {@code LOWER}</li>
 *   <li>{@code ABS}, {@code ROUND}</li>
 *   <li>{@code SUBSTRING}</li>
 *   <li>{@code CHAR_LENGTH}, {@code CHARACTER_LENGTH}, {@code LENGTH}</li>
 *   <li>{@code L2_DISTANCE} — pgvector L2 distance; renders as {@code <->} via
 *       {@link com.dremio.plugins.jdbc.postgresql.DremioPostgresDialect}</li>
 *   <li>{@code COSINE_DISTANCE} — pgvector cosine distance; renders as {@code <=>}</li>
 *   <li>{@code INNER_PRODUCT} — pgvector inner product; renders as {@code <#>}</li>
 * </ul>
 *
 * <p>This class is a singleton. Per-dialect subclasses (e.g. a PostgreSQL registry that also
 * supports {@code pgvector} operators) can extend or replace {@code INSTANCE} by overriding
 * {@link com.dremio.plugins.jdbc.JdbcStoragePlugin#getPushdownFunctionRegistry()}.
 */
public class StandardPushdownFunctionRegistry implements PushdownFunctionRegistry {

  /** Shared singleton instance. All standard JDBC sources use this registry. */
  public static final StandardPushdownFunctionRegistry INSTANCE =
      new StandardPushdownFunctionRegistry();

  /**
   * SqlKind values whose operators are unconditionally whitelisted.
   * These cover CAST, numeric rounding (FLOOR/CEIL), string trimming (TRIM/LTRIM/RTRIM),
   * date-part extraction (EXTRACT), and null-handling (COALESCE, NULLIF).
   */
  private static final Set<SqlKind> WHITELISTED_KINDS = EnumSet.of(
      SqlKind.CAST,      // CAST(col AS type)
      SqlKind.FLOOR,     // FLOOR(x)
      SqlKind.CEIL,      // CEIL(x)
      SqlKind.TRIM,      // TRIM(x)
      SqlKind.LTRIM,     // LTRIM(x)
      SqlKind.RTRIM,     // RTRIM(x)
      SqlKind.EXTRACT,   // EXTRACT(YEAR FROM date)
      SqlKind.COALESCE,  // COALESCE(a, b, ...)
      SqlKind.NULLIF     // NULLIF(a, b)
  );

  /**
   * Function names (uppercase) for operators with kind {@link SqlKind#OTHER_FUNCTION}.
   * These are standard SQL scalar functions present in both PostgreSQL and Oracle.
   */
  private static final Set<String> WHITELISTED_FUNCTION_NAMES = ImmutableSet.of(
      "UPPER",            // UPPER(str)
      "LOWER",            // LOWER(str)
      "ABS",              // ABS(n)
      "ROUND",            // ROUND(n) / ROUND(n, scale)
      "SUBSTRING",        // SUBSTRING(str FROM start FOR len)
      "CHAR_LENGTH",      // CHAR_LENGTH(str) — SQL standard alias for LENGTH
      "CHARACTER_LENGTH",  // CHARACTER_LENGTH(str) — SQL standard
      "LENGTH",           // LENGTH(str) — common dialect alias
      "L2_DISTANCE",      // pgvector L2 distance; renders as <-> via DremioPostgresDialect
      "COSINE_DISTANCE",  // pgvector cosine distance; renders as <=>
      "INNER_PRODUCT"     // pgvector inner product; renders as <#>
  );

  /** Private constructor; use {@link #INSTANCE}. */
  protected StandardPushdownFunctionRegistry() {}

  /**
   * Returns {@code true} if {@code op} is in the whitelist.
   *
   * <p>Lookup order:
   * <ol>
   *   <li>Check {@link #WHITELISTED_KINDS} — O(1) EnumSet lookup</li>
   *   <li>If kind is {@link SqlKind#OTHER_FUNCTION}, check {@link #WHITELISTED_FUNCTION_NAMES}</li>
   *   <li>Otherwise return {@code false}</li>
   * </ol>
   */
  @Override
  public boolean isFunctionPushable(SqlOperator op) {
    if (WHITELISTED_KINDS.contains(op.getKind())) {
      return true;
    }
    if (op.getKind() == SqlKind.OTHER_FUNCTION) {
      return WHITELISTED_FUNCTION_NAMES.contains(op.getName().toUpperCase(Locale.ROOT));
    }
    return false;
  }
}
