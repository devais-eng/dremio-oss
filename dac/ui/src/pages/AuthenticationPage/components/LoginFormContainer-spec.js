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
import Immutable from "immutable";

import LoginForm from "./LoginForm";

import { LoginFormContainer } from "./LoginFormContainer";

describe("LoginFormContainer", () => {
  let minimalProps;
  let commonProps;
  beforeEach(() => {
    minimalProps = {
      viewState: Immutable.Map(),
    };
    commonProps = {
      ...minimalProps,
    };
  });

  it("should render with minimal props without exploding", () => {
    const wrapper = shallow(<LoginFormContainer {...minimalProps} />);
    expect(wrapper).to.have.length(1);
  });

  describe("SSO button", () => {
    // Button from dremio-ui-lib renders as ForwardRef in enzyme shallow mode.
    // We find the SSO button by variant="secondary" which is unique (the primary
    // login button lives inside the nested LoginForm and is not visible in shallow).
    function findSSOButton(wrapper) {
      return wrapper.find({ variant: "secondary" });
    }

    it("should render SSO button when authType is keycloak", () => {
      const wrapper = shallow(<LoginFormContainer {...minimalProps} />);
      wrapper.setState({ authType: "keycloak" });
      expect(findSSOButton(wrapper)).to.have.length(1);
      expect(findSSOButton(wrapper).text()).to.include("Login with SSO");
    });

    it("should NOT render SSO button when authType is internal", () => {
      const wrapper = shallow(<LoginFormContainer {...minimalProps} />);
      wrapper.setState({ authType: "internal" });
      expect(findSSOButton(wrapper)).to.have.length(0);
    });

    it("should NOT render SSO button when authType is null", () => {
      const wrapper = shallow(<LoginFormContainer {...minimalProps} />);
      // authType is null by default (initial state)
      expect(findSSOButton(wrapper)).to.have.length(0);
    });

    it("should navigate to /api/v3/oidc/login on SSO button click", () => {
      const navigateStub = sinon.stub(LoginFormContainer, "_navigate");
      try {
        const wrapper = shallow(<LoginFormContainer {...minimalProps} />);
        wrapper.setState({ authType: "keycloak" });
        findSSOButton(wrapper).simulate("click");
        expect(navigateStub).to.have.been.calledWith("/api/v3/oidc/login");
      } finally {
        sinon.restore();
      }
    });
  });
});
