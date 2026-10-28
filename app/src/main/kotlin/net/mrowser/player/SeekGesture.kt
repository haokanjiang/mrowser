package net.mrowser.player

/**
 * Pure decision table for the player's seek keys: left/right as tap-versus-hold, and OK.
 *
 * media3 gives the first D-pad press to the controls and nothing else
 * (`PlayerView.dispatchKeyEvent`), so seeking costs three presses and OK does not pause. The
 * Activity intercepts those keys before the view tree instead, and this decides what each one
 * means. [PlayerActivity] owns the Handler, the ticker and the `down`/`holding` flags; it only
 * applies the [Decision] returned here.
 *
 * Key actions arrive as raw Ints (`android.view.KeyEvent.ACTION_DOWN` == 0, `ACTION_UP` == 1)
 * so this module stays free of Android imports, as [net.mrowser.web.BackGesture] does.
 */
object SeekGesture {

    /** Mirrors `android.view.KeyEvent.ACTION_DOWN`. */
    const val ACTION_DOWN = 0

    /** Mirrors `android.view.KeyEvent.ACTION_UP`. */
    const val ACTION_UP = 1

    /** How long left/right must be held before it becomes a ramp instead of a single step. */
    const val HOLD_MS = 400L

    enum class Decision {
        /** First press: begin the gesture and arm the hold timer. Nothing moves yet — a short
         *  press must not also reveal the controls, so the seek waits for the release. */
        ArmHold,

        /** An auto-repeat press while the key is still held, the release of a key we are not
         *  tracking, or an action we don't model. Consume it and leave the state alone: the
         *  system's repeat rate is vendor-specific and would otherwise fire a burst of seeks. */
        Ignore,

        /** Released before the threshold: seek by the configured amount, show no controls. */
        Step,

        /** Released after the ramp started: stop the ticker, leave scrubbing mode, and let the
         *  controls time out. The position is already where the ramp left it. */
        EndHold
    }

    /**
     * @param action the raw key action ([ACTION_DOWN] / [ACTION_UP]).
     * @param sameKeyDown true if **this** key is the one whose gesture is in progress. A press
     *   on the other direction mid-hold is therefore not an auto-repeat, and the release of a
     *   key we stopped tracking cannot be mistaken for a tap.
     * @param holding true if this gesture's hold timer already fired and the ramp is running.
     */
    fun decide(action: Int, sameKeyDown: Boolean, holding: Boolean): Decision =
        when (action) {
            ACTION_DOWN -> if (sameKeyDown) Decision.Ignore else Decision.ArmHold
            // A release we are not tracking is dropped rather than treated as a tap: otherwise
            // alternating left and right would leave the stale key's release to seek once more.
            ACTION_UP -> when {
                !sameKeyDown -> Decision.Ignore
                holding -> Decision.EndHold
                else -> Decision.Step
            }
            else -> Decision.Ignore
        }

    /**
     * Whether a **new** gesture may start here.
     *
     * Allowed when the progress bar holds focus, and when the controls are hidden (nothing in
     * them has focus). With focus anywhere else — the settings gear — the keys fall through to
     * normal focus movement, or the gear could never be reached.
     *
     * This gates starting only. A gesture already in flight always runs to its release, because
     * revealing the controls mid-hold moves focus: re-testing this on the release could drop it
     * and leave the ramp running with scrubbing mode still on.
     */
    fun canStart(controlsVisible: Boolean, progressBarFocused: Boolean): Boolean =
        !controlsVisible || progressBarFocused
}
