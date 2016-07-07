/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.image.Color
import com.scalableminds.util.reactivemongo.{DBAccessContext, GlobalAccessContext}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.annotation.AnnotationSettings
import models.basics.SecuredBaseDAO
import oxalis.nml._
import play.api.libs.json.Json
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import play.modules.reactivemongo.json.BSONFormats._
import play.api.libs.concurrent.Execution.Implicits._

case class DBEdge(edge: Edge, _treeId: BSONObjectID, _id: BSONObjectID = BSONObjectID.generate)

object DBEdge {
  implicit val dbEdgeFormat = Json.format[DBEdge]
}

object DBEdgeDAO extends SecuredBaseDAO[DBEdge] {

  val collectionName = "edges"

  val formatter = DBEdge.dbEdgeFormat

  underlying.indexesManager.ensure(Index(Seq("_treeId" -> IndexType.Ascending)))

  def findByTree(_tree: BSONObjectID)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find(Json.obj("_treeId" -> _tree)).cursor[DBEdge]().collect[List]()
  }
}

case class DBNode(node: Node, _treeId: BSONObjectID, _id: BSONObjectID = BSONObjectID.generate)

object DBNode {
  implicit val dbNodeFormat = Json.format[DBNode]
}

object DBNodeDAO extends SecuredBaseDAO[DBNode] {

  val collectionName = "nodes"

  val formatter = DBNode.dbNodeFormat

  underlying.indexesManager.ensure(Index(Seq("_treeId" -> IndexType.Ascending)))

  def findByTree(_tree: BSONObjectID)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find("_treeId", _tree).collect[List]()
  }
}

case class DBTree(_tracing: BSONObjectID, treeId: Int, color: Option[Color], timestamp: Long = System.currentTimeMillis, name: String = "", branchPoints: List[BranchPoint], comments: List[Comment], _id: BSONObjectID = BSONObjectID.generate){
  def nodes = DBNodeDAO.findByTree(_id)(GlobalAccessContext).map(_.map(_.node).toSet)

  def edges = DBEdgeDAO.findByTree(_id)(GlobalAccessContext).map(_.map(_.edge).toSet)

  def toTree = for{
  ns <- nodes
  es <- edges
  } yield Tree(treeId, ns, es, color, branchPoints, comments, name)
}

object DBTree {
  implicit val dbTreeFormat = Json.format[DBTree]
}

object DBTreeDAO extends SecuredBaseDAO[DBTree] {

  val collectionName = "trees"

  val formatter = DBTree.dbTreeFormat

  underlying.indexesManager.ensure(Index(Seq("_tracing" -> IndexType.Ascending)))

  def findByTracing(_tracing: BSONObjectID)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find("_tracing", _tracing).collect[List]()
  }
}


case class DBSkeletonTracing(
  dataSetName: String,
  timestamp: Long,
  activeNodeId: Option[Int],
  editPosition: Point3D,
  editRotation: Vector3D,
  zoomLevel: Double,
  boundingBox: Option[BoundingBox],
  stats: Option[SkeletonTracingStatistics],
  settings: AnnotationSettings = AnnotationSettings.skeletonDefault,
  _id: BSONObjectID = BSONObjectID.generate
) {
  def DBTrees = DBTreeDAO.findByTracing(_id)(GlobalAccessContext)

  def trees: Fox[List[Tree]] = DBTrees.flatMap{ ts =>
    Fox.combined(ts.map(t => t.toTree))
  }

}

object DBSkeletonTracing{
  implicit val dbSkeletonTracingFormat = Json.format[DBSkeletonTracing]

}

object DBSkeletonTracingDAO extends SecuredBaseDAO[DBSkeletonTracing] with FoxImplicits {

  val collectionName = "skeletons"

  val formatter = DBSkeletonTracing.dbSkeletonTracingFormat
}

object DBSkeletonTracingService{
  def findOneById(id: String): Fox[SkeletonTracing] = {
    for{
      sk <- DBSkeletonTracingDAO.findOneById(id)(GlobalAccessContext)
      trees <- sk.trees
    } yield {
      SkeletonTracing(
        sk.dataSetName,
        sk.timestamp,
        sk.activeNodeId,
        sk.editPosition,
        sk.editRotation,
        sk.zoomLevel,
        sk.boundingBox,
        sk.settings,
        isArchived = false,
        trees = trees,
        id = sk._id.stringify
      )
    }
  }
}
