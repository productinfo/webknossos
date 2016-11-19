package models.tracing.skeleton

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.image.Color
import com.scalableminds.util.reactivemongo.{DBAccessContext, GlobalDBAccess}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.util.xml.{XMLWrites, Xml}
import models.annotation.{AnnotationContent, AnnotationSettings}
import models.binary.DataSetService
import models.tracing.skeleton.persistence.SkeletonTracingService
import org.apache.commons.io.IOUtils
import oxalis.nml._
import oxalis.nml.utils._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import reactivemongo.bson.BSONObjectID

case class SkeletonTracing(
  dataSetName: String,
  timestamp: Long,
  activeNodeId: Option[Int],
  editPosition: Point3D,
  editRotation: Vector3D,
  zoomLevel: Double,
  boundingBox: Option[BoundingBox],
  settings: AnnotationSettings = AnnotationSettings.skeletonDefault,
  isArchived: Boolean,
  trees: List[Tree],
  id: String = BSONObjectID.generate.stringify
)
  extends AnnotationContent with TreeMergeHelpers {

  type Self = SkeletonTracing

  lazy val treeMap = trees.map(t => t.id -> t).toMap

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
    trees.find(t => t.id == treeId)

  def maxTreeId = {
    if (trees.nonEmpty)
      Some(trees.maxBy(_.id).id)
    else
      None
  }

  def splitByNodes(maxNodeCount: Int) = {
    def splitStep(t: SkeletonTracing, mergeMapping: Map[Int, Int]): (SkeletonTracing, Map[Int, Int]) = {
      t.trees.find(_.nodes.size > maxNodeCount) match {
        case Some(tree) =>
          val nextTreeId = t.maxTreeId.map(_ + 1).getOrElse(0)
          val nodeIds = tree.nodes.take(maxNodeCount).map(_.id)
          val (splittedOriginal, splittedTarget) = tree.moveTreeComponent(nodeIds, Tree(nextTreeId))
          val updated = t.withUpdatedTree(tree.id, splittedOriginal).withNewTree(splittedTarget)
          splitStep(updated, mergeMapping + (splittedTarget.id -> tree.id))
        case _          =>
          (t, mergeMapping)
      }
    }

    splitStep(this, Map.empty)
  }

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

  def withNewTree(tree: Tree) = {
    this.copy(trees = tree :: trees)
  }

  def withUpdatedTree(treeId: Int, tree: Tree) = {
    this.copy(trees = tree :: trees.filter(_.id != treeId))
  }

  def withUpdatedTreeProperties(
    treeId: Int,
    updatedId: Int,
    color: Option[Color],
    name: String,
    branchPoints: List[BranchPoint],
    comments: List[Comment]) = {

    treeMap.get(treeId).map { tree =>
      val updatedTree = tree.copy(id = updatedId, color = color, name = name, branchPoints = branchPoints, comments = comments)
      withUpdatedTree(treeId, updatedTree)
    }.getOrElse(this)
  }

  def withMovedTreeComponent(sourceTreeId: Int, targetTreeId: Int, nodeIds: Set[Int]) = {
    val updated = for {
      sourceTree <- treeMap.get(sourceTreeId)
      targetTree <- treeMap.get(targetTreeId)
    } yield {
      val (sourceUpdated, targetUpdated) = sourceTree.moveTreeComponent(nodeIds, targetTree)
      withUpdatedTree(targetTreeId, targetUpdated).withUpdatedTree(sourceTreeId, sourceUpdated)
    }
    updated getOrElse this
  }

  def withMergedTrees(sourceTreeId: Int, targetTreeId: Int) = {
    val updated = for {
      sourceTree <- treeMap.get(sourceTreeId)
      targetTree <- treeMap.get(targetTreeId)
    } yield {
      val updatedTree = targetTree
                        .addNodes(sourceTree.nodes)
                        .addEdges(sourceTree.edges)
                        .copy(
                          branchPoints = targetTree.branchPoints ::: sourceTree.branchPoints,
                          comments = targetTree.comments ::: sourceTree.comments)
      withoutTree(sourceTreeId).withUpdatedTree(targetTreeId, updatedTree)
    }
    updated getOrElse this
  }

  def withoutTree(treeId: Int) = {
    this.copy(trees = trees.filter(_.id != treeId))
  }

  def renameTrees(reNamer: Tree => String) = {
    this.copy(trees = trees.map(t => t.changeName(reNamer(t))))
  }

  def maxNodeId =
    oxalis.nml.utils.maxNodeId(trees)

  def mergeWith(
    annotationContent: AnnotationContent,
    settings: Option[AnnotationSettings])(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {

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
        val mergedBoundingBox = mergeBoundingBoxes(boundingBox, s.boundingBox)
        val result = this.copy(trees = mergedTrees, boundingBox = mergedBoundingBox, settings = settings.getOrElse(this.settings))
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

  def contentType = SkeletonTracing.contentType

  def downloadFileExtension = ".nml"

  def toDownloadStream(name: String)(implicit ctx: DBAccessContext): Fox[Enumerator[Array[Byte]]] =
    NMLService.toNML(this).map(data => Enumerator.fromStream(IOUtils.toInputStream(data)))

  override def contentData =
    SkeletonTracing.skeletonTracingLikeWrites(this)
}

trait TreeMergeHelpers {

  protected def mergeTrees(
    sourceTrees: Iterable[Tree],
    targetTrees: Iterable[Tree],
    nodeMapping: FunctionalNodeMapping) = {

    val treeMaxId = maxTreeId(targetTrees)

    val mappedSourceTrees = sourceTrees.map(tree =>
      tree.changeTreeId(tree.id + treeMaxId).applyNodeMapping(nodeMapping))

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

object SkeletonTracing extends SkeletonTracingWrites with FoxImplicits {
  implicit val skeletonTracingFormat = Json.format[SkeletonTracing]

  val contentType = "skeletonTracing"

  val defaultZoomLevel = 2.0

  def createFrom(
    nml: NML,
    id: String,
    boundingBox: Option[BoundingBox],
    settings: Option[AnnotationSettings] = None)(implicit ctx: DBAccessContext) = {

    val box = boundingBox.flatMap { box => if (box.isEmpty) None else Some(box) }
    val start = nml.editPosition.toFox.orElse(DataSetService.defaultDataSetPosition(nml.dataSetName))

    start.map {
      SkeletonTracing(
        nml.dataSetName,
        System.currentTimeMillis(),
        nml.activeNodeId,
        _,
        nml.editRotation.getOrElse(Vector3D(0, 0, 0)),
        nml.zoomLevel.getOrElse(SkeletonTracing.defaultZoomLevel),
        box,
        settings.getOrElse(AnnotationSettings.default),
        isArchived = false,
        nml.trees,
        id)
    }.toFox
  }

  def createFrom(tracing: SkeletonTracing, id: String)(implicit ctx: DBAccessContext) = {
    tracing.copy(id = id)
  }

  def createFrom(
    nmls: List[NML],
    boundingBox: Option[BoundingBox],
    settings: Option[AnnotationSettings])(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {

    def renameTrees(nml: NML) = {
      // Only rename the trees of the tracings if there is more than one NML
      if (nmls.length > 1) {
        val prefix = nml.name.replaceAll("\\.[^.]*$", "") + "_"
        nml.copy(trees = nml.trees.map(_.addNamePrefix(prefix)))
      } else
          nml
    }

    nmls match {
      case nml :: tail =>
        val startTracing = createFrom(renameTrees(nml), nml.timestamp.toString, boundingBox, settings)

        tail.foldLeft(startTracing) {
          case (f, s) =>
            for {
              t <- f
              n <- createFrom(renameTrees(s), BSONObjectID.generate().stringify, boundingBox)
              r <- t.mergeWith(n, settings)
            } yield {
              r
            }
        }
      case _           =>
        Fox.empty
    }
  }
}

trait SkeletonTracingWrites extends FoxImplicits {

  implicit object SkeletonTracingXMLWrites extends XMLWrites[SkeletonTracing] with GlobalDBAccess {
    def writes(e: SkeletonTracing): Fox[scala.xml.Node] = {
      for {
        parameters <- AnnotationContent.writeParametersAsXML(e)
        treesXml <- Xml.toXML(e.trees.filterNot(_.nodes.isEmpty))
        branchpoints <- Xml.toXML(e.trees.flatMap(_.branchPoints).sortBy(-_.timestamp))
        comments <- Xml.toXML(e.trees.flatMap(_.comments))
      } yield {
        <things>
          <parameters>
            {parameters}{e.activeNodeId.map(id => scala.xml.XML.loadString(s"""<activeNode id="$id"/>""")).getOrElse(scala.xml.Null)}
          </parameters>{treesXml}<branchpoints>
          {branchpoints}
        </branchpoints>
          <comments>
            {comments}
          </comments>
        </things>
      }
    }
  }

  def skeletonTracingLikeWrites(t: SkeletonTracing) =
    Fox.successful(Json.obj(
      "activeNode" -> t.activeNodeId,
      "trees" -> t.trees,
      "zoomLevel" -> t.zoomLevel
    ))
}
