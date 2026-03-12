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
import { shallow } from "enzyme";
import localStorageUtils from "#oss/utils/storageUtils/localStorageUtils";
import { SSOLandingPage } from "./SSOLandingPage";

describe("SSOLandingPage", () => {
  let navigateStub;
  let getHashStub;
  let setUserDataStub;

  beforeEach(() => {
    // Stub static navigation so we do not touch the real window.location
    navigateStub = sinon.stub(SSOLandingPage, "_navigate");
    getHashStub = sinon.stub(SSOLandingPage, "_getHash").returns("");

    // Stub localStorageUtils.setUserData
    setUserDataStub = sinon.stub(localStorageUtils, "setUserData");
  });

  afterEach(() => {
    sinon.restore();
  });

  it("should call setUserData with token and userName and navigate to /", () => {
    getHashStub.returns("#token=abc123&userName=testuser");
    const wrapper = shallow(<SSOLandingPage />);
    wrapper.instance().componentDidMount();
    expect(setUserDataStub).to.have.been.calledWith({
      token: "abc123",
      userName: "testuser",
    });
    expect(navigateStub).to.have.been.calledWith("/");
  });

  it("should decode URI-encoded tokens and userNames", () => {
    getHashStub.returns("#token=abc%3D123&userName=test%40user");
    const wrapper = shallow(<SSOLandingPage />);
    wrapper.instance().componentDidMount();
    expect(setUserDataStub).to.have.been.calledWith({
      token: "abc=123",
      userName: "test@user",
    });
    expect(navigateStub).to.have.been.calledWith("/");
  });

  it("should set userName to null when only token is in hash", () => {
    getHashStub.returns("#token=abc123");
    const wrapper = shallow(<SSOLandingPage />);
    wrapper.instance().componentDidMount();
    expect(setUserDataStub).to.have.been.calledWith({
      token: "abc123",
      userName: null,
    });
    expect(navigateStub).to.have.been.calledWith("/");
  });

  it("should redirect to /login when no token in hash", () => {
    getHashStub.returns("");
    const wrapper = shallow(<SSOLandingPage />);
    wrapper.instance().componentDidMount();
    expect(setUserDataStub).to.not.have.been.called;
    expect(navigateStub).to.have.been.calledWith("/login");
  });

  it("should render loading state", () => {
    const wrapper = shallow(<SSOLandingPage />);
    expect(wrapper.text()).to.include("Completing login...");
  });
});
