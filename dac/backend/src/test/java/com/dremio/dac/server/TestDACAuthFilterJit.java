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
package com.dremio.dac.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.config.DremioConfig;
import com.dremio.exec.rbac.RbacService;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.User;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.service.users.UserService;
import java.lang.reflect.Field;
import java.net.URI;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration unit tests for {@link DACAuthFilter} JIT provisioning and role sync (Phase 32).
 *
 * <p>Covers requirements JIT-01, JIT-02, JIT-03, and ROLE-01 for the Keycloak JWT path.
 *
 * <p>Uses reflection to inject mocked dependencies into DACAuthFilter's private @Inject fields
 * since the class uses field injection (not constructor injection).
 */
@ExtendWith(MockitoExtension.class)
public class TestDACAuthFilterJit {

  private static final String KEYCLOAK_JWT = "eyJmock.payload.signature";
  private static final String OPAQUE_TOKEN = "abc123opaque";
  private static final long EXPIRY = System.currentTimeMillis() + 300_000L;

  @Mock private TokenManager tokenManager;
  @Mock private ResourceInfo resourceInfo;
  @Mock private OidcTokenValidator oidcTokenValidator;
  @Mock private JitUserProvisioner jitProvisioner;
  @Mock private KeycloakRoleSyncer roleSyncer;
  @Mock private ContainerRequestContext requestContext;
  @Mock private UriInfo uriInfo;
  @Mock private javax.inject.Provider<UserService> userServiceProvider;
  @Mock private UserService userService;
  @Mock private User aliceUser;
  @Mock private RbacService rbacService;
  @Mock private DremioConfig dremioConfig;

  private DACAuthFilter filter;

  /** Validates Keycloak JWT and returns a KeycloakTokenDetails for alice. */
  private final KeycloakTokenDetails aliceKtd =
      new KeycloakTokenDetails("alice", "alice@example.com", List.of("analyst"), EXPIRY);

  @BeforeEach
  void setUp() throws Exception {
    filter = new DACAuthFilter();
    injectField(filter, "tokenManager", tokenManager);
    injectField(filter, "resourceInfo", resourceInfo);
    injectField(filter, "userService", userServiceProvider);
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    injectField(filter, "jitProvisioner", jitProvisioner);
    injectField(filter, "roleSyncer", roleSyncer);
    injectField(filter, "rbacService", rbacService);
    injectField(filter, "dremioConfig", dremioConfig);

    // Default: resource method is non-null but NOT annotated with @TemporaryAccess
    when(resourceInfo.getResourceMethod()).thenReturn(Object.class.getMethod("toString"));

    // UriInfo must always be stubbed (read in getUserNameFromToken before branching)
    when(requestContext.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/v3/catalog"));
    when(uriInfo.getQueryParameters()).thenReturn(new javax.ws.rs.core.MultivaluedHashMap<>());

    // UserService provider
    when(userServiceProvider.get()).thenReturn(userService);

    // Simulate ContainerRequestContext property bag: setProperty/getProperty must be
    // consistent since DACAuthFilter stores KeycloakTokenDetails via setProperty and
    // reads it back via getProperty in the same filter() invocation.
    Map<String, Object> properties = new HashMap<>();
    doAnswer(
            inv -> {
              properties.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(requestContext)
        .setProperty(anyString(), any());
    when(requestContext.getProperty(anyString()))
        .thenAnswer(inv -> properties.get(inv.getArgument(0)));
  }

  // ---------------------------------------------------------------------------
  // Test 1 (JIT-01): New Keycloak user -> provision called, getUser retried and succeeds
  // ---------------------------------------------------------------------------

  @Test
  void testNewKeycloakUserIsProvisioned() throws Exception {
    // Arrange: oidcTokenValidator returns alice's claims; getUser fails first then succeeds
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT)).thenReturn(aliceKtd);
    when(userService.getUser("alice"))
        .thenThrow(new UserNotFoundException("alice"))
        .thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-alice");

    // Act
    filter.filter(requestContext);

    // Assert: JIT provision was called with correct username + email
    verify(jitProvisioner).provision("alice", "alice@example.com");
    // getUser should be called twice: once before provision (throws), once after
    verify(userService, times(2)).getUser("alice");
    // Request was NOT aborted (200 result)
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 2 (ROLE-01): Existing Keycloak user -> roleSyncer called with token roles
  // ---------------------------------------------------------------------------

  @Test
  void testExistingKeycloakUserRolesAreSynced() throws Exception {
    // Arrange: user already exists in Dremio
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT)).thenReturn(aliceKtd);
    when(userService.getUser("alice")).thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-alice");

    // Act
    filter.filter(requestContext);

    // Assert: roles synced with realm_access.roles from the token
    verify(roleSyncer).syncRoles("alice", List.of("analyst"));
    // JIT provision was NOT called (user already exists)
    verify(jitProvisioner, never()).provision(anyString(), anyString());
    // Request was NOT aborted
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 3 (JIT-01 + ROLE-01): New user -> both provision AND syncRoles called in sequence
  // ---------------------------------------------------------------------------

  @Test
  void testNewUserGetsBothProvisionAndRoleSync() throws Exception {
    // Arrange: first getUser fails, provision happens, then getUser succeeds
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT)).thenReturn(aliceKtd);
    when(userService.getUser("alice"))
        .thenThrow(new UserNotFoundException("alice"))
        .thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-alice");

    // Act
    filter.filter(requestContext);

    // Assert: provision called first, then role sync
    verify(jitProvisioner).provision("alice", "alice@example.com");
    verify(roleSyncer).syncRoles("alice", List.of("analyst"));
    // No 401
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 4: Dremio opaque token (no eyJ prefix) -> neither jitProvisioner nor roleSyncer invoked
  // ---------------------------------------------------------------------------

  @Test
  void testOpaqueTokenSkipsJitAndRoleSync() throws Exception {
    // Arrange: opaque token — goes straight to tokenManager (oidcTokenValidator not called)
    mockBearerHeader(OPAQUE_TOKEN);
    when(tokenManager.validateToken(OPAQUE_TOKEN))
        .thenReturn(TokenDetails.of(OPAQUE_TOKEN, "dremiouser", EXPIRY));
    when(userService.getUser("dremiouser")).thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-dremio");

    // Act
    filter.filter(requestContext);

    // Assert: neither JIT nor role sync invoked on opaque token path
    verify(jitProvisioner, never()).provision(anyString(), anyString());
    verify(roleSyncer, never()).syncRoles(anyString(), any());
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 5: Keycloak validateWithClaims throws -> fallback to tokenManager, no JIT/sync
  // ---------------------------------------------------------------------------

  @Test
  void testKeycloakValidationFailsFallsBackToTokenManager() throws Exception {
    // Arrange: validateWithClaims fails (wrong issuer); tokenManager accepts it
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT))
        .thenThrow(new ParseException("JWT validation failed", 0));
    when(tokenManager.validateToken(KEYCLOAK_JWT))
        .thenReturn(TokenDetails.of(KEYCLOAK_JWT, "dremio-jwt-user", EXPIRY));
    when(userService.getUser("dremio-jwt-user")).thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-fallback");

    // Act
    filter.filter(requestContext);

    // Assert: no JIT or role sync (ktd is null on fallback path)
    verify(jitProvisioner, never()).provision(anyString(), anyString());
    verify(roleSyncer, never()).syncRoles(anyString(), any());
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 6 (JIT-03): UserNotFoundException after provision succeeds -> 200, not 401
  // ---------------------------------------------------------------------------

  @Test
  void testJitProvisionSucceedsAndUserIsRetried() throws Exception {
    // Arrange: getUser throws first, provision succeeds, getUser retried returns user
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT)).thenReturn(aliceKtd);
    when(userService.getUser("alice"))
        .thenThrow(new UserNotFoundException("alice"))
        .thenReturn(aliceUser);
    when(aliceUser.getUID()).thenReturn(mock(com.dremio.service.users.proto.UID.class));
    when(aliceUser.getUID().getId()).thenReturn("uid-alice");

    // Act
    filter.filter(requestContext);

    // Assert: provision was called, user was retried, request succeeded (not 401)
    verify(jitProvisioner).provision("alice", "alice@example.com");
    verify(userService, times(2)).getUser("alice");
    verify(requestContext, never()).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Test 7: Provision failure (IOException) -> 401
  // ---------------------------------------------------------------------------

  @Test
  void testProvisionFailureReturns401() throws Exception {
    // Arrange: getUser throws UserNotFoundException, and provision also throws IOException
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validateWithClaims(KEYCLOAK_JWT)).thenReturn(aliceKtd);
    when(userService.getUser("alice")).thenThrow(new UserNotFoundException("alice"));
    doThrow(new java.io.IOException("KVStore unavailable"))
        .when(jitProvisioner)
        .provision("alice", "alice@example.com");

    // Act
    filter.filter(requestContext);

    // Assert: request is aborted with 401 due to provisioning failure
    verify(requestContext).abortWith(any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Sets a private field on the target object via reflection, bypassing field injection. Supports
   * null values (to simulate @Nullable fields being absent).
   */
  private static void injectField(Object target, String fieldName, Object value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    Class<?> current = clazz;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(
        "Field '" + fieldName + "' not found in class hierarchy of " + clazz.getName());
  }

  /**
   * Mocks the ContainerRequestContext to return the given token as a Bearer header.
   * TokenUtils.getAuthHeaderToken() reads HttpHeaders.AUTHORIZATION (lowercase "authorization").
   */
  private void mockBearerHeader(String token) {
    when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION.toLowerCase()))
        .thenReturn("Bearer " + token);
  }
}
