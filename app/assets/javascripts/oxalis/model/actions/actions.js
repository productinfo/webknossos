/* eslint-disable import/prefer-default-export */

// @flow
import type { SkeletonTracingActionTypes } from "oxalis/model/actions/skeletontracing_actions";
import type { SettingActionTypes } from "oxalis/model/actions/settings_actions";
import type { TaskActionTypes } from "oxalis/model/actions/task_actions";

export type ActionType =
  SkeletonTracingActionTypes |
  SettingActionTypes |
  TaskActionTypes;

export const wkReadyAction = () => ({
  type: "WK_READY",
});
