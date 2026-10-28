package net.mrowser.player

import net.mrowser.player.SeekGesture.ACTION_DOWN
import net.mrowser.player.SeekGesture.ACTION_UP
import net.mrowser.player.SeekGesture.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekGestureTest {

    @Test fun `first press arms the hold timer`() {
        assertEquals(
            Decision.ArmHold,
            SeekGesture.decide(ACTION_DOWN, sameKeyDown = false, holding = false)
        )
    }

    @Test fun `system auto-repeat while held is ignored`() {
        // Vendors differ on repeat rate; a burst of seeks per hold would be unusable.
        assertEquals(
            Decision.Ignore,
            SeekGesture.decide(ACTION_DOWN, sameKeyDown = true, holding = false)
        )
        assertEquals(
            Decision.Ignore,
            SeekGesture.decide(ACTION_DOWN, sameKeyDown = true, holding = true)
        )
    }

    @Test fun `pressing the other direction mid-hold starts a new gesture`() {
        // Not an auto-repeat: it is a different key, so it arms rather than being swallowed.
        assertEquals(
            Decision.ArmHold,
            SeekGesture.decide(ACTION_DOWN, sameKeyDown = false, holding = true)
        )
    }

    @Test fun `release before the threshold steps by the configured amount`() {
        assertEquals(
            Decision.Step,
            SeekGesture.decide(ACTION_UP, sameKeyDown = true, holding = false)
        )
    }

    @Test fun `release after the ramp started ends the hold instead of stepping`() {
        // Otherwise a hold would also jump one more step on release.
        assertEquals(
            Decision.EndHold,
            SeekGesture.decide(ACTION_UP, sameKeyDown = true, holding = true)
        )
    }

    @Test fun `the release of an untracked key is dropped, not treated as a tap`() {
        // Alternating left and right: the stale key's release must not seek once more.
        assertEquals(
            Decision.Ignore,
            SeekGesture.decide(ACTION_UP, sameKeyDown = false, holding = false)
        )
        assertEquals(
            Decision.Ignore,
            SeekGesture.decide(ACTION_UP, sameKeyDown = false, holding = true)
        )
    }

    @Test fun `an unmodelled action changes nothing`() {
        val multiple = 2 // KeyEvent.ACTION_MULTIPLE
        assertEquals(
            Decision.Ignore,
            SeekGesture.decide(multiple, sameKeyDown = false, holding = false)
        )
    }

    @Test fun `the player owns its keys while the controls are hidden`() {
        assertTrue(SeekGesture.ownsKey(controlsVisible = false, progressBarFocused = false))
    }

    @Test fun `the player owns its keys when the progress bar has focus`() {
        // The case visibility alone got wrong: a hold reveals the controls and seats focus back
        // on the bar, where OK must still pause rather than reach the bar's own commit-a-drag
        // handler, which the key path never starts.
        assertTrue(SeekGesture.ownsKey(controlsVisible = true, progressBarFocused = true))
    }

    @Test fun `a focused control keeps its own keys`() {
        // The settings gear must stay reachable with left/right, and OK must open its menu.
        assertFalse(SeekGesture.ownsKey(controlsVisible = true, progressBarFocused = false))
    }

    @Test fun `a hold in flight is finished whatever focus did meanwhile`() {
        // Revealing the controls mid-hold moves focus; the release must still end the ramp, so
        // its decision is independent of ownsKey.
        assertFalse(SeekGesture.ownsKey(controlsVisible = true, progressBarFocused = false))
        assertEquals(
            Decision.EndHold,
            SeekGesture.decide(ACTION_UP, sameKeyDown = true, holding = true)
        )
    }
}
