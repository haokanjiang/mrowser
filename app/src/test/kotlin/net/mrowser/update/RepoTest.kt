package net.mrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every update route must agree on one repository. These pin the derivation, so changing a
 * fork's owner can't leave one URL behind on another repository — which is how a fork ends up
 * offering an APK signed with a key that cannot install over its own.
 */
class RepoTest {

    @Test fun `every url is derived from the same slug`() {
        assertTrue(Repo.HTML_URL.endsWith("/${Repo.SLUG}"))
        assertTrue(Repo.RELEASES_URL.startsWith(Repo.HTML_URL))
        assertTrue(Repo.LATEST_RELEASE_API.contains("/repos/${Repo.SLUG}/"))
        assertTrue(Repo.OBTAINIUM_ADD_URL.endsWith(Repo.HTML_URL))
    }

    @Test fun `the slug is owner over name`() {
        assertEquals("${Repo.OWNER}/${Repo.NAME}", Repo.SLUG)
    }

    @Test fun `the in-app check reads this repository`() {
        // ReleaseFetcher is what actually runs; it must not carry its own copy.
        assertEquals(Repo.LATEST_RELEASE_API, ReleaseFetcher.LATEST_RELEASE_URL)
    }

    @Test fun `the api url asks for the latest stable release`() {
        // /releases/latest excludes drafts and prereleases, which is what keeps a prerelease
        // from being offered to every user.
        assertTrue(Repo.LATEST_RELEASE_API.endsWith("/releases/latest"))
        assertTrue(Repo.LATEST_RELEASE_API.startsWith("https://api.github.com/"))
    }

    @Test fun `the download host is one DownloadUrl accepts`() {
        // The dialog's fallback link and the asset URLs live on these hosts; a mismatch would
        // make every download refuse at the last step.
        assertTrue(DownloadUrl.isAllowed("${Repo.RELEASES_URL}/download/v1.0.0/app-release.apk"))
    }

    @Test fun `obtainium is handed an https repository url`() {
        assertTrue(Repo.OBTAINIUM_ADD_URL.startsWith("obtainium://add/https://"))
    }
}
