package com.ridecompanion.core.location.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class CrashDetector(
    context: Context,
    private val onCrashDetected: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val gForceThreshold = 4.5f
    private val immobilityThresholdVariance = 0.05f
    private val immobilityDurationMs = 10000L

    private var lastImpactTime = 0L
    private var isImpactDetected = false
    private var immobilityStartTime = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        reset()
    }

    private fun reset() {
        isImpactDetected = false
        lastImpactTime = 0L
        immobilityStartTime = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val magnitude = sqrt(x * x + y * y + z * z)
                val gForce = magnitude / SensorManager.GRAVITY_EARTH

                if (gForce > gForceThreshold && !isImpactDetected) {
                    isImpactDetected = true
                    lastImpactTime = System.currentTimeMillis()
                    immobilityStartTime = System.currentTimeMillis()
                } else if (isImpactDetected) {
                    val timeSinceImpact = System.currentTimeMillis() - lastImpactTime
                    val motionMagnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
                    val isStationary = Math.abs(motionMagnitude) < immobilityThresholdVariance

                    if (isStationary) {
                        if (System.currentTimeMillis() - immobilityStartTime >= immobilityDurationMs) {
                            onCrashDetected()
                            reset()
                        }
                    } else {
                        if (timeSinceImpact > 15000L) {
                            reset()
                        } else {
                            immobilityStartTime = System.currentTimeMillis()
                        }
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
