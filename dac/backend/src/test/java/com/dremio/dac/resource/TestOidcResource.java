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
package com.dremio.dac.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.exec.server.SabotContext;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakConfig;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcSessionStore;
import com.dremio.service.keycloak.OidcStateStore;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.SystemUser;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OidcResource} covering the OIDC Authorization Code Flow with PKCE.
 *
 * <p>Covers requirements OIDC-01, OIDC-02, OIDC-03, and LOUT-02.
 *
 * <p>Uses reflection to inject mocked dependencies into {@code OidcResource}'s private
 * {@code @Inject} fields, mirroring the pattern in {@code TestDACAuthFilterKeycloak}.
 */
@ExtendWith(MockitoExtension.class)
public class TestOidcResource {

  private static final String ISSUER = "https://keycloak.test/realms/test";
  private static final String CLIENT_ID = "dremio";
  private static final String CLIENT_SECRET = "secret";
  private static final String REDIRECT_URI = "https://dremio.test/api/v3/oidc/callback";
  private static final String ACCESS_TOKEN = "access.token.value";
  private static final String ID_TOKEN = "id.token.value";
  private static final String DREMIO_SESSION_TOKEN = "dremio-session-abc";
  private static final long EXPIRY = 9_999_999_999L;

  @Mock private OidcStateStore oidcStateStore;
  @Mock private OidcSessionStore oidcSessionStore;
  @Mock private OidcTokenValidator oidcTokenValidator;
  @Mock private JitUserProvisioner jitUserProvisioner;
  @Mock private KeycloakRoleSyncer keycloakRoleSyncer;
  @Mock private KeycloakConfig keycloakConfig;
  @Mock private TokenManager tokenManager;
  @Mock private SabotContext sabotContext;
  @Mock private NamespaceService namespaceService;
  @Mock private HttpServletRequest request;

  private OidcResource resource;

  @BeforeEach
  void setUp() throws Exception {
    resource = new OidcResource();
    injectField(resource, "oidcStateStore", oidcStateStore);
    injectField(resource, "oidcSessionStore", oidcSessionStore);
    injectField(resource, "oidcTokenValidator", oidcTokenValidator);
    injectField(resource, "jitUserProvisioner", jitUserProvisioner);
    injectField(resource, "keycloakRoleSyncer", keycloakRoleSyncer);
    injectField(resource, "keycloakConfig", keycloakConfig);
    injectField(resource, "tokenManager", tokenManager);
    injectField(resource, "sabotContext", sabotContext);

    // Use lenient() for keycloakConfig stubs that are only needed by some tests.
    // Tests that null out oidcStateStore (e.g., testLoginKeycloakNotConfiguredReturns503)
    // or tests that don't call keycloakConfig methods would otherwise trigger
    // UnnecessaryStubbingException in Mockito strict mode.
    lenient().when(keycloakConfig.getIssuerUrl()).thenReturn(ISSUER);
    lenient().when(keycloakConfig.getClientId()).thenReturn(CLIENT_ID);
    lenient().when(keycloakConfig.getClientSecret()).thenReturn(CLIENT_SECRET);
    lenient().when(keycloakConfig.getRedirectUri()).thenReturn(REDIRECT_URI);
  }

  // ---------------------------------------------------------------------------
  // Login endpoint tests
  // ---------------------------------------------------------------------------

  /** OIDC-01: initiateLogin() must return 302 redirect to Keycloak auth endpoint. */
  @Test
  void testLoginReturnsRedirectToKeycloakAuthEndpoint() {
    Response response = resource.initiateLogin();

    assertThat(response.getStatus()).isEqualTo(302);
    URI location = (URI) response.getHeaders().getFirst("Location");
    assertThat(location.toString()).startsWith(ISSUER + "/protocol/openid-connect/auth");
  }

  /** OIDC-01: Location URI contains all required PKCE + OIDC parameters. */
  @Test
  void testLoginRedirectContainsRequiredParams() {
    Response response = resource.initiateLogin();

    URI location = (URI) response.getHeaders().getFirst("Location");
    String query = location.getQuery();
    assertThat(query).contains("response_type=code");
    assertThat(query).contains("client_id=" + CLIENT_ID);
    assertThat(query).contains("redirect_uri=");
    assertThat(query).contains("scope=");
    assertThat(query).contains("openid");
    assertThat(query).contains("state=");
    assertThat(query).contains("code_challenge=");
    assertThat(query).contains("code_challenge_method=S256");
  }

  /** OIDC-03: After initiateLogin(), oidcStateStore.put() is called with state and codeVerifier. */
  @Test
  void testLoginStoresStateInOidcStateStore() {
    resource.initiateLogin();

    verify(oidcStateStore).put(any(String.class), any(String.class));
  }

  /** When Keycloak auth is not configured (oidcStateStore is null), login returns 503. */
  @Test
  void testLoginKeycloakNotConfiguredReturns503() throws Exception {
    injectField(resource, "oidcStateStore", null);

    Response response = resource.initiateLogin();

    assertThat(response.getStatus()).isEqualTo(503);
  }

  // ---------------------------------------------------------------------------
  // Callback endpoint tests
  // ---------------------------------------------------------------------------

  /**
   * OIDC-02: Valid code+state exchanges code for tokens, provisions user, syncs roles, mints Dremio
   * token, returns 302 to /login/sso/landing#token=...
   */
  @Test
  void testCallbackHappyPathRedirectsToLandingWithToken() throws Exception {
    // Arrange
    String state = "valid-state";
    String code = "auth-code";
    when(oidcStateStore.removeIfValid(state)).thenReturn("code-verifier");
    KeycloakTokenDetails ktd =
        new KeycloakTokenDetails("testuser", "test@example.com", List.of("analyst"), EXPIRY);
    when(oidcTokenValidator.validateWithClaims(ACCESS_TOKEN)).thenReturn(ktd);
    when(tokenManager.createToken("testuser", "127.0.0.1"))
        .thenReturn(TokenDetails.of(DREMIO_SESSION_TOKEN, "testuser", EXPIRY));
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(sabotContext.getNamespaceService(SystemUser.SYSTEM_USERNAME)).thenReturn(namespaceService);

    // Use a spy to stub the HTTP token exchange call
    OidcResource spy = spy(resource);
    doReturn(new OidcResource.OidcTokenExchangeResult(ACCESS_TOKEN, ID_TOKEN))
        .when(spy)
        .exchangeCodeForTokens(eq(code), eq("code-verifier"));

    // Act
    Response response = spy.handleCallback(code, state, request);

    // Assert
    assertThat(response.getStatus()).isEqualTo(302);
    URI location = (URI) response.getHeaders().getFirst("Location");
    assertThat(location.toString())
        .isEqualTo(
            "/login/sso/landing#token="
                + DREMIO_SESSION_TOKEN
                + "&userName=testuser&admin=false");
  }

  /**
   * LOUT-02: After successful callback, oidcSessionStore.put(dremioToken, idToken) is called to
   * enable RP-Initiated Logout in Phase 35.
   */
  @Test
  void testCallbackStoresIdTokenInSessionStore() throws Exception {
    String state = "valid-state";
    String code = "auth-code";
    when(oidcStateStore.removeIfValid(state)).thenReturn("code-verifier");
    KeycloakTokenDetails ktd =
        new KeycloakTokenDetails("testuser", "test@example.com", List.of(), EXPIRY);
    when(oidcTokenValidator.validateWithClaims(ACCESS_TOKEN)).thenReturn(ktd);
    when(tokenManager.createToken("testuser", "127.0.0.1"))
        .thenReturn(TokenDetails.of(DREMIO_SESSION_TOKEN, "testuser", EXPIRY));
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(sabotContext.getNamespaceService(SystemUser.SYSTEM_USERNAME)).thenReturn(namespaceService);

    OidcResource spy = spy(resource);
    doReturn(new OidcResource.OidcTokenExchangeResult(ACCESS_TOKEN, ID_TOKEN))
        .when(spy)
        .exchangeCodeForTokens(eq(code), eq("code-verifier"));

    spy.handleCallback(code, state, request);

    verify(oidcSessionStore).put(DREMIO_SESSION_TOKEN, ID_TOKEN);
  }

  /** OIDC-03: state not in store (removeIfValid returns null) returns 400. */
  @Test
  void testCallbackTamperedStateReturns400() {
    when(oidcStateStore.removeIfValid("bad-state")).thenReturn(null);

    Response response = resource.handleCallback("some-code", "bad-state", request);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  /** OIDC-03: null state parameter returns 400. */
  @Test
  void testCallbackMissingStateReturns400() {
    Response response = resource.handleCallback("some-code", null, request);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  /** OIDC-02: null code parameter returns 400. */
  @Test
  void testCallbackMissingCodeReturns400() {
    Response response = resource.handleCallback(null, "some-state", request);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  /** When the token exchange HTTP call fails (throws IOException), returns 502 Bad Gateway. */
  @Test
  void testCallbackTokenExchangeFailsReturns502() throws Exception {
    String state = "valid-state";
    String code = "auth-code";
    when(oidcStateStore.removeIfValid(state)).thenReturn("code-verifier");

    OidcResource spy = spy(resource);
    doThrow(new java.io.IOException("connection refused"))
        .when(spy)
        .exchangeCodeForTokens(eq(code), eq("code-verifier"));

    Response response = spy.handleCallback(code, state, request);

    assertThat(response.getStatus()).isEqualTo(502);
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
}
