/**
 * error_handling.js
 * @flow
 */
import AirbrakeClient from "airbrake-js";
import _ from "lodash";

import { getActionLog } from "oxalis/model/helpers/action_logger_middleware";
import type { APIUser } from "admin/api_flow_types";
import Toast from "libs/toast";
import messages from "messages";
import window, { document, location } from "libs/window";

type ErrorHandlingOptions = {
  throwAssertions: boolean,
  sendLocalErrors: boolean,
};

class ErrorWithParams extends Error {
  params: ?mixed;
}

// This method can be used when catching error within async processes.
// For example:
// try {
//   this.setState({ isLoading: true });
// } except (error) {
//   handleGenericError(error);
// } finally {
//   this.setState({isLoading: false});
// }
// When the thrown error is coming from the server, our request module
// will show the error to the user.
// If some other error occurred, this function will tell the user so.
export function handleGenericError(error: { ...Error, messages?: mixed }) {
  if (error.messages) {
    // The user was already notified about this error
    return;
  }
  Toast.error(messages.unknown_error);
  console.warn(error);
}

class ErrorHandling {
  throwAssertions: boolean;
  sendLocalErrors: boolean;
  commitHash: ?string;
  airbrake: AirbrakeClient;

  initialize(options: ErrorHandlingOptions) {
    if (options == null) {
      options = { throwAssertions: false, sendLocalErrors: false };
    }
    this.throwAssertions = options.throwAssertions;
    this.sendLocalErrors = options.sendLocalErrors;

    const metaElement = document.querySelector("meta[name='commit-hash']");
    this.commitHash = metaElement ? metaElement.getAttribute("content") : null;

    this.initializeAirbrake();
  }

  initializeAirbrake() {
    // read Airbrake config from DOM
    // config is inject from backend
    const scriptTag = document.querySelector("[data-airbrake-project-id]");
    if (!scriptTag) throw new Error("failed to initialize airbrake");

    const projectId = scriptTag.dataset.airbrakeProjectId;
    const projectKey = scriptTag.dataset.airbrakeProjectKey;
    const envName = scriptTag.dataset.airbrakeEnvironmentName;

    this.airbrake = new AirbrakeClient({
      projectId,
      projectKey,
    });

    this.airbrake.addFilter(notice => {
      notice.context = notice.context || {};
      notice.context.environment = envName;
      if (this.commitHash != null) {
        notice.context.version = this.commitHash;
      }
      return notice;
    });

    if (!this.sendLocalErrors) {
      this.airbrake.addFilter(notice => {
        if (location.hostname !== "127.0.0.1" && location.hostname !== "localhost") {
          return notice;
        }
        return null;
      });
    }

    window.onerror = (message: string, file: string, line: number, colno: number, error: Error) => {
      if (error == null) {
        // older browsers don't deliver the error parameter
        error = new Error(message);
      }
      console.error(error);
      this.notify(error);

      Toast.error(
        `An unknown error occurred. Please consider refreshing this page to avoid an inconsistent state. Error message: ${error.toString()}`,
      );
    };
  }

  notify(error: Error, optParams: Object = {}, escalateToSlack: boolean = false) {
    const actionLog = getActionLog();
    this.airbrake.notify({ error, params: { ...optParams, actionLog } });
    if (escalateToSlack) {
      const webhookUrl =
        "https://hooks.slack.com/services/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";
      fetch(webhookUrl, {
        method: "POST",
        headers: {},
        body: JSON.stringify({
          text: `*Inconsistent tracing* :k:
*Error*: \`${error.toString()}\`
*Url*: \`${location.href}\`
*Action Log*: \`${JSON.stringify(actionLog)}\`
*Params*: \`${JSON.stringify(optParams)}\``,
        }),
      });
    }
  }

  assertExtendContext(additionalContext: Object) {
    this.airbrake.addFilter(notice => {
      Object.assign(notice.context, additionalContext);
      return notice;
    });
  }

  assert = (
    bool: boolean,
    message: string,
    assertionContext?: Object,
    dontThrowError?: boolean = false,
  ) => {
    if (bool) {
      return;
    }

    const error: ErrorWithParams = new ErrorWithParams(`Assertion violated - ${message}`);

    error.params = assertionContext;
    error.stack = this.trimCallstack(error.stack);

    Toast.error(`Assertion violated - ${message}`);

    if (this.throwAssertions && !dontThrowError) {
      // error will be automatically pushed to airbrake due to global handler
      throw error;
    } else {
      console.error(error);
      this.airbrake.notify(error);
    }
  };

  assertExists(variable: any, message: string, assertionContext?: Object) {
    if (variable != null) {
      return;
    }
    this.assert(false, `${message} (variable is ${variable})`, assertionContext);
  }

  assertEquals(actual: any, wanted: any, message: string, assertionContext?: Object) {
    if (actual === wanted) {
      return;
    }
    this.assert(false, `${message} (${actual} != ${wanted})`, assertionContext);
  }

  setCurrentUser(user: APIUser) {
    this.airbrake.addFilter(notice => {
      notice.context = notice.context || {};
      notice.context.user = _.pick(user, ["id", "email", "firstName", "lastName", "isActive"]);
      return notice;
    });
  }

  trimCallstack(callstack: string) {
    // cut function calls caused by ErrorHandling so that Airbrake won't cluster all assertions into one group
    const trimmedCallstack = [];

    for (const line of callstack.split("\n")) {
      if (line.indexOf("errorHandling.js") === -1) {
        trimmedCallstack.push(line);
      }
    }

    return trimmedCallstack.join("\n");
  }
}

export default new ErrorHandling();
