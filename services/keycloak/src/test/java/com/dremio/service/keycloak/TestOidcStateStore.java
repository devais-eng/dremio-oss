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

/** Unit tests for {@link OidcStateStore}. */
public class TestOidcStateStore {

  @Test
  public void testPutThenRemoveIfValidReturnsVerifier() {
    OidcStateStore store = new OidcStateStore();
    store.put("state1", "verifier1");
    assertThat(store.removeIfValid("state1")).isEqualTo("verifier1");
  }

  @Test
  public void testRemoveIfValidUnknownStateReturnsNull() {
    OidcStateStore store = new OidcStateStore();
    assertThat(store.removeIfValid("unknown")).isNull();
  }

  @Test
  public void testRemoveIfValidSameStateTwiceReturnsNullOnSecond() {
    OidcStateStore store = new OidcStateStore();
    store.put("state1", "verifier1");
    // First call returns the verifier
    assertThat(store.removeIfValid("state1")).isEqualTo("verifier1");
    // Second call must return null (one-time use)
    assertThat(store.removeIfValid("state1")).isNull();
  }

  @Test
  public void testRemoveIfValidExpiredStateReturnsNull() throws InterruptedException {
    // Use very short TTL (50 ms) for expiry test
    OidcStateStore store = new OidcStateStore(50L);
    store.put("state1", "verifier1");
    // Wait past the TTL
    Thread.sleep(100L);
    assertThat(store.removeIfValid("state1")).isNull();
  }

  @Test
  public void testPutCleanupExpiredEntries() throws InterruptedException {
    // Use very short TTL (50 ms) to test lazy cleanup on subsequent put()
    OidcStateStore store = new OidcStateStore(50L);
    store.put("state1", "verifier1");
    // Wait past the TTL
    Thread.sleep(100L);
    // This put() should trigger lazy cleanup of the expired "state1" entry
    store.put("state2", "verifier2");
    // The expired entry should now be gone (cleanup ran)
    assertThat(store.removeIfValid("state1")).isNull();
    // And state2 is accessible (it was just inserted with fresh TTL)
    assertThat(store.removeIfValid("state2")).isEqualTo("verifier2");
  }
}
