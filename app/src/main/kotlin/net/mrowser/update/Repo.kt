package net.mrowser.update

/**
 * The repository this build updates from, in one place.
 *
 * Every user-facing update route has to agree on it: the release the in-app check reads, the
 * repository Obtainium is told to subscribe to, and the link the dialog falls back to when
 * `DownloadManager` is unavailable. Two of those were hardcoded separately and drifted onto
 * different repositories — a fork's users were offered the upstream's APK, which **cannot be
 * installed over this one**, since the two are signed with different keys. So the owner and
 * name are declared once here and every URL is derived.
 *
 * Changing a fork's update channel is now this file alone.
 */
object Repo {

    const val OWNER = "haokanjiang"
    const val NAME = "mrowser"

    /** `owner/name`, as GitHub paths spell it. */
    const val SLUG = "$OWNER/$NAME"

    const val HTML_URL = "https://github.com/$SLUG"

    /** The releases page — where the update dialog sends a user it cannot download for. */
    const val RELEASES_URL = "$HTML_URL/releases"

    /**
     * GitHub's newest non-draft, non-prerelease release. Prereleases published from this
     * repository therefore never reach users through the in-app check.
     */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$SLUG/releases/latest"

    /** Hands this repository to Obtainium, which then tracks its releases by itself. */
    const val OBTAINIUM_ADD_URL = "obtainium://add/$HTML_URL"
}
