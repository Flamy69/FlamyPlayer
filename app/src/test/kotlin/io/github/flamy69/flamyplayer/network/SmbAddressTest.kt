package io.github.flamy69.flamyplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbAddressTest {

    @Test
    fun acceptsABareHost() {
        assertEquals("192.168.1.10" to emptyList<String>(), SmbAddress.parse("192.168.1.10"))
        assertEquals("nas" to emptyList<String>(), SmbAddress.parse("  nas  "))
    }

    @Test
    fun acceptsHostWithShareAndFolders() {
        assertEquals("nas" to listOf("video", "Films 2024"), SmbAddress.parse("nas/video/Films 2024"))
        assertEquals("nas" to listOf("video"), SmbAddress.parse("nas/video/"))
    }

    @Test
    fun acceptsWindowsNotation() {
        assertEquals("PC" to listOf("Share", "Movies"), SmbAddress.parse("""\\PC\Share\Movies"""))
    }

    @Test
    fun acceptsSmbUrls() {
        assertEquals("nas.local" to listOf("video"), SmbAddress.parse("smb://nas.local/video"))
        assertEquals("nas" to emptyList<String>(), SmbAddress.parse("SMB://nas"))
    }

    @Test
    fun rejectsInputWithoutAHost() {
        assertNull(SmbAddress.parse(""))
        assertNull(SmbAddress.parse("   "))
        assertNull(SmbAddress.parse("//"))
        assertNull(SmbAddress.parse("my nas/video"))
    }

    @Test
    fun splitsDomainFromUser() {
        assertEquals(null to "marge", SmbAddress.splitUser("marge"))
        assertEquals("HOME" to "marge", SmbAddress.splitUser("""HOME\marge"""))
        assertEquals("HOME" to "marge", SmbAddress.splitUser("HOME/marge"))
        assertEquals(null to "", SmbAddress.splitUser("  "))
    }

    @Test
    fun describesAServer() {
        val server = SmbServer("1", "", "nas", listOf("video", "films"), "", "")
        assertEquals("nas/video/films", server.address)
        assertEquals("nas/video/films", server.title)
        assertEquals("Living room", server.copy(name = "Living room").title)
    }
}
