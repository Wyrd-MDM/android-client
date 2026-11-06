package link.wyrd.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationStateTest {

    private val ready = LocationState(
        locationOn = true,
        workProfileLocation = true,
        networkLocation = true,
        precise = true,
        background = true,
        unrestricted = true,
    )

    @Test
    fun everything_held_leaves_nothing_to_ask_for() {
        assertEquals(emptyList<Requirement>(), ready.unmet())
    }

    @Test
    fun each_missing_condition_is_named_on_its_own() {
        assertEquals(listOf(Requirement.WORK_PROFILE_LOCATION), ready.copy(workProfileLocation = false).unmet())
        assertEquals(listOf(Requirement.NETWORK_LOCATION), ready.copy(networkLocation = false).unmet())
        assertEquals(listOf(Requirement.UNRESTRICTED_BACKGROUND), ready.copy(unrestricted = false).unmet())
    }

    @Test
    fun the_order_is_the_order_to_fix_them_in() {
        val nothing = LocationState(false, false, false, false, false, false)
        assertEquals(Requirement.entries.toList(), nothing.unmet())
        assertEquals(Requirement.PRECISE_PERMISSION, ready.copy(precise = false, background = false).unmet().first())
    }
}
