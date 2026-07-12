package com.ridecompanion.core.location.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * True-north device heading from the rotation-vector sensor (gyro + compass
 * fusion done by the OS). GPS only knows the direction of *movement*, so a
 * rider stopped at a light — or turning on the spot — gets no heading from it.
 * This provider is what keeps the map and the puck pointing where the rider
 * is actually facing, exactly like dedicated navigation apps.
 */
@Singleton
class CompassHeadingProvider @Inject constructor(
    @ApplicationContext context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Smoothed heading in degrees from true north, or null before the first reading. */
    private val _headingDegrees = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = _headingDegrees

    // Difference between magnetic and true north at the rider's location.
    @Volatile private var declinationDegrees = 0f

    private var started = false
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothed = Float.NaN

    fun start() {
        val sensor = rotationSensor ?: return
        if (started) return
        started = true
        smoothed = Float.NaN
        // GAME rate (~20 ms) — heading must feel instant on the handlebars.
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        _headingDegrees.value = null
    }

    /** Feed location fixes so magnetic north can be corrected to true north. */
    fun updateDeclination(location: Location) {
        declinationDegrees = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            location.time
        ).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val heading = (azimuth + declinationDegrees + 360f) % 360f

        // Wrap-aware adaptive smoothing: barely moves on sensor jitter, but
        // follows almost 1:1 on a real turn, so the map reacts instantly.
        smoothed = if (smoothed.isNaN()) {
            heading
        } else {
            var delta = heading - smoothed
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            val alpha = (0.15f + abs(delta) / 60f).coerceAtMost(0.85f)
            (smoothed + alpha * delta + 360f) % 360f
        }

        // Only publish meaningful changes so collectors aren't spammed at 50 Hz.
        val current = _headingDegrees.value
        if (current == null || angleDelta(current, smoothed) > 1.0f) {
            _headingDegrees.value = smoothed
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun angleDelta(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }
}
