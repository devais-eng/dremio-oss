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

import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.service.catalog.CatalogServiceHelper;
import com.dremio.exec.server.SabotContext;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakConfig;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcSessionStore;
import com.dremio.service.keycloak.OidcStateStore;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.SystemUser;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS resource providing OIDC Authorization Code Flow with PKCE endpoints.
 *
 * <p>Registered under {@code /api/v3/oidc/*} via the {@link APIResource} classpath scan.
 *
 * <ul>
 *   <li>{@code GET /api/v3/oidc/login} — initiates the OIDC redirect to Keycloak (OIDC-01, OIDC-03)
 *   <li>{@code GET /api/v3/oidc/callback} — exchanges the authorization code for tokens, JIT-
 *       provisions the user, syncs roles, mints a Dremio session token, and redirects the browser
 *       to the SSO landing page (OIDC-02, LOUT-02)
 * </ul>
 *
 * <p>These endpoints are intentionally NOT annotated with {@code @Secured}: they are
 * unauthenticated browser endpoints used before the user has a session token.
 *
 * <p>All Keycloak-specific dependencies are injected with {@code @Nullable} so that the resource
 * degrades gracefully when Keycloak is not configured (returns 503).
 */
@APIResource
@Path("/oidc")
public class OidcResource {

  private static final Logger logger = LoggerFactory.getLogger(OidcResource.class);

  @Inject @javax.annotation.Nullable OidcStateStore oidcStateStore;

  @Inject @javax.annotation.Nullable OidcSessionStore oidcSessionStore;

  @Inject @javax.annotation.Nullable OidcTokenValidator oidcTokenValidator;

  @Inject @javax.annotation.Nullable JitUserProvisioner jitUserProvisioner;

  @Inject @javax.annotation.Nullable KeycloakRoleSyncer keycloakRoleSyncer;

  @Inject @javax.annotation.Nullable KeycloakConfig keycloakConfig;

  @Inject TokenManager tokenManager;

  @Inject SabotContext sabotContext;

  /**
   * Initiates the OIDC Authorization Code Flow with PKCE.
   *
   * <p>Generates a new PKCE {@code code_verifier} and {@code state} nonce, builds the Keycloak
   * authorization URL, stores the state-to-verifier mapping in {@link OidcStateStore}, and returns
   * a 302 redirect to the Keycloak authorization endpoint.
   *
   * @return 302 redirect to Keycloak, or 503 if Keycloak is not configured
   */
  @GET
  @Path("/login")
  public Response initiateLogin() {
    if (oidcStateStore == null) {
      return Response.status(503).entity("Keycloak auth not enabled").build();
    }

    CodeVerifier codeVerifier = new CodeVerifier();
    State state = new State();

    URI authorizationEndpoint =
        URI.create(keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/auth");

    AuthorizationRequest authRequest =
        new AuthorizationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new ClientID(keycloakConfig.getClientId()))
            .endpointURI(authorizationEndpoint)
            .redirectionURI(URI.create(keycloakConfig.getRedirectUri()))
            .scope(new Scope("openid", "profile", "email"))
            .state(state)
            .codeChallenge(codeVerifier, CodeChallengeMethod.S256)
            .build();

    oidcStateStore.put(state.getValue(), codeVerifier.getValue());

    return Response.status(Response.Status.FOUND).location(authRequest.toURI()).build();
  }

  /**
   * Handles the OIDC authorization code callback from Keycloak.
   *
   * <p>Validates the state nonce, exchanges the authorization code for tokens, JIT-provisions the
   * user if needed, syncs Keycloak roles to Dremio RBAC, mints a Dremio session token, stores the
   * id_token for RP-Initiated Logout (LOUT-02), and redirects the browser to the SSO landing page
   * with the Dremio token in the URL fragment.
   *
   * @param code the authorization code from Keycloak
   * @param state the state nonce from Keycloak
   * @param request the HTTP servlet request (for remote address)
   * @return 302 redirect to landing page with token, 400 on invalid parameters, 502 on exchange
   *     failure
   */
  @GET
  @Path("/callback")
  public Response handleCallback(
      @QueryParam("code") String code,
      @QueryParam("state") String state,
      @Context HttpServletRequest request) {

    if (state == null || code == null) {
      return Response.status(400).entity("Missing code or state").build();
    }

    String storedVerifier = oidcStateStore.removeIfValid(state);
    if (storedVerifier == null) {
      return Response.status(400).entity("Invalid or expired state").build();
    }

    OidcTokenExchangeResult exchangeResult;
    try {
      exchangeResult = exchangeCodeForTokens(code, storedVerifier);
    } catch (IOException e) {
      logger.warn("Token exchange failed: {}", e.getMessage());
      return Response.status(502).entity("Token exchange failed").build();
    }

    if (exchangeResult == null) {
      logger.warn("Token exchange returned null (Keycloak error response)");
      return Response.status(502).entity("Token exchange rejected by Keycloak").build();
    }

    KeycloakTokenDetails ktd;
    try {
      ktd = oidcTokenValidator.validateWithClaims(exchangeResult.accessToken);
    } catch (Exception e) {
      logger.warn("Access token validation failed: {}", e.getMessage());
      return Response.status(502).entity("Invalid access token from Keycloak").build();
    }

    try {
      jitUserProvisioner.provision(ktd.getUsername(), ktd.getEmail());
    } catch (IOException e) {
      logger.warn("JIT provisioning failed for '{}': {}", ktd.getUsername(), e.getMessage());
    }

    keycloakRoleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());

    try {
      CatalogServiceHelper.ensureUserHasHomespace(
          sabotContext.getNamespaceService(SystemUser.SYSTEM_USERNAME),
          ktd.getUsername(),
          sabotContext.getOptionManager());
    } catch (Exception e) {
      logger.warn("Could not ensure homespace for '{}': {}", ktd.getUsername(), e.getMessage());
    }

    TokenDetails dremioToken = tokenManager.createToken(ktd.getUsername(), request.getRemoteAddr());

    oidcSessionStore.put(dremioToken.token, exchangeResult.idToken);

    boolean isAdmin = ktd.getRealmRoles().contains("ADMIN");

    URI landingUri =
        URI.create(
            "/login/sso/landing#token="
                + dremioToken.token
                + "&userName="
                + java.net.URLEncoder.encode(ktd.getUsername(), java.nio.charset.StandardCharsets.UTF_8)
                + "&admin="
                + isAdmin);
    return Response.status(Response.Status.FOUND).location(landingUri).build();
  }

  /**
   * Exchanges an OIDC authorization code for tokens via the Keycloak token endpoint.
   *
   * <p>This method is {@code protected} to allow test spies to stub it, avoiding real HTTP calls in
   * unit tests.
   *
   * @param code the authorization code from Keycloak
   * @param codeVerifier the PKCE code verifier stored when the flow was initiated
   * @return the access token and id_token strings, or {@code null} if Keycloak returns an error
   * @throws IOException if the HTTP request to the token endpoint fails
   */
  protected OidcTokenExchangeResult exchangeCodeForTokens(String code, String codeVerifier)
      throws IOException {
    URI tokenEndpoint =
        URI.create(keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/token");

    TokenRequest tokenRequest =
        new TokenRequest(
            tokenEndpoint,
            new ClientSecretBasic(
                new ClientID(keycloakConfig.getClientId()),
                new Secret(keycloakConfig.getClientSecret())),
            new AuthorizationCodeGrant(
                new AuthorizationCode(code),
                URI.create(keycloakConfig.getRedirectUri()),
                new CodeVerifier(codeVerifier)));

    HTTPResponse httpResponse = tokenRequest.toHTTPRequest().send();

    com.nimbusds.oauth2.sdk.TokenResponse tokenResponse;
    try {
      tokenResponse = OIDCTokenResponseParser.parse(httpResponse);
    } catch (com.nimbusds.oauth2.sdk.ParseException e) {
      throw new IOException("Failed to parse token response: " + e.getMessage(), e);
    }

    if (!tokenResponse.indicatesSuccess()) {
      logger.warn(
          "Token exchange failed: {}",
          tokenResponse.toErrorResponse().getErrorObject().getDescription());
      return null;
    }

    OIDCTokenResponse oidcTokenResponse = (OIDCTokenResponse) tokenResponse.toSuccessResponse();
    String accessToken = oidcTokenResponse.getOIDCTokens().getAccessToken().getValue();
    String idToken = oidcTokenResponse.getOIDCTokens().getIDToken().serialize();

    return new OidcTokenExchangeResult(accessToken, idToken);
  }

  /**
   * Carries the result of a successful OIDC token exchange: access token and serialized id_token.
   *
   * <p>Package-private to allow spy-based stubbing in unit tests without a separate interface.
   */
  static final class OidcTokenExchangeResult {
    final String accessToken;
    final String idToken;

    OidcTokenExchangeResult(String accessToken, String idToken) {
      this.accessToken = accessToken;
      this.idToken = idToken;
    }
  }
}
