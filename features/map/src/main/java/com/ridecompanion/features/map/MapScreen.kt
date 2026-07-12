package com.ridecompanion.features.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2

private const val TAG = "MapScreen"

// Navigation camera: street level, course-up, rider in the lower third.
private const val NAV_ZOOM = 18.0
private const val FREE_ZOOM = 16.5
// Above this speed the GPS travel course is the truth; below it the compass
// takes over, so the map keeps responding while stopped or turning in place.
private const val GPS_COURSE_MIN_SPEED_MPS = 2.0f
private const val CAMERA_ANIMATION_MS = 800L

// CARTO retina raster tiles (keyless, OSM-based). osmdroid caches every
// downloaded tile on disk automatically, so anywhere you've ridden — plus the
// pre-cached route corridor — keeps rendering with no internet.
// Dark for night/indoor riding…
private val CARTO_DARK = XYTileSource(
    "CartoDark2x",
    1, 19, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

// …and bright, high-contrast Voyager for daylight, where a dark map washes
// out completely in direct sun.
private val CARTO_VOYAGER = XYTileSource(
    "CartoVoyager2x",
    1, 19, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var rideOverlays by remember { mutableStateOf<RideOverlays?>(null) }
    var hasInitialZoom by remember { mutableStateOf(false) }
    // Last reliable travel heading — held while stopped so the map doesn't snap north.
    var lastBearing by remember { mutableStateOf(0f) }
    // Route signature we already pre-cached, to download each corridor once.
    var cachedRouteKey by remember { mutableStateOf("") }

    val navigating = uiState.routePoints.isNotEmpty()

    // ---- Compass: live heading while slow or stopped ----
    // GPS course only exists while moving; the rotation-vector compass keeps
    // the puck and the course-up camera tracking where the rider faces the
    // moment they turn — the responsiveness real navigation apps have.
    val compassHeading by viewModel.compassHeading.collectAsState()
    DisposableEffect(Unit) {
        viewModel.setCompassActive(true)
        onDispose { viewModel.setCompassActive(false) }
    }
    LaunchedEffect(compassHeading, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val heading = compassHeading ?: return@LaunchedEffect
        val speed = uiState.currentLocation?.speed ?: 0f
        if (speed < GPS_COURSE_MIN_SPEED_MPS) {
            lastBearing = heading
            runCatching { ov.puck.bearingDeg = heading }
            if (uiState.isFollowingUser) {
                map.mapOrientation = -heading
            }
            map.invalidate()
        }
    }

    // ---- Camera + rider puck ----
    LaunchedEffect(uiState.currentLocation, uiState.isFollowingUser, navigating, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val location = uiState.currentLocation ?: return@LaunchedEffect

        if (location.hasBearing() && location.speed >= GPS_COURSE_MIN_SPEED_MPS) {
            lastBearing = location.bearing
        }

        val here = GeoPoint(location.latitude, location.longitude)
        runCatching {
            ov.puck.geo = here
            ov.puck.bearingDeg = lastBearing
        }

        if (uiState.isFollowingUser || !hasInitialZoom) {
            val zoom = if (navigating) NAV_ZOOM else FREE_ZOOM
            // While navigating, shift the camera focus so the rider sits low on
            // the screen and the road ahead fills the view — no manual panning.
            map.setMapCenterOffset(0, if (navigating) (map.height * 0.22).toInt() else 0)
            if (!hasInitialZoom) {
                map.controller.setZoom(zoom)
                map.mapOrientation = -lastBearing
                map.controller.setCenter(here)
                hasInitialZoom = true
            } else if (location.speed >= GPS_COURSE_MIN_SPEED_MPS) {
                // Course-up: the map rotates so your heading is always "up".
                map.controller.animateTo(here, zoom, CAMERA_ANIMATION_MS, -lastBearing)
            } else {
                // Slow/stopped: the compass owns rotation (it writes the
                // orientation directly). Animating rotation here too would
                // rubber-band against it and make the heading feel laggy.
                map.controller.animateTo(here, zoom, CAMERA_ANIMATION_MS)
            }
        }
        map.invalidate()
    }

    // ---- Route line + offline corridor pre-cache ----
    LaunchedEffect(uiState.routePoints, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val pts = uiState.routePoints.map { GeoPoint(it.latitude, it.longitude) }

        // Polylines are rebuilt from scratch on every change — osmdroid
        // overlays can be silently detached (which nulls their internals),
        // so reusing an old instance is a crash waiting to happen.
        runCatching {
            ov.routeCasing?.let { map.overlays.remove(it) }
            ov.routeLine?.let { map.overlays.remove(it) }
            ov.routeCasing = null
            ov.routeLine = null
            if (pts.size >= 2) {
                val casing = ov.newRouteCasing(map).also { it.setPoints(pts) }
                val line = ov.newRouteLine(map).also { it.setPoints(pts) }
                // Keep the route underneath markers and the puck.
                map.overlays.add(0, casing)
                map.overlays.add(1, line)
                ov.routeCasing = casing
                ov.routeLine = line
            }
            map.invalidate()
        }.onFailure { Log.e(TAG, "Route overlay update failed: ${it.message}") }

        // Download the tiles along the route once, while we still have signal,
        // so dead zones on the way are already on disk.
        if (pts.size >= 2) {
            val key = "${pts.size}:${pts.first()}:${pts.last()}"
            if (key != cachedRouteKey) {
                cachedRouteKey = key
                preCacheRouteCorridor(map, pts)
            }
        }
    }

    // ---- Map style: dark for night, bright Voyager for sunlight ----
    LaunchedEffect(uiState.lightMap, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        runCatching {
            map.setTileSource(if (uiState.lightMap) CARTO_VOYAGER else CARTO_DARK)
            ov.copyright?.setTextColor(
                AColor.parseColor(if (uiState.lightMap) "#5A6270" else "#8A93A8")
            )
            map.invalidate()
        }
    }

    // ---- Progress: the route line recedes as the rider travels it ----
    // Only the remaining stretch is drawn — from the rider's snapped position
    // to the destination — so the blue line visibly shrinks behind you.
    LaunchedEffect(uiState.snapResult, uiState.routePoints, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val snap = uiState.snapResult ?: return@LaunchedEffect
        val route = uiState.routePoints
        if (route.size < 2 || ov.routeLine == null) return@LaunchedEffect
        val segIndex = snap.routeSegmentIndex.coerceIn(0, route.size - 2)
        val remaining = ArrayList<GeoPoint>(route.size - segIndex)
        remaining.add(GeoPoint(snap.closestPoint.latitude, snap.closestPoint.longitude))
        for (i in segIndex + 1 until route.size) {
            remaining.add(GeoPoint(route[i].latitude, route[i].longitude))
        }
        runCatching {
            ov.routeCasing?.setPoints(remaining)
            ov.routeLine?.setPoints(remaining)
            map.invalidate()
        }.onFailure { Log.e(TAG, "Route trim failed: ${it.message}") }
    }

    // ---- Destination marker ----
    LaunchedEffect(uiState.destinationLatitude, uiState.destinationLongitude, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val lat = uiState.destinationLatitude
        val lon = uiState.destinationLongitude
        runCatching {
            ov.destMarker?.let { map.overlays.remove(it) }
            ov.destMarker = null
            if (lat != null && lon != null) {
                val marker = Marker(map).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ov.destIcon
                    setInfoWindow(null)
                }
                ov.destMarker = marker
                map.overlays.add(marker)
            }
            map.invalidate()
        }.onFailure { Log.e(TAG, "Destination overlay update failed: ${it.message}") }
    }

    // ---- Dropped pin marker ----
    LaunchedEffect(uiState.droppedPin, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        val pin = uiState.droppedPin
        runCatching {
            ov.pinMarker?.let { map.overlays.remove(it) }
            ov.pinMarker = null
            if (pin != null) {
                val marker = Marker(map).apply {
                    position = GeoPoint(pin.latitude, pin.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ov.pinIcon
                    setInfoWindow(null)
                }
                ov.pinMarker = marker
                map.overlays.add(marker)
            }
            map.invalidate()
        }.onFailure { Log.e(TAG, "Pin overlay update failed: ${it.message}") }
    }

    // ---- Other riders ----
    LaunchedEffect(uiState.otherRiders, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val ov = rideOverlays ?: return@LaunchedEffect
        runCatching {
            ov.riderMarkers.forEach { map.overlays.remove(it) }
            ov.riderMarkers.clear()
            uiState.otherRiders.forEach { rider ->
                val marker = Marker(map).apply {
                    position = GeoPoint(rider.latitude, rider.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ov.riderIcon
                    setInfoWindow(null)
                }
                ov.riderMarkers.add(marker)
                map.overlays.add(marker)
            }
            map.invalidate()
        }.onFailure { Log.e(TAG, "Rider overlay update failed: ${it.message}") }
    }

    // ---- Route preview: fit the whole route once when a destination is set ----
    LaunchedEffect(uiState.destinationLatitude, uiState.destinationLongitude, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val dLat = uiState.destinationLatitude ?: return@LaunchedEffect
        val dLon = uiState.destinationLongitude ?: return@LaunchedEffect
        val loc = uiState.currentLocation ?: return@LaunchedEffect
        // Un-follow to show the whole route; following resumes automatically
        // once the rider starts moving.
        viewModel.onRoutePreviewShown()
        map.setMapCenterOffset(0, 0)
        map.mapOrientation = 0f
        val box = BoundingBox.fromGeoPoints(
            listOf(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLon))
        )
        runCatching { map.zoomToBoundingBox(box.increaseByScale(1.4f), true, 80) }
    }

    // osmdroid MapView lifecycle: resume/pause its tile loading with the screen.
    // Keyed on the lifecycle owner ONLY — keying on mapView would restart the
    // effect when the view is created, and the restart's dispose would call
    // onDetach() on the brand-new map, killing its tile loader and overlays.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDetach()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    // Compose detaches/reattaches views during transitions; stop
                    // osmdroid from destroying its overlays when that happens.
                    // Real teardown runs in our DisposableEffect via onDetach().
                    setDestroyMode(false)
                    setTileSource(CARTO_DARK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    // 512px retina tiles already carry the 2x detail.
                    isTilesScaledToDpi = false
                    minZoomLevel = 4.0
                    maxZoomLevel = 19.5
                    controller.setZoom(FREE_ZOOM)

                    val ov = RideOverlays(ctx, this)
                    // Long-press anywhere to drop a pin and ride to it.
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            p ?: return false
                            viewModel.dropPin(p.latitude, p.longitude)
                            return true
                        }
                    }))
                    overlays.add(ov.puck)
                    ov.copyright = CopyrightOverlay(ctx).apply {
                        setAlignBottom(true)
                        setOffset(12, 220)
                    }
                    overlays.add(ov.copyright)

                    // Touch handling, done by hand so it survives every device
                    // and lifecycle quirk (osmdroid's RotationGestureOverlay
                    // silently dies after view detach events):
                    //  - any drag/pinch stops auto-following until recenter
                    //  - a two-finger twist rotates the map
                    var rotating = false
                    var rotStartFingerAngle = 0f
                    var rotStartMapOrientation = 0f
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                if (event.pointerCount == 2) {
                                    rotating = true
                                    rotStartFingerAngle = twoFingerAngle(event)
                                    rotStartMapOrientation = mapOrientation
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                viewModel.setFollowingUser(false)
                                if (rotating && event.pointerCount >= 2) {
                                    var delta = twoFingerAngle(event) - rotStartFingerAngle
                                    while (delta > 180f) delta -= 360f
                                    while (delta < -180f) delta += 360f
                                    mapOrientation = rotStartMapOrientation + delta
                                }
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                if (event.pointerCount <= 2) rotating = false
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                rotating = false
                                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                            }
                        }
                        false
                    }

                    rideOverlays = ov
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ -> }
        )

        // Map controls: day/night style toggle (always) + recenter (when unfollowed)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = { viewModel.toggleMapStyle() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xF20C0F1D))
                    .border(1.dp, Color(0x3300E5FF), CircleShape)
            ) {
                Icon(
                    imageVector = if (uiState.lightMap) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = if (uiState.lightMap) "Switch to dark map" else "Switch to bright map for sunlight",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(24.dp)
                )
            }

            AnimatedVisibility(
                visible = !uiState.isFollowingUser,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(
                    onClick = { viewModel.setFollowingUser(true) },
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xF20C0F1D))
                        .border(1.dp, Color(0x3300E5FF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter on my location",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** All the overlays we draw on top of the base map. */
private class RideOverlays(ctx: Context, map: MapView) {
    private val density = ctx.resources.displayMetrics.density

    // Route polylines are created fresh for every route change.
    var routeCasing: Polyline? = null
    var routeLine: Polyline? = null

    fun newRouteCasing(map: MapView) = Polyline(map).apply {
        setInfoWindow(null)
        outlinePaint.apply {
            color = AColor.parseColor("#003A44")
            strokeWidth = 9f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }

    fun newRouteLine(map: MapView) = Polyline(map).apply {
        setInfoWindow(null)
        outlinePaint.apply {
            color = AColor.parseColor("#00E5FF")
            strokeWidth = 5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }

    val puck = PuckOverlay(buildPuckBitmap(density))

    val riderIcon = BitmapDrawable(
        ctx.resources,
        buildDotBitmap(density, fill = AColor.parseColor("#FFAB40"), ring = AColor.parseColor("#0C0F1D"))
    )
    val destIcon = BitmapDrawable(
        ctx.resources,
        buildDotBitmap(density, fill = AColor.parseColor("#FF5252"), ring = AColor.WHITE)
    )
    val pinIcon = BitmapDrawable(
        ctx.resources,
        buildDotBitmap(density, fill = AColor.parseColor("#00E5FF"), ring = AColor.WHITE)
    )

    var destMarker: Marker? = null
    var pinMarker: Marker? = null
    var copyright: CopyrightOverlay? = null
    val riderMarkers = mutableListOf<Marker>()
}

/** Angle of the line between the first two pointers, in degrees. */
private fun twoFingerAngle(event: MotionEvent): Float {
    val dx = (event.getX(1) - event.getX(0)).toDouble()
    val dy = (event.getY(1) - event.getY(0)).toDouble()
    return Math.toDegrees(atan2(dy, dx)).toFloat()
}

/**
 * The rider's own marker. Drawn by hand so the rotation math is explicit:
 * the overlay canvas is already rotated by the map orientation, so rotating
 * the bitmap by the travel bearing makes the arrow point at the true heading —
 * straight up while the camera is course-up, and at the correct map angle when
 * the rider pans around.
 */
private class PuckOverlay(private val bitmap: Bitmap) : Overlay() {
    @Volatile var geo: GeoPoint? = null
    @Volatile var bearingDeg: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val screenPoint = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val g = geo ?: return
        mapView.projection.toPixels(g, screenPoint)
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        canvas.save()
        canvas.rotate(bearingDeg, x, y)
        canvas.drawBitmap(bitmap, x - bitmap.width / 2f, y - bitmap.height / 2f, paint)
        canvas.restore()
    }
}

/** Brand-cyan navigation puck: dark ring, cyan disc, white heading arrow (points north). */
private fun buildPuckBitmap(density: Float): Bitmap {
    val size = (46 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = AColor.parseColor("#0C0F1D")
    c.drawCircle(cx, cx, cx * 0.98f, paint)
    paint.color = AColor.parseColor("#00E5FF")
    c.drawCircle(cx, cx, cx * 0.80f, paint)

    paint.color = AColor.WHITE
    val arrow = Path().apply {
        moveTo(cx, cx * 0.32f)              // tip (north)
        lineTo(cx * 0.62f, cx * 1.30f)      // bottom left
        lineTo(cx, cx * 1.05f)              // notch
        lineTo(cx * 1.38f, cx * 1.30f)      // bottom right
        close()
    }
    c.drawPath(arrow, paint)
    return bmp
}

/** Small round marker used for other riders and the destination. */
private fun buildDotBitmap(density: Float, fill: Int, ring: Int): Bitmap {
    val size = (22 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ring
    c.drawCircle(cx, cx, cx * 0.95f, paint)
    paint.color = fill
    c.drawCircle(cx, cx, cx * 0.70f, paint)
    return bmp
}

/**
 * Download the map tiles covering the route (zooms 12–16) while we still have
 * internet, so the navigation map keeps working through dead zones. Runs
 * silently in the background; failures are non-fatal (tiles just load live).
 */
private fun preCacheRouteCorridor(map: MapView, points: List<GeoPoint>) {
    runCatching {
        val box = BoundingBox.fromGeoPoints(points)
        val padded = BoundingBox(
            box.latNorth + 0.01, box.lonEast + 0.01,
            box.latSouth - 0.01, box.lonWest - 0.01
        )
        CacheManager(map).downloadAreaAsyncNoUI(
            map.context, padded, 12, 16,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    Log.d(TAG, "Route corridor cached for offline use")
                }
                override fun onTaskFailed(errors: Int) {
                    Log.w(TAG, "Corridor caching finished with $errors errors")
                }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(total: Int) {}
            }
        )
    }.onFailure {
        Log.w(TAG, "Corridor caching unavailable: ${it.message}")
    }
}
