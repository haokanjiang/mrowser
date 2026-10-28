package net.mrowser.player

import net.mrowser.data.SeekSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekRampTest {

    @Test fun `a hold starts at 2x`() {
        for (preset in SeekSpeed.entries) {
            assertEquals(SeekRamp.START_RATE, SeekRamp.rateAt(0, preset))
        }
    }

    @Test fun `the rate doubles once per step`() {
        val p = SeekSpeed.STANDARD // 1200ms per step, cap 16
        assertEquals(2, SeekRamp.rateAt(1_199, p))
        assertEquals(4, SeekRamp.rateAt(1_200, p))
        assertEquals(8, SeekRamp.rateAt(2_400, p))
        assertEquals(16, SeekRamp.rateAt(3_600, p))
    }

    @Test fun `each preset stops at its own cap`() {
        val longHold = 60_000L
        assertEquals(8, SeekRamp.rateAt(longHold, SeekSpeed.GENTLE))
        assertEquals(16, SeekRamp.rateAt(longHold, SeekSpeed.STANDARD))
        assertEquals(32, SeekRamp.rateAt(longHold, SeekSpeed.AGGRESSIVE))
    }

    @Test fun `a very long hold neither overflows nor exceeds the cap`() {
        // The shift count is clamped, so a half-hour hold stays at the cap rather than wrapping.
        for (preset in SeekSpeed.entries) {
            val rate = SeekRamp.rateAt(Long.MAX_VALUE / 2, preset)
            assertEquals(preset.maxRate, rate)
            assertTrue(rate > 0)
        }
    }

    @Test fun `a negative held time is treated as the start of the hold`() {
        assertEquals(SeekRamp.START_RATE, SeekRamp.rateAt(-1, SeekSpeed.STANDARD))
    }

    @Test fun `a tick advances by rate times its length`() {
        val next = SeekRamp.advance(
            positionMs = 10_000, durationMs = 600_000, rate = 8, tickMs = 50, forward = true
        )
        assertEquals(10_400, next) // 8 * 50
    }

    @Test fun `a backward tick subtracts`() {
        val next = SeekRamp.advance(
            positionMs = 10_000, durationMs = 600_000, rate = 8, tickMs = 50, forward = false
        )
        assertEquals(9_600, next)
    }

    @Test fun `a tick never runs past the end`() {
        val next = SeekRamp.advance(
            positionMs = 599_000, durationMs = 600_000, rate = 32, tickMs = 50, forward = true
        )
        assertEquals(600_000, next)
    }

    @Test fun `a tick never runs before the start`() {
        val next = SeekRamp.advance(
            positionMs = 500, durationMs = 600_000, rate = 32, tickMs = 50, forward = false
        )
        assertEquals(0, next)
    }

    @Test fun `an unknown duration clamps only the lower bound`() {
        // A live edge or an unloaded timeline must not pin the position to 0.
        val forward = SeekRamp.advance(
            positionMs = 10_000, durationMs = SeekRamp.UNKNOWN_DURATION,
            rate = 8, tickMs = 50, forward = true
        )
        assertEquals(10_400, forward)

        val back = SeekRamp.advance(
            positionMs = 100, durationMs = SeekRamp.UNKNOWN_DURATION,
            rate = 8, tickMs = 50, forward = false
        )
        assertEquals(0, back)
    }

    @Test fun `the ramp stays inside the stream over a whole hold`() {
        // Walk a 20-minute film with the fastest preset; the position must never leave the range.
        val duration = 20 * 60 * 1_000L
        var position = duration / 2
        var held = 0L
        while (held < 30_000) {
            val rate = SeekRamp.rateAt(held, SeekSpeed.AGGRESSIVE)
            position = SeekRamp.advance(position, duration, rate, TICK, forward = true)
            assertTrue("position $position out of range", position in 0..duration)
            held += TICK
        }
        assertEquals(duration, position)
    }

    private companion object {
        const val TICK = 50L
    }
}
