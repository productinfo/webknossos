package models.knowledge

import play.api.libs.json._
import play.api.libs.functional.syntax._
import reactivemongo.bson.BSONObjectID
import models.knowledge.basics.BasicReactiveDAO
import play.modules.reactivemongo.json.BSONFormats._
import reactivemongo.core.commands.Count
import braingames.reactivemongo.DBAccessContext
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

case class MissionInfo(_id: BSONObjectID, key: String, possibleEnds: List[EndSegment]) {
  def id = _id.toString
}

case class RenderedStack(
  _level: LevelId,
  mission: MissionInfo,
  downloadUrls: List[String],
  _id: BSONObjectID = BSONObjectID.generate) {

  lazy val id = _id.stringify

}

object RenderedStackDAO extends BasicReactiveDAO[RenderedStack] {
  val collectionName = "renderedStacks"

  import LevelDAO.levelIdFormat

  implicit val missionInfoFormat: Format[MissionInfo] = Json.format[MissionInfo]
  implicit val formatter: OFormat[RenderedStack] = Json.format[RenderedStack]

  def findFor(levelId: LevelId)(implicit ctx: DBAccessContext) = {
    collectionFind(Json.obj(
      "_level.name" -> levelId.name,
      "_level.version" -> levelId.version)).cursor[RenderedStack].toList
  }

  def countFor(levelName: String)(implicit ctx: DBAccessContext) = {
    count(Json.obj("_level.name" -> levelName))
  }

  def countAll(levels: List[Level])(implicit ctx: DBAccessContext) = {
    Future.traverse(levels)(l => countFor(l.levelId.name).map(l.levelId.name -> _)).map(_.toMap)
  }

  def remove(levelId: LevelId, missionOId: String)(implicit ctx: DBAccessContext) {
    BSONObjectID.parse(missionOId).map {
      id =>
        collectionRemove(Json.obj(
          "_level.name" -> levelId.name,
          "_level.version" -> levelId.version,
          "mission._id" -> id))
    }
  }

  def removeAllOfMission(missionOId: String)(implicit ctx: DBAccessContext) = {
    BSONObjectID.parse(missionOId).map {
      id =>
      collectionRemove(Json.obj("mission._id" -> id))
    }
  }

  def removeAllOf(levelId: LevelId)(implicit ctx: DBAccessContext) = {
    collectionRemove(Json.obj(
      "_level.name" -> levelId.name,
      "_level.version" -> levelId.version))
  }

  def updateOrCreate(r: RenderedStack)(implicit ctx: DBAccessContext) = {
    val json = Json.toJson(r).transform(removeId).get
    collectionUpdate(Json.obj(
      "_level.name" -> r._level.name,
      "_level.version" -> r._level.version,
      "mission.key" -> r.mission.key), Json.obj("$set" -> json))
  }
}