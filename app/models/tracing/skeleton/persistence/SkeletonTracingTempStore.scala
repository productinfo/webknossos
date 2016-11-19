package models.tracing.skeleton.persistence

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

import com.scalableminds.util.tools.Fox
import com.typesafe.scalalogging.LazyLogging
import models.tracing.skeleton.SkeletonTracing
import oxalis.cleanup.CleanUpService
import play.api.libs.concurrent.Execution.Implicits._

/**
  * A temporary concurrent store to exchange skeleton tracing instances
  */
object SkeletonTracingTempStore extends LazyLogging {

  case class TempEntry(validTill: Long, skeletonTracing: Option[SkeletonTracing])

  private val entries = TrieMap.empty[String, TempEntry]

  val validDuration = 1 minute

  val cleanUpInterval = 1 hour

  CleanUpService.register("deletion of expired skeletons", cleanUpInterval) {
    SkeletonTracingTempStore.removeInvalid()
  }

  def popEntry(key: String) = {
    entries.remove(key) match {
      case Some(entry) =>
        entry.skeletonTracing
      case _           =>
        logger.warn("Tried to pop non existing skeleton from temp store! ID: " + key)
        None
    }
  }

  def pushEntry(key: String, skeletonTracing: Option[SkeletonTracing]) = {
    entries.put(key, TempEntry(System.currentTimeMillis + validDuration.toMillis, skeletonTracing))
  }

  def removeInvalid(): Fox[String] = {
    val current = System.currentTimeMillis()
    val sizeBefore = entries.size
    entries.retain { case (key, value) => value.validTill > current }
    Fox.successful(s"remove roughly ${sizeBefore - entries.size} entries")
  }
}
