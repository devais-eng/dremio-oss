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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OidcTokenValidator. Uses an in-process HTTP server to serve JWKS, so no external
 * dependencies or mocking frameworks are required.
 */
class TestOidcTokenValidator {

  private static final String ISSUER = "https://keycloak.example.com/realms/test";
  private static final String AUDIENCE = "dremio-client";

  // Key pair 1
  private static KeyPair keyPair1;
  private static RSAKey rsaJwk1;

  // Key pair 2 (for key rotation test)
  private static KeyPair keyPair2;
  private static RSAKey rsaJwk2;

  // In-process JWKS HTTP server
  private static HttpServer jwksServer;
  private static AtomicReference<String> jwksContent;
  private static String jwksUrl;

  @BeforeAll
  static void setUpClass() throws Exception {
    // Generate RSA key pair 1
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keyPair1 = gen.generateKeyPair();
    rsaJwk1 =
        new RSAKey.Builder((RSAPublicKey) keyPair1.getPublic())
            .privateKey((RSAPrivateKey) keyPair1.getPrivate())
            .keyID("key-id-1")
            .build();

    // Generate RSA key pair 2 (different kid — for rotation test)
    keyPair2 = gen.generateKeyPair();
    rsaJwk2 =
        new RSAKey.Builder((RSAPublicKey) keyPair2.getPublic())
            .privateKey((RSAPrivateKey) keyPair2.getPrivate())
            .keyID("key-id-2")
            .build();

    // Build initial JWKS document (only key1)
    jwksContent = new AtomicReference<>(buildJwks(rsaJwk1));

    // Start in-process JWKS HTTP server on an auto-allocated port
    jwksServer = HttpServer.create(new InetSocketAddress(0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body = jwksContent.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.getResponseBody().close();
        });
    jwksServer.start();

    int port = jwksServer.getAddress().getPort();
    jwksUrl = "http://localhost:" + port + "/jwks";
  }

  @AfterAll
  static void tearDownClass() {
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  /** Helper: build a JWKS document containing a single RSA public key. */
  private static String buildJwks(RSAKey jwk) throws Exception {
    // Only expose the public key in the JWKS
    RSAKey publicKey = jwk.toPublicJWK();
    return "{\"keys\":[" + publicKey.toJSONString() + "]}";
  }

  /**
   * Helper: sign a JWT with the given private key and claims.
   *
   * @param privateKey the RSAKey (with private) to sign with
   * @param claimsBuilder the claims to embed
   * @return compact JWT string
   */
  private static String signJwt(RSAKey privateKey, JWTClaimsSet.Builder claimsBuilder)
      throws Exception {
    JWSSigner signer = new RSASSASigner(privateKey);
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(privateKey.getKeyID()).build();
    SignedJWT jwt = new SignedJWT(header, claimsBuilder.build());
    jwt.sign(signer);
    return jwt.serialize();
  }

  /** Helper: build a standard valid claims set (correct issuer, audience, exp, sub). */
  private static JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject("user-uuid-123")
        .claim("preferred_username", "alice")
        .issueTime(Date.from(Instant.now().minusSeconds(5)))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)));
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  @Test
  void testValidToken() throws Exception {
    // Reset JWKS to key1
    jwksContent.set(buildJwks(rsaJwk1));
    String jwt = signJwt(rsaJwk1, validClaims());

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);
    var details = validator.validate(jwt);

    assertThat(details).isNotNull();
    assertThat(details.username).isEqualTo("alice");
    assertThat(details.token).isEqualTo(jwt);
    assertThat(details.expiresAt).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  void testPreferredUsernameFallbackToSub() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    // No preferred_username claim
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("user-uuid-123")
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));

    String jwt = signJwt(rsaJwk1, claims);

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);
    var details = validator.validate(jwt);

    assertThat(details.username).isEqualTo("user-uuid-123"); // falls back to sub
  }

  @Test
  void testWrongIssuer() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    JWTClaimsSet.Builder claims =
        validClaims().issuer("https://wrong-issuer.example.com/realms/test");
    String jwt = signJwt(rsaJwk1, claims);

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    assertThatThrownBy(() -> validator.validate(jwt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validation failed");
  }

  @Test
  void testWrongAudience() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    JWTClaimsSet.Builder claims = validClaims().audience("wrong-client");
    String jwt = signJwt(rsaJwk1, claims);

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    assertThatThrownBy(() -> validator.validate(jwt))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testExpiredToken() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    JWTClaimsSet.Builder claims =
        validClaims()
            .issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(Instant.now().minusSeconds(300))); // expired
    String jwt = signJwt(rsaJwk1, claims);

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    assertThatThrownBy(() -> validator.validate(jwt))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testBadSignature() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    // Sign with key2 but JWKS only has key1 -- key2's kid is not in JWKS
    // Use key2 with key-id-1 to simulate a bad signature (wrong key, matching kid label trick)
    // Actually: sign with key2 but set kid to "key-id-1" so the validator looks up key1 but sig fails
    JWSSigner signer = new RSASSASigner(keyPair2.getPrivate());
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID("key-id-1") // claim to be key1 but actually signed by key2
            .build();
    SignedJWT jwt = new SignedJWT(header, validClaims().build());
    jwt.sign(signer);
    String jwtString = jwt.serialize();

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    assertThatThrownBy(() -> validator.validate(jwtString))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testKeyRotation() throws Exception {
    // Start with key1 in JWKS
    jwksContent.set(buildJwks(rsaJwk1));

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    // Validate a token signed by key1 -- should succeed
    String jwt1 = signJwt(rsaJwk1, validClaims());
    var details1 = validator.validate(jwt1);
    assertThat(details1.username).isEqualTo("alice");

    // Rotate: update JWKS endpoint to serve key2
    jwksContent.set(buildJwks(rsaJwk2));

    // Sign a new token with key2
    String jwt2 = signJwt(rsaJwk2, validClaims());

    // Validate with same validator instance -- JWKSourceBuilder should re-fetch on unknown kid
    var details2 = validator.validate(jwt2);
    assertThat(details2.username).isEqualTo("alice");
  }

  @Test
  void testMalformedToken() {
    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);

    assertThatThrownBy(() -> validator.validate("not-a-jwt"))
        .isInstanceOf(Exception.class); // ParseException or wrapped IllegalArgumentException
  }

  /** Extra test: verify sub with preferred_username present uses preferred_username. */
  @Test
  void testUsernameIsPreferredUsernameNotSub() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    JWTClaimsSet.Builder claims =
        validClaims()
            .subject("sub-uuid-456")
            .claim("preferred_username", "bob");
    String jwt = signJwt(rsaJwk1, claims);

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);
    var details = validator.validate(jwt);

    assertThat(details.username).isEqualTo("bob");
    assertThat(details.username).isNotEqualTo("sub-uuid-456");
  }

  /** Unique JWT ID test: different tokens with same claims should produce different TokenDetails. */
  @Test
  void testTokenStringPreservedInDetails() throws Exception {
    jwksContent.set(buildJwks(rsaJwk1));
    String jwt =
        signJwt(
            rsaJwk1,
            validClaims().jwtID(UUID.randomUUID().toString()));

    OidcTokenValidator validator = new OidcTokenValidator(jwksUrl, ISSUER, AUDIENCE);
    var details = validator.validate(jwt);

    assertThat(details.token).isEqualTo(jwt);
  }
}
