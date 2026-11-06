package link.wyrd.geo

import link.wyrd.socket.KeepAliveScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEnforcerTest {

    private fun square(name: String, kind: String, action: String) = GeoZone(
        name, kind, action,
        listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 10.0),
            doubleArrayOf(10.0, 10.0), doubleArrayOf(10.0, 0.0),
        ),
    )

    @Test
    fun inside_and_outside_a_square() {
        val sq = square("x", "keep_in", "lock").polygon
        assert(GeofenceEnforcer.contains(sq, 5.0, 5.0))
        assert(!GeofenceEnforcer.contains(sq, 15.0, 5.0))
        assert(!GeofenceEnforcer.contains(sq, 5.0, 15.0))
    }

    @Test
    fun keep_in_breaches_only_outside() {
        val gf = Geofence(listOf(square("in", "keep_in", "lock")))
        assertNull(GeofenceEnforcer.breach(gf, 5.0, 5.0))
        assertEquals(GeofenceEnforcer.Breach("lock"), GeofenceEnforcer.breach(gf, 50.0, 50.0))
    }

    @Test
    fun keep_out_breaches_only_inside_and_names_the_zone() {
        val gf = Geofence(listOf(square("out", "keep_out", "wipe")))
        assertNull(GeofenceEnforcer.breach(gf, 50.0, 50.0))
        assertEquals(GeofenceEnforcer.Breach("wipe", "out"), GeofenceEnforcer.breach(gf, 5.0, 5.0))
    }

    @Test
    fun outside_all_keep_in_zones_is_a_breach_but_inside_one_of_several_is_not() {
        val a = square("a", "keep_in", "lock")
        val b = GeoZone("b", "keep_in", "lock", listOf(
            doubleArrayOf(100.0, 100.0), doubleArrayOf(100.0, 110.0),
            doubleArrayOf(110.0, 110.0), doubleArrayOf(110.0, 100.0),
        ))
        val gf = Geofence(listOf(a, b))
        assertNull(GeofenceEnforcer.breach(gf, 5.0, 5.0))
        assertNull(GeofenceEnforcer.breach(gf, 105.0, 105.0))
        assertEquals("lock", GeofenceEnforcer.breach(gf, 50.0, 50.0)?.action)
    }

    @Test
    fun wipe_outranks_lock_when_two_rules_break_at_once() {
        val keepIn = GeoZone("in", "keep_in", "lock", listOf(
            doubleArrayOf(100.0, 100.0), doubleArrayOf(100.0, 110.0), doubleArrayOf(110.0, 110.0),
        ))
        val keepOut = square("out", "keep_out", "wipe")
        val gf = Geofence(listOf(keepIn, keepOut))
        assertEquals(GeofenceEnforcer.Breach("wipe", "out"), GeofenceEnforcer.breach(gf, 5.0, 5.0))
    }

    @Test
    fun an_equally_strict_keep_out_zone_does_not_take_the_blame_from_a_keep_in_one() {
        val keepIn = GeoZone("in", "keep_in", "wipe", listOf(
            doubleArrayOf(100.0, 100.0), doubleArrayOf(100.0, 110.0), doubleArrayOf(110.0, 110.0),
        ))
        val gf = Geofence(listOf(keepIn, square("out", "keep_out", "wipe")))
        assertEquals(GeofenceEnforcer.Breach("wipe"), GeofenceEnforcer.breach(gf, 5.0, 5.0))
    }

    private val minute = 60_000L

    @Test
    fun the_timer_tightens_how_fresh_a_fix_must_be_and_never_loosens_it() {
        assertEquals(20 * minute, GeofenceEnforcer.freshFor(0))
        assertEquals(5 * minute, GeofenceEnforcer.freshFor(5 * minute))
        assertEquals(20 * minute, GeofenceEnforcer.freshFor(60 * minute))
    }

    @Test
    fun an_unknown_position_counts_past_the_timer_from_the_later_of_fix_and_start() {
        val now = 1_000 * minute
        assertFalse("off never fires", GeofenceEnforcer.unknownTooLong(now, null, 0L, 0L))
        assertTrue(GeofenceEnforcer.unknownTooLong(now, now - 30 * minute, 0L, 10 * minute))
        assertFalse(
            "a geofence that began 5 minutes ago does not answer for an older fix",
            GeofenceEnforcer.unknownTooLong(now, now - 30 * minute, now - 5 * minute, 10 * minute),
        )
        assertTrue(GeofenceEnforcer.unknownTooLong(now, null, now - 11 * minute, 10 * minute))
    }

    @Test
    fun a_pause_counts_past_the_timer_and_past_a_running_apps_own_silence() {
        val ownSilence = 2 * KeepAliveScheduler.MAX_INTERVAL_MS
        assertFalse("off never fires", GeofenceEnforcer.pausedTooLong(10 * ownSilence, 0L))
        assertFalse("a restart in between is not a pause", GeofenceEnforcer.pausedTooLong(null, 10 * minute))
        assertFalse(
            "a tick Doze held back is not a pause, however short the timer",
            GeofenceEnforcer.pausedTooLong(ownSilence, 5 * minute),
        )
        assertTrue(GeofenceEnforcer.pausedTooLong(ownSilence + minute, 5 * minute))
        assertFalse("shorter than the timer", GeofenceEnforcer.pausedTooLong(50 * minute, 60 * minute))
        assertTrue(GeofenceEnforcer.pausedTooLong(61 * minute, 60 * minute))
    }

    @Test
    fun a_missing_condition_erases_only_when_a_later_read_still_finds_it_gone() {
        val first = 1_000 * minute
        assertFalse("nothing found gone yet", GeofenceEnforcer.conditionConfirmed(0L, first))
        assertFalse("the same moment is the same read", GeofenceEnforcer.conditionConfirmed(first, first + 30_000L))
        assertTrue(GeofenceEnforcer.conditionConfirmed(first, first + minute))
    }

    @Test
    fun the_timer_takes_the_strictest_zone_action() {
        val mixed = Geofence(listOf(square("a", "keep_in", "lock"), square("b", "keep_out", "wipe")))
        assertEquals("wipe", GeofenceEnforcer.strictest(mixed))
        assertEquals("lock", GeofenceEnforcer.strictest(Geofence(listOf(square("a", "keep_in", "lock")))))
    }
}
