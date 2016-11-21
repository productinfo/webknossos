package models.annotation

import com.scalableminds.util.geometry.{BoundingBox, Point3D, Vector3D}
import play.api.Logger
import models.tracing.skeleton.SkeletonTracing
import models.tracing.volume.{VolumeTracing, VolumeTracingService}
import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits._
import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.Fox
import models.binary.DataSet
import models.tracing.skeleton.persistence.SkeletonTracingService
import play.api.libs.json.JsValue

/**
 * Company: scalableminds
 * User: tmbo
 * Date: 02.06.13
 * Time: 02:49
 */
trait AnnotationContentService {
  type AType <: AnnotationContent

  def updateSettings(dataSetName: String, boundingBox: Option[BoundingBox], settings: AnnotationSettings, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean]

  def updateSettings(settings: AnnotationSettings, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean]

  def updateFromJson(id: String, jsUpdate: JsValue)(implicit ctx: DBAccessContext): Fox[Boolean]

  def updateEditPosRot(editPosition: Point3D, editRotation: Vector3D, tracingId: String)(implicit ctx: DBAccessContext): Fox[Boolean]

  def findOneById(id: String)(implicit ctx: DBAccessContext): Fox[AType]

  def createFrom(dataSet: DataSet)(implicit ctx: DBAccessContext): Fox[ContentReference]

  def clearAndRemove(id: String)(implicit ctx: DBAccessContext): Fox[ContentReference]

  def downloadFileExtension: String
}

trait AnnotationContentProviders {

  val contentProviders: Map[String, AnnotationContentService] = Map(
    SkeletonTracing.contentType -> SkeletonTracingService,
    VolumeTracing.contentType -> VolumeTracingService
  )

  val providerList = contentProviders.keys

  def withProviderForContentType[T](contentType: String)(f: AnnotationContentService => Fox[T]): Fox[T] = {
    contentProviders.get(contentType) match {
      case Some(p) =>
        f(p)
      case _ =>
        Logger.warn(s"Couldn't find content provider for $contentType")
        Fox.failure(s"Couldn't find content provider for $contentType")
    }
  }
}
