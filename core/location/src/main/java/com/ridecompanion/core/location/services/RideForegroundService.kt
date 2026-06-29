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
import javax.inject.Inject

@AndroidEntryPoint
class RideForegroundService : Service() {

    @Inject
    lateinit var rideDao: RideDao

    @Inject
    lateinit var transportManager: AdaptiveTransportManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var crashDetector: CrashDetector? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentInterval = 10000L
    private var currentSessionId: String? = null
    
    companion object {
        private const val CHANNEL_ID = "ride_service_channel"
        private const val NOTIFICATION_ID = 101
        
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

        crashDetector = CrashDetector(this) {
            serviceScope.launch {
                val sosPacket = DataPacket(
                    senderId = "self",
                    sessionId = sessionId,
                    packetType = PacketType.SOS,
                    payload = "SOS_CRASH_DETECTED".toByteArray()
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

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
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

    private fun onNewLocationReceived(location: Location) {
        val sessionId = currentSessionId ?: return
        
        serviceScope.launch {
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
        }

        val nextInterval = BatteryOptimizer.getOptimalInterval(location.speed)
        if (nextInterval != currentInterval) {
            currentInterval = nextInterval
            requestLocationUpdates(currentInterval)
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ride Companion Active Session",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
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
