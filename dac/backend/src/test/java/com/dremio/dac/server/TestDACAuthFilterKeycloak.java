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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.SuppressForbidden;
import com.dremio.dac.model.usergroup.UserName;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import java.lang.reflect.Field;
import java.net.URI;
import java.text.ParseException;
import javax.ws.rs.NotAuthorizedException;
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
 * Unit tests for {@link DACAuthFilter} Keycloak JWT dispatch logic.
 *
 * <p>Covers requirements TKN-02 (Keycloak Bearer JWT acceptance), COEX-01 (internal auth mode
 * unchanged), and COEX-02 (Dremio opaque/JWT token coexistence).
 *
 * <p>Uses reflection to inject mocked dependencies into DACAuthFilter's private @Inject fields
 * since the class uses field injection (not constructor injection).
 */
@ExtendWith(MockitoExtension.class)
public class TestDACAuthFilterKeycloak {

  private static final String OPAQUE_TOKEN = "abc123opaque";
  private static final String KEYCLOAK_JWT = "eyJmock.payload.signature";
  private static final long EXPIRY = System.currentTimeMillis() + 300_000L;

  @Mock private TokenManager tokenManager;
  @Mock private ResourceInfo resourceInfo;
  @Mock private OidcTokenValidator oidcTokenValidator;
  @Mock private ContainerRequestContext requestContext;
  @Mock private UriInfo uriInfo;
  @Mock private javax.inject.Provider<com.dremio.service.users.UserService> userServiceProvider;

  private DACAuthFilter filter;

  @BeforeEach
  void setUp() throws Exception {
    filter = new DACAuthFilter();
    injectField(filter, "tokenManager", tokenManager);
    injectField(filter, "resourceInfo", resourceInfo);
    injectField(filter, "userService", userServiceProvider);

    // Default: resource method is non-null but NOT annotated with @TemporaryAccess
    // (Object.toString() has no @TemporaryAccess annotation)
    when(resourceInfo.getResourceMethod()).thenReturn(Object.class.getMethod("toString"));

    // getUserNameFromToken() reads UriInfo for uriPath and queryParams before branching.
    // Even the non-TemporaryAccess path calls getUriInfo(), so we must stub it.
    when(requestContext.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/v3/catalog"));
    when(uriInfo.getQueryParameters()).thenReturn(new javax.ws.rs.core.MultivaluedHashMap<>());
  }

  // ---------------------------------------------------------------------------
  // TKN-02: Valid Keycloak JWT returns correct username
  // ---------------------------------------------------------------------------

  @Test
  void testValidKeycloakJwtReturnsUsername() throws Exception {
    // Arrange: inject oidcTokenValidator, mock request with eyJ token
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validate(KEYCLOAK_JWT))
        .thenReturn(TokenDetails.of(KEYCLOAK_JWT, "kcuser", EXPIRY));

    // Act
    UserName result = filter.getUserNameFromToken(requestContext);

    // Assert: username comes from OidcTokenValidator
    assertThat(result.getName()).isEqualTo("kcuser");
    verify(oidcTokenValidator).validate(KEYCLOAK_JWT);
    verify(tokenManager, never()).validateToken(anyString());
  }

  // ---------------------------------------------------------------------------
  // Error handling: malformed JWT -> NotAuthorizedException (not 500)
  // ---------------------------------------------------------------------------

  @Test
  void testMalformedJwtThrowsNotAuthorized() throws Exception {
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    mockBearerHeader(KEYCLOAK_JWT);
    // ParseException from OidcTokenValidator.validate() must NOT escape as 500
    when(oidcTokenValidator.validate(KEYCLOAK_JWT))
        .thenThrow(new ParseException("malformed jwt", 0));
    // Fallback to TokenManager also fails (so we can observe the NotAuthorizedException path)
    when(tokenManager.validateToken(KEYCLOAK_JWT))
        .thenThrow(new IllegalArgumentException("not a dremio token either"));

    assertThatThrownBy(() -> filter.getUserNameFromToken(requestContext))
        .isInstanceOf(NotAuthorizedException.class);
  }

  // ---------------------------------------------------------------------------
  // Error handling: expired/bad-signature JWT -> NotAuthorizedException
  // ---------------------------------------------------------------------------

  @Test
  void testExpiredJwtThrowsNotAuthorized() throws Exception {
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    mockBearerHeader(KEYCLOAK_JWT);
    when(oidcTokenValidator.validate(KEYCLOAK_JWT))
        .thenThrow(new IllegalArgumentException("JWT expired or bad signature"));
    // Fallback to TokenManager also fails
    when(tokenManager.validateToken(KEYCLOAK_JWT))
        .thenThrow(new IllegalArgumentException("not a dremio token"));

    assertThatThrownBy(() -> filter.getUserNameFromToken(requestContext))
        .isInstanceOf(NotAuthorizedException.class);
  }

  // ---------------------------------------------------------------------------
  // COEX-02: Opaque token (no eyJ prefix) always routes to TokenManager
  // ---------------------------------------------------------------------------

  @Test
  void testOpaqueTokenUsesTokenManager() throws Exception {
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    mockBearerHeader(OPAQUE_TOKEN);
    when(tokenManager.validateToken(OPAQUE_TOKEN))
        .thenReturn(TokenDetails.of(OPAQUE_TOKEN, "dremiouser", EXPIRY));

    UserName result = filter.getUserNameFromToken(requestContext);

    assertThat(result.getName()).isEqualTo("dremiouser");
    verify(tokenManager).validateToken(OPAQUE_TOKEN);
    // OidcTokenValidator must never be called for non-eyJ tokens
    verify(oidcTokenValidator, never()).validate(anyString());
  }

  // ---------------------------------------------------------------------------
  // COEX-01: Internal auth mode (oidcTokenValidator=null) -> TokenManager only
  // ---------------------------------------------------------------------------

  @Test
  void testInternalAuthModeNoOidcValidator() throws Exception {
    // oidcTokenValidator is null (auth.type=internal)
    injectField(filter, "oidcTokenValidator", null);
    // Token starts with eyJ but should still go to TokenManager when validator is null
    mockBearerHeader(KEYCLOAK_JWT);
    when(tokenManager.validateToken(KEYCLOAK_JWT))
        .thenReturn(TokenDetails.of(KEYCLOAK_JWT, "dremio-jwt-user", EXPIRY));

    UserName result = filter.getUserNameFromToken(requestContext);

    assertThat(result.getName()).isEqualTo("dremio-jwt-user");
    verify(tokenManager).validateToken(KEYCLOAK_JWT);
  }

  // ---------------------------------------------------------------------------
  // COEX-02: Keycloak validation fails -> fallback to TokenManager (Dremio JWT)
  // ---------------------------------------------------------------------------

  @Test
  void testKeycloakFailsFallsBackToTokenManager() throws Exception {
    injectField(filter, "oidcTokenValidator", oidcTokenValidator);
    mockBearerHeader(KEYCLOAK_JWT);
    // Keycloak rejects this token (e.g. Dremio-issued JWT, wrong issuer)
    when(oidcTokenValidator.validate(KEYCLOAK_JWT))
        .thenThrow(new IllegalArgumentException("wrong issuer"));
    // TokenManager accepts it (Dremio-issued JWT starting with eyJ)
    when(tokenManager.validateToken(KEYCLOAK_JWT))
        .thenReturn(TokenDetails.of(KEYCLOAK_JWT, "dremio-jwt-user", EXPIRY));

    UserName result = filter.getUserNameFromToken(requestContext);

    assertThat(result.getName()).isEqualTo("dremio-jwt-user");
    verify(oidcTokenValidator).validate(KEYCLOAK_JWT);
    verify(tokenManager).validateToken(KEYCLOAK_JWT);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Sets a private field on the target object via reflection, bypassing field injection. Supports
   * null values (to simulate @Nullable fields being absent).
   */
  @SuppressForbidden
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
