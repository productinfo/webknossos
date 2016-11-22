package models.tracing.skeleton

import models.tracing.TracingStatistics
import scala.concurrent.Future

import com.scalableminds.util.reactivemongo.AccessRestrictions.{AllowIf, DenyEveryone}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import play.api.libs.concurrent.Execution.Implicits._
import com.scalableminds.util.reactivemongo.{DBAccessContext, DefaultAccessDefinitions, GlobalAccessContext}
import models.basics.SecuredBaseDAO
import models.team.TeamDAO._
import models.user.User
import oxalis.nml.Tree
import play.api.libs.json.Json
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID

case class SkeletonTracingStatistics(
                                      _skeleton: String,
                                      numberOfNodes: Long,
                                      numberOfEdges: Long,
                                      numberOfTrees: Long) extends TracingStatistics with FoxImplicits

object SkeletonTracingStatistics {
  implicit val skeletonTracingStatisticsFormat = Json.format[SkeletonTracingStatistics]
}

object SkeletonTracingStatisticsDAO extends SecuredBaseDAO[SkeletonTracingStatistics] with FoxImplicits {
  val collectionName = "skeletonStats"

  implicit val formatter = SkeletonTracingStatistics.skeletonTracingStatisticsFormat

  underlying.indexesManager.ensure(Index(Seq("_skeleton" -> IndexType.Ascending)))

  def findOneBySkeleton(_skeleton: String)(implicit ctx: DBAccessContext) =
    findOne("_skeleton", _skeleton)

  def updateStats(skeletonTracingStatistics: SkeletonTracingStatistics)(implicit ctx: DBAccessContext) = withExceptionCatcher {
    update(Json.obj("_skeleton" -> skeletonTracingStatistics._skeleton),
      Json.obj("$set" -> Json.toJson(skeletonTracingStatistics)), upsert = true)
  }
}
