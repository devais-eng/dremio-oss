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
package com.dremio.service.flight;

import static com.dremio.service.flight.DremioFlightService.FLIGHT_AUTH2_AUTH_MODE;
import static com.dremio.service.flight.DremioFlightService.FLIGHT_LEGACY_AUTH_MODE;

import com.dremio.config.DremioConfig;
import com.dremio.service.flight.auth.DremioFlightServerBasicAuthValidator;
import com.dremio.service.flight.auth2.DremioBearerTokenAuthenticator;
import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.UserService;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.flight.DremioFlightServer;
import org.apache.arrow.flight.auth.BasicServerAuthHandler;

/** Adds the appropriate authentication handler for the Dremio software release. */
public class DremioFlightAuthProviderImpl implements DremioFlightAuthProvider {
  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(DremioFlightAuthProviderImpl.class);

  private final Provider<DremioConfig> configProvider;
  private final Provider<UserService> userServiceProvider;
  private final Provider<TokenManager> tokenManagerProvider;
  private final Provider<OidcTokenValidator> oidcTokenValidatorProvider;
  private final Provider<JitUserProvisioner> jitProvisionerProvider;
  private final Provider<KeycloakRoleSyncer> roleSyncerProvider;

  /**
   * Full constructor with Keycloak support.
   *
   * @param configProvider DremioConfig provider
   * @param userServiceProvider UserService provider
   * @param tokenManagerProvider TokenManager provider
   * @param oidcTokenValidatorProvider OidcTokenValidator provider (returns null if Keycloak not
   *     configured)
   * @param jitProvisionerProvider JitUserProvisioner provider (returns null if Keycloak not
   *     configured)
   * @param roleSyncerProvider KeycloakRoleSyncer provider (returns null if Keycloak not configured)
   */
  public DremioFlightAuthProviderImpl(
      Provider<DremioConfig> configProvider,
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider,
      @Nullable Provider<OidcTokenValidator> oidcTokenValidatorProvider,
      @Nullable Provider<JitUserProvisioner> jitProvisionerProvider,
      @Nullable Provider<KeycloakRoleSyncer> roleSyncerProvider) {
    this.configProvider = configProvider;
    this.userServiceProvider = userServiceProvider;
    this.tokenManagerProvider = tokenManagerProvider;
    this.oidcTokenValidatorProvider =
        oidcTokenValidatorProvider != null ? oidcTokenValidatorProvider : () -> null;
    this.jitProvisionerProvider =
        jitProvisionerProvider != null ? jitProvisionerProvider : () -> null;
    this.roleSyncerProvider = roleSyncerProvider != null ? roleSyncerProvider : () -> null;
  }

  /**
   * Backward-compatible 3-arg constructor. Keycloak components are disabled (providers return
   * null).
   */
  public DremioFlightAuthProviderImpl(
      Provider<DremioConfig> configProvider,
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider) {
    this(configProvider, userServiceProvider, tokenManagerProvider, () -> null, () -> null, () -> null);
  }

  @Override
  public void addAuthHandler(
      DremioFlightServer.Builder builder, DremioFlightSessionsManager dremioFlightSessionsManager) {
    final String authMode =
        configProvider.get().getString(DremioConfig.FLIGHT_SERVICE_AUTHENTICATION_MODE);

    // Resolve Keycloak components (may be null when Keycloak is not configured)
    final OidcTokenValidator oidcTokenValidator = oidcTokenValidatorProvider.get();
    final JitUserProvisioner jitProvisioner = jitProvisionerProvider.get();
    final KeycloakRoleSyncer roleSyncer = roleSyncerProvider.get();

    if (FLIGHT_LEGACY_AUTH_MODE.equals(authMode)) {
      builder.authHandler(
          new BasicServerAuthHandler(
              createBasicAuthValidator(
                  userServiceProvider,
                  tokenManagerProvider,
                  dremioFlightSessionsManager,
                  oidcTokenValidator,
                  jitProvisioner,
                  roleSyncer)));
      logger.info("Using basic authentication with ServerAuthHandler.");
    } else if (FLIGHT_AUTH2_AUTH_MODE.equals(authMode)) {
      builder.headerAuthenticator(
          new DremioBearerTokenAuthenticator(
              userServiceProvider,
              tokenManagerProvider,
              dremioFlightSessionsManager,
              oidcTokenValidator,
              jitProvisioner,
              roleSyncer));
      logger.info("Using bearer token authentication with CallHeaderAuthenticator.");
    } else {
      throw new RuntimeException(
          authMode
              + " is not a supported authentication mode for the Dremio FlightServer Endpoint.");
    }
  }

  /**
   * Factory method for creating an instance of BasicAuthValidator.
   *
   * @param userServiceProvider The UserService Provider.
   * @param tokenManagerProvider The TokenManager Provider.
   * @param dremioFlightSessionsManager An instance of DremioFlightSessionsManager.
   * @param oidcTokenValidator OidcTokenValidator instance (may be null).
   * @param jitProvisioner JitUserProvisioner instance (may be null).
   * @param roleSyncer KeycloakRoleSyncer instance (may be null).
   * @return An Instance of BasicAuthValidator.
   */
  protected BasicServerAuthHandler.BasicAuthValidator createBasicAuthValidator(
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider,
      DremioFlightSessionsManager dremioFlightSessionsManager,
      @Nullable OidcTokenValidator oidcTokenValidator,
      @Nullable JitUserProvisioner jitProvisioner,
      @Nullable KeycloakRoleSyncer roleSyncer) {
    return new DremioFlightServerBasicAuthValidator(
        userServiceProvider,
        tokenManagerProvider,
        dremioFlightSessionsManager,
        oidcTokenValidator,
        jitProvisioner,
        roleSyncer);
  }
}
