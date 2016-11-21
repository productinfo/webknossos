/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package models.annotation

import java.io.{FileInputStream, FileOutputStream, InputStream, File}
import java.nio.channels.Channels

import com.scalableminds.util.io.NamedFileStream
import com.scalableminds.util.reactivemongo.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import models.tracing.skeleton.SkeletonTracing
import org.apache.commons.io.IOUtils
import models.annotation.handler.SavedTracingInformationHandler
import oxalis.nml.NMLService
import play.api.Play
import play.api.libs.concurrent.Execution.Implicits._

trait AnnotationFileService extends FoxImplicits {

  def annotation: Annotation

  val conf = Play.current.configuration

  def fileExtension(annotation: Annotation) =
    annotation.contentReference.service.downloadFileExtension

  val annotationStorageFolder = {
    val folder = conf.getString("oxalis.annotation.storageFolder") getOrElse "data/nmls"
    new File(folder).mkdirs()
    folder
  }

  def outputPathForAnnotation() = {
    s"$annotationStorageFolder/${annotation.id}${fileExtension(annotation)}"
  }

  def writeAnnotationToFile(): Fox[Boolean] = {
    for {
      in: InputStream <- annotationToInputStream()
      path = outputPathForAnnotation()
    } yield {
      val f = new File(path)
      val out = new FileOutputStream(f).getChannel
      val ch = Channels.newChannel(in)
      try {
        out.transferFrom(ch, 0, in.available)
        true
      } finally {
        in.close()
        ch.close()
        out.close()
      }
    }
  }

  def loadAnnotationContentFromFileStream(): Fox[InputStream] = {
    if (annotation.state.isFinished) {
      val path = outputPathForAnnotation()
      val f = new File(path)
      if (f.exists())
        Some(new FileInputStream(f))
      else
        None
    } else
      None
  }

  def loadAnnotationContentStream(): Fox[InputStream] = {
    loadAnnotationContentFromFileStream().orElse {
      writeAnnotationToFile().flatMap{ x =>
        loadAnnotationContentFromFileStream()
      }
    }.orElse(annotationToInputStream())
  }

  def loadAnnotationContent()(implicit ctx: DBAccessContext): Fox[NamedFileStream] = {
    for{
      annotationStream <- loadAnnotationContentStream()
      name <- SavedTracingInformationHandler.nameForAnnotation(annotation)
    } yield
      NamedFileStream( () => annotationStream, name + fileExtension(annotation))
  }

  def annotationToInputStream(): Fox[InputStream] = {
    annotation.content.flatMap {
      case t: SkeletonTracing =>
        NMLService.toNML(t).map(s => IOUtils.toInputStream(s, "utf-8")).toFox
      case _ =>
        throw new Exception("Invalid content!")
    }
  }
}
