package com.ridecompanion.core.location.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.database.entity.GPSPointEntity
import com.ridecompanion.core.location.sensor.CrashDetector
import com.ridecompanion.core.location.utils.BatteryOptimizer
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.network.transport.DataPacket
import com.ridecompanion.core.network.transport.PacketType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import android.os.BatteryManager
import android.content.IntentFilter
import com.ridecompanion.core.database.entity.RiderStateEntity
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class RideForegroundService : Service() {

    @Inject
    lateinit var rideDao: RideDao

    @Inject
    lateinit var transportManager: AdaptiveTransportManager

    @Inject
    lateinit var locationBroadcaster: LocationBroadcaster

    @Inject
    lateinit var navigationStatusHolder: NavigationStatusHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var crashDetector: CrashDetector? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentInterval = 10000L
    private var currentSessionId: String? = null
    private var telemetryJob: Job? = null
    @Volatile private var lastLocation: Location? = null

    companion object {
        private const val CHANNEL_ID = "ride_service_channel"
        private const val ALERT_CHANNEL_ID = "ride_alerts_channel"
        private const val NOTIFICATION_ID = 101
        private const val SOS_NOTIFICATION_ID = 102

        const val ACTION_START = "ACTION_START_RIDE"
        const val ACTION_STOP = "ACTION_STOP_RIDE"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    val sessionId = it.getStringExtra(EXTRA_SESSION_ID) ?: "unknown_session"
                    startRideService(sessionId)
                }
                ACTION_STOP -> {
                    stopRideService()
                }
            }
        }
        return START_STICKY
    }

    private fun startRideService(sessionId: String) {
        currentSessionId = sessionId
        lastGoodLocation = null
        val notification = buildNotification("Ride Active", "Tracking location and voice intercom...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        requestLocationUpdates(currentInterval)

        // React immediately when navigation starts/stops — don't wait for the
        // next (possibly 15 s away) fix to tighten the GPS interval.
        serviceScope.launch {
            navigationStatusHolder.isNavigating.collect { navigating ->
                val wanted = BatteryOptimizer.getOptimalInterval(
                    lastLocation?.speed ?: 0f, navigating
                )
                if (wanted != currentInterval) {
                    currentInterval = wanted
                    requestLocationUpdates(wanted)
                }
            }
        }

        // Clear any rider states left over from a previous ride so the map
        // never shows ghost riders who aren't in this session.
        serviceScope.launch {
            rideDao.clearRiderStates()
        }

        // Listen for incoming location and telemetry updates from other riders
        telemetryJob = serviceScope.launch {
            transportManager.incomingPackets.collect { packet ->
                if (packet.packetType == PacketType.SOS) {
                    val reason = try {
                        JSONObject(String(packet.payload)).optString("reason", "SOS")
                    } catch (e: Exception) { "SOS" }
                    postSosNotification(packet.senderId, reason)
                }
                if (packet.packetType == PacketType.LOCATION) {
                    try {
                        val json = JSONObject(String(packet.payload))
                        val riderState = RiderStateEntity(
                            userId = packet.senderId,
                            latitude = json.getDouble("lat"),
                            longitude = json.getDouble("lon"),
                            heading = json.getDouble("heading").toFloat(),
                            speed = json.getDouble("speed").toFloat(),
                            batteryPercentage = json.getInt("battery"),
                            networkType = json.getString("netType"),
                            networkSignalStrength = json.getInt("netStrength"),
                            locationAccuracy = json.getDouble("accuracy").toFloat(),
                            lastUpdated = json.getLong("updated"),
                            movementState = json.getString("moveState")
                        )
                        rideDao.insertRiderState(riderState)
                    } catch (e: Exception) {
                        android.util.Log.e("ForegroundService", "Failed to parse telemetry packet", e)
                    }
                }
            }
        }

        crashDetector = CrashDetector(this) {
            serviceScope.launch {
                val loc = lastLocation
                val payload = JSONObject().apply {
                    put("sender", transportManager.localRiderName)
                    put("reason", "CRASH")
                    put("lat", loc?.latitude ?: 0.0)
                    put("lon", loc?.longitude ?: 0.0)
                }
                val sosPacket = DataPacket(
                    senderId = "self",
                    sessionId = sessionId,
                    packetType = PacketType.SOS,
                    payload = payload.toString().toByteArray()
                )
                transportManager.sendData(sosPacket)
            }
        }.apply {
            start()
        }
    }

    private fun stopRideService() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        telemetryJob?.cancel()
        telemetryJob = null
        crashDetector?.stop()
        crashDetector = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates(intervalMs: Long) {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        val navigating = navigationStatusHolder.isNavigating.value
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(
                BatteryOptimizer.getFastestInterval(lastLocation?.speed ?: 0f, navigating)
            )
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    onNewLocationReceived(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {}
    }

    /**
     * Clean up a raw GPS fix before anyone uses it:
     *  - drop wildly inaccurate fixes (cell/wifi fallback) that teleport the rider
     *  - drop physically impossible jumps
     *  - derive bearing and speed from actual movement when the GPS chip omits
     *    them, so the heading arrow always tracks the real travel direction.
     * Returns null when the fix should be ignored entirely.
     */
    private var lastGoodLocation: Location? = null

    private fun sanitizeLocation(raw: Location): Location? {
        val prev = lastGoodLocation

        // A very inaccurate fix right after a good one is noise, not movement.
        if (prev != null && raw.hasAccuracy() && raw.accuracy > 60f &&
            raw.time - prev.time < 15_000
        ) {
            return null
        }

        if (prev != null) {
            val dtSeconds = ((raw.time - prev.time).coerceAtLeast(1L)) / 1000.0
            val distMeters = prev.distanceTo(raw).toDouble()
            val impliedSpeed = distMeters / dtSeconds

            // Faster than 45 m/s (162 km/h) on a bike = GPS teleport. Drop it.
            if (impliedSpeed > 45.0) return null

            if (distMeters >= 3.0) {
                // Movement-derived heading is ground truth; GPS bearing is often
                // missing or stale at low speeds.
                if (!raw.hasBearing() || raw.speed < 1.0f) {
                    raw.bearing = (prev.bearingTo(raw) + 360f) % 360f
                }
                if (!raw.hasSpeed() || raw.speed == 0f) {
                    raw.speed = impliedSpeed.toFloat()
                }
            }
        }
        lastGoodLocation = raw
        return raw
    }

    private fun onNewLocationReceived(rawLocation: Location) {
        val sessionId = currentSessionId ?: return
        val location = sanitizeLocation(rawLocation) ?: return
        lastLocation = location

        serviceScope.launch {
            // Broadcast to local UI (MapViewModel etc.)
            locationBroadcaster.broadcast(location)

            // Save in local GPS trace history
            val point = GPSPointEntity(
                sessionId = sessionId,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                heading = location.bearing,
                timestamp = location.time,
                isSynced = false
            )
            rideDao.insertGPSPoint(point)

            // Broadcast telemetry to other riders in the session
            try {
                val battery = getBatteryPercentage()
                val moveState = if (location.speed > 0.5f) "RIDING" else "STOPPED"
                val (netType, netStrength) = getNetworkInfo()
                val telemetryJson = JSONObject().apply {
                    put("sender", transportManager.localRiderName)
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    put("heading", location.bearing.toDouble())
                    put("speed", location.speed.toDouble())
                    put("battery", battery)
                    put("netType", netType)
                    put("netStrength", netStrength)
                    put("accuracy", location.accuracy.toDouble())
                    put("updated", System.currentTimeMillis())
                    put("moveState", moveState)
                }

                val packet = DataPacket(
                    senderId = "self",
                    sessionId = sessionId,
                    packetType = PacketType.LOCATION,
                    payload = telemetryJson.toString().toByteArray()
                )
                transportManager.sendData(packet)
            } catch (e: Exception) {
                android.util.Log.e("ForegroundService", "Failed to package and send telemetry", e)
            }
        }

        val nextInterval = BatteryOptimizer.getOptimalInterval(
            location.speed, navigationStatusHolder.isNavigating.value
        )
        if (nextInterval != currentInterval) {
            currentInterval = nextInterval
            requestLocationUpdates(currentInterval)
        }
    }

    /** Real network type (WIFI/CELLULAR/OFFLINE) and a coarse 0-4 signal strength. */
    private fun getNetworkInfo(): Pair<String, Int> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return "OFFLINE" to 0
        val caps = cm.getNetworkCapabilities(network) ?: return "OFFLINE" to 0
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI" to wifiSignalLevel()
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR" to 3
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "CELLULAR" to 2
            else -> "OFFLINE" to 0
        }
    }

    private fun wifiSignalLevel(): Int {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val rssi = wifi.connectionInfo.rssi
            android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5) // 0..4
        } catch (e: Exception) {
            3
        }
    }

    private fun getBatteryPercentage(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100).toInt()
        } else {
            100
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideCompanion::GPSWakeLock").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Ride Companion Active Session",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            // High-importance channel so crash/SOS alerts pop up and vibrate.
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Ride Safety Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                }
            )
        }
    }

    private fun postSosNotification(riderId: String, reason: String) {
        val title = if (reason == "CRASH") "⚠️ Possible crash detected" else "🆘 SOS"
        val text = "$riderId may need help — open Ride Companion."
        val intent = Intent(this, Class.forName("com.ridecompanion.MainActivity"))
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(SOS_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val notificationIntent = Intent(this, Class.forName("com.ridecompanion.MainActivity"))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
