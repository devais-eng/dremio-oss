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
package com.dremio.plugins.jdbc.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for Oracle INTERVAL string parsing in {@link JdbcRecordReader}.
 *
 * <p>Oracle INTERVAL types are returned as strings by the JDBC driver. These parsers convert them
 * to Arrow-compatible integer representations:
 *
 * <ul>
 *   <li>INTERVAL YEAR TO MONTH → total months (int)
 *   <li>INTERVAL DAY TO SECOND → [days, milliseconds] (int[2])
 * </ul>
 */
public class TestIntervalParsing {

  // ---- INTERVAL YEAR TO MONTH → total months ----

  @Test
  public void testYearMonthPositive() {
    assertEquals(30, JdbcRecordReader.parseIntervalYearMonths("+02-06"));
  }

  @Test
  public void testYearMonthNegative() {
    assertEquals(-15, JdbcRecordReader.parseIntervalYearMonths("-01-03"));
  }

  @Test
  public void testYearMonthNoSign() {
    assertEquals(30, JdbcRecordReader.parseIntervalYearMonths("02-06"));
  }

  @Test
  public void testYearMonthZero() {
    assertEquals(0, JdbcRecordReader.parseIntervalYearMonths("00-00"));
  }

  @Test
  public void testYearMonthYearsOnly() {
    assertEquals(36, JdbcRecordReader.parseIntervalYearMonths("3-0"));
  }

  @Test
  public void testYearMonthMonthsOnly() {
    assertEquals(6, JdbcRecordReader.parseIntervalYearMonths("0-6"));
  }

  @Test
  public void testYearMonthLargeValue() {
    assertEquals(1200, JdbcRecordReader.parseIntervalYearMonths("+100-00"));
  }

  @Test
  public void testYearMonthNull() {
    assertEquals(0, JdbcRecordReader.parseIntervalYearMonths(null));
  }

  @Test
  public void testYearMonthEmpty() {
    assertEquals(0, JdbcRecordReader.parseIntervalYearMonths(""));
  }

  @Test
  public void testYearMonthWithSpaces() {
    assertEquals(30, JdbcRecordReader.parseIntervalYearMonths("  +02-06  "));
  }

  // ---- INTERVAL DAY TO SECOND → [days, millis] ----

  @Test
  public void testDaySecondBasic() {
    // 5 days, 12:30:45.123 = 12*3600000 + 30*60000 + 45123 = 45045123
    int[] result = JdbcRecordReader.parseIntervalDayMillis("+5 12:30:45.123");
    assertEquals(5, result[0]);
    assertEquals(45045123, result[1]);
  }

  @Test
  public void testDaySecondNegative() {
    int[] result = JdbcRecordReader.parseIntervalDayMillis("-2 06:00:00.0");
    assertEquals(-2, result[0]);
    assertEquals(-21600000, result[1]);
  }

  @Test
  public void testDaySecondNoFraction() {
    int[] result = JdbcRecordReader.parseIntervalDayMillis("1 02:00:00");
    assertEquals(1, result[0]);
    assertEquals(7200000, result[1]);
  }

  @Test
  public void testDaySecondZero() {
    int[] result = JdbcRecordReader.parseIntervalDayMillis("0 00:00:00.0");
    assertEquals(0, result[0]);
    assertEquals(0, result[1]);
  }

  @Test
  public void testDaySecondDaysOnly() {
    // Oracle format for days-only: "5 0:0:0.0"
    int[] result = JdbcRecordReader.parseIntervalDayMillis("5 0:0:0.0");
    assertEquals(5, result[0]);
    assertEquals(0, result[1]);
  }

  @Test
  public void testDaySecondNull() {
    assertArrayEquals(new int[] {0, 0}, JdbcRecordReader.parseIntervalDayMillis(null));
  }

  @Test
  public void testDaySecondEmpty() {
    assertArrayEquals(new int[] {0, 0}, JdbcRecordReader.parseIntervalDayMillis(""));
  }

  @Test
  public void testDaySecondWithSpaces() {
    int[] result = JdbcRecordReader.parseIntervalDayMillis("  +3 01:00:00.0  ");
    assertEquals(3, result[0]);
    assertEquals(3600000, result[1]);
  }

  @Test
  public void testDaySecondHighPrecision() {
    // Oracle can return 6 fractional digits: "5 12:30:45.123456"
    int[] result = JdbcRecordReader.parseIntervalDayMillis("+5 12:30:45.123456");
    assertEquals(5, result[0]);
    // milliseconds truncated: 45.123456 → 45123
    assertEquals(45045123, result[1]);
  }
}
