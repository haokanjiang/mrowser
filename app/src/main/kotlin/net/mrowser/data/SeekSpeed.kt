package net.mrowser.data

/**
 * How fast a held left/right key scrubs, as the time it takes to cross a whole stream.
 *
 * Normalising to the duration — rather than a multiplier of real time — is what makes one hold
 * feel the same on a 20-minute episode and a 3-hour film. A rate that climbs with time instead
 * (2x, 4x, … 32x) was far too slow on a long film: at the 32x cap, three hours still needed
 * nearly six seconds of holding *after* reaching it.
 *
 * [traverseMs] is how long a hold takes to run end to end, so a shorter value scrubs faster.
 */
enum class SeekSpeed(val traverseMs: Long) {
    /** ~20s end to end: fine for finding a scene. */
    GENTLE(traverseMs = 20_000),

    /** ~10s end to end. */
    STANDARD(traverseMs = 10_000),

    /** ~5s end to end: a flick across a long film. */
    FAST(traverseMs = 5_000)
}
