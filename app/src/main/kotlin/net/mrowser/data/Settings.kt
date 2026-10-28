package net.mrowser.data

/** App-wide settings. Defaults are the shipped values. Immutable — update via copy(). */
data class Settings(
    val autoOpenPlayer: Boolean = true,
    val cursorSpeed: CursorSpeed = CursorSpeed.NORMAL,
    /**
     * Refuse a window the page opens whose destination is on the block list. On a browser with
     * no tabs a new window replaces the page, which is how streaming sites turn a click on
     * their video into an ad. Off opens whatever was clicked with no list check.
     */
    val blockPopups: Boolean = true,
    /** Answer requests to listed ad hosts with an empty stand-in, and refuse in-page
     *  navigations to them. Sub-resources only; see AdBlockPolicy for what is never blocked. */
    val blockAds: Boolean = true,
    /** Registrable domains (`site.example`) on which the user has allowed ads. */
    val adsAllowedOn: Set<String> = emptySet(),
    /** Switch the display's refresh rate to the video's frame rate (or a multiple) while
     *  playing. Off by default: most TVs blank for a moment on every switch. */
    val matchFrameRate: Boolean = false,
    /** Switch the display's resolution to the video's while playing. Off by default, as above. */
    val matchResolution: Boolean = false,
    /** Seconds a short right press jumps forward. Clamped to [SEEK_MIN_SEC]..[SEEK_MAX_SEC]. */
    val seekForwardSec: Int = 15,
    /** Seconds a short left press jumps back. Smaller than forward by default: re-hearing a
     *  line needs a few seconds, skipping an intro needs more. */
    val seekBackSec: Int = 5,
    /** How fast a held left/right key ramps up. */
    val seekSpeed: SeekSpeed = SeekSpeed.STANDARD,
    /** Internal bookkeeping, not a user preference: default favorites written once. */
    val seeded: Boolean = false,
    /** Internal bookkeeping, not a user preference: hold-BACK hint shown once. */
    val navHintShown: Boolean = false
) {
    companion object {
        /** Seek-amount bounds, in seconds. The step is [SEEK_STEP_SEC], giving 12 stops. */
        const val SEEK_MIN_SEC = 5
        const val SEEK_MAX_SEC = 60
        const val SEEK_STEP_SEC = 5
    }
}
