package controllers.levelcreator

import akka.pattern.ask
import akka.routing.RoundRobinRouter
import play.api.libs.concurrent._
import scala.concurrent.duration._
import scala.concurrent._
import ExecutionContext.Implicits.global
import akka.actor._
import akka.util.Timeout
import akka.pattern.AskTimeoutException
import play.api.Play.current
import play.api.libs.json._
import play.api.i18n.Messages
import play.api._
import braingames.levelcreator._
import views._
import models.knowledge._
import play.api.mvc.Action

object StackController extends LevelCreatorController {

  val conf = Play.current.configuration

  lazy val stackWorkDistributor = Akka.system.actorFor(s"user/${StackWorkDistributor.name}")

  def list(levelName: String) = Action {
    implicit request =>
      Async {
        LevelDAO.findByName(levelName).flatMap(l => Future.traverse(l) {
          level =>
            RenderedStackDAO.findFor(level.levelId).map {
              rendered =>
                level -> rendered
            }
        }).map {
          renderedMap =>
            Ok(html.levelcreator.stackList(renderedMap))
        }
      }
  }

  def listJson(levelId: String) = ActionWithValidLevel(levelId) {
    implicit request =>
      Async {
        for {
          rendered <- RenderedStackDAO.findFor(request.level.levelId)
        } yield {
          Ok(Json.toJson(rendered.map(_.mission.id)))
        }
      }
  }

  def delete(levelId: String, missionId: String) = ActionWithValidLevel(levelId) {
    implicit request =>
      val level = request.level
      RenderedStackDAO.remove(level.levelId, missionId)
      JsonOk(Messages("level.stack.removed"))
  }

  def deleteAll(levelId: String) = ActionWithValidLevel(levelId) {
    implicit request =>
      RenderedStackDAO.removeAllOf(request.level.levelId)
      JsonOk(Messages("level.stack.removedAll"))
  }

  def create(level: Level, missions: Seq[Mission]) = {
    stackWorkDistributor ! CreateStacks(missions.map(m => Stack(level, m)))
    JsonOk("Creation is in progress.")
  }

  def create(level: Level, num: Int) = {
    stackWorkDistributor ! CreateRandomStacks(level, num)
    JsonOk("Creation is in progress.")
  }

  def produce(levelId: String, count: Int) = ActionWithValidLevel(levelId) {
    implicit request =>
      create(request.level, count)
  }

  def produceAll(levelId: String) = ActionWithValidLevel(levelId) {
    implicit request =>
      Async {
        for {
          missions <- MissionDAO.findByDataSetName(request.level.dataSetName)
        } yield {
          create(request.level, missions)
        }
      }
  }

  def produceBulk(levelId: String) = ActionWithValidLevel(levelId, parse.urlFormEncoded) {
    implicit request =>
      Async {
        for {
          missionIds <- postParameterList("missionId") ?~> Messages("mission.startId.missing")
          missions <- Future.traverse(missionIds)(MissionDAO.findOneById)
        } yield {
          create(request.level, missions.flatten)
        }
      }
  }

}