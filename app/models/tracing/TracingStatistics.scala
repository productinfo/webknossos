package models.tracing

import models.tracing.skeleton.{SkeletonTracing, SkeletonTracingStatistics, SkeletonTracingStatisticsDAO}
import models.tracing.volume.VolumeTracingStatistics
import models.annotation.{AnnotationContent, AnnotationLike}
import play.api.Logger
import scala.concurrent.Future

import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.tracing.skeleton.persistence.SkeletonTracingService
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._

trait TracingStatistics{
  def writeAsJson = {
    this match {
      case stats: SkeletonTracingStatistics =>
        Json.toJson(stats)
      case _                                =>
        JsNull
    }
  }
}

trait AnnotationStatistics extends FoxImplicits { this: AnnotationLike =>
  def statisticsForAnnotation()(implicit ctx: DBAccessContext): Fox[TracingStatistics] = {
    this.contentReference.contentType match {
      case SkeletonTracing.contentType =>
        SkeletonTracingStatisticsDAO.findOneBySkeleton(this.contentReference._id).orElse(this.content.map(_.stats))
      case _                      =>
        Logger.debug("No statistics available for content")
        // TODO: remove when volume statistics are implemented
        Future.successful(VolumeTracingStatistics())
    }
  }
}
