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
package com.dremio.service.keycloak;

import com.dremio.service.tokens.TokenDetails;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Validates Keycloak-issued RS256 JWTs against the Keycloak JWKS endpoint.
 *
 * <p>Uses Nimbus {@code JWKSourceBuilder} for JWKS caching (5-minute TTL), rate-limited refresh,
 * and automatic key rotation support — when an unknown {@code kid} is encountered the JWKS endpoint
 * is re-fetched automatically without any custom cache-invalidation logic.
 *
 * <p>Called from {@code DACAuthFilter} (Phase 31) when the Bearer token starts with {@code eyJ},
 * indicating a Keycloak JWT rather than a Dremio opaque token.
 */
public class OidcTokenValidator {

  private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

  /**
   * Constructs an OidcTokenValidator targeting the given JWKS endpoint.
   *
   * @param jwksUri full URL of the Keycloak JWKS endpoint, e.g.
   *     {@code https://keycloak.host/realms/my-realm/protocol/openid-connect/certs}
   * @param expectedIssuer exact value expected in the JWT {@code iss} claim (Keycloak realm URL)
   * @param expectedAudience exact value expected in the JWT {@code aud} claim (Keycloak client-id)
   * @throws IllegalArgumentException if {@code jwksUri} is not a valid URL
   */
  public OidcTokenValidator(String jwksUri, String expectedIssuer, String expectedAudience) {
    URL jwksUrl;
    try {
      jwksUrl = new URL(jwksUri);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JWKS URI: " + jwksUri, e);
    }

    // JWKSourceBuilder provides: 5-min cache TTL, 30-s rate limit, retrying on transient failures,
    // and automatic re-fetch on unknown kid. No custom cache logic needed.
    JWKSource<SecurityContext> keySource =
        JWKSourceBuilder.<SecurityContext>create(jwksUrl).retrying(true).build();

    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
    processor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier<>(
            new JWTClaimsSet.Builder()
                .issuer(expectedIssuer)
                .audience(expectedAudience)
                .build(),
            new HashSet<>(Arrays.asList("sub", "exp", "iat", "iss", "aud"))));

    this.jwtProcessor = processor;
  }

  /**
   * Validates the JWT string against the configured JWKS endpoint and claim set.
   *
   * <p>Extracts {@code preferred_username} as the Dremio username; falls back to {@code sub} if
   * the claim is absent (e.g. client-credentials grant tokens).
   *
   * @param jwtString compact serialised JWT (three Base64URL parts separated by dots)
   * @return {@link TokenDetails} with the original JWT string, resolved username, and expiry epoch
   *     milliseconds
   * @throws ParseException if {@code jwtString} cannot be parsed as a JWT
   * @throws IllegalArgumentException if the JWT fails signature, issuer, audience, or expiry
   *     validation
   */
  public TokenDetails validate(String jwtString) throws ParseException {
    JWT jwt = JWTParser.parse(jwtString);
    JWTClaimsSet claims;
    try {
      claims = jwtProcessor.process(jwt, null);
    } catch (BadJOSEException | JOSEException e) {
      throw new IllegalArgumentException("Keycloak JWT validation failed: " + e.getMessage(), e);
    }

    String username = claims.getStringClaim("preferred_username");
    if (username == null) {
      username = claims.getSubject();
    }

    long expiresAt = claims.getExpirationTime().getTime();
    return TokenDetails.of(jwtString, username, expiresAt);
  }
}
