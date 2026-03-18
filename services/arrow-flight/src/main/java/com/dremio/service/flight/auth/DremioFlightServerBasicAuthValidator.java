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

package com.dremio.service.flight.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.dremio.service.flight.DremioFlightSessionsManager;
import com.dremio.service.flight.utils.DremioFlightAuthUtils;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.UserService;
import java.io.IOException;
import java.text.ParseException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.auth.BasicServerAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dremio authentication specialized implementation of BasicAuthValidator. Authenticates with
 * provided credentials, creates a new UserSession, creates and validates associated bearer token.
 */
public class DremioFlightServerBasicAuthValidator
    implements BasicServerAuthHandler.BasicAuthValidator {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DremioFlightServerBasicAuthValidator.class);

  /** Prefix shared by all JWT compact-serialized tokens (base64url header). */
  private static final String JWT_COMPACT_PREFIX = "eyJ";

  private final Provider<UserService> userServiceProvider;
  private final Provider<TokenManager> tokenManagerProvider;
  private final DremioFlightSessionsManager dremioFlightSessionsManager;
  @Nullable private final OidcTokenValidator oidcTokenValidator;
  @Nullable private final JitUserProvisioner jitProvisioner;
  @Nullable private final KeycloakRoleSyncer roleSyncer;

  /**
   * Constructor with Keycloak support.
   *
   * @param userServiceProvider UserService provider
   * @param tokenManagerProvider TokenManager provider
   * @param dremioFlightSessionsManager Flight session manager
   * @param oidcTokenValidator OidcTokenValidator instance (null when Keycloak not configured)
   * @param jitProvisioner JitUserProvisioner instance (null when Keycloak not configured)
   * @param roleSyncer KeycloakRoleSyncer instance (null when Keycloak not configured)
   */
  public DremioFlightServerBasicAuthValidator(
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider,
      DremioFlightSessionsManager dremioFlightSessionsManager,
      @Nullable OidcTokenValidator oidcTokenValidator,
      @Nullable JitUserProvisioner jitProvisioner,
      @Nullable KeycloakRoleSyncer roleSyncer) {
    this.userServiceProvider = userServiceProvider;
    this.tokenManagerProvider = tokenManagerProvider;
    this.dremioFlightSessionsManager = dremioFlightSessionsManager;
    this.oidcTokenValidator = oidcTokenValidator;
    this.jitProvisioner = jitProvisioner;
    this.roleSyncer = roleSyncer;
  }

  /**
   * Backward-compatible constructor without Keycloak support.
   *
   * @param userServiceProvider UserService provider
   * @param tokenManagerProvider TokenManager provider
   * @param dremioFlightSessionsManager Flight session manager
   */
  public DremioFlightServerBasicAuthValidator(
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider,
      DremioFlightSessionsManager dremioFlightSessionsManager) {
    this(userServiceProvider, tokenManagerProvider, dremioFlightSessionsManager, null, null, null);
  }

  @Override
  public byte[] getToken(String username, String password) {
    if (oidcTokenValidator != null && password.startsWith(JWT_COMPACT_PREFIX)) {
      return validateKeycloakJwtAndCreateToken(password);
    }
    final String token =
        DremioFlightAuthUtils.authenticateAndCreateToken(
            userServiceProvider,
            tokenManagerProvider,
            dremioFlightSessionsManager,
            username,
            password,
            LOGGER);

    return token.getBytes(UTF_8);
  }

  @Override
  public Optional<String> isValid(byte[] bytes) {
    final String token = new String(bytes, UTF_8);
    if (oidcTokenValidator != null && token.startsWith(JWT_COMPACT_PREFIX)) {
      try {
        KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(token);
        if (jitProvisioner != null) {
          jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
        }
        if (roleSyncer != null) {
          roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
        }
        final String newToken =
            DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
                tokenManagerProvider, ktd.getUsername());
        return Optional.of(newToken);
      } catch (Exception e) {
        LOGGER.error("Keycloak JWT validation failed in Flight legacy isValid()", e);
        return Optional.empty();
      }
    }
    try {
      tokenManagerProvider.get().validateToken(token);
      return Optional.of(token);
    } catch (IllegalArgumentException e) {
      LOGGER.error("Token validation failed.", e);
      return Optional.empty();
    }
  }

  /**
   * Validates a Keycloak JWT, runs JIT provisioning and role sync, mints a Dremio session token.
   *
   * @param jwtString the Keycloak JWT access token
   * @return the Dremio session token bytes
   * @throws org.apache.arrow.flight.FlightRuntimeException(UNAUTHENTICATED) on validation failure
   */
  private byte[] validateKeycloakJwtAndCreateToken(String jwtString) {
    try {
      KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(jwtString);
      if (jitProvisioner != null) {
        jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
      }
      if (roleSyncer != null) {
        roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
      }
      final String token =
          DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
              tokenManagerProvider, ktd.getUsername());
      return token.getBytes(UTF_8);
    } catch (ParseException | IllegalArgumentException | IOException e) {
      LOGGER.error("Keycloak JWT validation failed in Flight basic auth validator", e);
      throw CallStatus.UNAUTHENTICATED
          .withCause(e)
          .withDescription("Keycloak JWT auth failed: " + e.getMessage())
          .toRuntimeException();
    }
  }
}
