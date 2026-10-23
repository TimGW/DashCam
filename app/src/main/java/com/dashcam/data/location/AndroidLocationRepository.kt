package com.dashcam.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.repository.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** GPS-only location updates (once per second), active only while someone collects them. */
class AndroidLocationRepository(
    private val context: Context,
    scope: CoroutineScope,
) : LocationRepository {

    private val manager = context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    private val updates = callbackFlow {
        while (!hasPermission()) delay(PERMISSION_POLL_MS)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toSample())
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
        )
        awaitClose { manager.removeUpdates(listener) }
    }

    override val location: StateFlow<LocationSample?> =
        updates.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun Location.toSample() = LocationSample(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = System.currentTimeMillis(),
    )

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val PERMISSION_POLL_MS = 1_000L
    }
}
