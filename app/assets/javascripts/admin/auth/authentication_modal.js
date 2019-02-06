// @flow
import { Modal, Alert } from "antd";
import React, { useState } from "react";

import Toast from "libs/toast";
import messages from "messages";

import LoginForm from "./login_form";
import RegistrationForm from "./registration_form";

type Props = {
  onLoggedIn: () => void,
  onCancel: () => void,
  visible: boolean,
};

export default function AuthenticationModal({ onLoggedIn, onCancel, visible }: Props) {
  const [step, setStep] = useState("Register");

  const showLogin = () => setStep("Login");
  const onRegistered = (isUserLoggedIn: boolean) => {
    if (isUserLoggedIn) onLoggedIn();
    Toast.success(messages["auth.account_created"]);
    showLogin();
  };

  return (
    <Modal title={step} onCancel={onCancel} visible={visible} footer={null} maskClosable={false}>
      <Alert
        message="You have to register and/or login to create a tracing."
        type="info"
        showIcon
        style={{ marginBottom: 20 }}
      />
      {step === "Register" ? (
        <React.Fragment>
          <RegistrationForm onRegistered={onRegistered} />
          <a href="#" onClick={showLogin}>
            Already have an account? Login instead.
          </a>
        </React.Fragment>
      ) : (
        <LoginForm layout="inline" onLoggedIn={onLoggedIn} />
      )}
    </Modal>
  );
}
