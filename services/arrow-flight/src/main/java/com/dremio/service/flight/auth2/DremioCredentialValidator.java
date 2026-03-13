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
package com.dremio.service.flight.auth2;

import com.dremio.service.flight.utils.DremioFlightAuthUtils;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.users.UserService;
import java.io.IOException;
import java.text.ParseException;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator.AuthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dremio authentication specialized CredentialValidator implementation. */
public class DremioCredentialValidator implements BasicCallHeaderAuthenticator.CredentialValidator {
  private static final Logger LOGGER = LoggerFactory.getLogger(DremioCredentialValidator.class);

  /** Prefix shared by all JWT compact-serialized tokens (base64url header). */
  private static final String JWT_COMPACT_PREFIX = "eyJ";

  private final Provider<UserService> userServiceProvider;
  @Nullable private final OidcTokenValidator oidcTokenValidator;
  @Nullable private final JitUserProvisioner jitProvisioner;
  @Nullable private final KeycloakRoleSyncer roleSyncer;

  /**
   * Constructor with Keycloak support.
   *
   * @param userServiceProvider Dremio UserService provider
   * @param oidcTokenValidator OidcTokenValidator instance (null when Keycloak not configured)
   * @param jitProvisioner JitUserProvisioner instance (null when Keycloak not configured)
   * @param roleSyncer KeycloakRoleSyncer instance (null when Keycloak not configured)
   */
  DremioCredentialValidator(
      Provider<UserService> userServiceProvider,
      @Nullable OidcTokenValidator oidcTokenValidator,
      @Nullable JitUserProvisioner jitProvisioner,
      @Nullable KeycloakRoleSyncer roleSyncer) {
    this.userServiceProvider = userServiceProvider;
    this.oidcTokenValidator = oidcTokenValidator;
    this.jitProvisioner = jitProvisioner;
    this.roleSyncer = roleSyncer;
  }

  /**
   * Authenticates against Dremio or Keycloak with the provided username and password.
   *
   * <p>If {@code password} starts with {@code eyJ} and an {@link OidcTokenValidator} is configured,
   * the password is treated as a Keycloak JWT and validated via OIDC. Otherwise, standard Dremio
   * username/password authentication is used.
   *
   * @param username Dremio username (ignored when JWT path is taken; username is extracted from the
   *     JWT).
   * @param password Dremio user password or a Keycloak JWT access token.
   * @return AuthResult with username as the peer identity.
   */
  @Override
  public AuthResult validate(String username, String password) {
    if (oidcTokenValidator != null && password.startsWith(JWT_COMPACT_PREFIX)) {
      return validateKeycloakJwt(password);
    }
    com.dremio.service.users.AuthResult authResult =
        DremioFlightAuthUtils.authenticateCredentials(
            userServiceProvider, username, password, LOGGER);
    return authResult::getUserName;
  }

  /**
   * Validates a Keycloak JWT, runs JIT provisioning and role sync, and returns an AuthResult.
   *
   * @param jwtString the Keycloak JWT access token
   * @return AuthResult with the Keycloak username as peer identity
   * @throws org.apache.arrow.flight.FlightRuntimeException(UNAUTHENTICATED) on validation failure
   */
  private AuthResult validateKeycloakJwt(String jwtString) {
    try {
      KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(jwtString);
      if (jitProvisioner != null) {
        jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
      }
      if (roleSyncer != null) {
        roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
      }
      final String resolvedUsername = ktd.getUsername();
      return () -> resolvedUsername;
    } catch (ParseException | IllegalArgumentException | IOException e) {
      LOGGER.error("Keycloak JWT validation failed for Flight connection", e);
      throw CallStatus.UNAUTHENTICATED
          .withCause(e)
          .withDescription("Keycloak JWT auth failed: " + e.getMessage())
          .toRuntimeException();
    }
  }
}
