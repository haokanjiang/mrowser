package net.mrowser.player

/**
 * Pure: where a seek gesture is pointing, and how fast a hold moves it.
 *
 * The gesture owns its target here rather than reading one back from the player or from
 * `DefaultTimeBar`. Both of those lag: after a `seekTo`, media3 masks the position to the
 * requested value, but the internal player then acks the **resolved** one, and an adaptive
 * stream resolves to a segment boundary — so the reported position can settle several seconds
 * behind the request. A gesture that re-bases on it loses that difference on every press, which
 * is why a run of forward presses could stall, or even net out backwards.
 *
 * The hold's step is normalised to the stream's duration rather than to a multiplier of real
 * time, which is what makes one hold feel the same on a 20-minute episode and a 3-hour film. A
 * rate that climbs with time instead (2x, 4x, … 32x) was far too slow on a long film: at the
 * 32x cap, three hours still needed nearly six seconds of holding *after* reaching it.
 *
 * Modelled on blbl's player (`holdScrubStepMs` and the deferred key-seek preview in
 * `PlayerActivityControlsSeek.kt`).
 */
object SeekPreview {

    /** No target is held: the next step starts from the player's own position. */
    const val NONE = -1L

    /** How far from the end a step stops, so a seek cannot trip `STATE_ENDED`. */
    const val END_GUARD_MS = 500L

    /**
     * Where a step of [deltaMs] lands, clamped to the stream.
     *
     * [targetMs] is the gesture's own running target ([NONE] for a fresh gesture) and
     * [positionMs] the player's position, used only to start one. **A run of presses must never
     * re-read the player**: after a seek, media3 masks the position to the requested value but
     * the internal player then acks the *resolved* one, and an adaptive stream resolves to a
     * segment boundary — so `currentPosition` can settle several seconds **behind** the request.
     * Re-basing on it mid-run loses that difference every press, and enough of them turn a run
     * of forward presses into a net move backwards.
     *
     * A [durationMs] that is not positive (media3's `C.TIME_UNSET`) means the timeline is not
     * loaded, so only the lower bound applies — a live edge must not be pinned to 0.
     */
    fun step(targetMs: Long, positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val base = if (targetMs == NONE) positionMs else targetMs
        val next = base + deltaMs
        if (next < 0) return 0
        if (durationMs <= 0) return next
        // Short of the very last frame: landing on the duration ends playback, which would turn
        // "skip towards the end" into "finish the film" and clear the resume entry with it.
        return next.coerceAtMost((durationMs - END_GUARD_MS).coerceAtLeast(0))
    }

    /**
     * Content milliseconds a hold advances per [tickMs], so that holding for [traverseMs]
     * crosses the whole stream.
     *
     * Returns 0 when the duration is unknown (media3's `C.TIME_UNSET`, or any non-positive
     * value): a hold cannot scrub a timeline it cannot measure, and the caller falls back to
     * stepping instead.
     */
    fun holdStepMs(durationMs: Long, tickMs: Long, traverseMs: Long): Long {
        if (durationMs <= 0) return 0
        val tick = tickMs.coerceAtLeast(1)
        val traverse = traverseMs.coerceAtLeast(tick)
        // Long arithmetic throughout: durationMs * tick overflows Int past about 36 seconds.
        return (durationMs * tick / traverse).coerceAtLeast(1)
    }

    /**
     * Whether a target recorded [ageMs] ago may still be accumulated onto.
     *
     * A target outlives its commit on purpose, so the presses of one run compound even while
     * the seek they caused is still resolving. It must not outlive the run itself, though, or a
     * press minutes later would start from a position the video left long ago.
     */
    fun targetStillValid(ageMs: Long): Boolean = ageMs in 0..TARGET_TTL_MS

    /** How long a target survives its last press. Comfortably over one commit delay. */
    const val TARGET_TTL_MS = 1_500L
}
