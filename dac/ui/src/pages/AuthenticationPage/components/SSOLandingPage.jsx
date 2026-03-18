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
import { Component } from "react";
import localStorageUtils from "#oss/utils/storageUtils/localStorageUtils";
import { LOGIN_PATH } from "#oss/sagas/loginLogout";

export class SSOLandingPage extends Component {
  // Exposed as a static so tests can stub it via sinon.stub(SSOLandingPage, '_navigate')
  static _navigate(url) {
    window.location.assign(url);
  }

  // Exposed as a static so tests can stub it via sinon.stub(SSOLandingPage, '_getHash')
  static _getHash() {
    return window.location.hash;
  }

  componentDidMount() {
    const hash = SSOLandingPage._getHash();
    const tokenMatch = hash.match(/[#&]token=([^&]+)/);
    if (tokenMatch) {
      const token = decodeURIComponent(tokenMatch[1]);
      const userNameMatch = hash.match(/[#&]userName=([^&]+)/);
      const userName = userNameMatch
        ? decodeURIComponent(userNameMatch[1])
        : null;
      const adminMatch = hash.match(/[#&]admin=([^&]+)/);
      const admin = adminMatch ? adminMatch[1] === "true" : false;
      const permissions = admin
        ? {
            canUploadProfiles: true,
            canDownloadProfiles: true,
            canEmailForSupport: true,
            canChatForSupport: true,
            canViewAllJobs: true,
            canCreateUser: true,
            canCreateRole: true,
            canCreateSource: true,
            canUploadFile: true,
            canManageNodeActivity: true,
            canManageEngines: true,
            canManageQueues: true,
            canManageEngineRouting: true,
            canManageSupportSettings: true,
            canConfigureSecurity: true,
            canRunDiagnostic: true,
          }
        : {};
      localStorageUtils.setUserData({ token, userName, admin, permissions });
      SSOLandingPage._navigate("/");
    } else {
      SSOLandingPage._navigate(LOGIN_PATH);
    }
  }

  render() {
    return (
      <div
        style={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100vh",
        }}
      >
        Completing login...
      </div>
    );
  }
}

export default SSOLandingPage;
