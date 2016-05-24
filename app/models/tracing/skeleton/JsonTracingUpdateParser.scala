package models.tracing.skeleton

import scala.collection.generic

import com.scalableminds.util.geometry.{Point3D, Vector3D}
import com.scalableminds.util.image.Color
import models.tracing.skeleton.persistence._
import oxalis.nml._
import play.api.libs.functional.syntax._
import play.api.libs.json._

object JsonTracingUpdateParser {
  val valueReads = (__ \ "value").read[JsObject]

  val actionReads = (__ \ "action").read[String]

  def parseUpdateArray(skeletonId: String): Reads[List[SkeletonCmd]] = {
    __.read(Reads.traversableReads[List, SkeletonCmd](implicitly[generic.CanBuildFrom[List[_],SkeletonCmd,List[SkeletonCmd]]],parseUpdate(skeletonId)))
  }

  class FailedReads[A](msg: String) extends Reads[A] {
    override def reads(json: JsValue): JsResult[A] = JsError(msg)
  }

  def parseUpdate(skeletonId: String): Reads[SkeletonCmd] = {
    actionReads.flatMap { action =>
      val valueReads = action match {
        case "createTree"        => createTreeReads(skeletonId)
        case "deleteTree"        => deleteTreeReads(skeletonId)
        case "updateTree"        => updateTreeReads(skeletonId)
        case "mergeTree"         => mergeTreeReads(skeletonId)
        case "moveTreeComponent" => moveTreeComponentReads(skeletonId)
        case "createNode"        => createNodeReads(skeletonId)
        case "deleteNode"        => deleteNodeReads(skeletonId)
        case "updateNode"        => updateNodeReads(skeletonId)
        case "createEdge"        => createEdgeReads(skeletonId)
        case "deleteEdge"        => deleteEdgeReads(skeletonId)
        case "updateTracing"     => updateTracingReads(skeletonId)
        case action              => new FailedReads[SkeletonCmd](s"Invalid action '$action'")
      }
      (__ \ "value").read(valueReads)
    }
  }

  private def nameFromId(treeId: Int) = f"Tree$treeId%03d"

  private def createTreeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val createTree =
      ((__ \ "id").read[Int] and
        (__ \ "color").readNullable[Color] and
        (__ \ "timestamp").read[Long] and
        (__ \ "name").readNullable[String]).tupled

    createTree.map {
      case ((id, color, timestamp, name)) =>
        CreateTreeCmd(skeletonId, Tree(id, Set.empty, Set.empty, color, name = name.getOrElse(nameFromId(id))))
    }
  }

  private def deleteTreeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val idReads = (__ \ "id").read[Int]

    idReads.map {
      case id =>
        DeleteTreeCmd(skeletonId, id)
    }
  }

  private def updateTreeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val updateTree =
      ((__ \ "id").read[Int] and
        (__ \ "updatedId").readNullable[Int] and
        (__ \ "color").readNullable[Color] and
        (__ \ "name").readNullable[String]).tupled

    updateTree.map {
      case ((id, updatedId, color, name)) =>
        UpdateTreePropertiesCmd(skeletonId, id, updatedId, color, name = name.getOrElse(nameFromId(id)))
    }
  }

  private def mergeTreeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val mergeTrees =
      ((__ \ "sourceId").read[Int] and
        (__ \ "targetId").read[Int]).tupled

    mergeTrees.map {
      case ((sourceId, targetId)) =>
        MergeTreesCmd(skeletonId, sourceId, targetId)
    }
  }

  private def moveTreeComponentReads(skeletonId: String): Reads[SkeletonCmd] = {
    val mergeTrees =
      ((__ \ "sourceId").read[Int] and
        (__ \ "targetId").read[Int] and
        (__ \ "nodeIds").read[List[Int]]).tupled

    mergeTrees.map {
      case ((sourceId, targetId, nodeIds)) =>
        MoveTreeComponentCmd(skeletonId, sourceId, targetId, nodeIds)
    }
  }

  private def createNodeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val createNode =
      ((__ \ "treeId").read[Int] and
        (__).read[Node]).tupled

    createNode.map {
      case ((treeId, node)) =>
        CreateNodeCmd(skeletonId, treeId, node)
    }
  }

  private def deleteNodeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val deleteNode =
      ((__ \ "id").read[Int] and
        (__ \ "treeId").read[Int]).tupled

    deleteNode.map {
      case ((id, treeId)) =>
        DeleteNodeCmd(skeletonId, id, treeId)
    }
  }

  private def updateNodeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val updateNode =
      ((__ \ "treeId").read[Int] and
        (__).read[Node]).tupled

    updateNode.map {
      case ((treeId, node)) =>
        UpdateNodePropertiesCmd(skeletonId, treeId, node)
    }
  }

  private def createEdgeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val createEdge =
      ((__ \ "treeId").read[Int] and
        (__).read[Edge]).tupled

    createEdge.map {
      case ((treeId, edge)) =>
        CreateEdgeCmd(skeletonId, treeId, edge)
    }
  }

  private def deleteEdgeReads(skeletonId: String): Reads[SkeletonCmd] = {
    val deleteEdge =
      ((__ \ "treeId").read[Int] and
        (__).read[Edge]).tupled

    deleteEdge.map {
      case ((treeId, edge)) =>
        DeleteEdgeCmd(skeletonId, treeId, edge)
    }
  }

  private def updateTracingReads(skeletonId: String): Reads[SkeletonCmd] = {
    val updateTracing =
      ((__ \ "branchPoints").read[List[BranchPoint]] and
        (__ \ "comments").read[List[Comment]] and
        (__ \ "activeNode").readNullable[Int] and
        (__ \ "editPosition").read[Point3D] and
        (__ \ "editRotation").read[Vector3D] and
        (__ \ "zoomLevel").read[Double]).tupled

    updateTracing.map {
      case ((branchPoints, comments, activeNode, editPosition, editRotation, zoomLevel)) =>
        UpdateMetadataCmd(skeletonId, Some(branchPoints), Some(comments), activeNode, Some(editPosition), Some(editRotation), Some(zoomLevel))
    }
  }
}
