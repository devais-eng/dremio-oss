---
phase: 30-jwt-validation-infrastructure-config
plan: "01"
subsystem: keycloak-config
tags: [keycloak, config, maven-module, DACDaemonModule]
dependency_graph:
  requires: []
  provides: [dremio-services-keycloak, KeycloakConfig, DremioConfig.KEYCLOAK_* constants]
  affects: [dac/backend/DACDaemonModule, common/legacy/DremioConfig, dremio-reference.conf]
tech_stack:
  added: [services/keycloak Maven module, com.typesafe.config, com.google.guava.Preconditions]
  patterns: [typed-config-bean, fast-fail-validation, TDD-red-green]
key_files:
  created:
    - services/keycloak/pom.xml
    - services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakConfig.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakConfig.java
  modified:
    - services/pom.xml
    - common/legacy/src/main/java/com/dremio/config/DremioConfig.java
    - common/legacy/src/main/resources/dremio-reference.conf
    - dac/backend/pom.xml
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
decisions:
  - "KeycloakConfig accepts Config interface (not DremioConfig) — enables direct testing via ConfigFactory.parseMap without needing full DremioConfig validation lifecycle"
  - "Removed exec-maven-plugin/BuildTimeScan from keycloak pom.xml — KeycloakConfig has no annotated classes requiring classpath scanning; tokens module was wrong template for this"
  - "Inserted keycloak module between jobtelemetry and maestro in services/pom.xml alphabetically"
metrics:
  duration: "~9 minutes"
  completed_date: "2026-03-12"
  tasks_completed: 2
  files_created: 3
  files_modified: 5
  tests_added: 7
  tests_passing: 7
requirements:
  - CFG-01
  - CFG-02
  - CFG-03
---

# Phase 30 Plan 01: Keycloak Config Infrastructure Summary

**One-liner:** New `dremio-services-keycloak` Maven module with `KeycloakConfig` typed bean reading 5 OIDC fields from DremioConfig, fast-fail validation, JWKS URI computation, and `keycloak` branch wired in `DACDaemonModule.setupUserService()`.

## Tasks Completed

### Task 1: Create services/keycloak Maven module with KeycloakConfig and config constants

- Created `services/keycloak/pom.xml` (parent: dremio-services-parent, deps: dremio-common, nimbus-jose-jwt, dremio-services-tokens, guava; test deps: junit-jupiter, assertj-core)
- Added `<module>keycloak</module>` to `services/pom.xml` between jobtelemetry and maestro
- Added 5 `KEYCLOAK_*` constants to `DremioConfig.java` after `RBAC_PDS_ENABLED`
- Added keycloak block to `dremio-reference.conf` with empty-string defaults and `additive` sync-mode
- Created `KeycloakConfig.java`: reads all 5 fields, validates issuer-url/client-id/client-secret non-blank via Guava Preconditions, provides typed getters, `getJwksUri()` and `isAdditive()`
- Created `TestKeycloakConfig.java`: 7 tests covering all behaviors — all passing
- Commit: `e29865b61`

### Task 2: Wire keycloak auth type branch in DACDaemonModule.setupUserService

- Added `dremio-services-keycloak` dependency to `dac/backend/pom.xml` after dremio-services-tokens
- Added `import com.dremio.service.keycloak.KeycloakConfig` to `DACDaemonModule.java`
- Added keycloak branch in `setupUserService()`: creates SimpleUserService, binds it as UserService and UserResolver, creates and binds `KeycloakConfig`
- `auth.type=keycloak` no longer throws RuntimeException — coordinator can start cleanly
- Commit: `4ec2b8af2`

## Verification Results

| Check | Result |
|-------|--------|
| `mvn compile -pl services/keycloak -am` | BUILD SUCCESS |
| `mvn test -pl services/keycloak -Dtest=TestKeycloakConfig` | 7/7 tests PASS |
| `mvn compile -pl dac/backend` | BUILD SUCCESS |
| `mvn test -pl common/legacy` | 479/479 tests PASS |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed exec-maven-plugin (BuildTimeScan) from keycloak pom.xml**
- **Found during:** Task 1 verification (`mvn install -pl services/keycloak`)
- **Issue:** `BuildTimeScan` fails when the module is not yet installed as a JAR — it checks the classpath for the module's jar but only finds the build output directory. Plan instructed to "include the exec-maven-plugin for BuildTimeScan (follow tokens pattern)" but tokens module has annotated classes; KeycloakConfig has none.
- **Fix:** Removed the `<build><plugins>...</plugins></build>` block from keycloak pom.xml. Modules without annotated classes do not need BuildTimeScan (see `services/credentials/pom.xml` as the correct pattern).
- **Files modified:** `services/keycloak/pom.xml`
- **Commit:** e29865b61

**2. [Rule 2 - Missing critical functionality] KeycloakConfig accepts Config interface instead of DremioConfig**
- **Found during:** Task 1 test design
- **Issue:** Plan said "Constructor takes DremioConfig" but DremioConfig.check() validates all config paths against the reference — using DremioConfig in tests requires `DremioConfig.create()` which loads from classpath and has complex validation. Simpler and more testable to accept `Config` (the interface that DremioConfig implements).
- **Fix:** `KeycloakConfig(Config config)` — production code passes `dacConfig.getConfig()` (which returns DremioConfig, which implements Config). Tests use `ConfigFactory.parseMap(map).withFallback(reference).resolve()` directly.
- **Files modified:** `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakConfig.java`

## Key Decisions

1. **KeycloakConfig accepts Config interface** — enables clean unit tests without DremioConfig validation overhead; production callers pass DremioConfig which implements Config
2. **No BuildTimeScan in keycloak pom.xml** — KeycloakConfig has no Dremio classpath-scanned annotations; added the plugin blindly from the wrong template (tokens has protocol buffers/annotated classes)
3. **keycloak module added alphabetically between jobtelemetry and maestro** in services/pom.xml

## Self-Check: PASSED

| Check | Result |
|-------|--------|
| `services/keycloak/pom.xml` exists | FOUND |
| `KeycloakConfig.java` exists | FOUND |
| `TestKeycloakConfig.java` exists | FOUND |
| Commit `e29865b61` (Task 1) exists | FOUND |
| Commit `4ec2b8af2` (Task 2) exists | FOUND |
| `KEYCLOAK_ISSUER_URL` in DremioConfig | FOUND |
| keycloak block in dremio-reference.conf | FOUND |
| `new KeycloakConfig` in DACDaemonModule | FOUND |
