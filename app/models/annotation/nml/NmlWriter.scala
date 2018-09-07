package models.annotation.nml

import javax.xml.stream.{XMLOutputFactory, XMLStreamWriter}
import com.scalableminds.util.geometry.Scale
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.util.xml.Xml
import com.scalableminds.webknossos.datastore.SkeletonTracing._
import com.scalableminds.webknossos.datastore.VolumeTracing.VolumeTracing
import com.scalableminds.webknossos.datastore.geometry.{BoundingBox, Point3D, Vector3D}
import com.sun.xml.txw2.output.IndentingXMLStreamWriter
import javax.inject.Inject
import models.annotation.{Annotation, AnnotationService}
import models.task.Task
import models.user.User
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator

import scala.concurrent.Future


case class NmlParameters(
                          dataSetName: String,
                          description: Option[String],
                          scale: Option[Scale],
                          createdTimestamp: Long,
                          editPosition: Point3D,
                          editRotation: Vector3D,
                          zoomLevel: Double,
                          activeNodeId: Option[Int],
                          userBoundingBox: Option[BoundingBox],
                          taskBoundingBox: Option[BoundingBox]
                        )

class NmlWriter @Inject() extends FoxImplicits {
  private lazy val outputService = XMLOutputFactory.newInstance()

  def toNmlStream(skeletonTracing: Option[SkeletonTracing],
                  volumeTracing: Option[VolumeTracing],
                  annotation: Option[Annotation],
                  scale: Option[Scale],
                  annotationOwner: Option[User],
                  annotationTask: Option[Task]
                 ): Enumerator[Array[Byte]] = Enumerator.outputStream { os =>
    implicit val writer: IndentingXMLStreamWriter = new IndentingXMLStreamWriter(outputService.createXMLStreamWriter(os))

    for {
      nml <- toNml(skeletonTracing, volumeTracing, annotation, scale, annotationOwner, annotationTask)
      _ = os.close()
    } yield nml
  }

  def toNml(skeletonTracingOpt: Option[SkeletonTracing],
            volumeTracingOpt: Option[VolumeTracing],
            annotation: Option[Annotation],
            scale: Option[Scale],
            annotationOwner: Option[User],
            annotationTask: Option[Task]
           )(implicit writer: XMLStreamWriter): Fox[Unit] = {
    for {
      _ <- Xml.withinElement("things") {
        for {
          _ <- Future.successful(writeMetaData(annotation, annotationOwner, annotationTask))
          parameters <- extractTracingParameters(skeletonTracingOpt, volumeTracingOpt, annotation.map(_.description), scale).toFox
          _ = writeParameters(parameters)
          _ = skeletonTracingOpt.foreach(writeSkeletonThings)
          _ = volumeTracingOpt.foreach(writeVolumeThings)
        } yield ()
      }
      _ = writer.writeEndDocument()
      _ = writer.close()
    } yield ()
  }

  def extractTracingParameters(skeletonTracingOpt: Option[SkeletonTracing],
                                       volumeTracingOpt: Option[VolumeTracing],
                                       description: Option[String],
                                       scale: Option[Scale]
                                      ): Option[NmlParameters] = {
    // in hybrid case, use data from skeletonTracing (should be identical)
    skeletonTracingOpt.map { s =>
      NmlParameters(
        s.dataSetName,
        description,
        scale,
        s.createdTimestamp,
        s.editPosition,
        s.editRotation,
        s.zoomLevel,
        s.activeNodeId,
        s.userBoundingBox,
        s.boundingBox
      )
    }.orElse {
      volumeTracingOpt.map { v: VolumeTracing =>
      NmlParameters(
          v.dataSetName,
          description,
          scale,
          v.createdTimestamp,
          v.editPosition,
          v.editRotation,
          v.zoomLevel,
          None,
          None,
          None
        )
      }
    }
  }

  def writeParameters(parameters: NmlParameters)(implicit writer: XMLStreamWriter): Unit = {
    Xml.withinElementSync("parameters") {
      Xml.withinElementSync("experiment") {
        writer.writeAttribute("name", parameters.dataSetName)
        parameters.description.foreach(writer.writeAttribute("description", _))
      }
      Xml.withinElementSync("scale") {
        writer.writeAttribute("x", parameters.scale.map(_.x).getOrElse(-1).toString)
        writer.writeAttribute("y", parameters.scale.map(_.y).getOrElse(-1).toString)
        writer.writeAttribute("z", parameters.scale.map(_.z).getOrElse(-1).toString)
      }
      Xml.withinElementSync("offset") {
        writer.writeAttribute("x", "0")
        writer.writeAttribute("y", "0")
        writer.writeAttribute("z", "0")
      }
      Xml.withinElementSync("time") {
        writer.writeAttribute("ms", parameters.createdTimestamp.toString)
      }
      Xml.withinElementSync("editPosition") {
        writer.writeAttribute("x", parameters.editPosition.x.toString)
        writer.writeAttribute("y", parameters.editPosition.y.toString)
        writer.writeAttribute("z", parameters.editPosition.z.toString)
      }
      Xml.withinElementSync("editRotation") {
        writer.writeAttribute("xRot", parameters.editRotation.x.toString)
        writer.writeAttribute("yRot", parameters.editRotation.y.toString)
        writer.writeAttribute("zRot", parameters.editRotation.z.toString)
      }
      Xml.withinElementSync("zoomLevel") {
        writer.writeAttribute("zoom", parameters.zoomLevel.toString)
      }
      parameters.activeNodeId.foreach { nodeId =>
        Xml.withinElementSync("activeNode") {
          writer.writeAttribute("id", nodeId.toString)
        }
      }
      parameters.userBoundingBox.foreach { b =>
        Xml.withinElementSync("userBoundingBox") {
          writer.writeAttribute("topLeftX", b.topLeft.x.toString)
          writer.writeAttribute("topLeftY", b.topLeft.y.toString)
          writer.writeAttribute("topLeftZ", b.topLeft.z.toString)
          writer.writeAttribute("width", b.width.toString)
          writer.writeAttribute("height", b.height.toString)
          writer.writeAttribute("depth", b.depth.toString)
        }
      }
      parameters.taskBoundingBox.foreach { b =>
        Xml.withinElementSync("taskBoundingBox") {
          writer.writeAttribute("topLeftX", b.topLeft.x.toString)
          writer.writeAttribute("topLeftY", b.topLeft.y.toString)
          writer.writeAttribute("topLeftZ", b.topLeft.z.toString)
          writer.writeAttribute("width", b.width.toString)
          writer.writeAttribute("height", b.height.toString)
          writer.writeAttribute("depth", b.depth.toString)
        }
      }
    }
  }

  def writeVolumeThings(volumeTracing: VolumeTracing)(implicit writer: XMLStreamWriter): Unit = {
    Xml.withinElementSync("volume") {
      writer.writeAttribute("id", "1")
      writer.writeAttribute("location", "data.zip")
      volumeTracing.fallbackLayer.foreach(writer.writeAttribute("fallbackLayer", _))
    }
  }

  def writeSkeletonThings(skeletonTracing: SkeletonTracing)(implicit writer: XMLStreamWriter): Unit = {
    writeTreesAsXml(skeletonTracing.trees.filterNot(_.nodes.isEmpty))
    Xml.withinElementSync("branchpoints")(writeBranchPointsAsXml(skeletonTracing.trees.flatMap(_.branchPoints).sortBy(-_.createdTimestamp)))
    Xml.withinElementSync("comments")(writeCommentsAsXml(skeletonTracing.trees.flatMap(_.comments)))
    Xml.withinElementSync("groups")(writeTreeGroupsAsXml(skeletonTracing.treeGroups))
  }

  def writeTreesAsXml(trees: Seq[Tree])(implicit writer: XMLStreamWriter): Unit = {
    trees.foreach { t =>
      Xml.withinElementSync("thing") {
        writer.writeAttribute("id", t.treeId.toString)
        writer.writeAttribute("color.r", t.color.map(_.r.toString).getOrElse(""))
        writer.writeAttribute("color.g", t.color.map(_.g.toString).getOrElse(""))
        writer.writeAttribute("color.b", t.color.map(_.b.toString).getOrElse(""))
        writer.writeAttribute("color.a", t.color.map(_.a.toString).getOrElse(""))
        writer.writeAttribute("name", t.name)
        t.groupId.foreach(groupId => writer.writeAttribute("groupId", groupId.toString))
        Xml.withinElementSync("nodes")(writeNodesAsXml(t.nodes.sortBy(_.id)))
        Xml.withinElementSync("edges")(writeEdgesAsXml(t.edges))
      }
    }
  }

  def writeNodesAsXml(nodes: Seq[Node])(implicit writer: XMLStreamWriter): Unit = {
    nodes.toSet.foreach { n: Node => //TODO 2017: once the tracings with duplicate nodes are fixed in the DB, remove the toSet workaround
      Xml.withinElementSync("node") {
        writer.writeAttribute("id", n.id.toString)
        writer.writeAttribute("radius", n.radius.toString)
        writer.writeAttribute("x", n.position.x.toString)
        writer.writeAttribute("y", n.position.y.toString)
        writer.writeAttribute("z", n.position.z.toString)
        writer.writeAttribute("rotX", n.rotation.x.toString)
        writer.writeAttribute("rotY", n.rotation.y.toString)
        writer.writeAttribute("rotZ", n.rotation.z.toString)
        writer.writeAttribute("inVp", n.viewport.toString)
        writer.writeAttribute("inMag", n.resolution.toString)
        writer.writeAttribute("bitDepth", n.bitDepth.toString)
        writer.writeAttribute("interpolation", n.interpolation.toString)
        writer.writeAttribute("time", n.createdTimestamp.toString)
      }
    }
  }

  def writeEdgesAsXml(edges: Seq[Edge])(implicit writer: XMLStreamWriter): Unit = {
    edges.foreach { e =>
      Xml.withinElementSync("edge") {
        writer.writeAttribute("source", e.source.toString)
        writer.writeAttribute("target", e.target.toString)
      }
    }
  }

  def writeBranchPointsAsXml(branchPoints: Seq[BranchPoint])(implicit writer: XMLStreamWriter): Unit = {
    branchPoints.foreach { b =>
      Xml.withinElementSync("branchpoint") {
        writer.writeAttribute("id", b.nodeId.toString)
        writer.writeAttribute("time", b.createdTimestamp.toString)
      }
    }
  }

  def writeCommentsAsXml(comments: Seq[Comment])(implicit writer: XMLStreamWriter): Unit = {
    comments.foreach { c =>
      Xml.withinElementSync("comment") {
        writer.writeAttribute("node", c.nodeId.toString)
        writer.writeAttribute("content", c.content)
      }
    }
  }

  def writeTreeGroupsAsXml(treeGroups: Seq[TreeGroup])(implicit writer: XMLStreamWriter): Unit = {
    treeGroups.foreach { t =>
      Xml.withinElementSync("group") {
        writer.writeAttribute("name", t.name)
        writer.writeAttribute("id", t.groupId.toString)
        writeTreeGroupsAsXml(t.children)
      }
    }
  }

  def writeMetaData(annotationOpt: Option[Annotation], userOpt: Option[User], taskOpt: Option[Task])(implicit writer: XMLStreamWriter): Unit = {
    Xml.withinElementSync("meta") {
      writer.writeAttribute("name", "writer")
      writer.writeAttribute("content", "NmlWriter.scala")
    }
    Xml.withinElementSync("meta") {
      writer.writeAttribute("name", "writerGitCommit")
      writer.writeAttribute("content", webknossos.BuildInfo.commitHash)
    }
    Xml.withinElementSync("meta") {
      writer.writeAttribute("name", "timestamp")
      writer.writeAttribute("content", DateTime.now().getMillis.toString)
    }
    annotationOpt.foreach { annotation =>
      Xml.withinElementSync("meta") {
        writer.writeAttribute("name", "annotationId")
        writer.writeAttribute("content", annotation.id)
      }
    }
    userOpt.foreach { user =>
      Xml.withinElementSync("meta") {
        writer.writeAttribute("name", "username")
        writer.writeAttribute("content", user.name)
      }
    }
    taskOpt.foreach { task =>
      Xml.withinElementSync("meta") {
        writer.writeAttribute("name", "taskId")
        writer.writeAttribute("content", task._id.toString)
      }
    }
  }
}
