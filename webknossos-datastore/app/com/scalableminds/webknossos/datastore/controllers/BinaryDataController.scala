package com.scalableminds.webknossos.datastore.controllers

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.Base64

import akka.actor.ActorSystem
import akka.stream.scaladsl.StreamConverters
import com.google.inject.Inject
import com.scalableminds.util.geometry.Point3D
import com.scalableminds.util.geometry.Point3D.Point3DWrites
import com.scalableminds.util.image.{ImageCreator, ImageCreatorParameters, JPEGWriter}
import com.scalableminds.util.tools.Fox
import com.scalableminds.webknossos.datastore.DataStoreConfig
import com.scalableminds.webknossos.datastore.models.DataRequestCollection._
import com.scalableminds.webknossos.datastore.models.datasource._
import com.scalableminds.webknossos.datastore.models.requests.{
  DataServiceDataRequest,
  DataServiceMappingRequest,
  DataServiceRequestSettings
}
import com.scalableminds.webknossos.datastore.models.{
  DataRequest,
  ImageThumbnail,
  VoxelPosition,
  WebKnossosDataRequest,
  _
}
import com.scalableminds.webknossos.datastore.services._
import net.liftweb.common.{Box, Empty, Failure, Full}
import net.liftweb.util.Helpers.tryo
import play.api.http.HttpEntity
import play.api.i18n.{Messages, MessagesProvider}
import play.api.libs.json.Json
import play.api.mvc.{PlayBodyParsers, ResponseHeader, Result}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class BinaryDataController @Inject()(
    dataSourceRepository: DataSourceRepository,
    config: DataStoreConfig,
    accessTokenService: DataStoreAccessTokenService,
    binaryDataServiceHolder: BinaryDataServiceHolder,
    mappingService: MappingService,
    isosurfaceService: IsosurfaceService,
    actorSystem: ActorSystem
)(implicit ec: ExecutionContext, bodyParsers: PlayBodyParsers)
    extends Controller {

  val binaryDataService = binaryDataServiceHolder.binaryDataService

  /**
    * Handles requests for raw binary data via HTTP POST from webKnossos.
    */
  def requestViaWebKnossos(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String
  ) = Action.async(validateJson[List[WebKnossosDataRequest]]) { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          (data, indices) <- requestData(dataSource, dataLayer, request.body)
        } yield Ok(data).withHeaders(getMissingBucketsHeaders(indices): _*)
      }
    }
  }

  def getMissingBucketsHeaders(indices: List[Int]): Seq[(String, String)] =
    List(("MISSING-BUCKETS" -> formatMissingBucketList(indices)),
         ("Access-Control-Expose-Headers" -> "MISSING-BUCKETS"))

  def formatMissingBucketList(indices: List[Int]): String =
    "[" + indices.mkString(", ") + "]"

  /**
    * Handles requests for raw binary data via HTTP GET.
    */
  def requestRawCuboid(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      x: Int,
      y: Int,
      z: Int,
      width: Int,
      height: Int,
      depth: Int,
      resolution: Int,
      halfByte: Boolean
  ) = Action.async { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          request = DataRequest(
            new VoxelPosition(x, y, z, dataLayer.lookUpResolution(resolution)),
            width,
            height,
            depth,
            DataServiceRequestSettings(halfByte = halfByte)
          )
          (data, indices) <- requestData(dataSource, dataLayer, request)
        } yield Ok(data).withHeaders(getMissingBucketsHeaders(indices): _*)
      }
    }
  }

  /**
    * Handles requests for raw binary data via HTTP GET for debugging.
    */
  def requestViaAjaxDebug(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      cubeSize: Int,
      x: Int,
      y: Int,
      z: Int,
      resolution: Int,
      halfByte: Boolean
  ) =
    requestRawCuboid(organizationName,
                     dataSetName,
                     dataLayerName,
                     x,
                     y,
                     z,
                     cubeSize,
                     cubeSize,
                     cubeSize,
                     resolution,
                     halfByte)

  /**
    * Handles a request for raw binary data via a HTTP GET. Used by knossos.
    */
  def requestViaKnossos(organizationName: String,
                        dataSetName: String,
                        dataLayerName: String,
                        resolution: Int,
                        x: Int,
                        y: Int,
                        z: Int,
                        cubeSize: Int) = Action.async { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          request = DataRequest(
            new VoxelPosition(x * cubeSize * resolution,
                              y * cubeSize * resolution,
                              z * cubeSize * resolution,
                              Point3D(resolution, resolution, resolution)),
            cubeSize,
            cubeSize,
            cubeSize
          )
          (data, indices) <- requestData(dataSource, dataLayer, request)
        } yield Ok(data).withHeaders(getMissingBucketsHeaders(indices): _*)
      }
    }
  }

  /**
    * Handles requests for data sprite sheets.
    */
  def requestSpriteSheet(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      cubeSize: Int,
      imagesPerRow: Int,
      x: Int,
      y: Int,
      z: Int,
      resolution: Int,
      halfByte: Boolean
  ) = Action.async(parse.raw) { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          dataRequest = DataRequest(new VoxelPosition(x, y, z, dataLayer.lookUpResolution(resolution)),
                                    cubeSize,
                                    cubeSize,
                                    cubeSize,
                                    DataServiceRequestSettings(halfByte = halfByte))
          imageProvider <- respondWithSpriteSheet(dataSource,
                                                  dataLayer,
                                                  dataRequest,
                                                  imagesPerRow,
                                                  blackAndWhite = false)
        } yield {
          Result(
            header = ResponseHeader(200),
            body = HttpEntity.Streamed(StreamConverters.asOutputStream().mapMaterializedValue { outputStream =>
              imageProvider(outputStream)
            }, None, Some(contentTypeJpeg))
          )
        }
      }
    }
  }

  /**
    * Handles requests for data images.
    */
  def requestImage(organizationName: String,
                   dataSetName: String,
                   dataLayerName: String,
                   width: Int,
                   height: Int,
                   x: Int,
                   y: Int,
                   z: Int,
                   resolution: Int,
                   halfByte: Boolean,
                   blackAndWhite: Boolean) = Action.async(parse.raw) { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          dataRequest = DataRequest(new VoxelPosition(x, y, z, dataLayer.lookUpResolution(resolution)),
                                    width,
                                    height,
                                    1,
                                    DataServiceRequestSettings(halfByte = halfByte))
          imageProvider <- respondWithSpriteSheet(dataSource, dataLayer, dataRequest, 1, blackAndWhite)
        } yield {
          Result(
            header = ResponseHeader(200),
            body = HttpEntity.Streamed(StreamConverters.asOutputStream().mapMaterializedValue { outputStream =>
              imageProvider(outputStream)
            }, None, Some(contentTypeJpeg))
          )
        }
      }
    }
  }

  /**
    * Handles requests for dataset thumbnail images as JPEG.
    */
  def requestImageThumbnailJpeg(organizationName: String,
                                dataSetName: String,
                                dataLayerName: String,
                                width: Int,
                                height: Int,
                                centerX: Option[Int],
                                centerY: Option[Int],
                                centerZ: Option[Int],
                                zoom: Option[Double]) = Action.async(parse.raw) { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          thumbnailProvider <- respondWithImageThumbnail(organizationName,
                                                         dataSetName,
                                                         dataLayerName,
                                                         width,
                                                         height,
                                                         centerX,
                                                         centerY,
                                                         centerZ,
                                                         zoom)
        } yield {
          Result(
            header = ResponseHeader(200),
            body = HttpEntity.Streamed(StreamConverters.asOutputStream().mapMaterializedValue { outputStream =>
              thumbnailProvider(outputStream)
            }, None, Some(contentTypeJpeg))
          )
        }
      }
    }
  }

  /**
    * Handles requests for dataset thumbnail images as base64-encoded JSON.
    */
  def requestImageThumbnailJson(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      width: Int,
      height: Int,
      centerX: Option[Int],
      centerY: Option[Int],
      centerZ: Option[Int],
      zoom: Option[Double]
  ) = Action.async(parse.raw) { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          thumbnailProvider <- respondWithImageThumbnail(organizationName,
                                                         dataSetName,
                                                         dataLayerName,
                                                         width,
                                                         height,
                                                         centerX,
                                                         centerY,
                                                         centerZ,
                                                         zoom)
        } yield {
          val os = new ByteArrayOutputStream()
          thumbnailProvider(Base64.getEncoder.wrap(os))
          Ok(Json.toJson(ImageThumbnail(contentTypeJpeg, os.toString)))
        }
      }
    }
  }

  /**
    * Handles mapping requests.
    */
  def requestMapping(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      mappingName: String
  ) = Action.async { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
      AllowRemoteOrigin {
        for {
          (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
          segmentationLayer <- tryo(dataLayer.asInstanceOf[SegmentationLayer]).toFox ?~> Messages("dataLayer.notFound")
          mappingRequest = DataServiceMappingRequest(dataSource, segmentationLayer, mappingName)
          result <- mappingService.handleMappingRequest(mappingRequest)
        } yield {
          Ok(result)
        }
      }
    }
  }

  /**
    * Handles isosurface requests.
    */
  def requestIsosurface(organizationName: String, dataSetName: String, dataLayerName: String) =
    Action.async(validateJson[WebKnossosIsosurfaceRequest]) { implicit request =>
      accessTokenService.validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
        AllowRemoteOrigin {
          for {
            (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
            segmentationLayer <- tryo(dataLayer.asInstanceOf[SegmentationLayer]).toFox ?~> Messages(
              "dataLayer.mustBeSegmentation")
            isosurfaceRequest = IsosurfaceRequest(dataSource,
                                                  segmentationLayer,
                                                  request.body.cuboid(dataLayer),
                                                  request.body.segmentId,
                                                  request.body.voxelDimensions,
                                                  request.body.mapping)
            // The client expects the isosurface as a flat float-array. Three consecutive floats form a 3D point, three
            // consecutive 3D points (i.e., nine floats) form a triangle.
            // There are no shared vertices between triangles.
            (vertices, neighbors) <- isosurfaceService.requestIsosurfaceViaActor(isosurfaceRequest)
          } yield {
            // We need four bytes for each float
            val responseBuffer = ByteBuffer.allocate(vertices.length * 4).order(ByteOrder.LITTLE_ENDIAN)
            responseBuffer.asFloatBuffer().put(vertices)
            Ok(responseBuffer.array()).withHeaders(getNeighborIndices(neighbors): _*)
          }
        }
      }
    }

  private def getNeighborIndices(neighbors: List[Int]) =
    List(("NEIGHBORS" -> formatNeighborList(neighbors)), ("Access-Control-Expose-Headers" -> "NEIGHBORS"))

  private def formatNeighborList(neighbors: List[Int]): String =
    "[" + neighbors.mkString(", ") + "]"

  def findData(organizationName: String, dataSetName: String, dataLayerName: String) = Action.async {
    implicit request =>
      accessTokenService
        .validateAccess(UserAccessRequest.readDataSources(DataSourceId(dataSetName, organizationName))) {
          AllowRemoteOrigin {
            for {
              positionOpt <- findPositionWithData(organizationName, dataSetName, dataLayerName)
            } yield Ok(Json.obj("position" -> positionOpt))
          }
        }
  }

  private def getDataSourceAndDataLayer(organizationName: String, dataSetName: String, dataLayerName: String)(
      implicit m: MessagesProvider): Fox[(DataSource, DataLayer)] =
    for {
      dataSource <- dataSourceRepository.findUsable(DataSourceId(dataSetName, organizationName)).toFox ?~> Messages(
        "dataSource.notFound") ~> 404
      dataLayer <- dataSource.getDataLayer(dataLayerName) ?~> Messages("dataLayer.notFound", dataLayerName) ~> 404
    } yield {
      (dataSource, dataLayer)
    }

  private def requestData(
      dataSource: DataSource,
      dataLayer: DataLayer,
      dataRequests: DataRequestCollection
  ): Fox[(Array[Byte], List[Int])] = {
    val requests =
      dataRequests.map(r => DataServiceDataRequest(dataSource, dataLayer, None, r.cuboid(dataLayer), r.settings))
    binaryDataService.handleDataRequests(requests)
  }

  private def contentTypeJpeg = "image/jpeg"

  private def respondWithSpriteSheet(
      dataSource: DataSource,
      dataLayer: DataLayer,
      request: DataRequest,
      imagesPerRow: Int,
      blackAndWhite: Boolean
  )(implicit m: MessagesProvider): Fox[(OutputStream) => Unit] = {
    val params = ImageCreatorParameters(
      dataLayer.bytesPerElement,
      request.settings.halfByte,
      request.cuboid(dataLayer).width,
      request.cuboid(dataLayer).height,
      imagesPerRow,
      blackAndWhite = blackAndWhite,
      isSegmentation = dataLayer.category == Category.segmentation
    )
    for {
      (data, indices) <- requestData(dataSource, dataLayer, request)
      dataWithFallback = if (data.length == 0)
        new Array[Byte](params.slideHeight * params.slideWidth * params.bytesPerElement)
      else data
      spriteSheet <- ImageCreator.spriteSheetFor(dataWithFallback, params) ?~> Messages("image.create.failed")
      firstSheet <- spriteSheet.pages.headOption ?~> Messages("image.page.failed")
    } yield {
      new JPEGWriter().writeToOutputStream(firstSheet.image)(_)
    }
  }

  private def respondWithImageThumbnail(
      organizationName: String,
      dataSetName: String,
      dataLayerName: String,
      width: Int,
      height: Int,
      centerX: Option[Int],
      centerY: Option[Int],
      centerZ: Option[Int],
      zoom: Option[Double]
  )(implicit m: MessagesProvider): Fox[(OutputStream) => Unit] =
    for {
      (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
      position = ImageThumbnail.goodThumbnailParameters(dataLayer, width, height, centerX, centerY, centerZ, zoom)
      request = DataRequest(position, width, height, 1)
      image <- respondWithSpriteSheet(dataSource, dataLayer, request, 1, blackAndWhite = false)
    } yield {
      image
    }

  private def findPositionWithData(organizationName: String, dataSetName: String, dataLayerName: String)(
      implicit m: MessagesProvider) =
    for {
      (dataSource, dataLayer) <- getDataSourceAndDataLayer(organizationName, dataSetName, dataLayerName)
      positionOpt <- checkAllPositionsForData(dataSource, dataLayer)
    } yield positionOpt

  private def checkAllPositionsForData(dataSource: DataSource, dataLayer: DataLayer) = {
    def checkIfPositionHasData(position: Point3D) = {
      val request = DataRequest(
        new VoxelPosition(position.x, position.y, position.z, dataLayer.lookUpResolution(0)),
        DataLayer.bucketLength,
        DataLayer.bucketLength,
        DataLayer.bucketLength
      )
      for {
        (data, _) <- requestData(dataSource, dataLayer, request)
        if data.nonEmpty && data.exists(_ != 0)
      } yield position
    }

    def positionIter(remainingRuns: List[Int]): Fox[Option[Point3D]] = {
      def createPositionsFromExponent(exponent: Int): Fox[Box[Point3D]] = {
        val seenPositions = new mutable.HashSet[Point3D]
        val power = math.pow(2, exponent).toInt
        val spaceBetweenWidth = dataLayer.boundingBox.width / power
        val spaceBetweenHeight = dataLayer.boundingBox.height / power
        val spaceBetweenDepth = dataLayer.boundingBox.depth / power
        val topLeft = dataLayer.boundingBox.topLeft

        if (spaceBetweenWidth < DataLayer.bucketLength && spaceBetweenHeight < DataLayer.bucketLength && spaceBetweenDepth < DataLayer.bucketLength) {
          Fox.successful(Failure(""))
        } else { samplingStepIter(Point3D(1, 1, 1)) }

        def samplingStepIter(factors: Point3D): Fox[Box[Point3D]] = {
          def nextFactorsToCheck(oldFactors: Point3D) =
            if (oldFactors.x < power - 1) Some(Point3D(oldFactors.x + 1, oldFactors.y, oldFactors.z))
            else if (oldFactors.y < power - 1) Some(Point3D(1, oldFactors.y + 1, oldFactors.z))
            else if (oldFactors.z < power - 1) Some(Point3D(1, 1, oldFactors.z + 1))
            else None

          val pointToCheck = Point3D(topLeft.x + factors.x * spaceBetweenWidth,
                                     topLeft.y + factors.y * spaceBetweenHeight,
                                     topLeft.z + factors.z * spaceBetweenDepth)

          if (seenPositions.add(pointToCheck)) {
            checkIfPositionHasData(pointToCheck).futureBox.flatMap {
              case Full(pos) => Fox.successful(Full(pos))
              case _ =>
                nextFactorsToCheck(factors) match {
                  case Some(f) => samplingStepIter(f)
                  case _       => Fox.successful(Empty)
                }
            }
          } else {
            nextFactorsToCheck(factors) match {
              case Some(f) => samplingStepIter(f)
              case _       => Fox.successful(Empty)
            }
          }
        }
      }

      remainingRuns match {
        case List() => Fox.successful(None)
        case head :: tail =>
          createPositionsFromExponent(head).futureBox.flatMap {
            case Full(value) =>
              value match {
                case Full(pos)        => Fox.successful(Some(pos))
                case Empty            => positionIter(tail)
                case Failure(_, _, _) => Fox.successful(None)
              }
            case _ => Fox.successful(None)
          }
      }
    }

    positionIter((1 to 5).toList)
  }

  def clearCache(organizationName: String, dataSetName: String) = Action.async { implicit request =>
    accessTokenService.validateAccess(UserAccessRequest.administrateDataSources) {
      AllowRemoteOrigin {
        val count = binaryDataService.clearCache(organizationName, dataSetName)
        Future.successful(Ok("Closed " + count + " file handles"))
      }
    }
  }

}
