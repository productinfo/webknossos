package models.task

import com.scalableminds.util.reactivemongo.AccessRestrictions.{AllowIf, DenyEveryone}
import com.scalableminds.util.reactivemongo.{DBAccessContext, DefaultAccessDefinitions}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.basics._
import models.project.Project
import models.user.{Experience, User}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsArray, JsObject, Json}
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.BSONFormats._

case class OpenAssignment(
  instances: Int,
  _task: BSONObjectID,
  team: String,
  _project: String,
  neededExperience: Experience = Experience.empty,
  priority: Int = 100,
  created: DateTime = DateTime.now(),
  _id: BSONObjectID = BSONObjectID.generate
  ) extends FoxImplicits {

  lazy val id = _id.stringify

  def task(implicit ctx: DBAccessContext) =
    TaskDAO.findOneById(_task)

  def hasEnoughExperience(user: User) = {
    neededExperience.isEmpty || user.experiences.get(neededExperience.domain).exists(_ >= neededExperience.value)
  }
}

object OpenAssignment extends FoxImplicits {
  implicit val openAssignmentFormat = Json.format[OpenAssignment]

  def from(task: Task, project: Project, instances: Int): OpenAssignment =
    OpenAssignment(instances, task._id, task.team, task._project, task.neededExperience,
      priority = if(project.paused) -1 else project.priority)
}

object OpenAssignmentDAO extends SecuredBaseDAO[OpenAssignment] with FoxImplicits {

  val collectionName = "openAssignments"

  val formatter = OpenAssignment.openAssignmentFormat

  underlying.indexesManager.ensure(Index(Seq("_task" -> IndexType.Ascending)))
  underlying.indexesManager.ensure(Index(Seq("_project" -> IndexType.Ascending)))
  underlying.indexesManager.ensure(Index(Seq("priority" -> IndexType.Descending)))
  underlying.indexesManager.ensure(Index(Seq("team" -> IndexType.Ascending, "neededExperience" -> IndexType.Ascending, "priority" -> IndexType.Descending)))

  override val AccessDefinitions = new DefaultAccessDefinitions {

    override def findQueryFilter(implicit ctx: DBAccessContext) = {
      ctx.data match {
        case Some(user: User) =>
          AllowIf(Json.obj("team" -> Json.obj("$in" -> user.teamNames)))
        case _ =>
          DenyEveryone()
      }
    }
  }

  private def byPriority =
    Json.obj("priority" -> -1)

  private def validPriorityQ =
    Json.obj("priority" -> Json.obj("$gte" -> 0))

  private def experiencesToQuery(user: User) =
    JsArray(user.experiences.map{ case (domain, value) => Json.obj("neededExperience.domain" -> domain, "neededExperience.value" -> Json.obj("$lte" -> value))}.toSeq)

  private def noRequiredExperience =
    Json.obj("neededExperience.domain" -> "", "neededExperience.value" -> 0)

  def findOrderedByPriority(user: User, teams: List[String])(implicit ctx: DBAccessContext): Enumerator[OpenAssignment] = {
    find(validPriorityQ ++ Json.obj(
        "team" -> Json.obj("$in" -> teams),
        "$or" -> (experiencesToQuery(user) :+ noRequiredExperience)))
      .sort(byPriority)
      .cursor[OpenAssignment]()
      .enumerate(stopOnError = true)
  }

  def findOrderedByPriority(implicit ctx: DBAccessContext): Enumerator[OpenAssignment] = {
    find(validPriorityQ).sort(byPriority).cursor[OpenAssignment]().enumerate()
  }

  private def countInstances(query: JsObject)(implicit ctx: DBAccessContext): Fox[Int] = {
    find(query).cursor[OpenAssignment]().fold(0)((count, assignment) => count + assignment.instances)
  }

  def countForTask(_task: BSONObjectID)(implicit ctx: DBAccessContext) = {
    countInstances(Json.obj("_task" -> _task))
  }

  def countForProject(project: String)(implicit ctx: DBAccessContext) = {
    countInstances(Json.obj("_project" -> project))
  }

  def removeByTask(_task: BSONObjectID)(implicit ctx: DBAccessContext) = {
    remove(Json.obj("_task" -> _task))
  }

  def removeByProject(_project: String)(implicit ctx: DBAccessContext) = {
    remove(Json.obj("_project" -> _project))
  }

  def countOpenAssignments(implicit ctx: DBAccessContext) = {
    countInstances(Json.obj())
  }

  def updateRemainingInstances(task: Task, project: Project, remainingInstances: Int)(implicit ctx: DBAccessContext) = {
    update(
      Json.obj("_project" -> project.name, "_task" -> task._id),
      Json.obj("$set" -> Json.obj("instances" -> remainingInstances))
    )
  }

  def decrementInstanceCount(id: BSONObjectID)(implicit ctx: DBAccessContext) = Fox[WriteResult] {
    update(
      Json.obj("_id" -> id, "instances" -> Json.obj("$gt" -> 0)),
      Json.obj("$inc" -> Json.obj("instances" -> -1))
    ).map { writeResult =>
      // delete OpenAssignment, if no more instances are available
      if (writeResult.n == 0) removeById(id)
      writeResult
    }
  }

  def updateAllOf(name: String, project: Project)(implicit ctx: DBAccessContext) = {
    update(Json.obj("_project" -> name), Json.obj("$set" -> Json.obj(
      "priority" -> (if(project.paused) -1 else project.priority),
      "_project" -> name
    )), multi = true)
  }

  def updateAllOf(task: Task)(implicit ctx: DBAccessContext) = {
    update(Json.obj("_task" -> task._id), Json.obj("$set" -> Json.obj(
      "team" -> task.team,
      "project" -> task._project,
      "neededExperience" -> task.neededExperience
    )), multi = true)
  }
}
