// @flow
import Maybe from "data.maybe";
import _ from "lodash";
import memoizeOne from "memoize-one";

import type { APIAllowedMode, APIDataset, APISegmentationLayer } from "admin/api_flow_types";
import type { Settings, DataLayerType } from "oxalis/store";
import ErrorHandling from "libs/error_handling";
import constants, { ModeValues, type Vector3, Vector3Indicies } from "oxalis/constants";
import messages from "messages";

function _getResolutions(dataset: APIDataset): Vector3[] {
  // Different layers can have different resolutions. At the moment,
  // unequal resolutions will result in undefined behavior.
  // However, if resolutions are subset of each other, everything should be fine.
  // For that case, returning the longest resolutions array should suffice

  const mostExtensiveResolutions = _.chain(dataset.dataSource.dataLayers)
    .map(dataLayer => dataLayer.resolutions)
    .sortBy(resolutions => resolutions.length)
    .last()
    .valueOf();
  if (!mostExtensiveResolutions) {
    return [];
  }

  const lastResolution = _.last(mostExtensiveResolutions);

  // We add another level of resolutions to allow zooming out even further
  const extendedResolutions = _.range(constants.DOWNSAMPLED_ZOOM_STEP_COUNT).map(idx =>
    lastResolution.map(el => 2 ** (idx + 1) * el),
  );

  return mostExtensiveResolutions.concat(extendedResolutions);
}

// _getResolutions itself is not very performance intensive, but other functions which rely
// on the returned resolutions are. To avoid busting memoization caches (which rely on references),
// we memoize _getResolutions, as well.
export const getResolutions = memoizeOne(_getResolutions);

function _getMaxZoomStep(maybeDataset: ?APIDataset): number {
  const minimumZoomStepCount = 1;
  const maxZoomstep = Maybe.fromNullable(maybeDataset)
    .map(dataset =>
      Math.max(
        minimumZoomStepCount,
        Math.max(0, ...getResolutions(dataset).map(r => Math.max(r[0], r[1], r[2]))),
      ),
    )
    .getOrElse(2 ** (minimumZoomStepCount + constants.DOWNSAMPLED_ZOOM_STEP_COUNT - 1));
  return maxZoomstep;
}

export const getMaxZoomStep = memoizeOne(_getMaxZoomStep);

function getDataLayers(dataset: APIDataset): DataLayerType[] {
  return dataset.dataSource.dataLayers;
}

export function getLayerByName(dataset: APIDataset, layerName: string): DataLayerType {
  const dataLayers = getDataLayers(dataset);
  const hasUniqueNames = _.uniqBy(dataLayers, "name").length === dataLayers.length;
  ErrorHandling.assert(hasUniqueNames, messages["dataset.unique_layer_names"]);

  const layer = dataLayers.find(l => l.name === layerName);
  if (!layer) {
    throw new Error(`Layer "${layerName}" not found`);
  }
  return layer;
}

export function getMappings(dataset: APIDataset, layerName: string): string[] {
  return getLayerByName(dataset, layerName).mappings || [];
}

export function isRgb(dataset: APIDataset, layerName: string): boolean {
  return (
    getLayerByName(dataset, layerName).category === "color" &&
    getByteCount(dataset, layerName) === 3
  );
}

export function getByteCountFromLayer(layerInfo: DataLayerType): number {
  return getBitDepth(layerInfo) >> 3;
}

export function getByteCount(dataset: APIDataset, layerName: string): number {
  return getByteCountFromLayer(getLayerByName(dataset, layerName));
}

type Boundary = { lowerBoundary: Vector3, upperBoundary: Vector3 };

export function getLayerBoundaries(dataset: APIDataset, layerName: string): Boundary {
  const { topLeft, width, height, depth } = getLayerByName(dataset, layerName).boundingBox;
  const lowerBoundary = topLeft;
  const upperBoundary = [topLeft[0] + width, topLeft[1] + height, topLeft[2] + depth];

  return { lowerBoundary, upperBoundary };
}

export function getBoundaries(dataset: APIDataset): Boundary {
  const lowerBoundary = [Infinity, Infinity, Infinity];
  const upperBoundary = [-Infinity, -Infinity, -Infinity];
  const layers = getDataLayers(dataset);

  for (const dataLayer of layers) {
    const layerBoundaries = getLayerBoundaries(dataset, dataLayer.name);
    for (const i of Vector3Indicies) {
      lowerBoundary[i] = Math.min(lowerBoundary[i], layerBoundaries.lowerBoundary[i]);
      upperBoundary[i] = Math.max(upperBoundary[i], layerBoundaries.upperBoundary[i]);
    }
  }

  return { lowerBoundary, upperBoundary };
}

export function getDatasetCenter(dataset: APIDataset): Vector3 {
  const { lowerBoundary, upperBoundary } = getBoundaries(dataset);
  return [
    (lowerBoundary[0] + upperBoundary[0]) / 2,
    (lowerBoundary[1] + upperBoundary[1]) / 2,
    (lowerBoundary[2] + upperBoundary[2]) / 2,
  ];
}

export function determineAllowedModes(
  dataset: APIDataset,
  settings: Settings,
): { preferredMode: ?APIAllowedMode, allowedModes: Array<APIAllowedMode> } {
  // The order of allowedModes should be independent from the server and instead be similar to ModeValues
  let allowedModes = _.intersection(ModeValues, settings.allowedModes);

  const colorLayer = _.find(dataset.dataSource.dataLayers, {
    category: "color",
  });
  if (colorLayer != null && colorLayer.elementClass !== "uint8") {
    allowedModes = allowedModes.filter(mode => !constants.MODES_ARBITRARY.includes(mode));
  }

  let preferredMode = null;
  if (settings.preferredMode != null) {
    const modeId = settings.preferredMode;
    if (allowedModes.includes(modeId)) {
      preferredMode = modeId;
    }
  }

  return { preferredMode, allowedModes };
}

export function getBitDepth(layerInfo: DataLayerType): number {
  return parseInt(layerInfo.elementClass.substring(4), 10);
}

export function isSegmentationLayer(dataset: APIDataset, layerName: string): boolean {
  return getLayerByName(dataset, layerName).category === "segmentation";
}

export function isColorLayer(dataset: APIDataset, layerName: string): boolean {
  return getLayerByName(dataset, layerName).category === "color";
}

export function getSegmentationLayer(dataset: APIDataset): ?APISegmentationLayer {
  const segmentationLayers = dataset.dataSource.dataLayers.filter(dataLayer =>
    isSegmentationLayer(dataset, dataLayer.name),
  );
  if (segmentationLayers.length === 0) {
    return null;
  }
  // Currently, only one segmentationLayer at a time is supported
  // Flow does not understand that this is a segmentation layer (since
  // we checked via `isSegmentationLayer`).
  // $FlowFixMe
  return segmentationLayers[0];
}

export function hasSegmentation(dataset: APIDataset): boolean {
  return getSegmentationLayer(dataset) != null;
}

export function getColorLayers(dataset: APIDataset): Array<DataLayerType> {
  return dataset.dataSource.dataLayers.filter(dataLayer => isColorLayer(dataset, dataLayer.name));
}

export function getThumbnailURL(dataset: APIDataset): string {
  const datasetName = dataset.name;
  const organizationName = dataset.owningOrganization;
  const layers = dataset.dataSource.dataLayers;
  const colorLayer = _.find(layers, { category: "color" });
  if (colorLayer) {
    return `/api/datasets/${organizationName}/${datasetName}/layers/${colorLayer.name}/thumbnail`;
  }
  return "";
}

export function getSegmentationThumbnailURL(dataset: APIDataset): string {
  const datasetName = dataset.name;
  const organizationName = dataset.owningOrganization;
  const segmentationLayer = getSegmentationLayer(dataset);
  if (segmentationLayer) {
    return `/api/datasets/${organizationName}/${datasetName}/layers/${
      segmentationLayer.name
    }/thumbnail`;
  }
  return "";
}

export default {};
