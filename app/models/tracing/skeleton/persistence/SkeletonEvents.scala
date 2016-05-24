/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.image.Color
import models.annotation.AnnotationSettings
import models.tracing.skeleton.SkeletonTracing
import oxalis.nml._

sealed trait SkeletonEvt extends Serializable {
  val skeletonId: String
}

case class WholeTracingChangedEvt(skeletonId: String, skeleton: SkeletonTracing) extends SkeletonEvt

// Node related events

case class NodeCreatedEvt(skeletonId: String, treeId: Int, node: Node) extends SkeletonEvt

case class NodeDeletedEvt(skeletonId: String, treeId: Int, node: Int) extends SkeletonEvt

case class NodePropertiesUpdatedEvt(skeletonId: String, treeId: Int, node: Node) extends SkeletonEvt

case class EdgeCreatedEvt(skeletonId: String, treeId: Int, edge: Edge) extends SkeletonEvt

case class EdgeDeletedEvt(skeletonId: String, treeId: Int, edge: Edge) extends SkeletonEvt

case class BranchPointsUpdatedEvt(skeletonId: String, branchPoints: List[BranchPoint]) extends SkeletonEvt

case class CommentsUpdatedEvt(skeletonId: String, comments: List[Comment]) extends SkeletonEvt

// Tree related events
case class TreeCreatedEvt(skeletonId: String, tree: Tree) extends SkeletonEvt

case class TreePropertiesUpdatedEvt(skeletonId: String, treeId: Int, updatedId: Int, color: Option[Color], name: String) extends SkeletonEvt

case class TreeMergedEvt(skeletonId: String, sourceTreeId: Int, targetTreeId: Int) extends SkeletonEvt

case class TreeComponentMovedEvt(skeletonId: String, sourceTreeId: Int, targetTreeId: Int, nodeIds: List[Int]) extends SkeletonEvt

case class TreeDeletedEvt(skeletonId: String, treeId: Int) extends SkeletonEvt

case class ActiveNodeUpdatedEvt(skeletonId: String, activeNode: Option[Int]) extends SkeletonEvt

case class EditViewUpdatedEvt(skeletonId: String, editPosition: Point3D, editRotation: Vector3D, zoomLevel: Double) extends SkeletonEvt

case class ArchivedTracingEvt(skeletonId: String) extends SkeletonEvt

case class UpdatedAnnotationSettingsEvt(skeletonId: String, settings: AnnotationSettings) extends SkeletonEvt

case class UpdatedBoundingBoxEvt(skeletonId: String, boundingBox: Option[BoundingBox]) extends SkeletonEvt

case class UpdatedDataSetEvt(skeletonId: String, dataSetName: String) extends SkeletonEvt
