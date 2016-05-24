/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.image.Color
import models.annotation.AnnotationSettings
import models.tracing.skeleton.SkeletonTracing
import oxalis.nml._

sealed trait SkeletonMsg extends Serializable {
  val skeletonId: String
}

sealed trait SkeletonCmd extends SkeletonMsg

case class InitSkeletonCmd(skeletonId: String, initilParams: SkeletonTracingInit) extends SkeletonCmd

case class SetSkeletonCmd(skeletonId: String, skeleton: SkeletonTracing) extends SkeletonCmd

case class CreateNodeCmd(skeletonId: String, treeId: Int, node: Node) extends SkeletonCmd

case class DeleteNodeCmd(skeletonId: String, treeId: Int, node: Int) extends SkeletonCmd

case class UpdateNodePropertiesCmd(skeletonId: String, treeId: Int, node: Node) extends SkeletonCmd

case class CreateEdgeCmd(skeletonId: String, treeId: Int, edge: Edge) extends SkeletonCmd

case class DeleteEdgeCmd(skeletonId: String, treeId: Int, edge: Edge) extends SkeletonCmd

case class UpdateMetadataCmd(
  skeletonId: String,
  branchPoints: Option[List[BranchPoint]] = None,
  comments: Option[List[Comment]] = None,
  activeNode: Option[Int] = None,
  editPosition: Option[Point3D] = None,
  editRotation: Option[Vector3D] = None,
  zoomLevel: Option[Double] = None) extends SkeletonCmd

// Tree related commands
case class CreateTreeCmd(skeletonId: String, tree: Tree) extends SkeletonCmd

case class UpdateTreePropertiesCmd(skeletonId: String, treeId: Int, updatedId: Option[Int], color: Option[Color], name: String) extends SkeletonCmd

case class MergeTreesCmd(skeletonId: String, sourceTreeId: Int, targetTreeId: Int) extends SkeletonCmd

case class MoveTreeComponentCmd(skeletonId: String, sourceTreeId: Int, targetTreeId: Int, nodeIds: List[Int]) extends SkeletonCmd

case class DeleteTreeCmd(skeletonId: String, treeId: Int) extends SkeletonCmd

// Remove all trees, branchpoins and comments
case class ResetSkeletonCmd(skeletonId: String) extends SkeletonCmd

case class ArchiveCmd(skeletonId: String) extends SkeletonCmd

case class UpdateSettingsCmd(skeletonId: String,
  settings: Option[AnnotationSettings] = None,
  dataSetName: Option[String] = None,
  boundingBox: Option[BoundingBox] = None
) extends SkeletonCmd

sealed trait SkeletonAck extends SkeletonMsg

case class ValidUpdateAck(skeletonId: String, skeleton: Option[SkeletonTracing]) extends SkeletonAck

case class InvalidUpdateAck(skeletonId: String, msg: String) extends SkeletonAck

case class InvalidCmdAck(skeletonId: String, msg: String) extends SkeletonAck

//  case class CreateTreeCmd()

sealed trait SkeletonQuery extends SkeletonMsg

case class GetSkeletonQuery(skeletonId: String) extends SkeletonQuery

sealed trait SkeletonQueryResponse extends SkeletonMsg

case class SkeletonResponse(skeletonId: String, skeleton: Option[SkeletonTracing]) extends SkeletonQueryResponse

