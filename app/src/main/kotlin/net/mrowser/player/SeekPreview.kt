package net.mrowser.player

/**
 * Pure: how far a held key moves the time bar's preview per tick.
 *
 * Normalising to the stream's duration — rather than to a multiplier of real time — is what
 * makes one hold feel the same on a 20-minute episode and a 3-hour film. A rate that climbs with
 * time instead (2x, 4x, … 32x) was far too slow on a long film: at the 32x cap, three hours
 * still needed nearly six seconds of holding *after* reaching it.
 *
 * The preview itself, its accumulation across presses and its single commit are all
 * `DefaultTimeBar`'s — see `PlayerActivity.scrubBy`. Only the step size is ours, because the
 * bar's own is `duration / 20`: 5m09s per press on a 1h43m film.
 *
 * Modelled on blbl's player (`holdScrubStepMs` in `PlayerActivityControlsSeek.kt`).
 */
object SeekPreview {

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
}
