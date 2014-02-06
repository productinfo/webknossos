package braingames.binary.watcher

import java.io.File
import braingames.geometry.{Scale, Point3D, Vector3D, BoundingBox}
import braingames.util.ExtendedTypes.ExtendedString
import braingames.util.JsonHelper._
import play.api.libs.json._
import braingames.util.ExtendedTypes
import braingames.binary.models._
import java.nio.file._
import braingames.binary.Cuboid

case class ImplicitLayerInfo(name: String, resolutions: List[Int])
case class ExplicitLayerInfo(name: String, dataType: String)

class DataSourceChangeHandler(dataSourceRepository: DataSourceRepository)
    extends DirectoryChangeHandler {

  import braingames.binary.Logger._

  val defaultTeam = "Structure of Neocortical Circuits Group"

  val maxRecursiveLayerDepth = 2

  def onStart(path: Path, recursive: Boolean) {
    val file = path.toFile()
    val files = file.listFiles()
    if (files != null) {
      val foundDataSources = files.filter(_.isDirectory).flatMap { f =>
        val dataSources = teamAwareDataSourcesFromFile(f)
        dataSources.foreach(dataSourceRepository.updateOrCreate)
        dataSources
      }.map(_.name)
      dataSourceRepository.deleteAllExcept(foundDataSources)
    }
  }

  def onTick(path: Path, recursive: Boolean) {
    onStart(path, recursive)
  }

  def teamNameFrom(f: File) = {
    f.getName
  }

  def onCreate(path: Path) {
    Option(path.toFile().getParentFile).map{ teamFolder =>
      val team = teamNameFrom(teamFolder)
      dataSourceFromFile(teamFolder, team).map { dataSource =>
        dataSourceRepository.updateOrCreate(dataSource)
      }
    }
  }

  def onDelete(path: Path) {
    Option(path.toFile().getParentFile).map{ teamFolder =>
      val team = teamNameFrom(teamFolder)
      dataSourceFromFile(teamFolder, team).map { dataSource =>
        dataSourceRepository.removeByName(dataSource.name)
      }
    }
  }

  def listFiles(f: File): Array[File] =
    Option(f.listFiles).getOrElse(Array.empty)

  def listDirectories(f: File) =
    f.listFiles.filter(_.isDirectory)

  def highestResolutionDir(l: Array[File]) = {
    if (l.isEmpty)
      None
    else
      Some(l.minBy(f => f.getName.toIntOpt.getOrElse(Int.MaxValue)))
  }

  def maxValueFromFiles(l: Array[File]): Option[Int] = {
    val numbers = l.flatMap { f =>
      if (f.getName.size > 1)
        f.getName.substring(1).toIntOpt
      else
        None
    }
    if (numbers.isEmpty)
      None
    else {
      Some(numbers.max)
    }
  }

  def extractSections(base: File, basePath: String): Iterable[DataLayerSection] = {
    val sectionSettingsMap = extractSectionSettings(base)
    sectionSettingsMap.map {
      case (path, settings) =>
        DataLayerSection(
          path.getAbsolutePath().replace(basePath, ""),
          settings.sectionId getOrElse path.getName,
          settings.resolutions,
          BoundingBox.createFrom(settings.bboxSmall),
          BoundingBox.createFrom(settings.bboxBig))
    }
  }

  def extractSectionSettings(base: File): Map[File, DataLayerSectionSettings] = {
    val basePath = base.getAbsolutePath()

    def extract(path: File, depth: Int = 0): List[Option[(File, DataLayerSectionSettings)]] = {
      if (depth > maxRecursiveLayerDepth) {
        List()
      } else {
        DataLayerSectionSettings.fromFile(path).map(path -> _) ::
          listDirectories(path).toList.flatMap(d => extract(d, depth + 1))
      }
    }

    extract(base).flatten.toMap
  }

  def extractLayers(file: File, dataSourcePath: String) = {
    for {
      layer <- listDirectories(file).toList
      settings <- DataLayerSettings.fromFile(layer)
    } yield {
      logger.info("Found Layer: " + settings)
      val dataLayerPath = layer.getAbsolutePath()
      val sections = extractSections(layer, dataLayerPath).toList
      DataLayer(settings.typ, dataLayerPath, settings.flags, settings.`class`, settings.fallback, sections)
    }
  }

  def teamAwareDataSourcesFromFile(folder: File): Array[DataSource] = {
    val (team, dataSources) = DataSourceSettings.settingsFileFromFolder(folder).exists() match {
      case true =>
        (defaultTeam, dataSourceFromFile(folder, defaultTeam).toArray)
      case false =>
        val team = folder.getName
        (team, listDirectories(folder).flatMap{ f =>
          dataSourceFromFile(f, team)
        })
    }
    logger.info(s"Datasets for team $team: ${dataSources.mkString(",")}")
    dataSources
  }

  def dataSourceFromFile(folder: File, team: String): Option[DataSource] = {
    if (folder.isDirectory) {
      val dataSource: DataSource = DataSourceSettings.readFromFolder(folder) match {
        case Some(settings) =>
          DataSource(
            settings.name,
            folder.getAbsolutePath,
            settings.priority getOrElse 0,
            settings.scale,
            Nil,
            team)
        case _ =>
          DataSource(
            folder.getName,
            folder.getAbsolutePath,
            0,
            Scale.default,
            Nil,
            team)
      }

      val layers = extractLayers(folder, folder.getAbsolutePath())

      Some(dataSource.copy(dataLayers = layers))
    } else
      None
  }
}
