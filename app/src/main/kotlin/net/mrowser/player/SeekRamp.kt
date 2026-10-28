package net.mrowser.player

import net.mrowser.data.SeekSpeed

/**
 * Pure ramp curve for a held left/right key: how fast the position runs, and where each tick
 * lands.
 *
 * A held key accelerates instead of stepping by a fixed amount, so one gesture covers both
 * "nudge past a line" and "skip a chapter". The rate doubles every [SeekSpeed.stepMs] from
 * [START_RATE] up to the preset's [SeekSpeed.maxRate].
 *
 * The timing is handed in rather than read from a clock, so this stays Android-free and
 * testable; [PlayerActivity] owns the Handler and the ticker.
 */
object SeekRamp {

    /** The rate a hold starts at, once the long-press threshold has passed. */
    const val START_RATE = 2

    /**
     * Content milliseconds per real millisecond at [heldMs] into the hold.
     *
     * [heldMs] is measured from the long-press threshold, not from the key going down, so a
     * hold that has only just been recognised starts at [START_RATE] rather than part-way up
     * the ramp.
     */
    fun rateAt(heldMs: Long, preset: SeekSpeed): Int {
        if (heldMs <= 0) return START_RATE
        val steps = heldMs / preset.stepMs
        // Doubling by shifting: step 0 -> 2x, step 1 -> 4x, ... Shift counts are clamped before
        // use, so a long hold can neither overflow nor exceed the preset's cap.
        val maxSteps = STEP_LIMIT.coerceAtMost(maxStepsFor(preset))
        val shift = steps.coerceIn(0, maxSteps.toLong()).toInt()
        return (START_RATE shl shift).coerceAtMost(preset.maxRate)
    }

    /**
     * Where a [tickMs]-long tick starting at [positionMs] lands, clamped to the stream.
     *
     * [durationMs] of [UNKNOWN_DURATION] (media3's `C.TIME_UNSET`, or any non-positive value)
     * means the timeline is not loaded, so only the lower bound is applied — a live edge or an
     * unknown length must not pin the position to 0.
     */
    fun advance(
        positionMs: Long,
        durationMs: Long,
        rate: Int,
        tickMs: Long,
        forward: Boolean
    ): Long {
        val delta = rate * tickMs * (if (forward) 1 else -1)
        val next = positionMs + delta
        if (next < 0) return 0
        if (durationMs > 0 && next > durationMs) return durationMs
        return next
    }

    /** Mirrors media3's `C.TIME_UNSET`: a duration that is not known yet. */
    const val UNKNOWN_DURATION = -9_223_372_036_854_775_807L

    /** Doublings needed to reach a preset's cap from [START_RATE]. */
    private fun maxStepsFor(preset: SeekSpeed): Int {
        var steps = 0
        var rate = START_RATE
        while (rate < preset.maxRate) {
            rate = rate shl 1
            steps++
        }
        return steps
    }

    /** Guards the shift itself, independently of any preset's cap. */
    private const val STEP_LIMIT = 16
}
