package link.wyrd.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWipeTest {

    private val day = 24 * 60 * 60L

    @Test
    fun `disarmed by default`() {
        assertNull(AutoWipe.deadline(lastUnlockAt = 1_000, armedAt = 1_000, days = 0))
    }

    @Test
    fun `counts from the last unlock`() {
        assertEquals(1_000 + 7 * day, AutoWipe.deadline(1_000, 500, 7))
    }

    @Test
    fun `counts from arming when that is later`() {
        assertEquals(9_000 + 7 * day, AutoWipe.deadline(1_000, 9_000, 7))
    }

    @Test
    fun `reboots do not move the deadline`() {
        val armed = 1_000L
        val unlocked = 2_000L
        val first = AutoWipe.deadline(unlocked, armed, 7)
        repeat(5) {
            assertEquals(first, AutoWipe.deadline(unlocked, armed, 7))
        }
    }

    @Test
    fun `in use means awake and open`() {
        assertTrue(AutoWipe.inUse(interactive = true, keyguardLocked = false))
        assertFalse(AutoWipe.inUse(interactive = false, keyguardLocked = false))
        assertFalse(AutoWipe.inUse(interactive = true, keyguardLocked = true))
        assertFalse(AutoWipe.inUse(interactive = false, keyguardLocked = true))
    }

    @Test
    fun `nothing to count from means no deadline`() {
        assertNull(AutoWipe.deadline(lastUnlockAt = 0, armedAt = 0, days = 7))
    }
}
