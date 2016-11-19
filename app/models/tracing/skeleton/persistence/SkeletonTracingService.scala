/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.tracing.skeleton.persistence

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.scalableminds.util.reactivemongo.{DBAccessContext, GlobalAccessContext}
import models.annotation.{AnnotationContentService, AnnotationSettings, ContentReference}
import models.tracing.CommonTracingService
import oxalis.nml.{BranchPoint, Comment, NML, TreeLike}
import akka.pattern.ask
import scala.concurrent.duration._

import akka.util.Timeout
import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.json.JsonUtils
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.annotation.CompoundAnnotation._
import models.binary.DataSet
import models.task.Task
import models.tracing.skeleton.{DBSkeletonTracingService, JsonTracingUpdateParser, SkeletonTracing}
import models.user.{UsedAnnotationDAO, User}
import net.liftweb.common.{Box, Failure, Full}
import play.api.libs.concurrent.Akka
import play.api.{Logger, Play}
import reactivemongo.bson.{BSON, BSONObjectID}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.{JsError, JsSuccess, JsValue}
import play.api.Play.current
import play.api.i18n.Messages.Implicits._

object SkeletonTracingService extends AnnotationContentService with FoxImplicits{

  type AType = SkeletonTracing

  implicit val timeout = Timeout(5.seconds)

  var underlying: ActorRef = _

  def start(system: ActorSystem) = {
    underlying = ClusterSharding(system).start(
      typeName = SkeletonTracingProcessor.shardName,
      entityProps = SkeletonTracingProcessor.props,
      settings = ClusterShardingSettings(system),
      extractEntityId = SkeletonTracingProcessor.idExtractor,
      extractShardId = SkeletonTracingProcessor.shardResolver)
  }

  private def cmdResult2Fox(result: SkeletonAck): Box[Boolean] = {
    result match {
      case ValidUpdateAck(_) =>
        Full(true)
      case InvalidUpdateAck(_, msg) =>
        Failure(msg)
      case InvalidCmdAck(_, msg) =>
        Failure(msg)
    }
  }

  private def retrieveUnderlyingCmdResult(cmd: SkeletonCmd): Fox[Boolean] = {
    for{
      response <- (underlying ? cmd).mapTo[SkeletonAck].toFox
      result <- cmdResult2Fox(response)
    } yield result
  }

  private def retrieveUnderlyingCmdResult(cmds: List[SkeletonCmd]): Fox[Boolean] = {
    Fox.serialSequence(cmds)(c => retrieveUnderlyingCmdResult(c)).map(_.last).toFox
  }

  def clearAndRemove(tracingId: String)(implicit ctx: DBAccessContext) = {
    retrieveUnderlyingCmdResult(ResetSkeletonCmd(tracingId)).map(_ => ContentReference(SkeletonTracing.contentType, tracingId))
  }

  def createFrom(skeleton: SkeletonTracing): Fox[ContentReference] = {
    val initCommand = InitSkeletonCmd(skeleton.id, SkeletonTracingInit.from(skeleton))
    val (splitted, mapping) = skeleton.splitByNodes(maxNodeCount=1000)

    val treeCreateCommands = splitted.trees.map(t => CreateTreeCmd(skeleton.id, t))
    val mergeCommands = mapping.toList.map{case (target, source) => MergeTreesCmd(skeleton.id, source, target)}

    retrieveUnderlyingCmdResult(initCommand :: treeCreateCommands ::: mergeCommands)
    .map( _ => ContentReference(SkeletonTracing.contentType, skeleton.id))
  }

  def createFrom(skeleton: SkeletonTracingInit): Fox[ContentReference] = {
    val skeletonId = BSONObjectID.generate.stringify
    retrieveUnderlyingCmdResult(InitSkeletonCmd(skeletonId, skeleton))
      .map( _ => ContentReference(SkeletonTracing.contentType, skeletonId))
  }

  def createFrom(dataSet: DataSet)(implicit ctx: DBAccessContext): Fox[ContentReference] =
    createFrom(SkeletonTracingInit(dataSet.name, dataSet.defaultStart, dataSet.defaultRotation, None, insertStartAsNode = false, isFirstBranchPoint = false))

  def archiveById(_skeleton: BSONObjectID)(implicit ctx: DBAccessContext) =
    for {
      // _ <- UsedAnnotationDAO.removeAll(AnnotationIdentifier(typ, _skeleton.stringify))
      _ <- retrieveUnderlyingCmdResult(ArchiveCmd(_skeleton.stringify))
    } yield true

  def findOneById(tracingId: String)(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {
    Logger.error(s"Looking for $tracingId")
    (underlying ? GetSkeletonQuery(tracingId)).mapTo[SkeletonResponse].map{r => SkeletonTracingTempStore.popEntry(r.retrievalKey)}.toFox.orElse{
      Logger.warn("USING DB tracing fallback!")
      DBSkeletonTracingService.findOneById(tracingId).flatMap(s => createFrom(s).map(_ => s))
    }
  }

  def uniqueTreePrefix(tracing: SkeletonTracing, user: Option[User], task: Option[Task])(tree: TreeLike): String = {
    val userName = user.map(_.abreviatedName) getOrElse ""
    val taskName = task.map(_.id) getOrElse ""
    formatHash(taskName) + "_" + userName + "_" + f"tree${tree.id}%03d"
  }

  def renameTreesOfTracing(tracing: SkeletonTracing, user: Fox[User], task: Fox[Task])(implicit ctx: DBAccessContext): Fox[SkeletonTracing] = {
    for {
      t <- task.futureBox
      u <- user.futureBox
    } yield
      tracing.renameTrees(uniqueTreePrefix(tracing, u, t))
  }

  def updateFromJson(id: String, json: JsValue)(implicit ctx: DBAccessContext): Fox[Boolean] = {
    json.validate(JsonTracingUpdateParser.parseUpdateArray(id)) match {
      case JsSuccess(updates, _) =>
        Fox.combined(updates.map { updateCmd =>
          retrieveUnderlyingCmdResult(updateCmd)
        }).flatMap(_.headOption) //.flatMap(_ => findOneById(id))
      case e: JsError =>
        Logger.warn("Failed to parse all update commands from json. " + JsonUtils.jsError2HumanReadable(e))
        Fox.failure(JsonUtils.jsError2HumanReadable(e))
    }
  }


  def resetComments(_tracing: String)(implicit ctx: DBAccessContext) = ???
//    update(Json.obj("_id" -> _tracing), Json.obj("$set" -> Json.obj("comments" -> Json.arr())))

  def resetBranchPoints(_tracing: String)(implicit ctx: DBAccessContext) = ???
//    update(Json.obj("_id" -> _tracing), Json.obj("$set" -> Json.obj("branchPoints" -> Json.arr())))

  def addBranchPoint(_tracing: String, bp: BranchPoint)(implicit ctx: DBAccessContext) = ???
//    findAndModify(
//      Json.obj("_id" -> _tracing),
//      Json.obj("$set" -> Json.obj(
//        "branchPoints.-1" -> bp)),
//      returnNew = true)

  def addComment(_tracing: String, comment: Comment)(implicit ctx: DBAccessContext) = ???
//    findAndModify(
//      Json.obj("_id" -> _tracing),
//      Json.obj("$set" -> Json.obj(
//        "comments.-1" -> comment)),
//      returnNew = true)
  override def updateSettings(dataSetName: String, boundingBox: Option[BoundingBox], settings: AnnotationSettings, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean] = {
  retrieveUnderlyingCmdResult(UpdateSettingsCmd(tracingId, dataSetName = Some(dataSetName), boundingBox = boundingBox, settings = Some(settings))).map(_ => true)
}

  override def updateSettings(settings: AnnotationSettings, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean] = {
    retrieveUnderlyingCmdResult(UpdateSettingsCmd(tracingId, settings = Some(settings))).map(_ => true)
  }

  override def updateEditPosRot(editPosition: Point3D, editRotation: Vector3D, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean] = {
    retrieveUnderlyingCmdResult(UpdateMetadataCmd(tracingId,
      editPosition = Some(editPosition),
      editRotation = Some(editRotation))).map(_ => true)
  }
}
