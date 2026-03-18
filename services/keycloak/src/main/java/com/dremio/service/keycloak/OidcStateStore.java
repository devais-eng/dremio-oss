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

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process store for pending OIDC Authorization Code flows.
 *
 * <p>Each entry maps a random {@code state} nonce to the corresponding PKCE {@code code_verifier}
 * and an expiry timestamp. Entries expire after a configurable TTL (default: 5 minutes) to prevent
 * memory leaks from abandoned flows. Retrieval is one-time-use to prevent replay attacks (OIDC-03).
 *
 * <p>This class is bound as a singleton in {@code DACDaemonModule} for the Keycloak auth branch and
 * is consumed by Phase 33's {@code OidcResource}.
 */
public class OidcStateStore {

  /** Default TTL for pending OIDC flows: 5 minutes. */
  private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

  private final long ttlMs;
  private final ConcurrentHashMap<String, PendingFlow> pending = new ConcurrentHashMap<>();

  /** Creates a store with the default 5-minute TTL. */
  public OidcStateStore() {
    this(DEFAULT_TTL_MS);
  }

  /**
   * Creates a store with a custom TTL. Package-private to allow short TTLs in unit tests.
   *
   * @param ttlMs time-to-live in milliseconds for each pending flow entry
   */
  OidcStateStore(long ttlMs) {
    this.ttlMs = ttlMs;
  }

  /**
   * Stores a pending OIDC flow entry keyed by the {@code state} nonce.
   *
   * <p>Also performs lazy cleanup of any already-expired entries in the map.
   *
   * @param state the random state nonce sent to the authorization endpoint
   * @param codeVerifier the PKCE code verifier generated for this flow
   */
  public void put(String state, String codeVerifier) {
    long expiresAt = System.currentTimeMillis() + ttlMs;
    pending.put(state, new PendingFlow(codeVerifier, expiresAt));
    // Lazy cleanup: opportunistically remove expired entries to prevent unbounded growth
    pending.entrySet().removeIf(e -> e.getValue().isExpired());
  }

  /**
   * Atomically removes and returns the {@code code_verifier} for the given {@code state} nonce.
   *
   * <p>Returns {@code null} if the state is unknown or has expired. The entry is removed on first
   * call (one-time use) to prevent replay attacks.
   *
   * @param state the state nonce received in the OIDC callback
   * @return the stored {@code code_verifier}, or {@code null} if not found or expired
   */
  public String removeIfValid(String state) {
    PendingFlow entry = pending.remove(state);
    if (entry == null || entry.isExpired()) {
      return null;
    }
    return entry.codeVerifier();
  }

  /** Immutable holder for a pending OIDC flow's code verifier and expiry time. */
  private static final class PendingFlow {
    private final String codeVerifier;
    private final long expiresAtEpochMs;

    PendingFlow(String codeVerifier, long expiresAtEpochMs) {
      this.codeVerifier = codeVerifier;
      this.expiresAtEpochMs = expiresAtEpochMs;
    }

    String codeVerifier() {
      return codeVerifier;
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiresAtEpochMs;
    }
  }
}
