package models.tracing.skeleton

import models.tracing.TracingStatistics
import scala.concurrent.Future

import com.scalableminds.util.tools.{Fox, FoxImplicits}
import play.api.libs.concurrent.Execution.Implicits._
import com.scalableminds.util.reactivemongo.{DBAccessContext, GlobalAccessContext}
import oxalis.nml.Tree
import play.api.libs.json.Json

// TODO: REMOVE???
case class SkeletonTracingStatistics(
                                      numberOfNodes: Long,
                                      numberOfEdges: Long,
                                      numberOfTrees: Long
                                    ) extends TracingStatistics with FoxImplicits {

  def createTree = Future.successful(this.copy(numberOfTrees = this.numberOfTrees + 1))

  def deleteTree(tree: Tree) = {
    this.copy(
      this.numberOfNodes - tree.nodes.size,
      this.numberOfEdges - tree.edges.size,
      this.numberOfTrees - 1
    )
  }

  def mergeTree = Future.successful(this.copy(numberOfTrees = this.numberOfTrees - 1))

  def createNode = Future.successful(this.copy(numberOfNodes = this.numberOfNodes + 1))

  def deleteNode(nodeId: Int, tree: Tree) = {
    val nEdges = tree.edges.count(e => e.source == nodeId || e.target == nodeId)
    this.copy(
      this.numberOfNodes - 1,
      this.numberOfEdges - nEdges,
      this.numberOfTrees
    )
  }

  def createEdge = Future.successful(this.copy(numberOfEdges = this.numberOfEdges + 1))

  def deleteEdge = Future.successful(this.copy(numberOfEdges = this.numberOfEdges + 1))
}

object SkeletonTracingStatistics {
  implicit val skeletonTracingStatisticsFormat = Json.format[SkeletonTracingStatistics]
}
