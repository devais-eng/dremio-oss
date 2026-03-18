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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.service.flight.BasicFlightAuthenticationTest;
import com.dremio.service.users.UserLoginException;
import java.util.Optional;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for Keycloak JWT authentication in DremioFlightServerBasicAuthValidator (legacy mode).
 */
public class TestDremioFlightServerBasicAuthValidator extends BasicFlightAuthenticationTest {
  private DremioFlightServerBasicAuthValidator validator;

  @Before
  @Override
  public void setup() throws UserLoginException, java.text.ParseException {
    super.setup();
    validator =
        new DremioFlightServerBasicAuthValidator(
            getMockUserServiceProvider(),
            getMockTokenManagerProvider(),
            getMockDremioFlightSessionsManager(),
            getMockOidcTokenValidator(),
            getMockJitProvisioner(),
            getMockRoleSyncer());
  }

  @After
  public void tearDown() throws Exception {
    validator = null;
  }

  @Test
  public void testGetTokenWithKeycloakJwt() throws Exception {
    // Act
    final byte[] tokenBytes = validator.getToken("ignored", KC_JWT);

    // Assert - returned token bytes decode to the Dremio TOKEN
    assertArrayEquals(TOKEN.getBytes(UTF_8), tokenBytes);

    // Verify OidcTokenValidator was called
    verify(getMockOidcTokenValidator()).validateWithClaims(KC_JWT);
  }

  @Test
  public void testGetTokenWithKeycloakJwtCallsJit() throws Exception {
    // Act
    validator.getToken("ignored", KC_JWT);

    // Verify JIT provisioning was called
    verify(getMockJitProvisioner()).provision(KC_USERNAME, KC_EMAIL);
  }

  @Test
  public void testGetTokenWithInvalidKeycloakJwt() throws Exception {
    // Act
    try {
      validator.getToken("ignored", "eyJinvalid");
      testFailed();
    } catch (FlightRuntimeException exception) {
      assertEquals(FlightStatusCode.UNAUTHENTICATED, exception.status().code());
    }
  }

  @Test
  public void testGetTokenWithDremioPassword() throws Exception {
    // Act - Dremio username+password path should still work
    final byte[] expectedToken = TOKEN.getBytes(UTF_8);
    final byte[] actualToken = validator.getToken(USERNAME, PASSWORD);

    // Assert
    assertArrayEquals(expectedToken, actualToken);
  }

  @Test
  public void testIsValidWithKeycloakJwt() throws Exception {
    // Act
    final Optional<String> result = validator.isValid(KC_JWT.getBytes(UTF_8));

    // Assert
    assertTrue(result.isPresent());
  }

  @Test
  public void testIsValidWithDremioToken() {
    // Arrange
    when(getMockTokenManager().validateToken(eq(TOKEN))).thenReturn(TOKEN_DETAILS);

    // Act
    final Optional<String> result = validator.isValid(TOKEN.getBytes(UTF_8));

    // Assert - TOKEN does not start with eyJ, so it goes through the Dremio path
    assertEquals(Optional.of(TOKEN), result);
  }
}
