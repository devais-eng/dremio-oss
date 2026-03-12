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

import com.dremio.config.DremioConfig;
import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.server.DACConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource providing an unauthenticated server configuration endpoint for the pre-login UI.
 *
 * <p>Registered under {@code /api/v3/server-config} via the {@link APIResource} classpath scan.
 *
 * <p>This endpoint is intentionally NOT annotated with {@code @Secured}: it is called by the login
 * page before any Dremio session exists, to determine whether to show the SSO button.
 *
 * <p>Only the auth type string is exposed — no secrets, no issuer URLs, no client IDs.
 */
@APIResource
@Path("/server-config")
@Produces(MediaType.APPLICATION_JSON)
public class ServerConfigResource {

  private final DACConfig dacConfig;

  @Inject
  public ServerConfigResource(DACConfig dacConfig) {
    this.dacConfig = dacConfig;
  }

  /**
   * Returns the server authentication configuration for the pre-login UI.
   *
   * <p>The response contains only {@code authType} — either {@code "keycloak"} or {@code
   * "internal"} — so the login page can decide whether to render the SSO button (UI-01, UI-02).
   *
   * @return 200 OK with {@code {"authType":"keycloak"|"internal"}}
   */
  @GET
  // NO @Secured — pre-login UI must be able to call this without a session token
  public Response getServerConfig() {
    String authType = dacConfig.getConfig().getString(DremioConfig.WEB_AUTH_TYPE);
    return Response.ok(new ServerConfig(authType)).build();
  }

  /**
   * Response body for {@code GET /api/v3/server-config}.
   *
   * <p>Deliberately contains only {@code authType} — no secrets, no URLs, no client IDs are
   * exposed to unauthenticated callers.
   */
  public static final class ServerConfig {
    private final String authType;

    @JsonCreator
    public ServerConfig(@JsonProperty("authType") String authType) {
      this.authType = authType;
    }

    @JsonProperty
    public String getAuthType() {
      return authType;
    }
  }
}
