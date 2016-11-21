package models.annotation

import java.io.{BufferedOutputStream, FileOutputStream}

import scala.concurrent.Future

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import com.scalableminds.util.io.ZipIO
import com.scalableminds.util.mvc.BoxImplicits
import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits, TextUtils}
import models.annotation.AnnotationType._
import models.binary.{DataSet, DataSetDAO}
import models.task.Task
import models.tracing.skeleton.SkeletonTracing
import models.tracing.skeleton.persistence.{SkeletonTracingInit, SkeletonTracingService}
import models.tracing.volume.VolumeTracingService
import models.user.{UsedAnnotationDAO, User}
import net.liftweb.common.Full
import oxalis.nml.NML
import play.api.i18n.Messages
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.BSONFormats._

/**
  * Company: scalableminds
  * User: tmbo
  * Date: 07.11.13
  * Time: 12:39
  */

object AnnotationService extends AnnotationContentProviders with BoxImplicits with FoxImplicits with TextUtils {

  private def selectSuitableTeam(user: User, dataSet: DataSet): String = {
    val dataSetTeams = dataSet.owningTeam +: dataSet.allowedTeams
    dataSetTeams.intersect(user.teamNames).head
  }

  def createExplorationalFor(
    user: User,
    dataSet: DataSet,
    contentType: String,
    id: String = "")(implicit ctx: DBAccessContext) = {

    withProviderForContentType(contentType) { provider =>
      for {
        contentReference <- provider.createFrom(dataSet)
        annotation = Annotation(
          Some(user._id),
          contentReference,
          selectSuitableTeam(user, dataSet),
          dataSet.name,
          typ = AnnotationType.Explorational,
          state = AnnotationState.InProgress,
          _id = BSONObjectID.parse(id).getOrElse(BSONObjectID.generate)
        )
        _ <- AnnotationDAO.insert(annotation)
      } yield annotation
    }
  }

  def updateAllOfTask(
    task: Task,
    team: String,
    dataSetName: String,
    boundingBox: Option[BoundingBox],
    settings: AnnotationSettings)(implicit ctx: DBAccessContext) = {
    for {
      _ <- AnnotationDAO.updateTeamForAllOfTask(task, team)
      _ <- AnnotationDAO.updateAllOfTask(task, dataSetName, boundingBox, settings)
    } yield true
  }

  def finish(annotation: Annotation)(implicit ctx: DBAccessContext) = {
    // WARNING: needs to be repeatable, might be called multiple times for an annotation
    AnnotationDAO.finish(annotation._id).map { r =>
      annotation.muta.writeAnnotationToFile()
      UsedAnnotationDAO.removeAll(AnnotationIdentifier(annotation.typ, annotation.id))
      r
    }
  }

  def baseFor(task: Task)(implicit ctx: DBAccessContext) =
    AnnotationDAO.findByTaskIdAndType(task._id, AnnotationType.TracingBase).one[Annotation].toFox

  def annotationsFor(task: Task)(implicit ctx: DBAccessContext) =
    AnnotationDAO.findByTaskIdAndType(task._id, AnnotationType.Task).cursor[Annotation]().collect[List]()

  def countUnfinishedAnnotationsFor(task: Task)(implicit ctx: DBAccessContext) =
    AnnotationDAO.countUnfinishedByTaskIdAndType(task._id, AnnotationType.Task)

  def freeAnnotationsOfUser(user: User)(implicit ctx: DBAccessContext) = {
    for {
      annotations <- AnnotationDAO.findOpenAnnotationsFor(user._id, AnnotationType.Task)
      _ = annotations.map(annotation => annotation.muta.cancelTask())
      result <- AnnotationDAO.unassignAnnotationsOfUser(user._id)
    } yield result
  }

  def openTasksFor(user: User)(implicit ctx: DBAccessContext) =
    AnnotationDAO.findOpenAnnotationsFor(user._id, AnnotationType.Task)

  def countOpenTasks(user: User)(implicit ctx: DBAccessContext) =
    AnnotationDAO.countOpenAnnotations(user._id, AnnotationType.Task)

  def hasAnOpenTask(user: User)(implicit ctx: DBAccessContext) =
    AnnotationDAO.hasAnOpenAnnotation(user._id, AnnotationType.Task)

  def findTasksOf(user: User, isFinished: Option[Boolean], limit: Int)(implicit ctx: DBAccessContext) =
    AnnotationDAO.findFor(user._id, isFinished, AnnotationType.Task, limit)

  def findExploratoryOf(user: User, isFinished: Option[Boolean], limit: Int)(implicit ctx: DBAccessContext) =
    AnnotationDAO.findForWithTypeOtherThan(
      user._id, isFinished, AnnotationType.Task :: AnnotationType.SystemTracings, limit)

  def countTaskOf(user: User, _task: BSONObjectID)(implicit ctx: DBAccessContext) =
    AnnotationDAO.countByTaskIdAndUser(user._id, _task, AnnotationType.Task)

  def createAnnotationFor(user: User, task: Task)(implicit ctx: DBAccessContext): Fox[Annotation] = {
    def useAsTemplateAndInsert(annotation: Annotation) =
      annotation.copy(
        _user = Some(user._id),
        state = AnnotationState.InProgress,
        typ = AnnotationType.Task,
        created = System.currentTimeMillis).temporaryDuplicate(keepId = false).flatMap(_.saveToDB)

    for {
      annotationBase <- task.annotationBase ?~> "Failed to retrieve annotation base."
      result <- useAsTemplateAndInsert(annotationBase).toFox ?~> "Failed to use annotation base as template."
    } yield {
      result
    }
  }

  def createAnnotationBase(
    task: Task,
    userId: BSONObjectID,
    boundingBox: Option[BoundingBox],
    settings: AnnotationSettings,
    dataSetName: String,
    start: Point3D,
    rotation: Vector3D)(implicit ctx: DBAccessContext) = {

    val skeletonInit = SkeletonTracingInit(
      dataSetName, start, rotation, boundingBox, insertStartAsNode = true, isFirstBranchPoint = true, settings)

    for {
      content <- SkeletonTracingService.createFrom(skeletonInit) ?~> "Failed to create skeleton tracing."
      annotation = Annotation(
        Some(userId),
        content,
        task.team,
        dataSetName,
        typ = AnnotationType.TracingBase,
        _task = Some(task._id))
      _ <- AnnotationDAO.insert(annotation) ?~> "Failed to insert annotation."
    } yield content
  }

  def updateAnnotationBase(task: Task, start: Point3D, rotation: Vector3D)(implicit ctx: DBAccessContext) = {
    for {
      base <- task.annotationBase
      contentReference = base.contentReference
    } yield {
      contentReference.service.updateEditPosRot(start, rotation, contentReference._id)
    }
  }

  def createFrom(
    dataSetName: String,
    user: User,
    content: ContentReference,
    annotationType: AnnotationType,
    name: Option[String])(implicit messages: Messages, ctx: DBAccessContext) = {

    for {
      dataSet <- DataSetDAO.findOneBySourceName(dataSetName) ?~> Messages("dataSet.notFound", dataSetName)
      annotation = Annotation(
        Some(user._id),
        content,
        selectSuitableTeam(user, dataSet),
        dataSetName,
        _name = name,
        typ = annotationType)
      _ <- AnnotationDAO.insert(annotation)
    } yield {
      annotation
    }
  }

  def createFrom(temporary: TemporaryAnnotation, contentReference: ContentReference, id: BSONObjectID)(implicit ctx: DBAccessContext) = {
    val annotation = Annotation(
      temporary._user,
      contentReference,
      temporary.team,
      temporary.dataSetName,
      temporary._task,
      temporary.state,
      temporary.typ,
      temporary.version,
      temporary._name,
      temporary.created,
      id)

    saveToDB(annotation)
  }

  def merge(
    newId: BSONObjectID,
    readOnly: Boolean,
    _user: BSONObjectID,
    team: String,
    typ: AnnotationType,
    annotationsLike: AnnotationLike*)(implicit ctx: DBAccessContext): Fox[TemporaryAnnotation] = {

    val restrictions =
      if (readOnly)
        AnnotationRestrictions.readonlyAnnotation()
      else
        AnnotationRestrictions.updateableAnnotation()

    CompoundAnnotation.createFromAnnotations(
      newId.stringify, Some(_user), team, None, annotationsLike.toList, typ, AnnotationState.InProgress, restrictions, None)
  }

  def saveToDB(annotation: Annotation)(implicit ctx: DBAccessContext): Fox[Annotation] = {
    AnnotationDAO.update(
      Json.obj("_id" -> annotation._id),
      Json.obj(
        "$set" -> AnnotationDAO.formatWithoutId(annotation),
        "$setOnInsert" -> Json.obj("_id" -> annotation._id)
      ),
      upsert = true).map { _ =>
      annotation
    }
  }

  def createAnnotationBase(
    task: Task,
    userId: BSONObjectID,
    boundingBox: Option[BoundingBox],
    settings: AnnotationSettings,
    nml: NML)(implicit ctx: DBAccessContext) = {

    for {
      tracing <- SkeletonTracing.createFrom(List(nml), boundingBox, Some(settings))
      contentReference <- SkeletonTracingService.createFrom(tracing)
      annotation = Annotation(
        Some(userId),
        contentReference,
        task.team,
        tracing.dataSetName,
        typ = AnnotationType.TracingBase,
        _task = Some(task._id))
      result <- AnnotationDAO.insert(annotation)
    } yield result
  }

  def createAnnotationFrom(
    user: User,
    nmls: List[NML],
    additionalFiles: Map[String, TemporaryFile],
    typ: AnnotationType,
    name: Option[String])(implicit messages: Messages, ctx: DBAccessContext): Fox[Annotation] = {

    // TODO: until we implemented workspaces, we need to decide if this annotation is going to be a skeleton or a volume
    // annotation --> hence, this hacky way of making a decision
    if (nmls.exists(_.volumes.nonEmpty)) {
      // There is a NML with a volume reference --> volume annotation
      for {
        content <- VolumeTracingService.createFrom(
          nmls,
          additionalFiles,
          None,
          AnnotationSettings.volumeDefault)
        annotation <- AnnotationService.createFrom(
          content.dataSetName,
          user,
          ContentReference.createFor(content),
          typ,
          name)
      } yield annotation
    } else {
      // There is no NML with a volume reference --> skeleton annotation
      for {
        tracing <- SkeletonTracing.createFrom(nmls, None, Some(AnnotationSettings.skeletonDefault))
        contentReference <- SkeletonTracingService.createFrom(tracing)
        annotation <- AnnotationService.createFrom(tracing.dataSetName, user, contentReference, typ, name)
      } yield annotation
    }
  }

  def zipAnnotations(annotations: List[Annotation], zipFileName: String)(implicit ctx: DBAccessContext) = {
    val zipped = TemporaryFile("annotationZips", normalize(zipFileName))
    val zipper = ZipIO.startZip(new BufferedOutputStream(new FileOutputStream(zipped.file)))

    def annotationContent(annotations: List[Annotation]): Future[Boolean] = {
      annotations match {
        case head :: tail =>
          head.muta.loadAnnotationContent().futureBox.flatMap {
            case Full(fs) =>
              zipper.addFile(fs)
              annotationContent(tail)
            case _        =>
              annotationContent(tail)
          }
        case _            =>
          Future.successful(true)
      }
    }

    annotationContent(annotations).map { _ =>
      zipper.close()
      zipped
    }
  }
}
