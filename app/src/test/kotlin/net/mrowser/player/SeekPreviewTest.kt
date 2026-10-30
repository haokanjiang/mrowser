package net.mrowser.player

import net.mrowser.data.SeekSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewTest {

    @Test fun `a first step starts from the player's position`() {
        assertEquals(
            75_000,
            SeekPreview.step(SeekPreview.NONE, positionMs = 60_000, deltaMs = 15_000, durationMs = DURATION)
        )
    }

    @Test fun `a run of presses accumulates on its own target`() {
        var target = SeekPreview.NONE
        repeat(5) {
            target = SeekPreview.step(target, positionMs = 60_000, deltaMs = 15_000, durationMs = DURATION)
        }
        assertEquals(60_000 + 5 * 15_000L, target)
    }

    @Test fun `a run never re-bases on a position that lagged the seek`() {
        // The bug this pins. A seekTo is masked to the request, but the internal player acks the
        // resolved position, and an adaptive stream resolves to a segment boundary — so
        // currentPosition settles behind. Re-basing on it loses that gap every press: enough
        // presses and a run of *forward* ones ends up behind where it started.
        val lag = 3_000L // what a segment boundary costs
        val delta = 15_000L
        val presses = 5

        var target = SeekPreview.NONE
        var reported = 60_000L
        repeat(presses) {
            target = SeekPreview.step(target, reported, delta, DURATION)
            reported = target - lag // the stream snapped the seek back
        }
        assertEquals(60_000 + presses * delta, target)

        // What re-basing would have produced instead, for contrast.
        var rebased = 60_000L
        repeat(presses) { rebased = SeekPreview.step(SeekPreview.NONE, rebased - lag, delta, DURATION) }
        assertTrue("re-basing should fall behind", rebased < target)
    }

    @Test fun `backward presses accumulate too`() {
        var target = SeekPreview.step(SeekPreview.NONE, 600_000, -5_000, DURATION)
        target = SeekPreview.step(target, 600_000, -5_000, DURATION)
        assertEquals(590_000, target)
    }

    @Test fun `a step reverses from the target, not the player`() {
        val forward = SeekPreview.step(SeekPreview.NONE, 60_000, 15_000, DURATION)
        assertEquals(70_000, SeekPreview.step(forward, 60_000, -5_000, DURATION))
    }

    @Test fun `a step clamps at the start`() {
        assertEquals(0, SeekPreview.step(SeekPreview.NONE, 3_000, -15_000, DURATION))
    }

    @Test fun `a step stops short of the last frame`() {
        // Landing on the duration ends playback, which would turn "skip towards the end" into
        // "finish the film" and clear the resume entry with it.
        val near = SeekPreview.step(SeekPreview.NONE, DURATION - 2_000, 15_000, DURATION)
        assertEquals(DURATION - SeekPreview.END_GUARD_MS, near)
        assertTrue(near < DURATION)
    }

    @Test fun `an unknown duration clamps only the lower bound`() {
        // A live edge must not be pinned to 0 by a duration media3 reports as TIME_UNSET.
        assertEquals(75_000, SeekPreview.step(SeekPreview.NONE, 60_000, 15_000, UNKNOWN))
        assertEquals(0, SeekPreview.step(SeekPreview.NONE, 1_000, -15_000, UNKNOWN))
    }

    @Test fun `a target outlives a commit but not its run`() {
        // It must survive long enough for the next press of a run to compound onto it, while
        // the seek it caused is still resolving — but a press minutes later starts fresh.
        assertTrue(SeekPreview.targetStillValid(0))
        assertTrue(SeekPreview.targetStillValid(500))
        assertTrue(SeekPreview.targetStillValid(SeekPreview.TARGET_TTL_MS))
        assertFalse(SeekPreview.targetStillValid(SeekPreview.TARGET_TTL_MS + 1))
        assertFalse(SeekPreview.targetStillValid(60_000))
    }

    @Test fun `a clock that went backwards expires the target`() {
        // Rather than treating a negative age as fresh forever.
        assertFalse(SeekPreview.targetStillValid(-1))
    }

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
