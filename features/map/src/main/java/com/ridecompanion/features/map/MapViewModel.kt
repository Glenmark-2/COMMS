package com.ridecompanion.features.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.database.entity.RideSummaryEntity
import com.ridecompanion.core.location.sensor.CompassHeadingProvider
import com.ridecompanion.core.navigation.engine.NavigationEngine
import com.ridecompanion.core.navigation.engine.RoutePoint
import com.ridecompanion.core.navigation.engine.SnapResult
import com.ridecompanion.core.navigation.engine.TurnDirection
import com.ridecompanion.core.navigation.engine.TurnInstruction
import com.ridecompanion.core.voice.manager.VoiceGuidanceManager
import com.ridecompanion.core.common.model.PlaceResult
import com.ridecompanion.core.common.model.RoutePath
import com.ridecompanion.core.common.model.RouteProfile
import com.ridecompanion.core.common.model.RouteTurn
import com.ridecompanion.core.common.model.TurnKind
import com.ridecompanion.core.common.model.RiderState
import com.ridecompanion.core.common.model.NetworkType
import com.ridecompanion.core.common.model.MovementState
import com.ridecompanion.core.location.services.LocationBroadcaster
import com.ridecompanion.core.location.services.NavigationStatusHolder
import com.ridecompanion.core.network.repository.PlacesRepository
import com.ridecompanion.core.network.repository.RoutingRepository
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.network.transport.DataPacket
import com.ridecompanion.core.network.transport.PacketType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class MapUiState(
    val currentLocation: Location? = null,
    val currentRiderState: RiderState? = null,
    val otherRiders: List<RiderState> = emptyList(),
    val routePoints: List<RoutePoint> = emptyList(),
    val snapResult: SnapResult? = null,
    val isOffRoute: Boolean = false,
    val isFollowingUser: Boolean = true,
    // Destination & route info
    val destinationName: String? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val routeDistanceMeters: Double = 0.0,
    val routeDurationSeconds: Double = 0.0,
    val routeAscentMeters: Double = 0.0,
    val routeProfile: RouteProfile = RouteProfile.BIKE,
    // Search
    val searchResults: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val isRoutingInProgress: Boolean = false,
    val routeError: String? = null,
    // Navigation progress
    val remainingDistanceMeters: Double = 0.0,
    val nextTurnDirection: TurnDirection? = null,
    val nextTurnDistanceMeters: Double = 0.0,
    /** Road the next turn leads onto, when the routing engine knows it. */
    val nextTurnStreetName: String? = null,
    // The turn after the next one — "then turn left" preview.
    val thenTurnDirection: TurnDirection? = null,
    // Recent destinations, shown before the rider types a query.
    val recentDestinations: List<PlaceResult> = emptyList(),
    // A long-pressed map point the rider may want to ride to.
    val droppedPin: PlaceResult? = null,
    // Live ride statistics
    val rideStartTimeMillis: Long = 0L,
    val rideDistanceMeters: Double = 0.0,
    val maxSpeedMps: Float = 0f,
    /** Jitter-free speed for ETA math (exponential moving average). */
    val smoothedSpeedMps: Float = 0f,
    // Safety
    val activeSos: SosAlert? = null,
    // Set when a ride is finished, for the summary screen
    val lastRideSummary: RideSummaryEntity? = null
)

data class SosAlert(
    val riderId: String,
    val latitude: Double,
    val longitude: Double,
    val reason: String,
    val timestamp: Long
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val rideDao: RideDao,
    private val locationBroadcaster: LocationBroadcaster,
    private val placesRepository: PlacesRepository,
    private val routingRepository: RoutingRepository,
    private val transportManager: AdaptiveTransportManager,
    private val voiceGuidanceManager: VoiceGuidanceManager,
    private val navigationStatusHolder: NavigationStatusHolder,
    private val recentDestinationsStore: RecentDestinationsStore,
    private val compassHeadingProvider: CompassHeadingProvider
) : ViewModel() {

    // Turn-by-turn guidance state
    private var turns: List<TurnInstruction> = emptyList()
    private val announcedFar = mutableSetOf<Int>()
    private val announcedNear = mutableSetOf<Int>()
    private var announcedArrival = false

    // Progress hint for route snapping: the segment the rider was on at the
    // previous fix. Keeps progress monotonic on routes that cross themselves.
    private var lastSnapSegment = -1

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Device-facing heading from the compass, for the map to fuse with GPS course. */
    val compassHeading: StateFlow<Float?> = compassHeadingProvider.headingDegrees

    // ---- Automatic rerouting ----
    // Reroute after this many consecutive off-route fixes (debounces GPS noise),
    // with a cooldown so we never hammer the routing service.
    private var consecutiveOffRouteCount = 0
    private var lastRerouteAttemptMillis = 0L
    private val offRouteFixesBeforeReroute = 3
    private val rerouteCooldownMillis = 20_000L

    // Set when the camera un-followed for a route preview: resume following
    // automatically as soon as the rider starts moving.
    private var resumeFollowOnMove = false

    // True when this device set the current destination — it is then responsible
    // for re-sharing the route to riders who join later.
    private var isRouteOwner = false

    init {
        observeRiderStates()
        observeLocationUpdates()
        observeIncomingRoutes()
        observeParticipantJoins()
        _uiState.update { it.copy(recentDestinations = recentDestinationsStore.load()) }
    }

    /** The map screen drives the compass with its own visibility. */
    fun setCompassActive(active: Boolean) {
        if (active) compassHeadingProvider.start() else compassHeadingProvider.stop()
    }

    private var previousLocation: Location? = null

    private fun observeLocationUpdates() {
        viewModelScope.launch {
            locationBroadcaster.locationUpdates.collect { location ->
                compassHeadingProvider.updateDeclination(location)

                // ---- Ride statistics ----
                val prev = previousLocation
                val addedDistance = if (prev != null) {
                    val d = NavigationEngine.calculateDistanceMeters(
                        prev.latitude, prev.longitude, location.latitude, location.longitude
                    )
                    // Ignore GPS jitter (tiny moves) and implausible jumps.
                    if (d in 1.0..500.0) d else 0.0
                } else 0.0
                previousLocation = location

                _uiState.update { state ->
                    // EMA smooths GPS speed spikes; decays toward zero while stopped
                    // so the ETA doesn't freeze on the last moving speed.
                    val smoothed = if (location.speed > 0.3f) {
                        state.smoothedSpeedMps * 0.7f + location.speed * 0.3f
                    } else {
                        state.smoothedSpeedMps * 0.8f
                    }
                    state.copy(
                        currentLocation = location,
                        rideStartTimeMillis = if (state.rideStartTimeMillis == 0L)
                            System.currentTimeMillis() else state.rideStartTimeMillis,
                        rideDistanceMeters = state.rideDistanceMeters + addedDistance,
                        maxSpeedMps = maxOf(state.maxSpeedMps, location.speed),
                        smoothedSpeedMps = smoothed
                    )
                }

                // ---- Camera: resume following after a route preview once moving ----
                if (resumeFollowOnMove && location.speed >= 2.0f) {
                    resumeFollowOnMove = false
                    _uiState.update { it.copy(isFollowingUser = true) }
                }

                // ---- Navigation: snap to route + remaining distance ----
                val route = _uiState.value.routePoints
                if (route.isNotEmpty()) {
                    val snap = NavigationEngine.snapToRoute(
                        location.latitude, location.longitude, route, lastSnapSegment
                    )
                    lastSnapSegment = snap?.routeSegmentIndex ?: lastSnapSegment
                    // GPS accuracy varies wildly under trees / between buildings —
                    // don't call "off route" when the fix itself is that uncertain.
                    val offRouteThreshold = maxOf(
                        30.0,
                        if (location.hasAccuracy()) location.accuracy * 2.0 else 0.0
                    )
                    val isOff = (snap?.distanceToRouteMeters ?: 0.0) > offRouteThreshold
                    val remaining = snap?.let { NavigationEngine.remainingDistanceMeters(route, it) } ?: 0.0
                    val nextTurn = snap?.let { s -> turns.firstOrNull { it.routeIndex > s.routeSegmentIndex } }
                    val nextTurnDist = if (snap != null && nextTurn != null)
                        NavigationEngine.distanceAlongRoute(route, snap, nextTurn.routeIndex) else 0.0
                    val thenTurn = nextTurn?.let { n -> turns.firstOrNull { it.routeIndex > n.routeIndex } }
                    _uiState.update {
                        it.copy(
                            snapResult = snap,
                            isOffRoute = isOff,
                            remainingDistanceMeters = remaining,
                            nextTurnDirection = nextTurn?.direction,
                            nextTurnDistanceMeters = nextTurnDist,
                            nextTurnStreetName = nextTurn?.streetName,
                            thenTurnDirection = thenTurn?.direction
                        )
                    }
                    if (snap != null) announceGuidance(route, snap, remaining)
                    maybeReroute(location, isOff)
                }
            }
        }
    }

    /**
     * Recalculate the route from the rider's current position when they stay
     * off-route — the behaviour every turn-by-turn navigator has. Debounced by
     * consecutive off-route fixes and a cooldown; needs internet, so offline
     * riders keep the rejoin banner instead.
     */
    private fun maybeReroute(location: Location, isOff: Boolean) {
        if (!isOff) {
            consecutiveOffRouteCount = 0
            return
        }
        consecutiveOffRouteCount++

        val state = _uiState.value
        val destLat = state.destinationLatitude ?: return
        val destLon = state.destinationLongitude ?: return
        val now = System.currentTimeMillis()
        if (consecutiveOffRouteCount < offRouteFixesBeforeReroute) return
        if (state.isRoutingInProgress) return
        if (now - lastRerouteAttemptMillis < rerouteCooldownMillis) return
        lastRerouteAttemptMillis = now

        _uiState.update { it.copy(isRoutingInProgress = true) }
        viewModelScope.launch {
            val path = routingRepository.route(
                startLatitude = location.latitude,
                startLongitude = location.longitude,
                destinationLatitude = destLat,
                destinationLongitude = destLon,
                destinationName = state.destinationName ?: "Destination"
            )
            if (path != null) {
                consecutiveOffRouteCount = 0
                applyRoutePath(path)
                voiceGuidanceManager.speak("Route recalculated.")
                if (isRouteOwner) broadcastCurrentRoute()
            } else {
                // Offline or routing failed — keep the rejoin banner, try again
                // after the cooldown.
                _uiState.update { it.copy(isRoutingInProgress = false) }
            }
        }
    }

    private fun observeRiderStates() {
        viewModelScope.launch {
            rideDao.getAllRiderStates().collect { entities ->
                val ridersList = entities.map { entity ->
                    RiderState(
                        userId = entity.userId,
                        latitude = entity.latitude,
                        longitude = entity.longitude,
                        heading = entity.heading,
                        speed = entity.speed,
                        batteryPercentage = entity.batteryPercentage,
                        networkType = NetworkType.valueOf(entity.networkType),
                        networkSignalStrength = entity.networkSignalStrength,
                        locationAccuracy = entity.locationAccuracy,
                        lastUpdated = entity.lastUpdated,
                        movementState = MovementState.valueOf(entity.movementState)
                    )
                }
                _uiState.update { it.copy(otherRiders = ridersList) }
            }
        }
    }

    private fun observeIncomingRoutes() {
        viewModelScope.launch {
            transportManager.incomingPackets.collect { packet ->
                when (packet.packetType) {
                    PacketType.ROUTE -> applyReceivedRoute(String(packet.payload))
                    PacketType.SOS -> applyReceivedSos(packet.senderId, String(packet.payload))
                    else -> {}
                }
            }
        }
    }

    private fun applyReceivedSos(senderId: String, payload: String) {
        try {
            val json = JSONObject(payload)
            _uiState.update {
                it.copy(
                    activeSos = SosAlert(
                        riderId = senderId,
                        latitude = json.optDouble("lat", 0.0),
                        longitude = json.optDouble("lon", 0.0),
                        reason = json.optString("reason", "SOS"),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            // Even a malformed SOS should still raise an alarm.
            _uiState.update {
                it.copy(activeSos = SosAlert(senderId, 0.0, 0.0, "SOS", System.currentTimeMillis()))
            }
        }
    }

    /** Manually broadcast an SOS with the rider's current position. */
    fun sendSos() {
        val location = _uiState.value.currentLocation
        val json = JSONObject().apply {
            put("sender", transportManager.localRiderName)
            put("reason", "MANUAL")
            put("lat", location?.latitude ?: 0.0)
            put("lon", location?.longitude ?: 0.0)
        }
        transportManager.sendData(
            DataPacket(
                senderId = "self",
                sessionId = "",
                packetType = PacketType.SOS,
                payload = json.toString().toByteArray()
            )
        )
    }

    fun dismissSos() {
        _uiState.update { it.copy(activeSos = null) }
    }

    /** Straight-line distance from this rider to the SOS location, in meters. */
    fun distanceToSos(sos: SosAlert): Double? {
        val loc = _uiState.value.currentLocation ?: return null
        if (sos.latitude == 0.0 && sos.longitude == 0.0) return null
        return NavigationEngine.calculateDistanceMeters(
            loc.latitude, loc.longitude, sos.latitude, sos.longitude
        )
    }

    /** Straight-line distance from this rider to a coordinate, in meters. */
    fun distanceToPoint(latitude: Double, longitude: Double): Double? {
        val loc = _uiState.value.currentLocation ?: return null
        return NavigationEngine.calculateDistanceMeters(loc.latitude, loc.longitude, latitude, longitude)
    }

    private fun observeParticipantJoins() {
        viewModelScope.launch {
            transportManager.riderEvents.collect { event ->
                // Re-share so a rider who joined after the route was set gets it too.
                if (event.joined && isRouteOwner && _uiState.value.routePoints.isNotEmpty()) {
                    broadcastCurrentRoute()
                }
            }
        }
    }

    // ---- Destination search ----

    fun searchPlaces(query: String) {
        // One character matches everything and nothing useful — wait for two.
        if (query.isBlank() || query.trim().length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val location = _uiState.value.currentLocation
            val results = placesRepository.search(
                query = query,
                nearLatitude = location?.latitude,
                nearLongitude = location?.longitude
            )
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchResults = emptyList(), isSearching = false, routeError = null) }
    }

    // ---- Dropped pins (long-press on the map) ----

    /** Long-press: drop a pin immediately, then refine its name via geocoder. */
    fun dropPin(latitude: Double, longitude: Double) {
        val placeholder = PlaceResult(
            name = "Dropped pin",
            description = "%.5f, %.5f".format(latitude, longitude),
            latitude = latitude,
            longitude = longitude
        )
        _uiState.update { it.copy(droppedPin = placeholder) }
        viewModelScope.launch {
            val named = placesRepository.reverse(latitude, longitude) ?: return@launch
            _uiState.update { state ->
                // Only apply if this pin is still the active one.
                val current = state.droppedPin
                if (current != null &&
                    current.latitude == latitude && current.longitude == longitude
                ) {
                    state.copy(
                        droppedPin = placeholder.copy(
                            name = named.name,
                            description = named.description.ifBlank { placeholder.description }
                        )
                    )
                } else state
            }
        }
    }

    fun dismissPin() {
        _uiState.update { it.copy(droppedPin = null) }
    }

    /** Route to the currently dropped pin. */
    fun rideToPin() {
        val pin = _uiState.value.droppedPin ?: return
        _uiState.update { it.copy(droppedPin = null) }
        selectDestination(pin)
    }

    /** Pick a searched place as the destination, compute a bike route, and share it. */
    fun selectDestination(place: PlaceResult) {
        val location = _uiState.value.currentLocation
        if (location == null) {
            _uiState.update { it.copy(routeError = "Waiting for GPS fix — try again in a moment.") }
            return
        }
        recentDestinationsStore.add(place)
        _uiState.update { it.copy(recentDestinations = recentDestinationsStore.load()) }
        _uiState.update {
            it.copy(isRoutingInProgress = true, routeError = null, searchResults = emptyList())
        }
        viewModelScope.launch {
            val path = routingRepository.route(
                startLatitude = location.latitude,
                startLongitude = location.longitude,
                destinationLatitude = place.latitude,
                destinationLongitude = place.longitude,
                destinationName = place.name
            )
            if (path == null) {
                _uiState.update {
                    it.copy(
                        isRoutingInProgress = false,
                        routeError = "No route found to ${place.name}. Check your connection and try again."
                    )
                }
                return@launch
            }
            applyRoutePath(path)
            when (path.profile) {
                RouteProfile.FOOT -> voiceGuidanceManager.speak(
                    "No cycling route found. Following a walking route instead."
                )
                RouteProfile.CAR -> voiceGuidanceManager.speak(
                    "No cycling route found. Following a road route. Ride carefully."
                )
                RouteProfile.BIKE -> {}
            }
            isRouteOwner = true
            broadcastCurrentRoute()
        }
    }

    private fun resetGuidanceFor(routePoints: List<RoutePoint>, routeTurns: List<TurnInstruction>) {
        turns = routeTurns.ifEmpty { NavigationEngine.computeTurns(routePoints) }
        announcedFar.clear()
        announcedNear.clear()
        announcedArrival = false
        lastSnapSegment = -1
        // Tell the location service to switch to fast (1 s) GPS updates.
        navigationStatusHolder.setNavigating(true)
    }

    private fun announceGuidance(
        route: List<RoutePoint>,
        snap: SnapResult,
        remaining: Double
    ) {
        // Arrival
        if (!announcedArrival && remaining in 1.0..40.0) {
            announcedArrival = true
            voiceGuidanceManager.speak("You have arrived at your destination.")
            return
        }
        // Next turn ahead of our current position
        val nextTurn = turns.firstOrNull { it.routeIndex > snap.routeSegmentIndex } ?: return
        val distance = NavigationEngine.distanceAlongRoute(route, snap, nextTurn.routeIndex)
        val phrase = spokenTurn(nextTurn)
        when {
            distance <= 30.0 && nextTurn.routeIndex !in announcedNear -> {
                announcedNear.add(nextTurn.routeIndex)
                announcedFar.add(nextTurn.routeIndex)
                voiceGuidanceManager.speak("$phrase now.")
            }
            distance <= 180.0 && nextTurn.routeIndex !in announcedFar -> {
                announcedFar.add(nextTurn.routeIndex)
                val rounded = (Math.round(distance / 10.0) * 10).toInt()
                voiceGuidanceManager.speak("In $rounded meters, $phrase.")
            }
        }
    }

    /** What the voice says for a turn — includes the road name when known. */
    private fun spokenTurn(turn: TurnInstruction): String {
        if (turn.direction == TurnDirection.ROUNDABOUT) {
            // Valhalla's sentence carries the exit number ("take the 2nd exit").
            return turn.instruction?.trimEnd('.') ?: "enter the roundabout"
        }
        val base = turnPhrase(turn.direction)
        return turn.streetName?.let { "$base onto $it" } ?: base
    }

    private fun turnPhrase(direction: TurnDirection): String = when (direction) {
        TurnDirection.LEFT -> "turn left"
        TurnDirection.SLIGHT_LEFT -> "turn slightly left"
        TurnDirection.SHARP_LEFT -> "make a sharp left"
        TurnDirection.RIGHT -> "turn right"
        TurnDirection.SLIGHT_RIGHT -> "turn slightly right"
        TurnDirection.SHARP_RIGHT -> "make a sharp right"
        TurnDirection.UTURN -> "make a U-turn"
        TurnDirection.ROUNDABOUT -> "enter the roundabout"
        TurnDirection.STRAIGHT -> "continue straight"
    }

    private fun turnKindToDirection(kind: TurnKind): TurnDirection = when (kind) {
        TurnKind.STRAIGHT -> TurnDirection.STRAIGHT
        TurnKind.SLIGHT_LEFT -> TurnDirection.SLIGHT_LEFT
        TurnKind.LEFT -> TurnDirection.LEFT
        TurnKind.SHARP_LEFT -> TurnDirection.SHARP_LEFT
        TurnKind.SLIGHT_RIGHT -> TurnDirection.SLIGHT_RIGHT
        TurnKind.RIGHT -> TurnDirection.RIGHT
        TurnKind.SHARP_RIGHT -> TurnDirection.SHARP_RIGHT
        TurnKind.UTURN -> TurnDirection.UTURN
        TurnKind.ROUNDABOUT -> TurnDirection.ROUNDABOUT
    }

    private fun applyRoutePath(path: RoutePath) {
        val routePoints = path.points.mapIndexed { i, p ->
            RoutePoint(p.latitude, p.longitude, p.altitude, i)
        }
        val routeTurns = path.turns.map { t ->
            TurnInstruction(
                routeIndex = t.pointIndex,
                latitude = t.latitude,
                longitude = t.longitude,
                direction = turnKindToDirection(t.kind),
                streetName = t.streetName,
                instruction = t.instruction
            )
        }
        resetGuidanceFor(routePoints, routeTurns)
        _uiState.update {
            it.copy(
                routePoints = routePoints,
                destinationName = path.destinationName,
                destinationLatitude = path.destinationLatitude,
                destinationLongitude = path.destinationLongitude,
                routeDistanceMeters = path.distanceMeters,
                routeDurationSeconds = path.durationSeconds,
                routeAscentMeters = path.ascentMeters,
                routeProfile = path.profile,
                isRoutingInProgress = false,
                routeError = null
            )
        }
    }

    /** Clear the destination for everyone is local-only; just clears this device. */
    fun clearDestination() {
        isRouteOwner = false
        navigationStatusHolder.setNavigating(false)
        turns = emptyList()
        announcedFar.clear()
        announcedNear.clear()
        announcedArrival = false
        lastSnapSegment = -1
        _uiState.update {
            it.copy(
                routePoints = emptyList(),
                destinationName = null,
                destinationLatitude = null,
                destinationLongitude = null,
                routeDistanceMeters = 0.0,
                routeDurationSeconds = 0.0,
                routeAscentMeters = 0.0,
                routeProfile = RouteProfile.BIKE,
                snapResult = null,
                isOffRoute = false,
                remainingDistanceMeters = 0.0,
                nextTurnDirection = null,
                nextTurnDistanceMeters = 0.0,
                nextTurnStreetName = null,
                thenTurnDirection = null
            )
        }
    }

    // ---- Route sharing over the transport ----

    private fun broadcastCurrentRoute() {
        val state = _uiState.value
        if (state.routePoints.isEmpty()) return

        val keptIndices = downsampleIndices(state.routePoints.size, MAX_SHARED_POINTS)
        val ptsArray = JSONArray()
        keptIndices.forEach { i ->
            val p = state.routePoints[i]
            ptsArray.put(JSONArray().apply {
                put(round5(p.latitude))
                put(round5(p.longitude))
            })
        }
        // Turns are shared too, so joiners get the same street-named guidance.
        // Indices are remapped onto the downsampled polyline.
        val turnsArray = JSONArray()
        turns.forEach { t ->
            turnsArray.put(JSONArray().apply {
                put(nearestKeptPosition(keptIndices, t.routeIndex))
                put(round5(t.latitude))
                put(round5(t.longitude))
                put(t.direction.name)
                put(t.streetName ?: "")
                put(t.instruction ?: "")
            })
        }
        val json = JSONObject().apply {
            put("name", state.destinationName ?: "Destination")
            put("dlat", state.destinationLatitude ?: 0.0)
            put("dlon", state.destinationLongitude ?: 0.0)
            put("dist", state.routeDistanceMeters)
            put("time", state.routeDurationSeconds)
            put("asc", state.routeAscentMeters)
            put("prof", state.routeProfile.name)
            put("pts", ptsArray)
            put("turns", turnsArray)
        }
        val packet = DataPacket(
            senderId = "self",
            sessionId = "",
            packetType = PacketType.ROUTE,
            payload = json.toString().toByteArray()
        )
        transportManager.sendData(packet)
    }

    private fun applyReceivedRoute(payload: String) {
        try {
            val json = JSONObject(payload)
            val ptsArray = json.getJSONArray("pts")
            val routePoints = ArrayList<RoutePoint>(ptsArray.length())
            for (i in 0 until ptsArray.length()) {
                val pair = ptsArray.getJSONArray(i)
                routePoints.add(RoutePoint(pair.getDouble(0), pair.getDouble(1), index = i))
            }
            if (routePoints.size < 2) return

            val receivedTurns = ArrayList<TurnInstruction>()
            json.optJSONArray("turns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONArray(i) ?: continue
                    val direction = runCatching { TurnDirection.valueOf(t.getString(3)) }
                        .getOrNull() ?: continue
                    receivedTurns.add(
                        TurnInstruction(
                            routeIndex = t.getInt(0).coerceIn(0, routePoints.size - 1),
                            latitude = t.getDouble(1),
                            longitude = t.getDouble(2),
                            direction = direction,
                            streetName = t.optString(4).ifBlank { null },
                            instruction = t.optString(5).ifBlank { null }
                        )
                    )
                }
            }

            resetGuidanceFor(routePoints, receivedTurns)
            // A received route means someone else owns it.
            isRouteOwner = false
            val profile = runCatching { RouteProfile.valueOf(json.optString("prof")) }
                .getOrDefault(RouteProfile.BIKE)
            _uiState.update {
                it.copy(
                    routePoints = routePoints,
                    destinationName = json.optString("name", "Destination"),
                    destinationLatitude = json.optDouble("dlat", 0.0),
                    destinationLongitude = json.optDouble("dlon", 0.0),
                    routeDistanceMeters = json.optDouble("dist", 0.0),
                    routeDurationSeconds = json.optDouble("time", 0.0),
                    routeAscentMeters = json.optDouble("asc", 0.0),
                    routeProfile = profile,
                    routeError = null
                )
            }
        } catch (e: Exception) {
            // Malformed route packet — ignore.
        }
    }

    fun loadGPXRoute(gpxXmlContent: String) {
        viewModelScope.launch {
            val parsedRoute = NavigationEngine.parseGPX(gpxXmlContent)
            if (parsedRoute.isNotEmpty()) {
                resetGuidanceFor(parsedRoute, emptyList())
                _uiState.update { it.copy(routePoints = parsedRoute) }
                isRouteOwner = true
                broadcastCurrentRoute()
            }
        }
    }

    fun setFollowingUser(following: Boolean) {
        // A deliberate un-follow (user pan) cancels any pending auto-refollow.
        if (!following) resumeFollowOnMove = false
        _uiState.update { it.copy(isFollowingUser = following) }
    }

    /**
     * Called when the map un-follows to preview a new route. The camera goes
     * back to following automatically once the rider starts moving.
     */
    fun onRoutePreviewShown() {
        resumeFollowOnMove = true
        _uiState.update { it.copy(isFollowingUser = false) }
    }

    /**
     * Finish the current ride: persist a summary (if the ride was meaningful) and
     * reset all live ride + route state for the next one.
     */
    fun finishRide() {
        val s = _uiState.value
        val start = if (s.rideStartTimeMillis > 0) s.rideStartTimeMillis else System.currentTimeMillis()
        val end = System.currentTimeMillis()
        val duration = end - start
        val meaningful = s.rideDistanceMeters >= 50.0 || duration >= 60_000L
        val summary = if (meaningful) {
            val avg = if (duration > 0) (s.rideDistanceMeters / (duration / 1000.0)).toFloat() else 0f
            RideSummaryEntity(
                name = rideName(start),
                startTime = start,
                endTime = end,
                distanceMeters = s.rideDistanceMeters,
                durationMillis = duration,
                avgSpeedMps = avg,
                maxSpeedMps = s.maxSpeedMps
            ).also { entity -> viewModelScope.launch { rideDao.insertRideSummary(entity) } }
        } else null

        previousLocation = null
        turns = emptyList()
        announcedFar.clear()
        announcedNear.clear()
        announcedArrival = false
        lastSnapSegment = -1
        isRouteOwner = false
        navigationStatusHolder.setNavigating(false)
        _uiState.update {
            it.copy(
                lastRideSummary = summary,
                rideStartTimeMillis = 0L,
                rideDistanceMeters = 0.0,
                maxSpeedMps = 0f,
                smoothedSpeedMps = 0f,
                routePoints = emptyList(),
                destinationName = null,
                destinationLatitude = null,
                destinationLongitude = null,
                routeProfile = RouteProfile.BIKE,
                remainingDistanceMeters = 0.0,
                nextTurnDirection = null,
                nextTurnDistanceMeters = 0.0,
                nextTurnStreetName = null,
                thenTurnDirection = null,
                snapResult = null,
                isOffRoute = false,
                droppedPin = null
            )
        }
    }

    fun clearLastSummary() {
        _uiState.update { it.copy(lastRideSummary = null) }
    }

    private fun rideName(startMillis: Long): String {
        val format = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(startMillis))
    }

    /** Original indices kept when thinning a polyline to at most [max] points. */
    private fun downsampleIndices(size: Int, max: Int): List<Int> {
        if (size <= max) return (0 until size).toList()
        val step = size.toDouble() / max
        val result = ArrayList<Int>(max + 1)
        var i = 0.0
        while (i < size) {
            result.add(i.toInt())
            i += step
        }
        // Always keep the final point so the line reaches the destination.
        if (result.last() != size - 1) result.add(size - 1)
        return result
    }

    /** Position in the kept-indices list closest to an original vertex index. */
    private fun nearestKeptPosition(keptIndices: List<Int>, originalIndex: Int): Int {
        val search = keptIndices.binarySearch(originalIndex)
        if (search >= 0) return search
        val insertion = -search - 1
        if (insertion == 0) return 0
        if (insertion >= keptIndices.size) return keptIndices.size - 1
        val before = keptIndices[insertion - 1]
        val after = keptIndices[insertion]
        return if (originalIndex - before <= after - originalIndex) insertion - 1 else insertion
    }

    private fun round5(value: Double): Double = Math.round(value * 1e5) / 1e5

    companion object {
        private const val MAX_SHARED_POINTS = 350
    }
}
