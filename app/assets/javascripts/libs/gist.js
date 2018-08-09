/*
 * gist.js
 * @flow
 */

import _ from "lodash";
import Request from "libs/request";
import Toast from "libs/toast";
import messages from "messages";

// https://developer.github.com/v3/gists/#get-a-single-gist
type GithubGistType = {
  files: {
    [string]: {
      size: number,
      raw_url: string,
      type: "text/plain",
      language: string,
      truncated: boolean,
      content: string,
    },
  },
};

function handleError(name: string) {
  Toast.error(`${messages["task.user_script_retrieval_error"]} ${name}`);
}

export async function fetchGistContent({
  gist,
  name,
}: {
  +gist: string,
  +name: string,
}): Promise<string> {
  const gistId = _.last(gist.split("/"));

  let gistObject;
  try {
    gistObject = (await Request.receiveJSON(
      `https://api.github.com/gists/${gistId}`,
    ): GithubGistType);
  } catch (e) {
    handleError(name);
    return "";
  }

  const firstFile = gistObject.files[Object.keys(gistObject.files)[0]];
  if (firstFile && firstFile.content) {
    return firstFile.content;
  } else {
    handleError(name);
    return "";
  }
}

export default {};
