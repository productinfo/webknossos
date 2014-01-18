### define
./binary/interpolation_collector : InterpolationCollector
./binary/cube : Cube
./binary/pullqueue : PullQueue
./binary/pushqueue : PushQueue
./binary/plane2d : Plane2D
./binary/ping_strategy : PingStrategy
./binary/ping_strategy_3d : PingStrategy3d
./dimensions : Dimensions
###

class Binary

  # Constants
  PING_THROTTLE_TIME : 50
  DIRECTION_VECTOR_SMOOTHER : .125
  TEXTURE_SIZE_P : 0

  cube : null
  pullQueue : null
  planes : []

  dataSetName : ""
  direction : [0, 0, 0]


  constructor : (@user, dataSet, @TEXTURE_SIZE_P, @layer, tracingId) ->

    @dataSetName = dataSet.name

    for layer in dataSet.dataLayers
      if layer.typ == @layer.name
        dataLayer = layer

    lowerBoundary = [dataLayer.maxCoordinates.topLeft]
    upperBoundary = [
      dataLayer.maxCoordinates.width + dataLayer.maxCoordinates.topLeft[0]
      dataLayer.maxCoordinates.height + dataLayer.maxCoordinates.topLeft[1]
      dataLayer.maxCoordinates.depth + dataLayer.maxCoordinates.topLeft[2]
    ]

    @cube = new Cube(upperBoundary, dataLayer.resolutions.length, @layer.bitDepth)
    @pullQueue = new PullQueue(@dataSetName, @cube, @layer.name, tracingId )
    @pushQueue = new PushQueue(@dataSetName, @cube, @layer.name, tracingId, false)
    @cube.setPushQueue( @pushQueue )

    @pingStrategies = [new PingStrategy.DslSlow(@cube, @TEXTURE_SIZE_P)]
    @pingStrategies3d = [new PingStrategy3d.DslSlow()]

    @planes = []
    @planes[Dimensions.PLANE_XY] = new Plane2D(Dimensions.PLANE_XY, @cube, @pullQueue, @TEXTURE_SIZE_P, @layer.bitDepth, 32)
    @planes[Dimensions.PLANE_XZ] = new Plane2D(Dimensions.PLANE_XZ, @cube, @pullQueue, @TEXTURE_SIZE_P, @layer.bitDepth, 32)
    @planes[Dimensions.PLANE_YZ] = new Plane2D(Dimensions.PLANE_YZ, @cube, @pullQueue, @TEXTURE_SIZE_P, @layer.bitDepth, 32)

    if @layer.allowManipulation
      # assume zoom step count to be at least 1
      @contrastCurves = []
      contrastCurve = new Uint8Array(256)
      @contrastCurves[0] = new Uint8Array(256)

      for i in [1..@cube.ZOOM_STEP_COUNT]
        @contrastCurves[i] = contrastCurve

    @user.on({
      set4BitChanged : (is4Bit) => @pullQueue(is4Bit)
    })

    @ping = _.throttle(@pingImpl, @PING_THROTTLE_TIME)


  updateContrastCurve : (brightness, contrast) ->

    unless @contrastCurves?
      return

    contrastCurve = @contrastCurves[1]
    contrastCurveMag1 = @contrastCurves[0]

    for i in [0..255] by 1
      contrastCurve[i] = Math.max(Math.min((i + brightness) * contrast, 255), 0)
      contrastCurveMag1[i] = Math.max(Math.min((i + brightness + 8) * contrast, 255), 0)

    for plane in @planes
      plane.updateContrastCurves(@contrastCurves)


  pingStop : ->

    @pullQueue.clear()


  pingImpl : (position, {zoomStep, area, activePlane}) ->

    if @lastPosition?
      
      @direction = [
        (1 - @DIRECTION_VECTOR_SMOOTHER) * @direction[0] + @DIRECTION_VECTOR_SMOOTHER * (position[0] - @lastPosition[0])
        (1 - @DIRECTION_VECTOR_SMOOTHER) * @direction[1] + @DIRECTION_VECTOR_SMOOTHER * (position[1] - @lastPosition[1])
        (1 - @DIRECTION_VECTOR_SMOOTHER) * @direction[2] + @DIRECTION_VECTOR_SMOOTHER * (position[2] - @lastPosition[2])
      ]

    unless _.isEqual(position, @lastPosition) and zoomStep == @lastZoomStep and _.isEqual(area, @lastArea)

      @lastPosition = position.slice()
      @lastZoomStep = zoomStep
      @lastArea     = area.slice()

      # console.log "ping", @pullQueue.roundTripTime, @pullQueue.bucketsPerSecond, @cube.bucketCount

      for strategy in @pingStrategies 
        if strategy.inVelocityRange(1) and strategy.inRoundTripTimeRange(@pullQueue.roundTripTime)

          pullQueue = strategy.ping(position, @direction, zoomStep, area, activePlane) if zoomStep? and area? and activePlane?
          @pullQueue.clear()
          for entry in pullQueue
            @pullQueue.insert(entry...)

          break

      @pullQueue.pull()


  arbitraryPing : _.once (matrix) ->

    @arbitraryPing = _.throttle(@arbitraryPingImpl, @PING_THROTTLE_TIME)
    @arbitraryPing(matrix)


  arbitraryPingImpl : (matrix) ->

    for strategy in @pingStrategies3d 
      if strategy.inVelocityRange(1) and strategy.inRoundTripTimeRange(@pullQueue.roundTripTime)
        
        pullQueue = strategy.ping(matrix)
      
        for entry in pullQueue
          @pullQueue.insert(entry...)

        break

    @pullQueue.pull() 


  getByVerticesSync : (vertices) ->
    # A synchronized implementation of `get`. Cuz its faster.

    contrastCurve = @contrastCurves[1]

    { buffer, accessedBuckets } = InterpolationCollector.bulkCollect(
      vertices
      @cube.getArbitraryCube()
    )

    @cube.accessBuckets(accessedBuckets)

    for i in [0...buffer.length] by 1
      buffer[i] = contrastCurve[buffer[i]]

    buffer
