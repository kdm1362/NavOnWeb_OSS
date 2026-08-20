/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant

/** Process-only coordinate cache shared by the visible Activity and projection Service. */
object ProjectionNightModeLocation {
    private val store = NightModeLocationStore()

    fun update(location: Location): Boolean {
        val roundedLatitude = roundForSolarPrivacy(location.latitude)
        val roundedLongitude = roundForSolarPrivacy(location.longitude)
        return runCatching {
            store.update(
                NightModeLocationFix(
                    coordinate = GeoCoordinate(roundedLatitude, roundedLongitude),
                    observedAt = Instant.ofEpochMilli(location.time),
                    horizontalAccuracyMeters = location.accuracy
                        .takeIf { location.hasAccuracy() && it.isFinite() }
                        ?.toDouble(),
                ),
            )
        }.getOrDefault(false)
    }

    fun latest(): NightModeLocationFix? = store.latest()

    fun clear() = store.clear()

    /** Roughly 1.1 km latitude precision is enough for sunrise/sunset and limits retained detail. */
    private fun roundForSolarPrivacy(value: Double): Double =
        kotlin.math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE

    private const val COORDINATE_SCALE = 100.0
}

class AndroidNightModeStateProvider(context: Context) : NightModeStateProvider {
    private val appContext = context.applicationContext
    private val delegate = TimeLocationNightModeProvider(
        // A session-start fix remains useful through a normal day of stationary bench operation.
        resolver = TimeLocationNightModeResolver(maxLocationAge = Duration.ofHours(12)),
        locationProvider = ::permissionAwareLocation,
        deviceNightModeHintProvider = ::deviceNightModeHint,
    )

    override fun currentDecision(): NightModeDecision = delegate.currentDecision()

    private fun permissionAwareLocation(): NightModeLocationFix? {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ProjectionNightModeLocation.clear()
            return null
        }
        return ProjectionNightModeLocation.latest()
    }

    private fun deviceNightModeHint(): Boolean? = when (
        appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    ) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }
}

/**
 * Permission-checked location acquisition for the visible Activity and a best-effort foreground
 * service refresh. Modern Android can still reject while-in-use location during a locked or
 * background cold start, so callers must tolerate null and use the night-mode fallback chain.
 */
class AndroidNightModeLocationSource(private val context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Guarded immediately by hasPermission().
    fun bestLastKnownLocation(): Location? {
        if (!hasPermission()) return null
        return LOCATION_PROVIDERS.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission") // Guarded immediately by hasPermission().
    fun requestCurrentLocation(onLocation: (Location?) -> Unit): CancellationSignal? {
        if (!hasPermission()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = LOCATION_PROVIDERS.firstOrNull { candidate ->
            runCatching { locationManager.isProviderEnabled(candidate) }.getOrDefault(false)
        } ?: return null
        val cancellation = CancellationSignal()
        return runCatching {
            locationManager.getCurrentLocation(
                provider,
                cancellation,
                context.mainExecutor,
                onLocation,
            )
            cancellation
        }.getOrNull()
    }

    private companion object {
        val LOCATION_PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
