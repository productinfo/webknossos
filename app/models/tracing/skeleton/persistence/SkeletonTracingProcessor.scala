/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import scala.concurrent.duration._

import akka.actor.Props
import akka.cluster.sharding.ShardRegion
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.scalableminds.util.geometry.BoundingBox
import models.tracing.skeleton.SkeletonTracing
import oxalis.actor.{ALogging, Passivation}
import oxalis.nml._
import play.api.Logger

object SkeletonTracingProcessor {

  def props: Props = Props(new SkeletonTracingProcessor)

  val idExtractor: ShardRegion.ExtractEntityId = new PartialFunction[ShardRegion.Msg, (ShardRegion.EntityId, ShardRegion.Msg)] {
    override def isDefinedAt(x: ShardRegion.Msg): Boolean = x match {
      case m: SkeletonMsg => true
      case m              => Logger.error(s"Shard '$shardName' received invalid msg type: " + m); false
    }

    override def apply(v1: ShardRegion.Msg) = v1 match {
      case m: SkeletonMsg => (m.skeletonId, m)
    }
  }

  val numberOfShards = 10

  val shardResolver: ShardRegion.ExtractShardId = {
    case m: SkeletonMsg => (math.abs(m.skeletonId.hashCode) % numberOfShards).toString
  }

  val shardName: String = "SkeletonTracingProcessor"
}

class SkeletonTracingProcessor extends PersistentActor with Passivation with ALogging {

  var state: Option[SkeletonTracing] = None

  /** passivate the entity when no activity for 1 minute */
  context.setReceiveTimeout(1.minute)

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  /**
    * Updates skeleton state
    */
  private def updateState(evt: SkeletonEvt, state: Option[SkeletonTracing]): Option[SkeletonTracing] = {
    evt match {
      case WholeTracingChangedEvt(skeletonId, skeleton)                                                 =>
        Some(skeleton)
      case NodeCreatedEvt(skeletonId, treeId, node)                                                     =>
        state.map(_.withNewNodeInTree(treeId, node))
      case NodeDeletedEvt(skeletonId, treeId, node)                                                     =>
        state.map(_.withoutNodeInTree(treeId, node))
      case NodePropertiesUpdatedEvt(skeletonId, treeId, node)                                           =>
        state.map(_.withUpdatedNode(treeId, node))
      case EdgeCreatedEvt(skeletonId, treeId, edge)                                                     =>
        state.map(_.withNewEdgeInTree(treeId, edge))
      case EdgeDeletedEvt(skeletonId, treeId, edge)                                                     =>
        state.map(_.withoutEdgeInTree(treeId, edge))
      case TreeCreatedEvt(skeletonId, tree)                                                             =>
        state.map(s => s.copy(trees = tree :: s.trees))
      case TreePropertiesUpdatedEvt(skeletonId, treeId, updatedId, color, name, branchPoints, comments) =>
        state.map(_.withUpdatedTreeProperties(treeId, updatedId, color, name, branchPoints, comments))
      case TreeMergedEvt(skeletonId, sourceTreeId, targetTreeId)                                        =>
        state.map(_.withMergedTrees(sourceTreeId, targetTreeId))
      case TreeComponentMovedEvt(skeletonId, sourceTreeId, targetTreeId, nodeIds)                       =>
        state.map(_.withMovedTreeComponent(sourceTreeId, targetTreeId, nodeIds.toSet))
      case TreeDeletedEvt(skeletonId, treeId)                                                           =>
        state.map(_.withoutTree(treeId))
      case ActiveNodeUpdatedEvt(skeletonId, activeNode)                                                 =>
        state.map(_.copy(activeNodeId = activeNode))
      case EditViewUpdatedEvt(skeletonId, editPosition, editRotation, zoomLevel)                        =>
        state.map(_.copy(editPosition = editPosition, editRotation = editRotation, zoomLevel = zoomLevel))
      case ArchivedTracingEvt(skeletonId)                                                               =>
        state.map(_.copy(isArchived = true))
      case UpdatedAnnotationSettingsEvt(skeletonId, settings)                                           =>
        state.map(_.copy(settings = settings))
      case UpdatedBoundingBoxEvt(skeletonId, boundingBox)                                               =>
        state.map(_.copy(boundingBox = boundingBox))
      case UpdatedDataSetEvt(skeletonId, dataSetName)                                                   =>
        state.map(_.copy(dataSetName = dataSetName))
      case e                                                                                            =>
        e.logError("Unknown Message: " + _.toString)
        state
    }
  }

  private def initSkeleton(id: String, init: SkeletonTracingInit) = {
    val trees =
      if (init.insertStartAsNode) {
        val node = Node(1, init.start, init.rotation)
        val branchPoints = if (init.isFirstBranchPoint) List(BranchPoint(node.id, System.currentTimeMillis)) else Nil
        List(Tree.createFrom(node).copy(branchPoints = branchPoints))
      } else
          Nil

    val box: Option[BoundingBox] = init.boundingBox.flatMap { box =>
      if (box.isEmpty)
        None
      else
        Some(box)
    }

    SkeletonTracing(
      init.dataSetName,
      timestamp = System.currentTimeMillis,
      activeNodeId = if (init.insertStartAsNode) Some(1) else None,
      editPosition = init.start,
      editRotation = init.rotation,
      zoomLevel = SkeletonTracing.defaultZoomLevel,
      boundingBox = box,
      settings = init.settings,
      trees = trees,
      isArchived = false,
      id = id)
  }

  override def receiveCommand: Receive = initialBehaviour

  def initialBehaviour = passivate(uninitialized.orElse(queries)).orElse(defaultCommands)

  def uninitialized: Receive = LoggingReceive.withLabel("unintialzed") {

    case InitSkeletonCmd(id, initParams) =>
      val skeleton = initSkeleton(id, initParams)
      state = Some(skeleton)
      persist(WholeTracingChangedEvt(id, skeleton)) { event =>
        updateStateAndNotifySender(id, event)
      }
      updateBehaviour(traceableSkeleton)
    case SetSkeletonCmd(id, skeleton)    =>
      persist(WholeTracingChangedEvt(id, skeleton)) { event =>
        updateStateAndNotifySender(id, event)
      }
      updateBehaviour(traceableSkeleton)
  }

  def updateStateAndNotifySender(skeletonId: String, evt: SkeletonEvt) = {
    state = updateState(evt, state)
    sender() ! ValidUpdateAck(skeletonId, state)
  }

  def handleCmd(cmd: SkeletonCmd, isValid: => Boolean, eventBuilder: => List[SkeletonEvt], errorMsgIfInvalid: String) = {
    if (!isValid)
      sender() ! InvalidUpdateAck(cmd.skeletonId, errorMsgIfInvalid)
    else {
      persistAll(eventBuilder) { evt =>
        updateStateAndNotifySender(cmd.skeletonId, evt)
      }
    }

  }

  def traceableSkeleton: Receive = LoggingReceive.withLabel("traceable") {
    case cmd: CreateNodeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(!_.tree(cmd.treeId).exists(_.containsNode(cmd.node.id))),
        eventBuilder = NodeCreatedEvt(cmd.skeletonId, cmd.treeId, cmd.node) :: Nil,
        errorMsgIfInvalid = "Node already exists ID:" + cmd.node.id
      )

    case cmd: DeleteNodeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).exists(_.containsNode(cmd.node))),
        eventBuilder = NodeDeletedEvt(cmd.skeletonId, cmd.treeId, cmd.node) :: Nil,
        errorMsgIfInvalid = "Node that should be deleted doesn't exist. ID:" + cmd.node
      )

    case cmd: CreateEdgeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(!_.tree(cmd.treeId).exists(_.containsEdge(cmd.edge))),
        eventBuilder = EdgeCreatedEvt(cmd.skeletonId, cmd.treeId, cmd.edge) :: Nil,
        errorMsgIfInvalid = "Edge already exists " + cmd.edge
      )

    case cmd: DeleteEdgeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).exists(_.containsEdge(cmd.edge))),
        eventBuilder = EdgeDeletedEvt(cmd.skeletonId, cmd.treeId, cmd.edge) :: Nil,
        errorMsgIfInvalid = "Edge that should be deleted doesn't exists " + cmd.edge
      )

    case cmd: SetSkeletonCmd =>
      handleCmd(
        cmd,
        isValid = true,
        eventBuilder = WholeTracingChangedEvt(cmd.skeletonId, cmd.skeleton) :: Nil,
        errorMsgIfInvalid = ""
      )

    case cmd: UpdateNodePropertiesCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).exists(_.containsNode(cmd.node.id))),
        eventBuilder = NodePropertiesUpdatedEvt(cmd.skeletonId, cmd.treeId, cmd.node) :: Nil,
        errorMsgIfInvalid = "Node that should be updated doesn't exist. ID:" + cmd.node
      )

    case cmd: UpdateMetadataCmd =>
      val events = state.map { tracing =>
        EditViewUpdatedEvt(
          cmd.skeletonId,
          cmd.editPosition getOrElse tracing.editPosition,
          cmd.editRotation getOrElse tracing.editRotation,
          cmd.zoomLevel getOrElse tracing.zoomLevel) ::
          (if (tracing.activeNodeId != cmd.activeNode) List(ActiveNodeUpdatedEvt(cmd.skeletonId, cmd.activeNode)) else Nil)
      } getOrElse Nil

      handleCmd(
        cmd,
        isValid = state.isDefined,
        eventBuilder = events,
        errorMsgIfInvalid = s"Couldn't update metadata of non existing tracing. ID: ${cmd.skeletonId}"
      )

    case cmd: CreateTreeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.tree.treeId).isEmpty),
        eventBuilder = TreeCreatedEvt(cmd.skeletonId, cmd.tree) :: Nil,
        errorMsgIfInvalid = "Tree already exists. ID:" + cmd.tree.treeId
      )

    case cmd: UpdateTreePropertiesCmd =>
      val event = state.flatMap(_.tree(cmd.treeId)).map { tree =>
        TreePropertiesUpdatedEvt(cmd.skeletonId, tree.treeId, cmd.updatedId getOrElse tree.treeId, cmd.color, cmd.name, cmd.branchPoints, cmd.comments)
      }

      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).isDefined),
        eventBuilder = event.toList,
        errorMsgIfInvalid = "Tree that should be updated doesn't exists. ID:" + cmd.treeId
      )

    case cmd: MergeTreesCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(s => s.tree(cmd.sourceTreeId).isDefined && s.tree(cmd.targetTreeId).isDefined),
        eventBuilder = TreeMergedEvt(cmd.skeletonId, cmd.sourceTreeId, cmd.targetTreeId) :: Nil,
        errorMsgIfInvalid = s"Can't merge trees. Either source or target don't exist. S: ${cmd.sourceTreeId} T: ${cmd.targetTreeId}"
      )

    case cmd: MoveTreeComponentCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(s => s.tree(cmd.sourceTreeId).isDefined && s.tree(cmd.targetTreeId).isDefined),
        eventBuilder = TreeComponentMovedEvt(cmd.skeletonId, cmd.sourceTreeId, cmd.targetTreeId, cmd.nodeIds) :: Nil,
        errorMsgIfInvalid = s"Can't move tree component. Either source or target don't exist. S: ${cmd.sourceTreeId} T: ${cmd.targetTreeId}"
      )

    case cmd: DeleteTreeCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(s => s.tree(cmd.treeId).isDefined),
        eventBuilder = TreeDeletedEvt(cmd.skeletonId, cmd.treeId) :: Nil,
        errorMsgIfInvalid = s"Tree that should be deleted doesn't exist. ID: ${cmd.treeId}"
      )

    case cmd: ResetSkeletonCmd =>
      val events = state.map { tracing =>
        val treeDeletions = tracing.trees.map(t => TreeDeletedEvt(cmd.skeletonId, t.treeId))

        treeDeletions
      } getOrElse Nil

      handleCmd(
        cmd,
        isValid = state.isDefined,
        eventBuilder = events,
        errorMsgIfInvalid = s"Couldn't reset non existing tracing. ID: ${cmd.skeletonId}"
      )

    case cmd: ArchiveCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(!_.isArchived),
        eventBuilder = ArchivedTracingEvt(cmd.skeletonId) :: Nil,
        errorMsgIfInvalid = s"Couldn't archive already archived tracing. ID: ${cmd.skeletonId}"
      )
      updateBehaviour(archivedSkeleton)

    case cmd: UpdateSettingsCmd =>
      val events = List(
        cmd.settings.map(s => UpdatedAnnotationSettingsEvt(cmd.skeletonId, s)),
        cmd.dataSetName.map(dsn => UpdatedDataSetEvt(cmd.skeletonId, dsn)),
        if (cmd.boundingBox != state.flatMap(_.boundingBox)) Some(UpdatedBoundingBoxEvt(cmd.skeletonId, cmd.boundingBox)) else None
      ).flatten

      handleCmd(
        cmd,
        isValid = state.isDefined,
        eventBuilder = events,
        errorMsgIfInvalid = s"Couldn't update settings of non existing tracing. ID: ${cmd.skeletonId}"
      )
  }

  def archivedSkeleton: Receive = LoggingReceive.withLabel("archived") {
    case t: Int => ???
  }

  /**
    * Once recovery is complete, check the state to become the appropriate behaviour
    */
  def postRecoveryBecome(auctionRecoverStateMaybe: Option[SkeletonTracing]): Unit =
    auctionRecoverStateMaybe.fold[Unit]({}) { auctionState =>
      log.info("postRecoveryBecome")
      state = Some(auctionState)
      if (auctionState.isArchived) {
        updateBehaviour(archivedSkeleton)
      } else {
        updateBehaviour(traceableSkeleton)
      }
    }

  def updateBehaviour(behaviour: => Receive): Unit = {
    Logger.debug("Switching behaviour of " + self.path.toString)
    context.become(passivate(behaviour.orElse(queries)).orElse(defaultCommands))
  }

  def queries: Receive = LoggingReceive.withLabel("queries") {
    case GetSkeletonQuery(skeletonId) =>
      sender() ! SkeletonResponse(skeletonId, state)
  }

  def defaultCommands: Receive = LoggingReceive.withLabel("default") {
    case other => {
      Logger.warn("unknownCommand:  " + other.toString + " ID: " + self.path.toString)
      sender() ! InvalidCmdAck("", "InvalidSkeletonAck")
    }
  }

  /** Used only for recovery */
  private var skeletonRecoverStateMaybe: Option[SkeletonTracing] = None

  def receiveRecover: Receive = LoggingReceive {
    case WholeTracingChangedEvt(id, skeleton) =>
      skeletonRecoverStateMaybe =
        Some(skeleton)

    case evt: SkeletonEvt => {
      skeletonRecoverStateMaybe =
        updateState(evt.logInfo("receiveRecover evt:" + _.toString), skeletonRecoverStateMaybe)
    }

    case RecoveryCompleted => postRecoveryBecome(skeletonRecoverStateMaybe)

    // if snapshots are implemented, currently the aren't.
    case SnapshotOffer(_, snapshot: SkeletonTracing) =>
      skeletonRecoverStateMaybe = Some(snapshot)
  }
}
