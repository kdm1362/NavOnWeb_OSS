package com.eigenkodex.navonweb.openauto.sensor

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeResolverTest {
    private val calculator = SolarEventCalculator()

    @Test
    fun equinoxAtGreenwichProducesExpectedSunriseAndSunsetWindow() {
        val date = LocalDate.of(2026, 3, 20)
        val events = calculator.calculate(
            date = date,
            zoneId = ZoneOffset.UTC,
            coordinate = GeoCoordinate(0.0, 0.0),
        ) as SolarDayEvents.SunriseSunset

        val sunriseMinute = events.sunrise.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay() / 60
        val sunsetMinute = events.sunset.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay() / 60
        assertTrue(sunriseMinute in (5 * 60 + 40)..(6 * 60 + 25))
        assertTrue(sunsetMinute in (17 * 60 + 40)..(18 * 60 + 30))
    }

    @Test
    fun utcEventsAreAnchoredToTheRequestedSeoulCivilDate() {
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.of(2026, 8, 2)
        val events = calculator.calculate(
            date = date,
            zoneId = zone,
            coordinate = GeoCoordinate(37.5665, 126.9780),
        ) as SolarDayEvents.SunriseSunset

        val localSunrise = events.sunrise.atZone(zone)
        val localSunset = events.sunset.atZone(zone)
        assertEquals(date, localSunrise.toLocalDate())
        assertEquals(date, localSunset.toLocalDate())
        assertTrue(localSunrise.hour in 5..6)
        assertTrue(localSunset.hour in 19..20)
    }

    @Test
    fun polarDayAndNightAreExplicitInsteadOfInventingEvents() {
        val tromso = GeoCoordinate(69.6492, 18.9553)
        val zone = ZoneId.of("Europe/Oslo")

        assertEquals(
            SolarDayEvents.PolarDay,
            calculator.calculate(LocalDate.of(2026, 6, 21), zone, tromso),
        )
        assertEquals(
            SolarDayEvents.PolarNight,
            calculator.calculate(LocalDate.of(2026, 12, 21), zone, tromso),
        )
    }

    @Test
    fun freshLocationSwitchesAtCalculatedSunriseAndSunset() {
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.of(2026, 8, 2)
        val coordinate = GeoCoordinate(37.5665, 126.9780)
        val events = calculator.calculate(date, zone, coordinate) as SolarDayEvents.SunriseSunset
        val resolver = TimeLocationNightModeResolver(solarCalculator = calculator)

        fun resolve(at: Instant): NightModeDecision = resolver.resolve(
            now = at,
            zoneId = zone,
            location = NightModeLocationFix(coordinate, at.minus(Duration.ofMinutes(1)), 10.0),
            deviceNightModeHint = null,
        )

        assertTrue(resolve(events.sunrise.minusSeconds(1)).isNight)
        assertFalse(resolve(events.sunrise.plusSeconds(1)).isNight)
        assertFalse(resolve(events.sunset.minusSeconds(1)).isNight)
        assertTrue(resolve(events.sunset).isNight)
        assertEquals(NightModeDecisionSource.SOLAR_LOCATION, resolve(events.sunset).source)
    }

    @Test
    fun missingLocationUsesDeviceHintAndOtherwiseFailsDark() {
        val now = Instant.parse("2026-08-02T03:00:00Z")
        val resolver = TimeLocationNightModeResolver()

        val hintedDay = resolver.resolve(now, ZoneOffset.UTC, null, deviceNightModeHint = false)
        val noHint = resolver.resolve(now, ZoneOffset.UTC, null, deviceNightModeHint = null)

        assertFalse(hintedDay.isNight)
        assertEquals(NightModeDecisionSource.DEVICE_HINT_NO_LOCATION, hintedDay.source)
        assertTrue(noHint.isNight)
        assertEquals(NightModeDecisionSource.FAIL_DARK_NO_LOCATION, noHint.source)
        assertNull(noHint.solarEvents)
    }

    @Test
    fun staleOrInaccurateLocationNeverDrivesSolarMode() {
        val now = Instant.parse("2026-08-02T03:00:00Z")
        val resolver = TimeLocationNightModeResolver(maxLocationAge = Duration.ofMinutes(30))
        val coordinate = GeoCoordinate(37.5665, 126.9780)
        val stale = NightModeLocationFix(coordinate, now.minus(Duration.ofMinutes(31)), 10.0)
        val inaccurate = NightModeLocationFix(coordinate, now, 20_000.0)

        listOf(stale, inaccurate).forEach { fix ->
            val decision = resolver.resolve(now, ZoneId.of("Asia/Seoul"), fix, false)
            assertFalse(decision.isNight)
            assertEquals(NightModeDecisionSource.DEVICE_HINT_UNUSABLE_LOCATION, decision.source)
            assertNull(decision.solarEvents)
        }
    }

    @Test
    fun providerReadsClockLocationAndDeviceHintAtEvaluationTime() {
        val now = Instant.parse("2026-08-02T03:00:00Z")
        var hint: Boolean? = false
        val provider = TimeLocationNightModeProvider(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            zoneProvider = { ZoneId.of("Asia/Seoul") },
            locationProvider = { null },
            deviceNightModeHintProvider = { hint },
        )

        assertFalse(provider.currentDecision().isNight)
        hint = null
        assertTrue(provider.currentDecision().isNight)
    }

    @Test
    fun locationStoreRejectsOutOfOrderFixesAndClearsOnPermissionLoss() {
        val store = NightModeLocationStore()
        val coordinate = GeoCoordinate(37.5665, 126.9780)
        val newer = NightModeLocationFix(
            coordinate = coordinate,
            observedAt = Instant.parse("2026-08-02T03:00:00Z"),
            horizontalAccuracyMeters = 10.0,
        )
        val older = newer.copy(observedAt = newer.observedAt.minusSeconds(1))

        assertTrue(store.update(newer))
        assertFalse(store.update(older))
        assertEquals(newer, store.latest())

        store.clear()
        assertNull(store.latest())
    }
}
