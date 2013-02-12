package models.knowledge

import models.basics.DAOCaseClass
import models.basics.BasicDAO
import brainflight.tools.geometry.Point3D
import org.bson.types.ObjectId
import com.mongodb.casbah.commons.MongoDBObject
import play.api.libs.json._
import com.novus.salat._
import models.context._
import scala.util.Random

case class Mission(dataSetName: String, start: MissionStart, possibleEnds: List[PossibleEnd], _id: ObjectId = new ObjectId) extends DAOCaseClass[Mission] {
  val dao = Mission
}

object Mission extends BasicKnowledgeDAO[Mission]("missions") {

  def createWithoutDataSet(start: MissionStart, possibleEnds: List[PossibleEnd]) =
    Mission("", start, possibleEnds)

  def findByDataSetName(dataSetName: String) = Option(find(MongoDBObject("dataSetName" -> dataSetName)).toList)

  def findByStartId(dataSetName: String, startIds: List[Int]):List[Mission] = startIds.flatMap(id => findOneByStartId(dataSetName, id))
  def findOneByStartId(dataSetName: String, startId: Int): Option[Mission] = findOne(MongoDBObject("dataSetName" -> dataSetName, "start.startId" -> startId))

  def hasAlreadyBeenInserted(mission: Mission): Boolean = {
    (findOne(MongoDBObject(
      "dataSetName" -> mission.dataSetName,
      "start" -> grater[MissionStart].asDBObject(mission.start)))).isDefined
  }

  def findNotProduced(dataSetName: String, alreadyProducedStartIds: List[Int], n: Int = 1) = {
    for (missions <- findByDataSetName(dataSetName)) yield {
      missions.filter(m => !alreadyProducedStartIds.contains(m.start.startId)).take(n)
    }
  }

  def randomByDataSetName(dataSetName: String) = {
    for { missions <- findByDataSetName(dataSetName) } yield { missions(Random.nextInt(missions.size)) }
  }

  implicit object MissionReads extends Format[Mission] {
    val START = "start"
    val POSSIBLE_ENDS = "possibleEnds"

    def reads(js: JsValue) =
      JsSuccess(Mission.createWithoutDataSet((js \ START).as[MissionStart],
        (js \ POSSIBLE_ENDS).as[List[PossibleEnd]]))

    def writes(mission: Mission) = Json.obj(
      START -> mission.start,
      POSSIBLE_ENDS -> Json.toJson(mission.possibleEnds))
  }
}