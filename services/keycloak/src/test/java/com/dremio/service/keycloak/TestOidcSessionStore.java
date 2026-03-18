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
package com.dremio.service.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link OidcSessionStore}. */
public class TestOidcSessionStore {

  @Test
  public void testPutThenGetReturnsIdToken() {
    OidcSessionStore store = new OidcSessionStore();
    store.put("dremioToken", "idTokenStr");
    assertThat(store.get("dremioToken")).isEqualTo("idTokenStr");
  }

  @Test
  public void testGetUnknownTokenReturnsNull() {
    OidcSessionStore store = new OidcSessionStore();
    assertThat(store.get("unknown")).isNull();
  }

  @Test
  public void testRemoveThenGetReturnsNull() {
    OidcSessionStore store = new OidcSessionStore();
    store.put("dremioToken", "idTokenStr");
    store.remove("dremioToken");
    assertThat(store.get("dremioToken")).isNull();
  }

  @Test
  public void testPutOverwriteReturnsLatest() {
    OidcSessionStore store = new OidcSessionStore();
    store.put("dremioToken", "idTokenFirst");
    store.put("dremioToken", "idTokenSecond");
    assertThat(store.get("dremioToken")).isEqualTo("idTokenSecond");
  }
}
