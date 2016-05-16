/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import models.annotation.AnnotationSettings

case class SkeletonTracingInit(
  dataSetName: String,
  start: Point3D,
  rotation: Vector3D,
  boundingBox: Option[BoundingBox],
  insertStartAsNode: Boolean,
  isFirstBranchPoint: Boolean,
  settings: AnnotationSettings = AnnotationSettings.skeletonDefault)
