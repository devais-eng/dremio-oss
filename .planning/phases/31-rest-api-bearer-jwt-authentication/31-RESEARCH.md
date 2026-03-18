# Phase 31: REST API Bearer JWT Authentication - Research

**Researched:** 2026-03-12
**Domain:** Dremio DACAuthFilter modification, JAX-RS ContainerRequestFilter, Keycloak JWT + Dremio opaque token coexistence
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| TKN-02 | DACAuthFilter accepts Keycloak Bearer tokens on REST API requests when auth.type=keycloak | Modify `DACAuthFilter.getUserNameFromToken()`: detect `eyJ` prefix on the extracted token, branch to `oidcTokenValidator.validate(token)` if present and `auth.type=keycloak`. OidcTokenValidator is already bound in the DI registry from Phase 30. |
| COEX-01 | When auth.type=keycloak, local admin can still login with username/password for bootstrap/recovery | `LogInLogOutResource.login()` calls `userService.authenticate()` which delegates to `SimpleUserService.authenticate()` — this code path is completely separate from DACAuthFilter (it runs before any Dremio session token is issued). No change needed for COEX-01, but must be verified end-to-end. |
| COEX-02 | Dremio session tokens continue to work alongside Keycloak tokens (DACAuthFilter tries Keycloak first, falls back to Dremio TokenManager) | In the non-temporary-access path: if `auth.type=keycloak` AND token starts with `eyJ`, call `oidcTokenValidator.validate()`; otherwise fall through to `tokenManager.validateToken()`. This is a pure additive change — existing behaviour preserved for all non-`eyJ` tokens. |
</phase_requirements>

---

## Summary

Phase 30 built `OidcTokenValidator` and bound it in `DACDaemonModule` under the keycloak auth-type branch. Phase 31 wires that validator into `DACAuthFilter` so that REST API clients can send `Authorization: Bearer <KC_JWT>` and have Dremio authenticate them.

The key design decision from `STATE.md` is the **`eyJ` prefix discriminator**: Keycloak JWTs are compact-serialised JWTs (three Base64URL-encoded segments separated by `.`), and every JWT starts with `eyJ` (the Base64URL encoding of `{"` — the beginning of a JSON header). Dremio opaque tokens are random hex or UUID strings that never start with `eyJ`. This makes the discriminator reliable with zero false positives.

`DACAuthFilter` uses JAX-RS field injection (`@Inject`). The `OidcTokenValidator` is only bound in the HK2 registry when `auth.type=keycloak`, so the field must be declared `@Inject @Nullable private OidcTokenValidator oidcTokenValidator`. The existing `@Inject @Nullable private DremioConfig dremioConfig` field already demonstrates this pattern.

**Primary recommendation:** Modify only `DACAuthFilter.getUserNameFromToken()`. Add `@Inject @Nullable private OidcTokenValidator oidcTokenValidator` field. In the non-temporary path, after extracting the token string, check: if `oidcTokenValidator != null` (keycloak mode) AND token starts with `eyJ`, call `oidcTokenValidator.validate(token)`. All other tokens fall through to `tokenManager.validateToken()` unchanged. Both `ParseException` and `IllegalArgumentException` from `validate()` must be caught and re-thrown as `NotAuthorizedException` to return HTTP 401 (not 500).

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.dremio.service.keycloak.OidcTokenValidator` | Phase 30 (project) | Validate Keycloak RS256 JWTs; returns `TokenDetails` | Built in Phase 30; already bound in DI registry for keycloak auth type |
| JAX-RS `ContainerRequestFilter` | javax.ws.rs 2.1 | Filter entry point for DACAuthFilter | Existing Dremio pattern; no change needed |
| HK2 `@Inject @Nullable` | jersey-hk2 | Optional field injection for OidcTokenValidator | Existing pattern in DACAuthFilter (`@Nullable private DremioConfig`, `@Nullable private RbacService`) |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.dremio.service.tokens.TokenManager` | project | Validate Dremio opaque tokens | Fallback path for non-`eyJ` tokens (unchanged) |
| `com.dremio.dac.server.tokens.TokenUtils` | project | Parse `Authorization:` header, detect `BEARER` vs `CUSTOM` token type | Already used; `TokenUtils.getAuthHeaderToken()` returns `Tuple<TokenType, String>` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `eyJ` prefix discriminator | Check `TokenType.BEARER` from `TokenUtils.getAuthHeaderToken()` | BEARER type already exists in `TokenType` enum but is used generically — both Dremio JWTs (from `TokenManager.createJwt()`) and Keycloak JWTs are Bearer tokens. The `eyJ` prefix uniquely identifies JWT compact serialisation regardless of issuer. |
| `@Inject @Nullable OidcTokenValidator` | `DremioConfig`-based conditional (check config at runtime) | Nullable injection is idiomatic HK2 and consistent with existing DACAuthFilter pattern; no need to inject DremioConfig separately for this check |

**No new Maven dependencies required.** `dremio-services-keycloak` is already in `dac/backend/pom.xml` from Phase 30.

---

## Architecture Patterns

### Current DACAuthFilter Token Resolution

```
getUserNameFromToken()
  ├─ TemporaryAccess resource?
  │   ├─ query param token → tokenManager.validateTemporaryToken()
  │   └─ header token → validateTemporaryToken(), fallback to validateToken()
  └─ Normal resource (most REST calls)
      └─ getAuthHeaderToken() → token string
          └─ tokenManager.validateToken(token)   ← ONLY PATH TODAY
```

### Phase 31 DACAuthFilter Token Resolution

```
getUserNameFromToken()
  ├─ TemporaryAccess resource?          (UNCHANGED)
  │   └─ ... (same as today)
  └─ Normal resource
      └─ getAuthHeaderToken() → token string
          ├─ oidcTokenValidator != null AND token.startsWith("eyJ")?
          │   └─ oidcTokenValidator.validate(token)   ← NEW: Keycloak JWT path
          │       throws ParseException/IllegalArgumentException → NotAuthorizedException (→ 401)
          └─ else
              └─ tokenManager.validateToken(token)    ← UNCHANGED: Dremio opaque token path
```

### Pattern 1: `@Inject @Nullable` for Optional HK2 Service

**What:** HK2 injects `null` when a binding for the type is not present in the registry. Used in DACAuthFilter already for `RbacService` and `DremioConfig`.

**When to use:** When a dependency is only bound conditionally (e.g., only when `auth.type=keycloak`).

```java
// Source: DACAuthFilter.java lines 57-58 — existing pattern
@Inject @Nullable private RbacService rbacService;
@Inject @Nullable private DremioConfig dremioConfig;

// Phase 31: add this field in the same style
@Inject @Nullable private OidcTokenValidator oidcTokenValidator;
```

### Pattern 2: `eyJ` Prefix Discriminator

**What:** A compact-serialised JWT always starts with `eyJ` (Base64URL of `{"`) in the header segment. Dremio opaque tokens are random alphanumeric strings that never match this prefix.

**When to use:** In `DACAuthFilter.getUserNameFromToken()` to decide which validation path to invoke.

```java
// Source: STATE.md key design decision; project convention documented there
private static final String JWT_COMPACT_PREFIX = "eyJ";

// In getUserNameFromToken(), normal-resource path:
final Tuple<TokenUtils.TokenType, String> tokenTuple = TokenUtils.getAuthHeaderToken(requestContext);
Preconditions.checkArgument(tokenTuple != null);
final String tokenStr = tokenTuple.second;

TokenDetails tokenDetails;
if (oidcTokenValidator != null && tokenStr.startsWith(JWT_COMPACT_PREFIX)) {
  // Keycloak JWT path
  try {
    tokenDetails = oidcTokenValidator.validate(tokenStr);
  } catch (java.text.ParseException | IllegalArgumentException e) {
    throw new NotAuthorizedException(e);
  }
} else {
  // Dremio opaque token path (unchanged)
  tokenDetails = tokenManager.validateToken(tokenStr);
}
```

### Pattern 3: Exception Translation to HTTP 401

**What:** `OidcTokenValidator.validate()` throws `ParseException` (non-JWT input) or `IllegalArgumentException` (failed validation). Both must map to `NotAuthorizedException`, which `DACAuthFilter.filter()` catches to return `Response.Status.UNAUTHORIZED`.

**When to use:** Any exception from the Keycloak path that is not a system fault.

```java
// Source: DACAuthFilter.filter() lines 71-73 — existing catch block
} catch (UserNotFoundException | NotAuthorizedException e) {
  requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
}
```

The existing catch block already handles `NotAuthorizedException`. Wrapping both Nimbus exceptions in `NotAuthorizedException(e)` correctly returns 401. A raw `ParseException` that escapes without being wrapped would become a 500 — this must not happen.

### Anti-Patterns to Avoid

- **Catching Nimbus exceptions in `filter()` directly:** The existing `filter()` method only catches `UserNotFoundException | NotAuthorizedException`. Do NOT widen this catch block. Instead, wrap Nimbus exceptions in `NotAuthorizedException` inside `getUserNameFromToken()`.
- **Checking `dremioConfig.getString(WEB_AUTH_TYPE)` in DACAuthFilter:** Redundant — if `oidcTokenValidator` is non-null, keycloak is active. Using the null-check is simpler and avoids injecting `DremioConfig` separately.
- **Modifying the TemporaryAccess path:** The `@TemporaryAccess` branch does not need Keycloak JWT support (those endpoints serve download/export, not programmatic API access). Leave that path unchanged.
- **Keycloak JWT validation for all Bearer tokens regardless of auth.type:** Only invoke `oidcTokenValidator` when it is non-null (i.e., keycloak mode). Internal auth deployments must be unaffected.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWT format detection | Regex on header.payload.signature pattern | `startsWith("eyJ")` check | Compact-serialised JWTs always start with `eyJ`; the Nimbus `JWTParser.parse()` will fail fast for non-JWT strings before any validation runs |
| Keycloak JWT validation | Manual RSA signature verify | `OidcTokenValidator.validate()` from Phase 30 | Already built and tested with 10 unit tests; JWKS caching, key rotation, expiry check all included |
| Exception → 401 mapping | Custom `ExceptionMapper` | Wrap in `NotAuthorizedException` and let existing `filter()` catch block handle it | Consistent with how `tokenManager.validateToken()` exceptions are handled today |

---

## Common Pitfalls

### Pitfall 1: `ParseException` Escapes Without Wrapping → 500

**What goes wrong:** `OidcTokenValidator.validate()` throws `java.text.ParseException` (not `IllegalArgumentException`) when the token string is not a valid JWT. If this is not caught in `getUserNameFromToken()`, it propagates to the Jersey runtime as an unhandled checked exception, resulting in HTTP 500.

**Why it happens:** `ParseException` is a checked exception not declared in any JAX-RS catch block. `getUserNameFromToken()` only has a `throws NotAuthorizedException` signature.

**How to avoid:** Catch BOTH `ParseException` and `IllegalArgumentException` from `oidcTokenValidator.validate()` and wrap them in `new NotAuthorizedException(e)`. The success criterion "malformed or expired JWT returns 401, not 500" tests exactly this.

**Warning signs:** Integration test for malformed JWT gets HTTP 500 instead of 401.

### Pitfall 2: `@Inject @Nullable` Field Remains Null in Internal Auth Mode (Expected)

**What goes wrong:** Fear that `oidcTokenValidator == null` in tests causes `NullPointerException`.

**Why it happens:** When `auth.type=internal` (default for test servers), `OidcTokenValidator` is not bound in DACDaemonModule, so HK2 injects `null`. This is the CORRECT behaviour — the null-check gates Keycloak validation.

**How to avoid:** Always guard with `if (oidcTokenValidator != null && ...)`. The existing DACAuthFilter already uses this pattern for `rbacService`.

**Warning signs:** Would manifest as `NullPointerException` if the null guard is forgotten.

### Pitfall 3: Import Conflict — `javax.annotation.Nullable` vs `org.jetbrains.annotations.Nullable`

**What goes wrong:** Using the wrong `@Nullable` import causes HK2 to not recognise the annotation and inject non-null (throwing `MultiException`), or the wrong semantics at compile time.

**Why it happens:** Multiple `@Nullable` annotations exist in the codebase.

**How to avoid:** Use `javax.annotation.Nullable` — this is what the existing DACAuthFilter fields use:
```java
import javax.annotation.Nullable;
```

**Warning signs:** DACAuthFilter startup exception about missing binding, or spotless/checkstyle error.

### Pitfall 4: Dremio JWT Tokens (`createJwt`) Start with `eyJ` Too

**What goes wrong:** `TokenManager.createJwt()` produces ES256 Dremio-issued JWTs, which also start with `eyJ`. If a client uses a Dremio JWT as their Bearer token in keycloak mode, the filter tries Keycloak validation, which fails with `IllegalArgumentException` (wrong issuer/signature), returning 401 instead of passing it to `tokenManager.validateToken()`.

**Why it happens:** `eyJ` is a JWT format prefix, not a Keycloak-specific prefix.

**How to handle (design decision):** The project decision (STATE.md) is that the `eyJ` discriminator distinguishes Keycloak JWTs from **Dremio opaque tokens** (random hex). Dremio JWT tokens (`createJwt`) are an internal mechanism used by specific flows (not end-user REST API auth). The fallback from Keycloak validation failure to Dremio `TokenManager` is possible but adds complexity.

**Recommended approach:** Try Keycloak validation first; if it throws, try Dremio `TokenManager.validateToken()` as a secondary fallback for tokens starting with `eyJ`. This ensures Dremio JWTs continue to work even in keycloak mode. Alternatively, note that Dremio JWT tokens start with `eyJ` only if the `createJwt()` path is in use — confirm whether any existing tests rely on Dremio JWTs in keycloak mode. If no such path exists, a simpler approach (Keycloak fail → 401, no fallback) is acceptable.

**Warning signs:** Integration test using `tokenManager.createJwt()` returns 401 in keycloak mode.

### Pitfall 5: Test Server Does Not Bind `OidcTokenValidator` (Internal Auth by Default)

**What goes wrong:** Unit tests for `DACAuthFilter` that use `BaseTestServer` will have `auth.type=internal`, so `oidcTokenValidator` is null. Tests for the keycloak path must override the DACConfig.

**Why it happens:** `BaseTestServer` defaults to `DACConfig.newDebugConfig(DEFAULT_SABOT_CONFIG)` which uses `"internal"` auth.

**How to avoid:** For keycloak path tests, either:
1. Create a standalone unit test that directly instantiates `DACAuthFilter` with a mock `OidcTokenValidator` (preferred for speed), or
2. Extend `BaseTestServer` with a `DACConfig.with(WEB_AUTH_TYPE, "keycloak")` override — heavier integration test.

The unit test approach (mock-based) is faster and does not require a running server.

**Warning signs:** Test passes despite wrong behaviour because `oidcTokenValidator` is null in test context.

---

## Code Examples

### DACAuthFilter Modification (Complete `getUserNameFromToken` for Normal-Resource Path)

```java
// Source: DACAuthFilter.java — Phase 31 modification
import com.dremio.service.keycloak.OidcTokenValidator;
import javax.annotation.Nullable;

@Secured
@Provider
@Priority(Priorities.AUTHENTICATION)
public class DACAuthFilter implements ContainerRequestFilter {

  private static final String JWT_COMPACT_PREFIX = "eyJ";

  @Inject private javax.inject.Provider<UserService> userService;
  @Inject private TokenManager tokenManager;
  @Inject private ResourceInfo resourceInfo;
  @Inject @Nullable private RbacService rbacService;
  @Inject @Nullable private DremioConfig dremioConfig;
  @Inject @Nullable private OidcTokenValidator oidcTokenValidator;  // null when auth.type!=keycloak

  // ... filter() method unchanged ...

  protected UserName getUserNameFromToken(ContainerRequestContext requestContext)
      throws NotAuthorizedException {
    final UserName userName;
    try {
      TokenDetails tokenDetails;
      final String uriPath = requestContext.getUriInfo().getRequestUri().getPath();
      final Map<String, List<String>> queryParams =
          requestContext.getUriInfo().getQueryParameters();
      if (resourceInfo.getResourceMethod() != null
          && resourceInfo.getResourceMethod().isAnnotationPresent(TemporaryAccess.class)) {
        // TemporaryAccess path — UNCHANGED
        String temporaryToken = TokenUtils.getTemporaryToken(requestContext);
        if (temporaryToken != null) {
          tokenDetails = tokenManager.validateTemporaryToken(temporaryToken, uriPath, queryParams);
        } else {
          final Tuple<TokenUtils.TokenType, String> tokenTuple =
              TokenUtils.getAuthHeaderToken(requestContext);
          Preconditions.checkArgument(tokenTuple != null);
          temporaryToken = tokenTuple.second;
          try {
            tokenDetails =
                tokenManager.validateTemporaryToken(temporaryToken, uriPath, queryParams);
          } catch (IllegalArgumentException e) {
            tokenDetails = tokenManager.validateToken(temporaryToken);
          }
        }
      } else {
        // Normal resource path — MODIFIED
        final Tuple<TokenUtils.TokenType, String> tokenTuple =
            TokenUtils.getAuthHeaderToken(requestContext);
        Preconditions.checkArgument(tokenTuple != null);
        final String tokenStr = tokenTuple.second;

        if (oidcTokenValidator != null && tokenStr.startsWith(JWT_COMPACT_PREFIX)) {
          // Keycloak JWT path: try Keycloak first; fall back to TokenManager for Dremio JWTs
          try {
            tokenDetails = oidcTokenValidator.validate(tokenStr);
          } catch (java.text.ParseException | IllegalArgumentException e) {
            // Fall back to Dremio token manager (handles Dremio-issued JWTs in keycloak mode)
            tokenDetails = tokenManager.validateToken(tokenStr);
          }
        } else {
          // Dremio opaque token path (or keycloak disabled) — UNCHANGED
          tokenDetails = tokenManager.validateToken(tokenStr);
        }
      }

      TokenInfo.setContext(requestContext, tokenDetails);
      userName = new UserName(tokenDetails.username);
      return userName;
    } catch (IllegalArgumentException e) {
      throw new NotAuthorizedException(e);
    }
  }
}
```

**Note on fallback strategy:** The code above falls back to `tokenManager.validateToken()` when the `eyJ`-prefix token fails Keycloak validation. This handles Dremio-issued JWTs in keycloak mode (Pitfall 4). If the planner prefers a simpler approach (no fallback, fail immediately on Keycloak validation error), replace the inner try-catch with:

```java
try {
  tokenDetails = oidcTokenValidator.validate(tokenStr);
} catch (java.text.ParseException | IllegalArgumentException e) {
  throw new NotAuthorizedException(e);  // simpler: no fallback
}
```

The fallback approach is safer and satisfies COEX-02 more completely.

### COEX-01: Local Admin Login Path (No Change Needed)

```java
// Source: LogInLogOutResource.java — POST /apiv2/login — this path is untouched
// userService.authenticate() → SimpleUserService.authenticate() → validates local password
// This path issues a Dremio opaque session token, unrelated to DACAuthFilter
```

COEX-01 is satisfied by the existing `LogInLogOutResource.login()` because:
1. `POST /apiv2/login` is not annotated `@Secured`, so `DACAuthFilter` does NOT run on it.
2. `userService.authenticate()` uses `SimpleUserService` (KVStore-backed), which is bound for both `internal` and `keycloak` auth types in `DACDaemonModule`.
3. The local admin user (created at startup) exists in KVStore regardless of auth type.

**Verification:** A test asserting that `POST /apiv2/login` with valid admin credentials returns 200 and a Dremio token when `auth.type=keycloak` proves COEX-01.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single `tokenManager.validateToken()` for all tokens | Discriminated dispatch: Keycloak JWT via `OidcTokenValidator`, opaque via `TokenManager` | Phase 31 | Keycloak REST API clients work without any Dremio session creation |
| `@Inject @Nullable` RbacService (established pattern) | Same pattern for OidcTokenValidator | Phase 31 | Optional injection; null when auth.type!=keycloak |

---

## Open Questions

1. **Fallback for `eyJ`-prefix Dremio JWTs in keycloak mode**
   - What we know: `TokenManager.createJwt()` produces ES256 JWTs starting with `eyJ`. In keycloak mode, the discriminator would route these to `OidcTokenValidator`, which rejects them (wrong issuer/algorithm).
   - What's unclear: Are there real callers of `createJwt()` that send those tokens via REST API in keycloak mode? If not, the simpler "fail → 401" approach is safe.
   - Recommendation: Implement the fallback (try Keycloak, catch, fall back to `tokenManager.validateToken()`). This is one extra try-catch and costs nothing in the happy path. Decide during plan whether to unit-test the fallback path.

2. **Test approach: unit vs. integration**
   - What we know: DACAuthFilter tests can be pure unit tests (mock `OidcTokenValidator`, mock `TokenManager`, call `getUserNameFromToken()` directly) or integration tests using `BaseTestServer` with a live JWKS server.
   - What's unclear: How much of the existing DAC backend test suite exercises DACAuthFilter with a real server vs. mocked components.
   - Recommendation: Write unit tests for `DACAuthFilter` that mock `OidcTokenValidator` and `TokenManager`. These test TKN-02 and COEX-02 fast (no server startup). Add a comment pointing to `TestOidcTokenValidator` for the actual JWT validation tests.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 Jupiter (consistent with services/keycloak pattern established in Phase 30) |
| Config file | `pom.xml` — Maven Surefire picks up both JUnit 4 and 5 |
| Quick run command | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak -am -DfailIfNoTests=false` |
| Full suite command | `mvn test -pl services/keycloak,dac/backend -am -DfailIfNoTests=false` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TKN-02 | Valid Keycloak JWT → `getUserNameFromToken()` returns correct username | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testValidKeycloakJwtReturnsUsername -am` | ❌ Wave 0 |
| TKN-02 | Malformed token (not a JWT) → `NotAuthorizedException` (→ 401, not 500) | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testMalformedJwtThrowsNotAuthorized -am` | ❌ Wave 0 |
| TKN-02 | Invalid/expired Keycloak JWT → `NotAuthorizedException` (→ 401) | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testExpiredJwtThrowsNotAuthorized -am` | ❌ Wave 0 |
| COEX-02 | Dremio opaque token → `tokenManager.validateToken()` path used (not OidcTokenValidator) | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testOpaqueTokenUsesTokenManager -am` | ❌ Wave 0 |
| COEX-02 | `oidcTokenValidator == null` (internal auth mode) → opaque token path used normally | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testInternalAuthModeNoOidcValidator -am` | ❌ Wave 0 |
| COEX-01 | `POST /apiv2/login` with local admin credentials returns 200 when auth.type=keycloak | manual | curl test or integration test | ❌ Wave 0 (optional integration test) |

### Sampling Rate
- **Per task commit:** `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak -am -DfailIfNoTests=false`
- **Per wave merge:** `mvn test -pl services/keycloak,dac/backend -am -DfailIfNoTests=false`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterKeycloak.java` — unit tests for the modified `getUserNameFromToken()` using mocked `OidcTokenValidator` and `TokenManager`
- [ ] `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — add `@Inject @Nullable OidcTokenValidator` field and `eyJ`-discriminated dispatch logic

*(If no gaps: "None — existing test infrastructure covers all phase requirements")*

---

## Sources

### Primary (HIGH confidence)
- Codebase: `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — exact current implementation; field injection pattern; exception handling
- Codebase: `dac/backend/src/main/java/com/dremio/dac/daemon/DremioBinder.java` — how `SingletonRegistry` bindings flow into HK2; `@Inject @Nullable` resolves to `null` when no binding exists
- Codebase: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` lines 2218-2239 — OidcTokenValidator bound under keycloak branch; injectable by DACAuthFilter
- Codebase: `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` — `validate()` signature; `ParseException` + `IllegalArgumentException` contract
- Codebase: `dac/backend/src/main/java/com/dremio/dac/server/tokens/TokenUtils.java` — `getAuthHeaderToken()` returns `Tuple<TokenType, String>`; `TokenType.BEARER` enum
- Codebase: `dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java` — `POST /apiv2/login` path; `userService.authenticate()` for COEX-01
- `.planning/STATE.md` — key design decision: `eyJ` prefix discriminates Keycloak JWTs from Dremio opaque tokens; `LocalUsernamePasswordAuthProvider` never disabled

### Secondary (MEDIUM confidence)
- JWT specification (RFC 7519): compact serialisation header is always `Base64URL({"alg":...})` → always starts with `eyJ`; independently verified via jwt.io
- HK2 documentation: `@Nullable` annotation combined with `@Inject` causes HK2 to inject `null` rather than throw `MultiException` when no binding is found — consistent with observed DACAuthFilter behaviour for `RbacService`

### Tertiary (LOW confidence)
- None for Phase 31

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all code paths verified directly in codebase; no new dependencies
- Architecture: HIGH — `@Inject @Nullable` pattern confirmed in DACAuthFilter; OidcTokenValidator injection path confirmed in DACDaemonModule; exception contract confirmed in OidcTokenValidator source
- Pitfalls: HIGH — `ParseException` wrapping requirement directly observed in method signatures; fallback requirement derived from TokenManager.createJwt() existence

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (stable codebase; no external library version changes)
