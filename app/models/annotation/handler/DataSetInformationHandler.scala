package models.annotation.handler

import scala.concurrent.Future

import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.annotation._
import models.tracing.skeleton.SkeletonTracing
import models.annotation.{AnnotationRestrictions, AnnotationType, TemporaryAnnotation}
import models.binary.DataSetDAO
import models.user.User
import play.api.libs.concurrent.Execution.Implicits._
import reactivemongo.bson.BSONObjectID

/**
  * Company: scalableminds
  * User: tmbo
  * Date: 03.08.13
  * Time: 18:39
  */
object DataSetInformationHandler extends AnnotationInformationHandler with FoxImplicits {

  type AType = TemporaryAnnotation

  def dataSetRestrictions() =
    new AnnotationRestrictions {
      override def allowAccess(user: Option[User]) = true

      override def allowDownload(user: Option[User]) = false
    }

  def provideAnnotation(dataSetName: String, user: Option[User])(implicit ctx: DBAccessContext): Fox[TemporaryAnnotation] = {
    for {
      dataSet <- DataSetDAO.findOneBySourceName(dataSetName) ?~> "dataSet.notFound"
      team = user.flatMap(_.teamNames.intersect(dataSet.allowedTeams).headOption).getOrElse("")
    } yield {
      val content = SkeletonTracing(
        dataSetName,
        System.currentTimeMillis(),
        Some(0),
        dataSet.defaultStart,
        dataSet.defaultRotation,
        SkeletonTracing.defaultZoomLevel,
        None,
        isArchived = false,
        trees = List.empty,
        id = dataSetName + "_" + BSONObjectID.generate.stringify)

      TemporaryAnnotation(
        dataSetName,
        user.map(_._id),
        () => Future.successful(Some(content)),
        ContentReference.createFor(content),
        None,
        team,
        dataSetName,
        None,
        typ = AnnotationType.View,
        restrictions = dataSetRestrictions())
    }
  }
}
