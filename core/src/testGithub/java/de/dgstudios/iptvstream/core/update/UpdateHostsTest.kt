package de.dgstudios.iptvstream.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHostsTest {
    @Test
    fun updateRedirectStaysOnGitHubHosts() {
        assertTrue(UpdateChecker.allowedDownloadHost("github.com"))
        assertTrue(UpdateChecker.allowedDownloadHost("objects.githubusercontent.com"))
        assertTrue(UpdateChecker.allowedDownloadHost("release-assets.githubusercontent.com"))
        assertFalse(UpdateChecker.allowedDownloadHost("evil.example"))
        assertFalse(UpdateChecker.allowedDownloadHost("github.com.evil.example"))
    }
}
