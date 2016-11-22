/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import java.util.UUID

import scala.concurrent.duration._

import akka.actor.{PoisonPill, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.LoggingReceive
import akka.persistence._
import com.scalableminds.util.geometry.BoundingBox
import com.scalableminds.util.reactivemongo.GlobalAccessContext
import com.typesafe.scalalogging.LazyLogging
import models.tracing.skeleton.{SkeletonTracing, SkeletonTracingStatisticsDAO}
import oxalis.actor.ALogging
import oxalis.nml._

object SkeletonTracingProcessor extends LazyLogging {

  def props: Props = Props(new SkeletonTracingProcessor)

  val idExtractor: ShardRegion.ExtractEntityId =
    new PartialFunction[ShardRegion.Msg, (ShardRegion.EntityId, ShardRegion.Msg)] {
      override def isDefinedAt(x: ShardRegion.Msg): Boolean = x match {
        case m: SkeletonMsg => true
        case m              => logger.error(s"Shard '$shardName' received invalid msg type: " + m); false
      }

      override def apply(v1: ShardRegion.Msg) = v1 match {
        case m: SkeletonMsg => (m.skeletonId, m)
      }
    }

  val numberOfShards = 10

  val maxCmdsBetweenSnapshots = 500

  val shardResolver: ShardRegion.ExtractShardId = {
    case m: SkeletonMsg => (math.abs(m.skeletonId.hashCode) % numberOfShards).toString
  }

  val shardName: String = "SkeletonTracingProcessor"

  def inactivityTimeout = 1.minute
}

class SkeletonTracingProcessor extends PersistentActor with ALogging with LazyLogging {

  logger.info(s"Started skeleton processor for [${self.path.name}]. LSN: " + lastSequenceNr)

  var state: Option[SkeletonTracing] = None

  var shouldSaveEvents = true

  var stateChangedSinceLastSnapshot = false

  /** passivate the entity when no activity for 1 minute */
  context.setReceiveTimeout(SkeletonTracingProcessor.inactivityTimeout)

  override def persistenceId: String = "skeleton-" + self.path.name

  /**
    * Updates skeleton state
    */
  private def handleEvents(evt: SkeletonEvt, state: Option[SkeletonTracing]): Option[SkeletonTracing] = {
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

  private def skeletonFromParameters(id: String, init: SkeletonTracingInit) = {
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

  private def persistAndExecute(skeletonId: String, eventBuilder: List[SkeletonEvt]) = {
    if (shouldSaveEvents) {
      persistAll(eventBuilder) { evt =>
        updateState(skeletonId, evt)
      }
    } else {
      eventBuilder.foreach(evt => updateState(skeletonId, evt))
    }
  }

  private def initState(skeletonId: String, skeleton: SkeletonTracing) = {
    // Although the event handler will also set the skeleton when handling the WholeTracingChangedEvt, but we
    // need the tracing to be set BEFORE switching the behaviour. Since the persist call is async, this can not be
    // ensured otherwise.
    state = Some(skeleton)
    persistAndExecute(skeletonId, List(WholeTracingChangedEvt(skeletonId, skeleton)))
    updateBehaviour(traceableSkeleton)
    sender() ! ValidUpdateAck(skeletonId)
  }

  private def storeSnapshotIfChanged() = {
    if (stateChangedSinceLastSnapshot) {
      state.foreach { s =>
        saveSnapshot(s)
        // TODO: think about using the event stream for this. e.g publish a stats changed event that someone picks up
        SkeletonTracingStatisticsDAO.updateStats(s.stats)(GlobalAccessContext)
      }
      stateChangedSinceLastSnapshot = false
    }
  }

  private def updateState(skeletonId: String, evt: SkeletonEvt) = {
    state = handleEvents(evt, state)
    stateChangedSinceLastSnapshot = true

    if (shouldSaveEvents && lastSequenceNr % SkeletonTracingProcessor.maxCmdsBetweenSnapshots == 0) {
      storeSnapshotIfChanged()
    }
  }

  private def handleCmd(cmd: SkeletonCmd, isValid: => Boolean, eventBuilder: => List[SkeletonEvt], errorMsgIfInvalid: String) = {
    if (!isValid) {
      sender() ! InvalidUpdateAck(cmd.skeletonId, errorMsgIfInvalid)
    } else {
      persistAndExecute(cmd.skeletonId, eventBuilder)
      sender() ! ValidUpdateAck(cmd.skeletonId)
    }
  }

  //
  // Messages this actor is going to handle
  //

  override def receiveCommand: Receive = initialBehaviour

  def initialBehaviour = passivate(uninitialized.orElse(queries)).orElse(defaultCommands)

  protected def passivate(receive: Receive): Receive = receive.orElse {
    case ReceiveTimeout =>
      // tell parent actor to send us a poison pill
      logger.info(s" $self ReceiveTimeout: passivating. ")
      storeSnapshotIfChanged()
      context.parent ! Passivate(stopMessage = PoisonPill)

    case PoisonPill =>
      // stop
      context.stop(self)
  }

  def uninitialized: Receive = LoggingReceive.withLabel("unintialzed") {

    case InitSkeletonCmd(id, initParams) =>
      initState(id, skeletonFromParameters(id, initParams))

    case PresetSkeletonCmd(id, skeleton) =>
      initState(id, skeleton)
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

    case cmd: UpdateNodePropertiesCmd =>
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).exists(_.containsNode(cmd.node.id))),
        eventBuilder = NodePropertiesUpdatedEvt(cmd.skeletonId, cmd.treeId, cmd.node) :: Nil,
        errorMsgIfInvalid = "Node that should be updated doesn't exist. ID:" + cmd.node
      )

    case cmd: UpdateMetadataCmd =>
      val events = state.map { tracing =>
        val nodeUpdateEvt = if (tracing.activeNodeId != cmd.activeNode)
                              List(ActiveNodeUpdatedEvt(cmd.skeletonId, cmd.activeNode))
                            else
                              Nil

        val viewUpdateEvt = EditViewUpdatedEvt(
          cmd.skeletonId,
          cmd.editPosition getOrElse tracing.editPosition,
          cmd.editRotation getOrElse tracing.editRotation,
          cmd.zoomLevel getOrElse tracing.zoomLevel)

        viewUpdateEvt :: nodeUpdateEvt
      } getOrElse Nil

      handleCmd(
        cmd,
        isValid = state.isDefined,
        eventBuilder = events,
        errorMsgIfInvalid = s"Couldn't update metadata of non existing tracing. ID: ${cmd.skeletonId}"
      )

    case cmd: CreateTreeCmd =>
      logger.warn("CREATE TREE. TID: " + cmd.tree.id)
      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.tree.id).isEmpty),
        eventBuilder = TreeCreatedEvt(cmd.skeletonId, cmd.tree) :: Nil,
        errorMsgIfInvalid = "Tree already exists. ID:" + cmd.tree.id
      )

    case cmd: UpdateTreePropertiesCmd =>
      val event = state.flatMap(_.tree(cmd.treeId)).map { tree =>
        val updatedTreeId = cmd.updatedId getOrElse tree.id
        TreePropertiesUpdatedEvt(
          cmd.skeletonId, tree.id, updatedTreeId, cmd.color, cmd.name, cmd.branchPoints, cmd.comments)
      }

      handleCmd(
        cmd,
        isValid = state.exists(_.tree(cmd.treeId).isDefined),
        eventBuilder = event.toList,
        errorMsgIfInvalid = "Tree that should be updated doesn't exists. ID:" + cmd.treeId
      )

    case cmd: MergeTreesCmd =>
      logger.warn("MERGING TREES. last: " + lastSequenceNr)
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
        val treeDeletions = tracing.trees.map(t => TreeDeletedEvt(cmd.skeletonId, t.id))

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
        if (cmd.boundingBox != state.flatMap(_.boundingBox))
          Some(UpdatedBoundingBoxEvt(cmd.skeletonId, cmd.boundingBox))
        else
          None
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
  private def postRecoveryBecome(skeletonRecoverStateMaybe: Option[SkeletonTracing]): Unit =
    skeletonRecoverStateMaybe.foreach { skeletonState =>
      state = Some(skeletonState)
      if (skeletonState.isArchived) {
        updateBehaviour(archivedSkeleton)
      } else {
        updateBehaviour(traceableSkeleton)
      }
    }

  private def updateBehaviour(behaviour: => Receive): Unit = {
    logger.debug("Switching behaviour of " + self.path.toString)
    context.become(passivate(behaviour.orElse(queries)).orElse(defaultCommands))
  }

  def queries: Receive = LoggingReceive.withLabel("queries") {
    case GetSkeletonQuery(skeletonId) =>
      val retrievalKey = UUID.randomUUID().toString
      SkeletonTracingTempStore.pushEntry(retrievalKey, state)
      sender() ! SkeletonResponse(skeletonId, retrievalKey)
  }

  def defaultCommands: Receive = LoggingReceive.withLabel("default") {
    case cmd: StopEventPersistence =>
      logger.warn("Stopped event persistence. Id: " + cmd.skeletonId)
      shouldSaveEvents = false
      sender ! ValidUpdateAck(cmd.skeletonId)

    case cmd: ResumeEventPersistence =>
      logger.warn("Resumed event persistence. Id: " + cmd.skeletonId)
      shouldSaveEvents = true
      storeSnapshotIfChanged()
      sender ! ValidUpdateAck(cmd.skeletonId)

    case m: SaveSnapshotSuccess =>
      logger.debug(s"Successfully stored snapshot [${m.metadata.persistenceId}]")

    case m: SaveSnapshotFailure =>
      logger.error(s"Failed to store snapshot [${m.metadata.persistenceId}]. Cause: " + m.cause.getMessage, m.cause)

    case other =>
      logger.warn("unknownCommand:  " + other.toString + " ID: " + self.path.toString)
      sender() ! InvalidCmdAck("", "InvalidSkeletonAck")
  }

  /** Used only for recovery */
  private var skeletonRecoverStateMaybe: Option[SkeletonTracing] = None

  def receiveRecover: Receive = {
    case evt: SkeletonEvt =>
      logger.info(s"Replaying evt [${evt.skeletonId}]. LSN: " + lastSequenceNr)
      skeletonRecoverStateMaybe = handleEvents(evt, skeletonRecoverStateMaybe)

    case RecoveryCompleted =>
      logger.info("Recovery completed.")
      postRecoveryBecome(skeletonRecoverStateMaybe)

    // if snapshots are implemented, currently the aren't.
    case SnapshotOffer(_, snapshot: SkeletonTracing) =>
      logger.info("Snapshot offer: " + snapshot.id)
      skeletonRecoverStateMaybe = Some(snapshot)
  }

  override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit =
    event match {
      case Some(evt) ⇒
        logger.error(s"Exception in receiveRecover when replaying event " +
          s"type ${evt.getClass.getName} with sequence number [$lastSequenceNr] for " +
          "persistenceId [$persistenceId].", cause)
      case None      ⇒
        logger.error(s"Persistence failure when replaying events for persistenceId [$persistenceId]. " +
          s"Last known sequence number [$lastSequenceNr]")
    }
}
