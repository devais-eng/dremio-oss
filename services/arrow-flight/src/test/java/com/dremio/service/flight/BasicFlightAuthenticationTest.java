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

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.service.keycloak.JitUserProvisioner;
import com.dremio.service.keycloak.KeycloakRoleSyncer;
import com.dremio.service.keycloak.KeycloakTokenDetails;
import com.dremio.service.keycloak.OidcTokenValidator;
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.ImmutableAuthResult;
import com.dremio.service.users.UserLoginException;
import com.dremio.service.users.UserService;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import javax.inject.Provider;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.Before;

/** Base class for all Flight Authentication unit tests. */
public abstract class BasicFlightAuthenticationTest {
  protected static final String USERNAME = "MY_USER";
  protected static final String PASSWORD = "MY_PASS";
  protected static final String TOKEN = "VALID_TOKEN";
  protected static final long MAX_NUMBER_OF_SESSIONS = 2L;
  protected static final TokenDetails TOKEN_DETAILS =
      TokenDetails.of(TOKEN, USERNAME, System.currentTimeMillis() + 1000);

  // Keycloak test constants
  protected static final String KC_USERNAME = "kc_user";
  protected static final String KC_EMAIL = "kc_user@example.com";
  protected static final String KC_JWT = "eyJhbGciOiJSUzI1NiJ9.test.signature";
  protected static final List<String> KC_REALM_ROLES = Arrays.asList("dremio_admin", "analyst");

  private final Provider<UserService> mockUserServiceProvider = mock(Provider.class);
  private final Provider<TokenManager> mockTokenManagerProvider = mock(Provider.class);
  private final TokenManager mockTokenManager = mock(TokenManager.class);
  private final UserService mockUserService = mock(UserService.class);
  private final DremioFlightSessionsManager mockDremioFlightSessionsManager =
      mock(DremioFlightSessionsManager.class);

  // Keycloak mocks
  private final OidcTokenValidator mockOidcTokenValidator = mock(OidcTokenValidator.class);
  private final JitUserProvisioner mockJitProvisioner = mock(JitUserProvisioner.class);
  private final KeycloakRoleSyncer mockRoleSyncer = mock(KeycloakRoleSyncer.class);
  private final KeycloakTokenDetails mockKeycloakTokenDetails = mock(KeycloakTokenDetails.class);

  protected static void testFailed() throws Exception {
    throw new Exception(
        "Test failed. Expected FlightRuntimeException with status code: "
            + FlightStatusCode.UNAUTHENTICATED
            + ", but none was thrown.");
  }

  @Before
  public void setup() throws UserLoginException, ParseException {
    when(mockUserServiceProvider.get()).thenReturn(mockUserService);
    when(mockUserService.authenticate(eq(USERNAME), eq(PASSWORD)))
        .thenReturn(
            new ImmutableAuthResult.Builder()
                .setUserName(USERNAME)
                .setExpiresAt(DateUtils.addHours(new Date(), 1))
                .build());

    when(mockTokenManagerProvider.get()).thenReturn((mockTokenManager));
    when(mockTokenManager.createToken(eq(USERNAME), eq(null))).thenReturn(TOKEN_DETAILS);

    // Keycloak mock setup
    when(mockKeycloakTokenDetails.getUsername()).thenReturn(KC_USERNAME);
    when(mockKeycloakTokenDetails.getEmail()).thenReturn(KC_EMAIL);
    when(mockKeycloakTokenDetails.getRealmRoles()).thenReturn(KC_REALM_ROLES);

    lenient()
        .when(mockOidcTokenValidator.validateWithClaims(eq(KC_JWT)))
        .thenReturn(mockKeycloakTokenDetails);
    lenient()
        .when(mockOidcTokenValidator.validateWithClaims(eq("eyJinvalid")))
        .thenThrow(new ParseException("bad jwt", 0));

    // KC_USERNAME token creation
    final TokenDetails kcTokenDetails =
        TokenDetails.of(TOKEN, KC_USERNAME, System.currentTimeMillis() + 1000);
    lenient().when(mockTokenManager.createToken(eq(KC_USERNAME), eq(null))).thenReturn(kcTokenDetails);
  }

  public Provider<UserService> getMockUserServiceProvider() {
    return mockUserServiceProvider;
  }

  public Provider<TokenManager> getMockTokenManagerProvider() {
    return mockTokenManagerProvider;
  }

  public TokenManager getMockTokenManager() {
    return mockTokenManager;
  }

  public UserService getMockUserService() {
    return mockUserService;
  }

  public DremioFlightSessionsManager getMockDremioFlightSessionsManager() {
    return mockDremioFlightSessionsManager;
  }

  public OidcTokenValidator getMockOidcTokenValidator() {
    return mockOidcTokenValidator;
  }

  public JitUserProvisioner getMockJitProvisioner() {
    return mockJitProvisioner;
  }

  public KeycloakRoleSyncer getMockRoleSyncer() {
    return mockRoleSyncer;
  }

  public KeycloakTokenDetails getMockKeycloakTokenDetails() {
    return mockKeycloakTokenDetails;
  }
}
