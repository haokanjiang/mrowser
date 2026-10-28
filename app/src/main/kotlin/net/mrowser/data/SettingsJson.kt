package net.mrowser.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Pure JSON (de)serialization of Settings. Missing/unparseable fields → defaults. */
object SettingsJson {

    fun toJson(settings: Settings): String =
        JSONObject()
            .put("autoOpenPlayer", settings.autoOpenPlayer)
            .put("cursorSpeed", settings.cursorSpeed.name)
            .put("blockPopups", settings.blockPopups)
            .put("blockAds", settings.blockAds)
            .put("adsAllowedOn", JSONArray(settings.adsAllowedOn.sorted()))
            .put("matchFrameRate", settings.matchFrameRate)
            .put("matchResolution", settings.matchResolution)
            .put("seekForwardSec", settings.seekForwardSec)
            .put("seekBackSec", settings.seekBackSec)
            .put("seekSpeed", settings.seekSpeed.name)
            .put("seeded", settings.seeded)
            .put("navHintShown", settings.navHintShown)
            .toString()

    fun fromJson(json: String): Settings {
        if (json.isBlank()) return Settings()
        return try {
            val o = JSONObject(json)
            val defaults = Settings()
            Settings(
                autoOpenPlayer = o.optBoolean("autoOpenPlayer", defaults.autoOpenPlayer),
                cursorSpeed = enumOrDefault(o.optString("cursorSpeed"), defaults.cursorSpeed),
                blockPopups = o.optBoolean("blockPopups", defaults.blockPopups),
                blockAds = o.optBoolean("blockAds", defaults.blockAds),
                adsAllowedOn = stringSet(o.optJSONArray("adsAllowedOn")) ?: defaults.adsAllowedOn,
                matchFrameRate = o.optBoolean("matchFrameRate", defaults.matchFrameRate),
                matchResolution = o.optBoolean("matchResolution", defaults.matchResolution),
                // Clamped on read as well as on write: a hand-edited file must not be able to
                // put the player on a 0s or 10-minute jump.
                seekForwardSec = seekSec(o, "seekForwardSec", defaults.seekForwardSec),
                seekBackSec = seekSec(o, "seekBackSec", defaults.seekBackSec),
                seekSpeed = enumOrDefault(o.optString("seekSpeed"), defaults.seekSpeed),
                seeded = o.optBoolean("seeded", defaults.seeded),
                navHintShown = o.optBoolean("navHintShown", defaults.navHintShown)
            )
        } catch (e: JSONException) {
            Settings()
        }
    }

    /** A seek amount in seconds, clamped to the supported range. */
    private fun seekSec(o: JSONObject, key: String, default: Int): Int =
        o.optInt(key, default).coerceIn(Settings.SEEK_MIN_SEC, Settings.SEEK_MAX_SEC)

    /** Non-string and blank elements are skipped; a missing/non-array value is null. */
    private fun stringSet(arr: JSONArray?): Set<String>? {
        if (arr == null) return null
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) as? String ?: continue
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        T::class.java.enumConstants?.firstOrNull { it.name == name } ?: default
}
