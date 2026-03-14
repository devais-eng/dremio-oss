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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;

/**
 * Unit tests for {@link LiteralInliner}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Type formatting (all supported SQL types)
 *   <li>String escaping (single-quote doubling, backslash pass-through)
 *   <li>SQL injection safety (adversarial string values)
 *   <li>Full SQL inlining (single param, multiple params, edge cases)
 * </ul>
 */
public class TestLiteralInliner {

  // ---- Type formatting tests ----

  @Test
  public void testNullValue() {
    BindParam param = new BindParam(null, SqlTypeName.VARCHAR);
    assertEquals("NULL", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testNullValueIntegerType() {
    BindParam param = new BindParam(null, SqlTypeName.INTEGER);
    assertEquals("NULL", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testIntegerLiteral() {
    BindParam param = new BindParam(42, SqlTypeName.INTEGER);
    assertEquals("42", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testBigIntLiteral() {
    BindParam param = new BindParam(9999999999L, SqlTypeName.BIGINT);
    assertEquals("9999999999", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testSmallIntLiteral() {
    BindParam param = new BindParam(100, SqlTypeName.SMALLINT);
    assertEquals("100", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testTinyIntLiteral() {
    BindParam param = new BindParam(7, SqlTypeName.TINYINT);
    assertEquals("7", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testDoubleLiteral() {
    BindParam param = new BindParam(3.14, SqlTypeName.DOUBLE);
    assertEquals("3.14", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testFloatLiteral() {
    BindParam param = new BindParam(2.5f, SqlTypeName.FLOAT);
    assertEquals("2.5", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testDecimalLiteral() {
    BindParam param = new BindParam(new BigDecimal("99.99"), SqlTypeName.DECIMAL);
    assertEquals("99.99", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testVarcharLiteral() {
    BindParam param = new BindParam("hello", SqlTypeName.VARCHAR);
    assertEquals("'hello'", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testCharLiteral() {
    BindParam param = new BindParam("X", SqlTypeName.CHAR);
    assertEquals("'X'", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testBooleanTrue() {
    BindParam param = new BindParam(Boolean.TRUE, SqlTypeName.BOOLEAN);
    assertEquals("TRUE", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testBooleanFalse() {
    BindParam param = new BindParam(Boolean.FALSE, SqlTypeName.BOOLEAN);
    assertEquals("FALSE", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testDateLiteral() {
    // 2024-01-15 in epoch millis (UTC).
    long epochMillis = java.sql.Date.valueOf("2024-01-15").getTime();
    BindParam param = new BindParam(epochMillis, SqlTypeName.DATE);
    String literal = LiteralInliner.toSqlLiteral(param);
    assertTrue("Expected DATE 'YYYY-MM-DD' format: " + literal,
        literal.startsWith("DATE '") && literal.endsWith("'"));
    assertTrue("Expected 2024-01-15 in literal: " + literal,
        literal.contains("2024-01-15"));
  }

  @Test
  public void testTimeLiteral() {
    // 14:30:00 in epoch millis.
    long epochMillis = java.sql.Time.valueOf("14:30:00").getTime();
    BindParam param = new BindParam(epochMillis, SqlTypeName.TIME);
    String literal = LiteralInliner.toSqlLiteral(param);
    assertTrue("Expected TIME 'HH:mm:ss' format: " + literal,
        literal.startsWith("TIME '") && literal.endsWith("'"));
    assertTrue("Expected 14:30:00 in literal: " + literal,
        literal.contains("14:30:00"));
  }

  @Test
  public void testTimestampLiteral() {
    // 2024-01-15 14:30:00 UTC in epoch millis.
    long epochMillis = java.sql.Timestamp.valueOf("2024-01-15 14:30:00.0").getTime();
    BindParam param = new BindParam(epochMillis, SqlTypeName.TIMESTAMP);
    String literal = LiteralInliner.toSqlLiteral(param);
    assertTrue("Expected TIMESTAMP '...' format: " + literal,
        literal.startsWith("TIMESTAMP '") && literal.endsWith("'"));
    assertTrue("Expected date part in literal: " + literal,
        literal.contains("2024-01-15"));
  }

  // ---- String escaping tests ----

  @Test
  public void testSingleQuoteEscape() {
    BindParam param = new BindParam("O'Malley", SqlTypeName.VARCHAR);
    assertEquals("'O''Malley'", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testMultipleSingleQuotes() {
    // Input: it's a 'test'
    BindParam param = new BindParam("it's a 'test'", SqlTypeName.VARCHAR);
    assertEquals("'it''s a ''test'''", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testBackslashNotEscaped() {
    // Backslashes must NOT be doubled — standard_conforming_strings=on in PG 9.1+
    BindParam param = new BindParam("path\\to\\file", SqlTypeName.VARCHAR);
    assertEquals("'path\\to\\file'", LiteralInliner.toSqlLiteral(param));
  }

  @Test
  public void testEmptyString() {
    BindParam param = new BindParam("", SqlTypeName.VARCHAR);
    assertEquals("''", LiteralInliner.toSqlLiteral(param));
  }

  // ---- SQL injection safety tests ----

  @Test
  public void testSqlInjectionSingleQuoteBreakout() {
    // Adversarial input: ' OR '1'='1
    // Must be rendered as: ''' OR ''1''=''1'  (all quotes doubled inside wrapping quotes)
    BindParam param = new BindParam("' OR '1'='1", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    assertEquals("''' OR ''1''=''1'", result);
    // Verify no unbalanced quotes: result is a valid SQL string literal.
    // A valid literal has an even total count of single quotes.
    long quoteCount = result.chars().filter(c -> c == '\'').count();
    assertEquals("Quote count must be even for a valid SQL literal", 0, quoteCount % 2);
  }

  @Test
  public void testSqlInjectionSemicolonCommand() {
    // Semicolons are NOT special inside quoted strings — no injection possible.
    BindParam param = new BindParam("val; DROP TABLE users; --", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    assertEquals("'val; DROP TABLE users; --'", result);
  }

  @Test
  public void testSqlInjectionSqlKeywords() {
    // SQL keywords inside a string literal are just string data.
    BindParam param = new BindParam("SELECT * FROM users", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    assertEquals("'SELECT * FROM users'", result);
  }

  @Test
  public void testSqlInjectionUnionSelect() {
    // Classic UNION-based injection attempt.
    BindParam param = new BindParam("' UNION SELECT * FROM pg_shadow --", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    // The leading ' gets doubled — cannot break out of quoted context.
    assertEquals("''' UNION SELECT * FROM pg_shadow --'", result);
    // Still has even quote count.
    long quoteCount = result.chars().filter(c -> c == '\'').count();
    assertEquals("Quote count must be even", 0, quoteCount % 2);
  }

  @Test
  public void testSqlInjectionBackslashQuote() {
    // Java string "\\'; DROP TABLE--" is the 3-char prefix: backslash, single-quote, semicolon.
    // Backslash is literal (standard_conforming_strings — not an escape char).
    // Single quote is doubled. Result: \''; DROP TABLE--' wrapped in outer quotes.
    BindParam param = new BindParam("\\'; DROP TABLE--", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    // backslash passed through, ' doubled to '' → '\''
    // Full: '\'''; DROP TABLE--' — but leading \' is NOT breaking out because backslash is literal.
    // In Java string literal: "'\\''; DROP TABLE--'"
    assertEquals("'\\''; DROP TABLE--'", result);
  }

  @Test
  public void testSqlInjectionNestedQuotes() {
    // Input already has doubled quotes: a''b
    // After our escaping: each ' becomes '' — result: 'a''''b'
    BindParam param = new BindParam("a''b", SqlTypeName.VARCHAR);
    String result = LiteralInliner.toSqlLiteral(param);
    assertEquals("'a''''b'", result);
  }

  // ---- Full SQL inlining tests ----

  @Test
  public void testInlineNoParams() {
    String sql = "SELECT * FROM t WHERE x = 1";
    String result = LiteralInliner.inlineBindParams(sql, Collections.emptyList());
    assertEquals(sql, result);
  }

  @Test
  public void testInlineNullParams() {
    String sql = "SELECT * FROM t WHERE x = 1";
    String result = LiteralInliner.inlineBindParams(sql, null);
    assertEquals(sql, result);
  }

  @Test
  public void testInlineEmptyParams() {
    String sql = "SELECT * FROM t WHERE x = ?";
    String result = LiteralInliner.inlineBindParams(sql, Collections.emptyList());
    // Empty params: SQL unchanged (? placeholder stays).
    assertEquals(sql, result);
  }

  @Test
  public void testInlineSingleParam() {
    String sql = "SELECT * FROM t WHERE x = ?";
    BindParam param = new BindParam(5, SqlTypeName.INTEGER);
    String result = LiteralInliner.inlineBindParams(sql, Collections.singletonList(param));
    assertEquals("SELECT * FROM t WHERE x = 5", result);
  }

  @Test
  public void testInlineMultipleParams() {
    String sql = "SELECT * FROM t WHERE x = ? AND name = ?";
    BindParam p1 = new BindParam(5, SqlTypeName.INTEGER);
    BindParam p2 = new BindParam("test", SqlTypeName.VARCHAR);
    String result = LiteralInliner.inlineBindParams(sql, Arrays.asList(p1, p2));
    assertEquals("SELECT * FROM t WHERE x = 5 AND name = 'test'", result);
  }

  @Test
  public void testInlineStringWithSingleQuote() {
    String sql = "SELECT * FROM t WHERE name = ?";
    BindParam param = new BindParam("O'Malley", SqlTypeName.VARCHAR);
    String result = LiteralInliner.inlineBindParams(sql, Collections.singletonList(param));
    assertEquals("SELECT * FROM t WHERE name = 'O''Malley'", result);
  }

  @Test
  public void testInlineMixedTypes() {
    String sql = "INSERT INTO t VALUES (?, ?, ?, ?)";
    BindParam p1 = new BindParam(1, SqlTypeName.INTEGER);
    BindParam p2 = new BindParam(3.14, SqlTypeName.DOUBLE);
    BindParam p3 = new BindParam("hello", SqlTypeName.VARCHAR);
    BindParam p4 = new BindParam(null, SqlTypeName.INTEGER);
    String result = LiteralInliner.inlineBindParams(sql, Arrays.asList(p1, p2, p3, p4));
    assertEquals("INSERT INTO t VALUES (1, 3.14, 'hello', NULL)", result);
  }

  @Test
  public void testInlineSqlWithNoPlaceholders() {
    // SQL has no ? placeholders even though params are provided — params are unused.
    String sql = "SELECT 1";
    BindParam param = new BindParam(42, SqlTypeName.INTEGER);
    String result = LiteralInliner.inlineBindParams(sql, Collections.singletonList(param));
    assertEquals("SELECT 1", result);
  }
}
