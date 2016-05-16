package models.tracing.skeleton

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.image.Color
import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.annotation.{AnnotationContent, AnnotationSettings}
import models.binary.DataSetDAO
import models.tracing.CommonTracing
import models.tracing.skeleton.persistence.SkeletonTracingService
import net.liftweb.common.Full
import oxalis.nml._
import oxalis.nml.utils._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import reactivemongo.play.json.BSONFormats._
import reactivemongo.bson.BSONObjectID

case class SkeletonTracing(
  dataSetName: String,
  branchPoints: List[BranchPoint],
  timestamp: Long,
  activeNodeId: Option[Int],
  editPosition: Point3D,
  editRotation: Vector3D,
  zoomLevel: Double,
  boundingBox: Option[BoundingBox],
  comments: List[Comment] = Nil,
  settings: AnnotationSettings = AnnotationSettings.skeletonDefault,
  isArchived: Boolean,
  trees: List[Tree],
  id: String = BSONObjectID.generate.stringify
)
  extends SkeletonTracingLike with AnnotationContent with CommonTracing with TreeMergeHelpers {

  type Self = SkeletonTracing

  lazy val treeMap = trees.map(t => t.treeId -> t).toMap

  lazy val stats = {
    val numberOfTrees = trees.size

    val (numberOfNodes, numberOfEdges) = trees.foldLeft((0l, 0l)) {
      case ((nodeCount, edgeCount), tree) =>
        val nNodes = tree.nodes.size
        val nEdges = tree.edges.size
        (nodeCount + nNodes, edgeCount + nEdges)
    }

    Some(SkeletonTracingStatistics(numberOfNodes, numberOfEdges, numberOfTrees))
  }

  def service =
    SkeletonTracingService

  def allowAllModes =
    this.copy(settings = settings.copy(allowedModes = AnnotationSettings.SKELETON_MODES))

  def tree(treeId: Int) =
    treeMap.get(treeId)

  def doesTreeExist(treeId: Int) =
    trees.find(t => t.treeId == treeId)

  def withNewNodeInTree(treeId: Int, node: Node) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.addNodes(Set(node))
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withoutNodeInTree(treeId: Int, nodeId: Int) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.removeNode(nodeId)
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withUpdatedNode(treeId: Int, node: Node) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.updateNode(node)
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withNewEdgeInTree(treeId: Int, edge: Edge) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.addEdges(Set(edge))
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withoutEdgeInTree(treeId: Int, edge: Edge) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.removeEdge(edge)
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withUpdatedTree(treeId: Int, tree: Tree) = {
    this.copy(trees = tree :: trees.filter(_.treeId != treeId))
  }

  def withUpdatedTreeProperties(treeId: Int, updatedId: Int, color: Option[Color], name: String) = {
    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.copy(treeId = updatedId, color = color, name = name)
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withMovedTreeComponent(sourceTreeId: Int, targetTreeId: Int, nodeIds: Set[Int]) = {
    val updated = for {
      sourceTree <- treeMap.get(sourceTreeId)
      targetTree <- treeMap.get(targetTreeId)
    } yield {
      val (targetNodes, sourceNodes) = sourceTree.nodes.partition(n => nodeIds.contains(n.id))
      val (targetEdges, sourceEdges) = sourceTree.edges.partition(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
      val filteredSourceEdges = sourceEdges.filterNot(e => nodeIds.contains(e.source) || nodeIds.contains(e.target))
      val updatedTargetTree = targetTree.addNodes(targetNodes).addEdges(targetEdges)
      val updatedSourceTree = sourceTree.copy(nodes = sourceNodes, edges = filteredSourceEdges)
      withUpdatedTree(targetTreeId, updatedTargetTree).withUpdatedTree(sourceTreeId, updatedSourceTree)
    }
    updated getOrElse this

  }

  def withMergedTrees(sourceTreeId: Int, targetTreeId: Int) = {
    val updated = for {
      sourceTree <- treeMap.get(sourceTreeId)
      targetTree <- treeMap.get(targetTreeId)
    } yield {
      val updatedTree = targetTree.addNodes(sourceTree.nodes).addEdges(sourceTree.edges)
      withoutTree(sourceTreeId).withUpdatedTree(targetTreeId, updatedTree)
    }
    updated getOrElse this
  }

  def withoutTree(treeId: Int) = {
    this.copy(trees = trees.filter(_.treeId != treeId))
  }

  def renameTrees(reNamer: Tree => String) = {
    this.copy(trees = trees.map(t => t.changeName(reNamer(t))))
  }

  def maxNodeId =
    oxalis.nml.utils.maxNodeId(trees)

  def mergeWith(annotationContent: AnnotationContent)(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {
    def mergeBoundingBoxes(aOpt: Option[BoundingBox], bOpt: Option[BoundingBox]) =
      for {
        a <- aOpt
        b <- bOpt
      } yield a.combineWith(b)

    annotationContent match {
      case s: SkeletonTracing =>
        val sourceTrees = s.trees
        val nodeMapping = calculateNodeMapping(sourceTrees, trees)
        val mergedTrees = mergeTrees(sourceTrees, trees, nodeMapping)
        val mergedBranchPoints = branchPoints ::: s.branchPoints.map(b => b.copy(id = nodeMapping(b.id)))
        val mergedComments = comments ::: s.comments.map(c => c.copy(node = nodeMapping(c.node)))
        val mergedBoundingBox = mergeBoundingBoxes(boundingBox, s.boundingBox)
        val result =
          this.copy(
            trees = mergedTrees,
            branchPoints = mergedBranchPoints,
            comments = mergedComments,
            boundingBox = mergedBoundingBox)
        Fox.successful(result)
      case s                  =>
        Fox.failure("Can't merge annotation content of a different type into TemporarySkeletonTracing. Tried to merge " + s.id)
    }
  }

  // TODO: remove?
  override def temporaryDuplicate(id: String)(implicit ctx: DBAccessContext): Fox[AnnotationContent] = {
    Fox.successful(this.copy(id = id))
  }

  // TODO: remove?
  override def saveToDB(implicit ctx: DBAccessContext): Fox[AnnotationContent] = ???
}

trait TreeMergeHelpers {

  protected def mergeTrees(sourceTrees: Iterable[Tree], targetTrees: Iterable[Tree], nodeMapping: FunctionalNodeMapping) = {
    val treeMaxId = maxTreeId(targetTrees)

    val mappedSourceTrees = sourceTrees.map(tree =>
      tree.changeTreeId(tree.treeId + treeMaxId).applyNodeMapping(nodeMapping))

    List.concat(targetTrees, mappedSourceTrees)
  }

  protected def calculateNodeMapping(sourceTrees: Iterable[Tree], targetTrees: Iterable[Tree]) = {
    val nodeIdOffset = calculateNodeOffset(sourceTrees, targetTrees)
    (nodeId: Int) => nodeId + nodeIdOffset
  }

  protected def calculateNodeOffset(sourceTrees: Iterable[Tree], targetTrees: Iterable[Tree]) = {
    if (targetTrees.isEmpty)
      0
    else {
      val targetNodeMaxId = maxNodeId(targetTrees)
      val sourceNodeMinId = minNodeId(sourceTrees)
      math.max(targetNodeMaxId + 1 - sourceNodeMinId, 0)
    }
  }
}

object SkeletonTracing extends FoxImplicits{
  implicit val skeletonTracingFormat = Json.format[SkeletonTracing]

  val contentType = "skeletonTracing"

  val defaultZoomLevel = 2.0

  private def defaultDataSetPosition(dataSetName: String)(implicit ctx: DBAccessContext) = {
    DataSetDAO.findOneBySourceName(dataSetName).futureBox.map {
      case Full(dataSet) =>
        dataSet.defaultStart
      case _ =>
        Point3D(0, 0, 0)
    }
  }

  def createFrom(
    nml: NML,
    id: String,
    boundingBox: Option[BoundingBox],
    settings: AnnotationSettings = AnnotationSettings.default)(implicit ctx: DBAccessContext) = {

    val box = boundingBox.flatMap { box => if (box.isEmpty) None else Some(box) }
    val start = nml.editPosition.toFox.orElse(defaultDataSetPosition(nml.dataSetName))

    start.map {
      SkeletonTracing(
        nml.dataSetName,
        nml.branchPoints,
        System.currentTimeMillis(),
        nml.activeNodeId,
        _,
        Vector3D(0,0,0),
        SkeletonTracing.defaultZoomLevel,
        box,
        nml.comments,
        settings,
        isArchived = false,
        nml.trees,
        id)
    }.toFox
  }

  def createFrom(tracing: SkeletonTracing, id: String)(implicit ctx: DBAccessContext) = {
    tracing.copy(id = id)
  }

  def createFrom(nmls: List[NML], boundingBox: Option[BoundingBox], settings: AnnotationSettings)(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {
    nmls match {
      case head :: tail =>
        val startTracing = createFrom(head, head.timestamp.toString, boundingBox, settings)

        tail.foldLeft(startTracing) {
          case (f, s) =>
            for {
              t <- f
              n <- createFrom(s, s.timestamp.toString, boundingBox)
              r <- t.mergeWith(n)
            } yield {
              r
            }
        }
      case _ =>
        Fox.empty
    }
  }
}
