package net.mrowser.player

import net.mrowser.data.SeekSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewTest {

    @Test fun `a hold crosses the stream in the preset's traverse time`() {
        for (preset in SeekSpeed.entries) {
            val step = SeekPreview.holdStepMs(DURATION, TICK, preset.traverseMs)
            val timeToCross = (DURATION / step) * TICK
            assertTrue(
                "$preset crossed in ${timeToCross}ms, wanted ~${preset.traverseMs}ms",
                timeToCross in (preset.traverseMs - TICK * 2)..(preset.traverseMs + TICK * 2)
            )
        }
    }

    @Test fun `the step scales with the duration`() {
        // One hold must feel the same on an episode and on a long film, which a rate fixed in
        // real time cannot do.
        val episode = SeekPreview.holdStepMs(20 * 60 * 1_000L, TICK, SeekSpeed.STANDARD.traverseMs)
        val film = SeekPreview.holdStepMs(3 * 60 * 60 * 1_000L, TICK, SeekSpeed.STANDARD.traverseMs)
        assertEquals(9, film / episode) // 3h is 9x a 20min episode
    }

    @Test fun `a faster preset takes a bigger step`() {
        val gentle = SeekPreview.holdStepMs(DURATION, TICK, SeekSpeed.GENTLE.traverseMs)
        val standard = SeekPreview.holdStepMs(DURATION, TICK, SeekSpeed.STANDARD.traverseMs)
        val fast = SeekPreview.holdStepMs(DURATION, TICK, SeekSpeed.FAST.traverseMs)
        assertTrue("$gentle < $standard < $fast", gentle < standard && standard < fast)
    }

    @Test fun `a long stream does not overflow the step`() {
        // durationMs * tickMs exceeds Int past about 36 seconds, so the arithmetic is Long.
        val tenHours = 10 * 60 * 60 * 1_000L
        val step = SeekPreview.holdStepMs(tenHours, TICK, SeekSpeed.STANDARD.traverseMs)
        assertEquals(tenHours * TICK / SeekSpeed.STANDARD.traverseMs, step)
        assertTrue("step was $step", step > 0)
    }

    @Test fun `an unknown duration cannot be scrubbed`() {
        // The caller falls back to stepping rather than scrubbing an unmeasurable timeline.
        assertEquals(0, SeekPreview.holdStepMs(UNKNOWN, TICK, SeekSpeed.STANDARD.traverseMs))
        assertEquals(0, SeekPreview.holdStepMs(0, TICK, SeekSpeed.STANDARD.traverseMs))
    }

    @Test fun `a very short stream still advances`() {
        // A 5s clip at a 20s traverse rounds below 1ms per tick; it must not stall at 0.
        assertTrue(SeekPreview.holdStepMs(5_000, TICK, SeekSpeed.GENTLE.traverseMs) >= 1)
    }

    @Test fun `degenerate timings are tolerated`() {
        assertTrue(SeekPreview.holdStepMs(DURATION, 0, SeekSpeed.STANDARD.traverseMs) > 0)
        assertTrue(SeekPreview.holdStepMs(DURATION, TICK, 0) > 0)
    }

    private companion object {
        const val DURATION = 100 * 60 * 1_000L // 1h40m
        const val UNKNOWN = -9_223_372_036_854_775_807L // media3's C.TIME_UNSET
        const val TICK = 40L
    }
}
