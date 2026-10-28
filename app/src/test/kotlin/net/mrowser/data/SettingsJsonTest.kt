package net.mrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {

    @Test fun `round trips all fields`() {
        val s = Settings(autoOpenPlayer = false, cursorSpeed = CursorSpeed.FAST)
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun `round trips the pop-up blocker`() {
        val s = Settings(blockPopups = false)
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun `the pop-up blocker is on when the field is absent`() {
        assertTrue(SettingsJson.fromJson("{\"autoOpenPlayer\":true}").blockPopups)
    }

    @Test fun `missing fields fall back to defaults`() {
        assertEquals(Settings(), SettingsJson.fromJson("{}"))
    }

    @Test fun `blank input is all defaults`() {
        assertEquals(Settings(), SettingsJson.fromJson(""))
    }

    @Test fun `corrupt input is all defaults`() {
        assertEquals(Settings(), SettingsJson.fromJson("not json"))
    }

    @Test fun `unknown enum name falls back to default`() {
        val s = SettingsJson.fromJson("""{"autoOpenPlayer":true,"cursorSpeed":"WARP"}""")
        assertEquals(CursorSpeed.NORMAL, s.cursorSpeed)
    }

    @Test fun `round trips the internal flags`() {
        val s = Settings(seeded = true, navHintShown = true)
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun `internal flags default to false for a pre-upgrade file`() {
        val s = SettingsJson.fromJson("""{"autoOpenPlayer":true,"cursorSpeed":"NORMAL"}""")
        assertFalse(s.seeded)
        assertFalse(s.navHintShown)
    }

    @Test fun `round trips the ad blocker fields`() {
        val s = Settings(blockAds = false, adsAllowedOn = setOf("site.example", "other.example"))
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun `ad blocking is on and the allowlist empty when the fields are absent`() {
        val s = SettingsJson.fromJson("""{"autoOpenPlayer":true}""")
        assertTrue(s.blockAds)
        assertTrue(s.adsAllowedOn.isEmpty())
    }

    @Test fun `non-string allowlist entries are skipped`() {
        val s = SettingsJson.fromJson("""{"adsAllowedOn":["site.example", 5, null, "", "b.example"]}""")
        assertEquals(setOf("site.example", "b.example"), s.adsAllowedOn)
    }

    @Test fun `a malformed allowlist falls back to empty`() {
        assertTrue(SettingsJson.fromJson("""{"adsAllowedOn":"nope"}""").adsAllowedOn.isEmpty())
    }

    @Test fun `round trips the display-mode matching toggles`() {
        val s = Settings(matchFrameRate = true, matchResolution = true)
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
        val rateOnly = Settings(matchFrameRate = true)
        assertEquals(rateOnly, SettingsJson.fromJson(SettingsJson.toJson(rateOnly)))
    }

    @Test fun `display-mode matching is off by default`() {
        assertFalse(Settings().matchFrameRate)
        assertFalse(Settings().matchResolution)
    }

    @Test fun `display-mode matching is off for a pre-upgrade file`() {
        val s = SettingsJson.fromJson("""{"autoOpenPlayer":false,"blockAds":false,"seeded":true}""")
        assertFalse(s.matchFrameRate)
        assertFalse(s.matchResolution)
        assertFalse(s.autoOpenPlayer)
        assertTrue(s.seeded)
    }

    @Test fun `round trips the seek settings`() {
        val s = Settings(seekForwardSec = 30, seekBackSec = 10, seekSpeed = SeekSpeed.GENTLE)
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun `seek settings keep their defaults for a pre-upgrade file`() {
        val s = SettingsJson.fromJson("""{"autoOpenPlayer":true,"seeded":true}""")
        assertEquals(15, s.seekForwardSec)
        assertEquals(5, s.seekBackSec)
        assertEquals(SeekSpeed.STANDARD, s.seekSpeed)
    }

    @Test fun `a seek amount outside the range is clamped on read`() {
        // A hand-edited file must not be able to put the player on a 0s or 10-minute jump.
        val low = SettingsJson.fromJson("""{"seekForwardSec":0,"seekBackSec":-30}""")
        assertEquals(Settings.SEEK_MIN_SEC, low.seekForwardSec)
        assertEquals(Settings.SEEK_MIN_SEC, low.seekBackSec)

        val high = SettingsJson.fromJson("""{"seekForwardSec":600,"seekBackSec":999}""")
        assertEquals(Settings.SEEK_MAX_SEC, high.seekForwardSec)
        assertEquals(Settings.SEEK_MAX_SEC, high.seekBackSec)
    }

    @Test fun `an unknown seek speed falls back to standard`() {
        assertEquals(
            SeekSpeed.STANDARD,
            SettingsJson.fromJson("""{"seekSpeed":"LUDICROUS"}""").seekSpeed
        )
    }

    @Test fun `a non-numeric seek amount falls back to the default`() {
        assertEquals(15, SettingsJson.fromJson("""{"seekForwardSec":"fast"}""").seekForwardSec)
    }
}
