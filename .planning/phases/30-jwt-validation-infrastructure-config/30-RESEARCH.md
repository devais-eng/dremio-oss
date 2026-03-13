# Phase 30: JWT Validation Infrastructure + Config - Research

**Researched:** 2026-03-12
**Domain:** Keycloak OIDC JWT validation, Dremio config system, Nimbus JOSE+JWT library
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CFG-01 | Operator sets `services.coordinator.web.auth.type = "keycloak"` | `DACDaemonModule.setupUserService()` throws `RuntimeException` for unknown auth types; add `"keycloak"` branch there. `DACConfig.isInternalUserAuth()` checks for `"internal"`; needs no change but a new `isKeycloakAuth()` helper is conventional. |
| CFG-02 | Operator configures Keycloak: issuer-url, client-id, client-secret, redirect-uri | New config path `services.keycloak.*` follows the pattern of `services.rbac.*`; constants in `DremioConfig`; defaults in `dremio-reference.conf`. |
| CFG-03 | Role sync mode (additive / authoritative) via `services.keycloak.role.sync-mode` | Same config pattern; string enum "additive" \| "authoritative"; read at startup, exposed as `KeycloakConfig.getRoleSyncMode()`. |
| TKN-01 | Dremio validates Keycloak RS256 JWTs against JWKS endpoint (signature, expiry, issuer, audience) | Nimbus `JWKSourceBuilder.create(url).retrying(true).build()` + `JWSVerificationKeySelector(RS256, source)` + `DefaultJWTClaimsVerifier` for issuer/audience/exp. |
| TKN-03 | JWKS cache auto-refreshes on unknown `kid` (key rotation) | `JWKSourceBuilder`-created source handles unknown-kid re-fetch automatically; no extra code needed; rate limiter prevents thundering-herd. |
</phase_requirements>

---

## Summary

Dremio OSS already ships the Nimbus JOSE+JWT library (`nimbus-jose-jwt` 9.41) in the `services/tokens` module. The existing `JWTValidator`, `JWKSetManager`, and `JWTProcessorFactory` types are designed for Dremio-issued (EC/ES256) tokens signed by the coordinator itself. Phase 30 introduces a parallel validator — `OidcTokenValidator` — that fetches and caches Keycloak's **remote** JWKS endpoint and validates RS256-signed Keycloak JWTs. The two validators co-exist independently.

The config story is straightforward: Dremio uses Typesafe Config (`dremio-reference.conf`) with string constants declared in `DremioConfig.java`. Adding `services.keycloak.*` keys follows an established pattern used for `services.rbac.*`. The coordinator startup crashes with a `RuntimeException` for unknown `services.coordinator.web.auth.type` values; adding a `"keycloak"` branch makes CFG-01 pass.

Key rotation (TKN-03) is handled for free by Nimbus `JWKSourceBuilder`: when a JWT's `kid` is not in the current cache, the source re-fetches Keycloak's JWKS endpoint, subject to a built-in rate limit. No custom cache-invalidation logic is required.

**Primary recommendation:** Place new code in a new `services/keycloak` Maven module (following the `services/tokens` pattern). Module exports `KeycloakConfig` (config bean), `OidcTokenValidator` (JWT validation), and `KeycloakJwksSource` (thin wrapper around `JWKSourceBuilder`). Register all in `DACDaemonModule` behind the `"keycloak"` auth-type branch.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.nimbusds:nimbus-jose-jwt` | 9.41 (already in root pom) | JWT parse/validate, RS256, JWKSourceBuilder, key rotation | Already used in `services/tokens`; no new dependency needed |
| `com.typesafe:config` | inherited | Read `services.keycloak.*` config keys | Existing config system throughout Dremio |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.nimbusds:oauth2-oidc-sdk` | 11.20 (declared in root pom, unused so far) | OIDC discovery, `OIDCProviderMetadata` | Phase 33 (OIDC flow); NOT needed for Phase 30 |
| `org.immutables:value` | inherited | Immutable config POJO | Follow existing pattern (`AuthResult`, `JWTClaims`) |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `JWKSourceBuilder` | Hand-rolled `HttpURLConnection` JWKS fetch | JWKSourceBuilder gives caching, rate-limiting, retrying, and unknown-kid re-fetch for free; do not hand-roll |
| New `services/keycloak` module | Inline in `services/tokens` or `dac/backend` | Separate module avoids polluting existing JWT token module; matches project convention for new service concerns |

**Installation:** No new Maven dependencies needed for Phase 30 — `nimbus-jose-jwt` 9.41 is already in the BOM. Add `dremio-services-keycloak` dependency in `dac/backend/pom.xml` once the new module is created.

---

## Architecture Patterns

### Recommended Project Structure
```
services/keycloak/
├── src/main/java/com/dremio/service/keycloak/
│   ├── KeycloakConfig.java           # Config bean: reads DremioConfig, exposes typed getters
│   ├── OidcTokenValidator.java       # Validates Keycloak JWTs; returns TokenDetails
│   └── KeycloakJwksSource.java       # Builds JWKSource<SecurityContext> from issuer-url
├── src/test/java/com/dremio/service/keycloak/
│   ├── TestKeycloakConfig.java       # Unit: config parsing, defaults, missing-key errors
│   ├── TestOidcTokenValidator.java   # Unit: valid RS256 JWT, wrong issuer, wrong aud, expired, bad sig
│   └── TestKeycloakJwksSource.java   # Unit (mocked HTTP): kid hit, kid miss → re-fetch
└── pom.xml                           # depends on services-authenticator, nimbus-jose-jwt, services-tokens (for TokenDetails)
```

### Pattern 1: Config Constants in DremioConfig + dremio-reference.conf

**What:** All Dremio service config keys are declared as `public static final String` constants in `DremioConfig.java` and given defaults in `dremio-reference.conf`.
**When to use:** Always, for every new config key. Planner never hard-codes string paths outside `DremioConfig`.
**Example:**
```java
// In DremioConfig.java — following RBAC_ENABLED pattern on line 152
public static final String KEYCLOAK_ISSUER_URL     = "services.keycloak.issuer-url";
public static final String KEYCLOAK_CLIENT_ID      = "services.keycloak.client-id";
public static final String KEYCLOAK_CLIENT_SECRET  = "services.keycloak.client-secret";
public static final String KEYCLOAK_REDIRECT_URI   = "services.keycloak.redirect-uri";
public static final String KEYCLOAK_ROLE_SYNC_MODE = "services.keycloak.role.sync-mode";
```

```hocon
// In dremio-reference.conf — under the services { ... } block, after rbac { }
keycloak: {
  issuer-url:    ""
  client-id:     ""
  client-secret: ""
  redirect-uri:  ""
  role: {
    sync-mode: "additive"   # "additive" | "authoritative"
  }
}
```

### Pattern 2: KeycloakConfig Bean

**What:** A typed config bean constructed from `DremioConfig`, analogous to how `RbacConfig` centralises RBAC constants.
**When to use:** Any component that needs Keycloak settings injects `KeycloakConfig`, not raw `DremioConfig`.
```java
// Source: project pattern (RbacConfig, DremioConfig)
public final class KeycloakConfig {
  private final String issuerUrl;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String roleSyncMode;

  public KeycloakConfig(DremioConfig config) {
    this.issuerUrl    = config.getString(DremioConfig.KEYCLOAK_ISSUER_URL);
    this.clientId     = config.getString(DremioConfig.KEYCLOAK_CLIENT_ID);
    this.clientSecret = config.getString(DremioConfig.KEYCLOAK_CLIENT_SECRET);
    this.redirectUri  = config.getString(DremioConfig.KEYCLOAK_REDIRECT_URI);
    this.roleSyncMode = config.getString(DremioConfig.KEYCLOAK_ROLE_SYNC_MODE);
  }

  public boolean isAdditive() { return "additive".equalsIgnoreCase(roleSyncMode); }
  // ... getters
}
```

### Pattern 3: OidcTokenValidator using Nimbus JWKSourceBuilder

**What:** Validates a Keycloak RS256 JWT against the remote JWKS endpoint. Returns `TokenDetails` (existing type) on success.
**When to use:** Called from `DACAuthFilter` (Phase 31) when the Bearer token starts with `eyJ`.

```java
// Source: https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens
public class OidcTokenValidator {
  private final JWTProcessor<SecurityContext> jwtProcessor;

  public OidcTokenValidator(String jwksUri, String expectedIssuer, String expectedAudience) {
    JWKSource<SecurityContext> keySource = JWKSourceBuilder
        .create(new URL(jwksUri))
        .retrying(true)
        .build();

    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
    processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        new JWTClaimsSet.Builder()
            .issuer(expectedIssuer)
            .audience(expectedAudience)
            .build(),
        new HashSet<>(Arrays.asList("sub", "exp", "iat", "iss", "aud"))));
    this.jwtProcessor = processor;
  }

  /** @throws ParseException if string is not a valid JWT */
  /** @throws IllegalArgumentException if JWT fails validation (wrong issuer/audience/expiry/sig) */
  public TokenDetails validate(String jwtString) throws ParseException {
    JWT jwt = JWTParser.parse(jwtString);
    JWTClaimsSet claims;
    try {
      claims = jwtProcessor.process(jwt, null);
    } catch (BadJOSEException | JOSEException e) {
      throw new IllegalArgumentException("Keycloak JWT validation failed: " + e.getMessage(), e);
    }
    String preferredUsername = claims.getStringClaim("preferred_username");
    if (preferredUsername == null) preferredUsername = claims.getSubject();
    return TokenDetails.of(jwtString, preferredUsername,
        claims.getExpirationTime().getTime());
  }
}
```

### Pattern 4: DACDaemonModule.setupUserService — add keycloak branch

**What:** The coordinator startup calls `setupUserService()`. Auth type `"keycloak"` must register `SimpleUserService` (same as internal auth — users are still stored in KVStore) so UserService is available.
```java
// In DACDaemonModule.setupUserService() — after the isInternalUserAuth() block
if ("keycloak".equals(dacConfig.getConfig().getString(WEB_AUTH_TYPE))) {
  // Users stored locally (JIT provisioning adds them in Phase 32)
  final SimpleUserService simpleUserService =
      new SimpleUserService(registry.provider(LegacyKVStoreProvider.class), isMaster);
  registry.bindProvider(UserService.class, () -> simpleUserService);
  registry.bindSelf(simpleUserService);
  registry.bindProvider(UserResolver.class, () -> simpleUserService);

  // Bind KeycloakConfig and OidcTokenValidator
  final KeycloakConfig keycloakConfig = new KeycloakConfig(dacConfig.getConfig());
  registry.bind(KeycloakConfig.class, keycloakConfig);
  registry.bind(OidcTokenValidator.class,
      new OidcTokenValidator(
          keycloakConfig.getJwksUri(),   // issuer-url + /protocol/openid-connect/certs
          keycloakConfig.getIssuerUrl(),
          keycloakConfig.getClientId())); // audience = client-id for Keycloak
  logger.info("Keycloak authentication is configured.");
  return true; // returns true because user records still live in KVStore
}
```

### JWKS URI Construction

Keycloak's JWKS endpoint is a well-known path relative to the issuer URL:
```
{issuer-url}/protocol/openid-connect/certs
```
Example: `https://auth.example.com/realms/my-realm/protocol/openid-connect/certs`

The operator sets `services.keycloak.issuer-url` to the realm URL (e.g. `https://auth.example.com/realms/my-realm`). The `KeycloakConfig` bean computes the JWKS URI by appending the path suffix.

**Important:** Keycloak's JWT `iss` claim equals exactly `{issuer-url}` and `aud` equals `{client-id}` (for realm-level tokens). These must match the `DefaultJWTClaimsVerifier` configuration.

### Anti-Patterns to Avoid
- **Storing Keycloak access tokens in KVStore:** They are self-contained; out of scope per requirements.
- **Per-request Keycloak introspection endpoint calls:** Adds 100 ms+ latency; out of scope.
- **Sharing the ES256-keyed SystemJWKSetManager for Keycloak validation:** Keycloak uses RS256; the existing JWKS manager uses EC/ES256. They are completely separate validators.
- **Using `RemoteJWKSet` (deprecated):** Use `JWKSourceBuilder` instead, available since Nimbus 9.28. `RemoteJWKSet` is deprecated in current versions.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWKS HTTP fetch + cache | Custom `HttpURLConnection` + `ConcurrentHashMap` | `JWKSourceBuilder.create(url).retrying(true).build()` | Handles caching (5 min TTL), rate limiting (30 s), automatic refresh on unknown kid, retry on transient failure — all in ~3 lines |
| JWT signature verification | Custom RSA verify with `java.security` | `JWSVerificationKeySelector` + `DefaultJWTProcessor` | Handles algorithm negotiation, key selection by kid, signature verification correctly |
| Issuer/audience/expiry claim validation | Manual `if` chain on claims | `DefaultJWTClaimsVerifier` with required claims set | Handles clock-skew tolerance, required-claims enforcement, exact-match claims |
| Key rotation detection | Track seen kids, trigger refresh on mismatch | JWKSourceBuilder's built-in unknown-kid refresh | The `JWSVerificationKeySelector` triggers re-fetch from the `JWKSource` when the `kid` is not cached; rate limiter prevents abuse |

**Key insight:** Nimbus JOSE+JWT is a production-grade library with battle-tested edge-case handling. The hardest part of JWT validation (clock skew, kid-based rotation, graceful re-fetch) is already solved.

---

## Common Pitfalls

### Pitfall 1: Coordinator Crashes on Startup with Unknown Auth Type
**What goes wrong:** Setting `services.coordinator.web.auth.type = "keycloak"` throws `RuntimeException("Unknown auth type 'keycloak'...")` in `DACDaemonModule.setupUserService()`.
**Why it happens:** The current code has only an `"internal"` branch; everything else falls through to the error.
**How to avoid:** Add the `"keycloak"` branch before the error throw. This is the first task in the phase.
**Warning signs:** Coordinator log shows `Unknown value 'keycloak' set for services.coordinator.web.auth.type`.

### Pitfall 2: Wrong JWKS URI for Keycloak
**What goes wrong:** `OidcTokenValidator` fails to fetch keys; all JWT validations fail with connection error.
**Why it happens:** Keycloak's JWKS path is `{issuer-url}/protocol/openid-connect/certs`, not `{issuer-url}/.well-known/jwks.json`.
**How to avoid:** `KeycloakConfig.getJwksUri()` must append `/protocol/openid-connect/certs` to the issuer URL, not a generic OIDC path.
**Warning signs:** HTTP 404 on JWKS fetch; or wrong endpoint returning empty key set.

### Pitfall 3: Keycloak Audience Claim Mismatch
**What goes wrong:** Valid Keycloak tokens rejected with `IllegalArgumentException: Keycloak JWT validation failed`.
**Why it happens:** Keycloak's `aud` claim for an access token is the client-id (e.g., `"dremio"`), not a URL. If `DefaultJWTClaimsVerifier` is configured with a URL audience, all tokens fail.
**How to avoid:** Set `expectedAudience = keycloakConfig.getClientId()` in the `JWTClaimsSet.Builder()`. Confirm by inspecting an actual Keycloak JWT at jwt.io during testing.
**Warning signs:** Validation error message containing `aud` mismatch.

### Pitfall 4: Keycloak RS256 vs Dremio ES256
**What goes wrong:** Using the existing `JWTProcessorFactory` (which hardcodes `JWSAlgorithm.ES256`) to validate Keycloak tokens fails silently or throws.
**Why it happens:** Keycloak uses RS256 by default; the existing Dremio JWT infrastructure uses EC/ES256.
**How to avoid:** `OidcTokenValidator` must use `JWSAlgorithm.RS256` in its `JWSVerificationKeySelector`. Never reuse `JWTProcessorFactory` for Keycloak validation.
**Warning signs:** `BadJWSException: Signed JWT rejected: Another algorithm expected`.

### Pitfall 5: Missing `preferred_username` Claim Requires Fallback
**What goes wrong:** `TokenDetails.username` is null, causing `NullPointerException` downstream.
**Why it happens:** Keycloak puts the user's login name in `preferred_username`, not in `sub` (which is a UUID). If the claim is absent (e.g., client credentials grant), the fallback must be `sub`.
**How to avoid:** `OidcTokenValidator.validate()` reads `preferred_username` first, falls back to `sub`. This matches the project decision documented in `STATE.md`.
**Warning signs:** NPE in `DACAuthFilter` or username appearing as a UUID in Dremio.

### Pitfall 6: Empty Keycloak Config Keys Not Validated at Startup
**What goes wrong:** Coordinator starts with `auth.type=keycloak` but empty `issuer-url`; first login attempt throws a cryptic `MalformedURLException`.
**Why it happens:** Typesafe Config's `getString()` returns empty string for defaulted keys.
**How to avoid:** `KeycloakConfig` constructor validates that `issuer-url`, `client-id`, and `client-secret` are non-empty strings (throw `IllegalArgumentException` at startup if blank). This is a fast-fail pattern.
**Warning signs:** First API call fails with URL parse error rather than a startup error.

---

## Code Examples

### Building the JWKS-backed JWT Processor (Nimbus 9.28+)

```java
// Source: https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

URL jwksUrl = new URL(keycloakIssuerUrl + "/protocol/openid-connect/certs");

JWKSource<SecurityContext> keySource = JWKSourceBuilder
    .create(jwksUrl)
    .retrying(true)   // retries transient HTTP failures
    .build();         // caches 5 min; rate-limited 30s; refreshes on unknown kid

ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
processor.setJWSKeySelector(
    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
```

### Configuring Claim Validation

```java
// Source: https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import java.util.Arrays;
import java.util.HashSet;

processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
    new JWTClaimsSet.Builder()
        .issuer(expectedIssuer)           // e.g. "https://auth.example.com/realms/my-realm"
        .audience(expectedAudience)       // e.g. "dremio" (= client-id)
        .build(),
    new HashSet<>(Arrays.asList("sub", "exp", "iat", "iss", "aud"))
));
```

### Processing a Token

```java
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;

JWT jwt = JWTParser.parse(jwtString);                 // throws ParseException if not a JWT
try {
  JWTClaimsSet claims = processor.process(jwt, null); // validates sig, exp, iss, aud
  String username = claims.getStringClaim("preferred_username");
  if (username == null) username = claims.getSubject();
  long expiresAt = claims.getExpirationTime().getTime();
  return TokenDetails.of(jwtString, username, expiresAt);
} catch (BadJOSEException | JOSEException e) {
  throw new IllegalArgumentException("Invalid Keycloak JWT: " + e.getMessage(), e);
}
```

### Declaring Config Constants (DremioConfig.java pattern)

```java
// Source: DremioConfig.java lines 151-158 (RBAC_ENABLED pattern)
public static final String KEYCLOAK_ISSUER_URL     = "services.keycloak.issuer-url";
public static final String KEYCLOAK_CLIENT_ID      = "services.keycloak.client-id";
public static final String KEYCLOAK_CLIENT_SECRET  = "services.keycloak.client-secret";
public static final String KEYCLOAK_REDIRECT_URI   = "services.keycloak.redirect-uri";
public static final String KEYCLOAK_ROLE_SYNC_MODE = "services.keycloak.role.sync-mode";
```

### dremio-reference.conf defaults (append after rbac { } block)

```hocon
# Source: dremio-reference.conf line 351 (rbac block pattern)
keycloak: {
  issuer-url:    ""
  client-id:     ""
  client-secret: ""
  redirect-uri:  ""
  role: {
    sync-mode: "additive"
  }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `RemoteJWKSet` directly | `JWKSourceBuilder.create(url)` | Nimbus 9.28 (2022) | Builder wraps caching, rate limiting, retry; `RemoteJWKSet` deprecated |
| Manual kid-mismatch refresh | Automatic via `JWKSourceBuilder` unknown-kid re-fetch | Nimbus 9.x | No custom re-fetch logic needed |

**Deprecated/outdated:**
- `com.nimbusds.jose.jwk.source.RemoteJWKSet`: Deprecated; replaced by `JWKSourceBuilder`. The codebase's `RemoteJWKSetManager` is an internal Dremio class (not the Nimbus one) — do not confuse them.
- `DefaultJOSEObjectTypeVerifier(JOSEObjectType.JWT)`: Still valid for Dremio-issued JWTs; for Keycloak access tokens the `typ` header value may be `"JWT"` or absent — do not mandate `typ` check for the Keycloak validator.

---

## Open Questions

1. **Keycloak `aud` claim format**
   - What we know: Keycloak's `aud` for an access token granted via Authorization Code Flow typically equals the client-id. With "Full Scope Allowed" disabled, `aud` may list multiple audiences.
   - What's unclear: Whether the operator's deployment always has `client-id` as sole audience, or if Dremio should accept any audience list containing `client-id`.
   - Recommendation: Start with exact-match `audience = client-id`. Relax to `contains` check in Phase 31 if integration testing reveals multi-audience tokens.

2. **HTTP timeouts for JWKS fetch**
   - What we know: Nimbus `JWKSourceBuilder` uses its built-in defaults (connect 500 ms, read 500 ms based on `RemoteJWKSet` constants).
   - What's unclear: Whether the production environment needs custom timeouts.
   - Recommendation: Use Nimbus defaults for Phase 30. Add a configurable `services.keycloak.jwks-timeout-ms` option in Phase 31 if needed.

3. **`services.keycloak.*` config accessible in new module**
   - What we know: `DremioConfig` is defined in `common/legacy` and is on the classpath of all modules that depend on it.
   - What's unclear: The new `services/keycloak` module POM parent chain — confirm it inherits from `dremio-services-parent` to get the common legacy dependency.
   - Recommendation: Verify parent POM chain when creating the new module; follow `services/tokens/pom.xml` as the template.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 (existing tests) + JUnit 5 Jupiter (newer tests in `services/tokens`) — both present |
| Config file | `pom.xml` — Maven Surefire picks up both |
| Quick run command | `mvn test -pl services/keycloak -am -DfailIfNoTests=false` |
| Full suite command | `mvn test -pl services/tokens,services/keycloak,dac/backend -am -DfailIfNoTests=false` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CFG-01 | Coordinator starts with `auth.type=keycloak` (no RuntimeException) | unit | `mvn test -pl dac/backend -Dtest=TestDACDaemonModuleKeycloak -am` | ❌ Wave 0 |
| CFG-02 | `KeycloakConfig` reads all 4 config keys correctly | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakConfig -am` | ❌ Wave 0 |
| CFG-03 | `KeycloakConfig.isAdditive()` returns correct value for each sync-mode string | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakConfig#testRoleSyncMode -am` | ❌ Wave 0 |
| TKN-01 | Valid RS256 JWT → `OidcTokenValidator.validate()` returns `TokenDetails` | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testValidToken -am` | ❌ Wave 0 |
| TKN-01 | Wrong issuer JWT → `IllegalArgumentException` | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testWrongIssuer -am` | ❌ Wave 0 |
| TKN-01 | Wrong audience JWT → `IllegalArgumentException` | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testWrongAudience -am` | ❌ Wave 0 |
| TKN-01 | Expired JWT → `IllegalArgumentException` | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testExpiredToken -am` | ❌ Wave 0 |
| TKN-01 | Bad signature JWT → `IllegalArgumentException` | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testBadSignature -am` | ❌ Wave 0 |
| TKN-03 | Unknown kid → JWKS re-fetched, new-key JWT accepted | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testKeyRotationRefetch -am` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl services/keycloak -am -DfailIfNoTests=false`
- **Per wave merge:** `mvn test -pl services/tokens,services/keycloak,dac/backend -am -DfailIfNoTests=false`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `services/keycloak/` — new Maven module (does not exist yet); must be created and added to `services/pom.xml`
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakConfig.java`
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java`
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakConfig.java`
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java`
- [ ] `services/keycloak/pom.xml` — modeled on `services/tokens/pom.xml`

---

## Sources

### Primary (HIGH confidence)
- Codebase: `services/tokens/src/main/java/com/dremio/service/tokens/jwt/` — existing JWTValidator, JWTProcessorFactory patterns (ES256)
- Codebase: `services/tokens/src/main/java/com/dremio/service/tokens/jwks/SystemJWKSetManager.java` — Nimbus usage patterns, Nimbus 9.41 already on classpath
- Codebase: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` lines 2194-2222 — exact crash point for unknown auth type
- Codebase: `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` lines 80, 151-158 — WEB_AUTH_TYPE constant, RBAC config pattern
- Codebase: `common/legacy/src/main/resources/dremio-reference.conf` lines 117-119, 351-356 — auth type default, rbac config defaults (pattern)
- [Nimbus JOSE+JWT JWT Access Token Validation](https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens) — RS256 + JWKSourceBuilder + DefaultJWTClaimsVerifier patterns
- [Nimbus JOSE+JWT Enhanced JWK Retrieval](https://connect2id.com/products/nimbus-jose-jwt/examples/enhanced-jwk-retrieval) — JWKSourceBuilder caching, rate-limiting, kid-refresh behavior

### Secondary (MEDIUM confidence)
- WebSearch: JWKSourceBuilder available since Nimbus 9.28, `RemoteJWKSet` deprecated in current versions — verified against connect2id.com documentation

### Tertiary (LOW confidence)
- None for Phase 30

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — nimbus-jose-jwt already on classpath; JWKSourceBuilder usage confirmed via official Nimbus docs
- Architecture: HIGH — config patterns verified directly from DremioConfig.java and dremio-reference.conf; module structure follows services/tokens
- Pitfalls: HIGH — issuer/aud mismatch and RS256/ES256 confusion verified from codebase reading; JWKS URI pattern from Keycloak documentation

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (Nimbus library stable; Keycloak JWKS URI pattern stable)
