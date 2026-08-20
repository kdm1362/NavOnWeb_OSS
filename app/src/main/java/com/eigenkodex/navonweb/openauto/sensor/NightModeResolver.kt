/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.sensor

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

data class GeoCoordinate(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitude must be finite and between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitude must be finite and between -180 and 180"
        }
    }
}

/** Framework-neutral representation of an Android last-known location. */
data class NightModeLocationFix(
    val coordinate: GeoCoordinate,
    val observedAt: Instant,
    val horizontalAccuracyMeters: Double? = null,
) {
    init {
        require(horizontalAccuracyMeters == null ||
            (horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0)
        ) { "location accuracy must be finite and non-negative" }
    }
}

/**
 * Process-local last-location holder. Coordinates are deliberately not persisted to disk. Calling
 * [clear] on permission loss prevents an old fix from silently surviving a privacy decision.
 */
class NightModeLocationStore {
    private val latest = AtomicReference<NightModeLocationFix?>(null)

    /** Returns false when an out-of-order fix attempted to replace a newer observation. */
    fun update(fix: NightModeLocationFix): Boolean {
        while (true) {
            val current = latest.get()
            if (current != null && fix.observedAt < current.observedAt) return false
            if (latest.compareAndSet(current, fix)) return true
        }
    }

    fun latest(): NightModeLocationFix? = latest.get()

    fun clear() {
        latest.set(null)
    }
}

sealed interface SolarDayEvents {
    data class SunriseSunset(
        val sunrise: Instant,
        val sunset: Instant,
    ) : SolarDayEvents {
        init {
            require(sunrise < sunset) { "sunrise must precede sunset" }
        }
    }

    data object PolarDay : SolarDayEvents
    data object PolarNight : SolarDayEvents
}

/**
 * Dependency-free NOAA sunrise/sunset calculation using the standard 90.833 degree zenith.
 * The requested [date] is the civil date in [zoneId]; returned events are absolute instants.
 */
class SolarEventCalculator(
    private val officialZenithDegrees: Double = 90.833,
) {
    init {
        require(officialZenithDegrees.isFinite() && officialZenithDegrees in 90.0..108.0) {
            "solar zenith is outside the supported range"
        }
    }

    fun calculate(
        date: LocalDate,
        zoneId: ZoneId,
        coordinate: GeoCoordinate,
    ): SolarDayEvents {
        val sunrise = utcEventHours(date, coordinate, sunrise = true)
        val sunset = utcEventHours(date, coordinate, sunrise = false)
        if (sunrise is EventHours.Value && sunset is EventHours.Value) {
            val sunriseInstant = eventInstantOnCivilDate(date, zoneId, sunrise.hours)
            val sunsetInstant = eventInstantOnCivilDate(date, zoneId, sunset.hours)
            if (sunriseInstant < sunsetInstant) {
                return SolarDayEvents.SunriseSunset(sunriseInstant, sunsetInstant)
            }
        }

        val noEvent = listOf(sunrise, sunset).filterIsInstance<EventHours.NoEvent>()
        return if (noEvent.any { it.sunAlwaysAboveHorizon }) {
            SolarDayEvents.PolarDay
        } else {
            SolarDayEvents.PolarNight
        }
    }

    private fun utcEventHours(
        date: LocalDate,
        coordinate: GeoCoordinate,
        sunrise: Boolean,
    ): EventHours {
        val longitudeHour = coordinate.longitudeDegrees / DEGREES_PER_HOUR
        val approximateTime = date.dayOfYear +
            ((if (sunrise) SUNRISE_APPROXIMATE_HOUR else SUNSET_APPROXIMATE_HOUR) - longitudeHour) /
            HOURS_PER_DAY
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly +
                1.916 * sinDegrees(meanAnomaly) +
                0.020 * sinDegrees(2.0 * meanAnomaly) +
                282.634,
        )

        var rightAscension = normalizeDegrees(toDegrees(atan(0.91764 * tanDegrees(trueLongitude))))
        val longitudeQuadrant = (trueLongitude / 90.0).toInt() * 90.0
        val rightAscensionQuadrant = (rightAscension / 90.0).toInt() * 90.0
        rightAscension = (rightAscension + longitudeQuadrant - rightAscensionQuadrant) /
            DEGREES_PER_HOUR

        val sinDeclination = 0.39782 * sinDegrees(trueLongitude)
        val cosDeclination = cos(asin(sinDeclination))
        val cosineHourAngle = (
            cosDegrees(officialZenithDegrees) -
                sinDeclination * sinDegrees(coordinate.latitudeDegrees)
            ) / (cosDeclination * cosDegrees(coordinate.latitudeDegrees))

        if (cosineHourAngle > 1.0) return EventHours.NoEvent(sunAlwaysAboveHorizon = false)
        if (cosineHourAngle < -1.0) return EventHours.NoEvent(sunAlwaysAboveHorizon = true)

        val localHourAngleDegrees = if (sunrise) {
            360.0 - toDegrees(acos(cosineHourAngle))
        } else {
            toDegrees(acos(cosineHourAngle))
        }
        val localMeanTime = localHourAngleDegrees / DEGREES_PER_HOUR +
            rightAscension -
            0.06571 * approximateTime -
            6.622
        return EventHours.Value(normalizeHours(localMeanTime - longitudeHour))
    }

    private fun eventInstantOnCivilDate(
        date: LocalDate,
        zoneId: ZoneId,
        utcHours: Double,
    ): Instant {
        var candidate = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            .plusMillis((utcHours * MILLIS_PER_HOUR).roundToLong())
        repeat(2) {
            val candidateDate = candidate.atZone(zoneId).toLocalDate()
            candidate = when {
                candidateDate < date -> candidate.plus(Duration.ofDays(1))
                candidateDate > date -> candidate.minus(Duration.ofDays(1))
                else -> return candidate
            }
        }
        return candidate
    }

    private sealed interface EventHours {
        data class Value(val hours: Double) : EventHours
        data class NoEvent(val sunAlwaysAboveHorizon: Boolean) : EventHours
    }

    private companion object {
        const val HOURS_PER_DAY = 24.0
        const val DEGREES_PER_HOUR = 15.0
        const val SUNRISE_APPROXIMATE_HOUR = 6.0
        const val SUNSET_APPROXIMATE_HOUR = 18.0
        const val MILLIS_PER_HOUR = 3_600_000.0

        fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
        fun normalizeHours(value: Double): Double = ((value % HOURS_PER_DAY) + HOURS_PER_DAY) % HOURS_PER_DAY
        fun toRadians(degrees: Double): Double = degrees * PI / 180.0
        fun toDegrees(radians: Double): Double = radians * 180.0 / PI
        fun sinDegrees(degrees: Double): Double = sin(toRadians(degrees))
        fun cosDegrees(degrees: Double): Double = cos(toRadians(degrees))
        fun tanDegrees(degrees: Double): Double = tan(toRadians(degrees))
    }
}

enum class NightModeDecisionSource(val wireName: String) {
    SOLAR_LOCATION("solar_location"),
    DEVICE_HINT_NO_LOCATION("device_hint_no_location"),
    DEVICE_HINT_UNUSABLE_LOCATION("device_hint_unusable_location"),
    FAIL_DARK_NO_LOCATION("fail_dark_no_location"),
    FAIL_DARK_UNUSABLE_LOCATION("fail_dark_unusable_location"),
}

data class NightModeDecision(
    val isNight: Boolean,
    val source: NightModeDecisionSource,
    val evaluatedAt: Instant,
    val solarEvents: SolarDayEvents? = null,
    val locationAge: Duration? = null,
)

/**
 * Uses a recent, reasonably accurate coordinate for sunrise/sunset. Missing permission is
 * represented by null. A stale/inaccurate/future fix falls back to the device night hint; if no
 * hint is available the resolver deliberately fails dark to avoid a bright display at night.
 */
class TimeLocationNightModeResolver(
    private val solarCalculator: SolarEventCalculator = SolarEventCalculator(),
    private val maxLocationAge: Duration = Duration.ofMinutes(30),
    private val maxFutureLocationSkew: Duration = Duration.ofMinutes(2),
    private val maxHorizontalAccuracyMeters: Double = 10_000.0,
) {
    init {
        require(!maxLocationAge.isNegative && !maxLocationAge.isZero) {
            "maximum location age must be positive"
        }
        require(!maxFutureLocationSkew.isNegative) { "future location skew cannot be negative" }
        require(maxHorizontalAccuracyMeters.isFinite() && maxHorizontalAccuracyMeters > 0.0) {
            "maximum location accuracy must be positive"
        }
    }

    fun resolve(
        now: Instant,
        zoneId: ZoneId,
        location: NightModeLocationFix?,
        deviceNightModeHint: Boolean?,
    ): NightModeDecision {
        if (location == null) {
            return fallback(
                now = now,
                deviceNightModeHint = deviceNightModeHint,
                hintedSource = NightModeDecisionSource.DEVICE_HINT_NO_LOCATION,
                failDarkSource = NightModeDecisionSource.FAIL_DARK_NO_LOCATION,
            )
        }

        val rawAge = Duration.between(location.observedAt, now)
        val locationAge = if (rawAge.isNegative) Duration.ZERO else rawAge
        val futureBeyondTolerance = rawAge < maxFutureLocationSkew.negated()
        val inaccurate = location.horizontalAccuracyMeters?.let {
            it > maxHorizontalAccuracyMeters
        } == true
        if (futureBeyondTolerance || rawAge > maxLocationAge || inaccurate) {
            return fallback(
                now = now,
                deviceNightModeHint = deviceNightModeHint,
                hintedSource = NightModeDecisionSource.DEVICE_HINT_UNUSABLE_LOCATION,
                failDarkSource = NightModeDecisionSource.FAIL_DARK_UNUSABLE_LOCATION,
                locationAge = locationAge,
            )
        }

        val events = runCatching {
            solarCalculator.calculate(now.atZone(zoneId).toLocalDate(), zoneId, location.coordinate)
        }.getOrNull() ?: return fallback(
            now = now,
            deviceNightModeHint = deviceNightModeHint,
            hintedSource = NightModeDecisionSource.DEVICE_HINT_UNUSABLE_LOCATION,
            failDarkSource = NightModeDecisionSource.FAIL_DARK_UNUSABLE_LOCATION,
            locationAge = locationAge,
        )
        val isNight = when (events) {
            is SolarDayEvents.SunriseSunset -> now < events.sunrise || now >= events.sunset
            SolarDayEvents.PolarDay -> false
            SolarDayEvents.PolarNight -> true
        }
        return NightModeDecision(
            isNight = isNight,
            source = NightModeDecisionSource.SOLAR_LOCATION,
            evaluatedAt = now,
            solarEvents = events,
            locationAge = locationAge,
        )
    }

    private fun fallback(
        now: Instant,
        deviceNightModeHint: Boolean?,
        hintedSource: NightModeDecisionSource,
        failDarkSource: NightModeDecisionSource,
        locationAge: Duration? = null,
    ): NightModeDecision = NightModeDecision(
        isNight = deviceNightModeHint ?: true,
        source = if (deviceNightModeHint == null) failDarkSource else hintedSource,
        evaluatedAt = now,
        locationAge = locationAge,
    )
}

fun interface NightModeStateProvider {
    fun currentDecision(): NightModeDecision
}

/** Android integration supplies permission-checked location and UiModeManager-backed hint lambdas. */
class TimeLocationNightModeProvider(
    private val resolver: TimeLocationNightModeResolver = TimeLocationNightModeResolver(),
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val locationProvider: () -> NightModeLocationFix?,
    private val deviceNightModeHintProvider: () -> Boolean?,
) : NightModeStateProvider {
    override fun currentDecision(): NightModeDecision {
        val now = clock.instant()
        return resolver.resolve(
            now = now,
            zoneId = zoneProvider(),
            location = locationProvider(),
            deviceNightModeHint = deviceNightModeHintProvider(),
        )
    }
}

/** Runtime default until an Android permission-aware provider is integrated. */
data object FailDarkNightModeStateProvider : NightModeStateProvider {
    override fun currentDecision(): NightModeDecision = NightModeDecision(
        isNight = true,
        source = NightModeDecisionSource.FAIL_DARK_NO_LOCATION,
        evaluatedAt = Instant.now(),
    )
}
