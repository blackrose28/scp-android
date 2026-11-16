package run.scp.mobile.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicKeyCardTest {
    private val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAmEWg672WPiPCwiG15NuFuQ6NnAEVLElJ4CBvbwjyL8"

    @Test fun `a valid authorized_keys line is returned trimmed`() {
        assertEquals(line, authorizedKeyLine("  $line\n"))
    }

    @Test fun `the loading placeholder and blanks are not copyable`() {
        assertNull(authorizedKeyLine(""))
        assertNull(authorizedKeyLine("   "))
        assertNull(authorizedKeyLine("…"))
    }

    @Test fun `anything that is not a single ed25519 line is rejected`() {
        assertNull(authorizedKeyLine("ssh-rsa AAAAB3Nza"))
        assertNull(authorizedKeyLine("ssh-ed25519"))
        assertNull(authorizedKeyLine("$line\n$line"))
        assertNull(authorizedKeyLine("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }
}
