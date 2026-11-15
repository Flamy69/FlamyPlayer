package io.github.flamy69.flamyplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DiscoveryTest {

    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun findsAnOpenPort() {
        ServerSocket(0, 1, loopback).use { server ->
            assertEquals(listOf(loopback), PortScan.open(listOf(loopback), server.localPort, 2000))
        }
    }

    @Test
    fun skipsAClosedPort() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        assertTrue(PortScan.open(listOf(loopback), port, 2000).isEmpty())
    }

    @Test
    fun triesAllMachinesAtOnce() {
        ServerSocket(0, 8, loopback).use { server ->
            // Nothing listens on the other loopback addresses.
            val others = (2..9).map { InetAddress.getByAddress(byteArrayOf(127, 0, 0, it.toByte())) }
            assertEquals(listOf(loopback), PortScan.open(others + loopback, server.localPort, 2000))
            assertTrue(PortScan.open(emptyList(), server.localPort, 2000).isEmpty())
        }
    }

    @Test
    fun asksForMediaServers() {
        val request = String(Ssdp.request(), Charsets.US_ASCII)
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in request)
        assertTrue("MAN: \"ssdp:discover\"\r\n" in request)
        assertTrue("ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" in request)
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun recognizesTheAnswerOfAMediaServer() {
        val answer = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.20:2869/upnphost/udhisapi.dll?content=uuid:1234\r\n" +
            "st:urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "USN: uuid:1234::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertTrue(Ssdp.isAnswer(answer))
    }

    @Test
    fun ignoresOtherTraffic() {
        // Somebody else searching, an announcement, and an answer about another kind of device.
        assertFalse(Ssdp.isAnswer(String(Ssdp.request(), Charsets.US_ASCII)))
        assertFalse(Ssdp.isAnswer("NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"))
        assertFalse(Ssdp.isAnswer("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
        assertFalse(Ssdp.isAnswer(""))
    }

    @Test
    fun matchesADeviceToTheHostOfASavedFolder() {
        val device = FoundDevice("MEDIA-PC", "192.168.1.20")
        assertTrue(device.isCalled("media-pc"))
        assertTrue(device.isCalled("192.168.1.20"))
        assertFalse(device.isCalled("OTHER-PC"))
        assertFalse(FoundDevice(null, "192.168.1.20").isCalled("MEDIA-PC"))
    }
}
