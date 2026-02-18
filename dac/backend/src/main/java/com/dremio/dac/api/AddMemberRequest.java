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
package com.dremio.dac.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body DTO for POST /api/v3/rbac/roles/{name}/members (add a user to a role). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AddMemberRequest {

  private final String userName;

  @JsonCreator
  public AddMemberRequest(@JsonProperty("userName") String userName) {
    this.userName = userName;
  }

  public String getUserName() {
    return userName;
  }
}
