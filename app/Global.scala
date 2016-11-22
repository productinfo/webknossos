import scala.concurrent.Future

import akka.actor.{PoisonPill, Props}
import akka.routing.RoundRobinPool
import akka.actor.{ActorSystem, Props}
import com.newrelic.api.agent.NewRelic
import com.scalableminds.util.reactivemongo.{GlobalAccessContext, GlobalDBAccess}
import com.scalableminds.util.security.SCrypt
import models.binary.{DataStore, DataStoreDAO, WebKnossosStore}
import models.team._
import net.liftweb.common.{Box, Full}
import oxalis.jobs.AvailableTasksJob
import play.api._
import play.api.libs.concurrent._
import models.user._
import models.task._
import com.scalableminds.util.mail.Mailer
import play.api.libs.concurrent.Execution.Implicits._
import com.typesafe.config.{Config, ConfigFactory}
import models.tracing.skeleton.persistence.SkeletonTracingService
import com.typesafe.config.Config
import oxalis.mturk.MTurkNotificationReceiver
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.duration._

import models.annotation.AnnotationDAO
import models.tracing.skeleton.{DBSkeletonTracing, DBSkeletonTracingDAO, DBSkeletonTracingService}
import play.api.libs.iteratee.Iteratee

object Global extends GlobalSettings {
  var clusters: List[ActorSystem] = Nil

  override def onStart(app: Application) {
    val conf = app.configuration

    Logger.info("Executing Global START")
    startActors(conf.underlying, app)

    if (conf.getBoolean("application.insertInitialData") getOrElse false) {
      InitialData.insert()
    }

    Akka.system(app).scheduler.scheduleOnce(10 seconds)(automaticSkeletonActorCreator())

    super.onStart(app)
  }

  def startActors(conf: Config, app: Application) {

    Akka.system(app).actorOf(
      Props(new Mailer(conf)),
      name = "mailActor")

    // We need to delay the start of the notification handle, since the database needs to be available first
    MTurkNotificationReceiver.startDelayed(app, 2.seconds)

    if (conf.getBoolean("workload.active")) {
      Akka.system(app).actorOf(
        Props(new AvailableTasksJob()),
        name = "availableTasksMailActor"
      )
    }

    clusters = List("2561") map { port =>
      // Override the configuration of the port
      val clusterConfig = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
                   withFallback(conf)

      // Create an Akka system
      val sys = ActorSystem("webknossos", clusterConfig)
      // Create an actor that handles cluster domain events
      SkeletonTracingService.start(sys)
      sys
    }
  }

  def automaticSkeletonActorCreator() = {
    Logger.info("Started skeleton actor creation.")
    val dbSkeletons = DBSkeletonTracingService
    .findAllAsStream()

    val actorCreator = Iteratee.foldM[DBSkeletonTracing, Box[Boolean]](Full(true)){
      case (state, skeleton) =>
        Logger.info(s"Creating actor for: ${skeleton._id.stringify}...")
        SkeletonTracingService.findOneById(skeleton._id.stringify)(GlobalAccessContext).futureBox.map(b => state.map(_ && b.isDefined))
    }

    val r = dbSkeletons.run(actorCreator)
    r.map(b => Logger.info("COMPLETED CREATION OF SKELETON ACTORS: " + b))
  }

  override def onError(request: RequestHeader, ex: Throwable) = {
    NewRelic.noticeError(ex)
    super.onError(request, ex)
  }

  override def onStop(app: Application): Unit = {
    clusters.foreach(_.terminate())
  }
}

/**
 * Initial set of data to be imported
 * in the sample application.
 */
object InitialData extends GlobalDBAccess {

  val mpi = Team("Connectomics department", None, RoleService.roles)

  def insert() = {
    insertUsers()
    insertTeams()
    insertTasks()
    insertLocalDataStore()
  }

  def insertUsers() = {
    UserDAO.findOneByEmail("scmboy@scalableminds.com").futureBox.map {
      case Full(_) =>
      case _ =>
        Logger.info("Inserted default user scmboy")
        UserDAO.insert(User(
          "scmboy@scalableminds.com",
          "SCM",
          "Boy",
          true,
          SCrypt.hashPassword("secret"),
          SCrypt.md5("secret"),
          List(TeamMembership(mpi.name, Role.Admin)))
        )
    }
  }

  def insertTeams() = {
    TeamDAO.findOne().futureBox.map {
      case Full(_) =>
      case _ =>
        TeamDAO.insert(mpi)
    }
  }

  def insertTasks() = {
    TaskTypeDAO.findAll.map {
      types =>
        if (types.isEmpty) {
          val taskType = TaskType(
            "ek_0563_BipolarCells",
            "Check those cells out!",
            TraceLimit(5, 10, 15),
            mpi.name)
          TaskTypeDAO.insert(taskType)
        }
    }
  }

  def insertLocalDataStore() = {
    DataStoreDAO.findOne(Json.obj("name" -> "localhost")).futureBox.map { maybeStore =>
      if (maybeStore.isEmpty) {
        DataStoreDAO.insert(DataStore("localhost", "something-secure", WebKnossosStore))
      }
    }
  }

}
