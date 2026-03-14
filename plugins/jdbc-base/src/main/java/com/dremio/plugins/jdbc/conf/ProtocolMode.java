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
package com.dremio.plugins.jdbc.conf;

/**
 * Selects the wire-protocol backend for JDBC storage plugin query execution.
 *
 * <ul>
 *   <li>{@link #AUTO} — Try ADBC (native Arrow via JNI) first; fall back to JDBC if the native
 *       driver is unavailable.
 *   <li>{@link #JDBC} — Force the traditional JDBC/ResultSet execution path (existing behavior).
 *   <li>{@link #ADBC} — Force the ADBC native execution path; fail if the native driver is
 *       unavailable.
 * </ul>
 */
public enum ProtocolMode {
  AUTO,
  JDBC,
  ADBC
}
