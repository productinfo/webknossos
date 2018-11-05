package controllers

import com.scalableminds.util.accesscontext.DBAccessContext
import com.scalableminds.util.tools.FoxImplicits
import javax.inject.Inject
import com.typesafe.config.ConfigRenderOptions
import models.analytics.{AnalyticsDAO, AnalyticsEntry}
import models.binary.DataStoreRpcClient
import oxalis.security.WkEnv
import com.mohiva.play.silhouette.api.Silhouette
import play.api.libs.json.Json
import utils.{ObjectId, SQLClient, SimpleSQLDAO, WkConf}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

class Application @Inject()(analyticsDAO: AnalyticsDAO,
                            releaseInformationDAO: ReleaseInformationDAO,
                            conf: WkConf,
                            sil: Silhouette[WkEnv])
                           (implicit ec: ExecutionContext)
  extends Controller {

  def buildInfo = sil.UserAwareAction.async { implicit request =>
    val token = request.identity.flatMap { user =>
      if (user.isSuperUser) Some(DataStoreRpcClient.webKnossosToken) else None
    }
    for {
      schemaVersion <- releaseInformationDAO.getSchemaVersion.futureBox
    } yield {
      Ok(Json.obj(
        "webknossos" -> webknossos.BuildInfo.toMap.mapValues(_.toString),
        "webknossos-wrap" -> webknossoswrap.BuildInfo.toMap.mapValues(_.toString),
        "schemaVersion" -> schemaVersion.toOption,
        "token" -> token
      ))
    }
  }

  def analytics(namespace: String) = sil.UserAwareAction(parse.json(1024 * 1024)) { implicit request =>
    analyticsDAO.insertOne(
      AnalyticsEntry(
        ObjectId.generate,
        request.identity.map(_._id),
        namespace,
        request.body))
    Ok
  }

  def features = sil.UserAwareAction { implicit request =>
    Ok(conf.raw.underlying.getConfig("features").resolve.root.render(ConfigRenderOptions.concise()))
  }

}


class ReleaseInformationDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext) extends SimpleSQLDAO(sqlClient) with FoxImplicits {
  def getSchemaVersion(implicit ec: ExecutionContext) = {
    for {
      rList <- run(sql"select schemaVersion from webknossos.releaseInformation".as[Int])
      r <- rList.headOption.toFox
    } yield r
  }
}
