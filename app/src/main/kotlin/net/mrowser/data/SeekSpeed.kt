package net.mrowser.data

/**
 * How fast a held left/right key ramps up while seeking.
 *
 * The cap and the ramp interval move together as one preset rather than two numeric settings:
 * "cap 16x, one step per 1.5s" is not something a user can predict the feel of, and the cap is
 * bounded in practice by the stream's segment length and key-frame spacing anyway (see
 * SeekRamp).
 *
 * [maxRate] is the top multiplier of content time over real time; [stepMs] is how long each
 * doubling holds before the next.
 */
enum class SeekSpeed(val maxRate: Int, val stepMs: Long) {
    /** 2x → 4x → 8x. ~25s of content for a 5s hold. */
    GENTLE(maxRate = 8, stepMs = 1_500),

    /** 2x → 4x → 8x → 16x. ~50s of content for a 5s hold. */
    STANDARD(maxRate = 16, stepMs = 1_200),

    /** 2x → 4x → 8x → 16x → 32x. ~1m50s of content for a 5s hold. */
    AGGRESSIVE(maxRate = 32, stepMs = 1_200)
}
