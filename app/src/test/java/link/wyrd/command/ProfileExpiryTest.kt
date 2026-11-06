package link.wyrd.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProfileExpiryTest {

    private class FakeHandset(override var expiresAt: Long = 0L, override var expirySeq: Long = 0L) :
        ProfileExpiry.Handset {
        val alarms = mutableListOf<Long>()
        var removals = 0

        override fun armAlarm(at: Long) {
            alarms += at
        }

        override fun removeProfile() {
            removals++
        }
    }

    private val minute = TimeUnit.MINUTES.toSeconds(1)
    private val hour = TimeUnit.HOURS.toSeconds(1)
    private val now = 1_793_037_600L
    private val at = now + hour

    @Test
    fun `a moment ahead is kept and waited for`() {
        val phone = FakeHandset()
        assertTrue(ProfileExpiry.receive(phone, at, 1, now))
        assertEquals(at to 1L, phone.expiresAt to phone.expirySeq)
        assertEquals(listOf(at), phone.alarms)
        assertEquals(0, phone.removals)
    }

    @Test
    fun `a moment already reached removes the profile at once`() {
        for (passed in listOf(now, now - minute)) {
            val phone = FakeHandset()
            assertTrue(ProfileExpiry.receive(phone, passed, 1, now))
            assertEquals(1, phone.removals)
            assertEquals(emptyList<Long>(), phone.alarms)
        }
    }

    @Test
    fun `a number not above the held one changes nothing`() {
        val phone = FakeHandset()
        ProfileExpiry.receive(phone, at + hour, 2, now)
        assertTrue(ProfileExpiry.receive(phone, now - minute, 2, now))
        assertTrue(ProfileExpiry.receive(phone, now - minute, 1, now))
        assertEquals(at + hour to 2L, phone.expiresAt to phone.expirySeq)
        assertEquals(0, phone.removals)

        ProfileExpiry.receive(phone, at, 3, now)
        assertEquals(at, phone.expiresAt)
        assertEquals(at, phone.alarms.last())
    }

    @Test
    fun `a higher number without a moment takes the moment away`() {
        val phone = FakeHandset()
        ProfileExpiry.receive(phone, at, 1, now)
        assertTrue(ProfileExpiry.receive(phone, null, 2, now))
        assertEquals(0L to 2L, phone.expiresAt to phone.expirySeq)
        ProfileExpiry.service(phone, at + minute)
        assertEquals(0, phone.removals)
    }

    @Test
    fun `the date from enrollment counts only until a numbered one arrives`() {
        val phone = FakeHandset()
        assertTrue(ProfileExpiry.receiveAtEnrollment(phone, at, now))
        assertEquals(at to 0L, phone.expiresAt to phone.expirySeq)
        ProfileExpiry.receive(phone, at + hour, 1, now)
        ProfileExpiry.receiveAtEnrollment(phone, now - minute, now)
        assertEquals(at + hour, phone.expiresAt)
        assertEquals(0, phone.removals)
    }

    @Test
    fun `after a reboot the kept moment is armed again or acted on`() {
        val beforeReboot = FakeHandset()
        ProfileExpiry.receive(beforeReboot, at, 1, now)

        val bootedInTime = FakeHandset(expiresAt = beforeReboot.expiresAt)
        ProfileExpiry.service(bootedInTime, at - minute)
        assertEquals(listOf(at), bootedInTime.alarms)
        assertEquals(0, bootedInTime.removals)

        val bootedTooLate = FakeHandset(expiresAt = beforeReboot.expiresAt)
        ProfileExpiry.service(bootedTooLate, at + minute)
        assertEquals(1, bootedTooLate.removals)
        assertEquals(emptyList<Long>(), bootedTooLate.alarms)
    }

    @Test
    fun `a value that names no moment is refused and changes nothing`() {
        val phone = FakeHandset()
        ProfileExpiry.receive(phone, at, 1, now)
        for (bad in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertFalse("$bad", ProfileExpiry.receive(phone, bad, 2, now))
        }
        assertEquals(at to 1L, phone.expiresAt to phone.expirySeq)
        assertEquals(listOf(at), phone.alarms)
        assertEquals(0, phone.removals)
    }

    @Test
    fun `without a moment nothing happens`() {
        val phone = FakeHandset()
        ProfileExpiry.service(phone, now)
        assertEquals(emptyList<Long>(), phone.alarms)
        assertEquals(0, phone.removals)
    }
}
