package io.github.flamy69.flamyplayer.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class NetBiosTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun short(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    /** An answer as a server would send it: header, the name asked for, one record. */
    private fun answer(transactionId: Int, flags: Int, encodedName: ByteArray, type: Int, data: ByteArray): ByteArray =
        short(transactionId) + short(flags) + short(0) + short(1) + short(0) + short(0) +
            encodedName + short(type) + short(1) + ByteArray(4) + short(data.size) + data

    private fun statusEntry(name: String, suffix: Int, group: Boolean): ByteArray =
        ascii(name.padEnd(15)) + byteArrayOf(suffix.toByte()) + short(if (group) 0x8400 else 0x0400)

    @Test
    fun encodesNamesAsInTheSpecification() {
        // The example from RFC 1001: "FRED" padded with spaces.
        val encoded = NetBios.encodeName("fred", 0x20)
        assertEquals(34, encoded.size)
        assertEquals(32, encoded[0].toInt())
        assertEquals("EGFCEFEECACACACACACACACACACACACA", String(encoded, 1, 32, Charsets.US_ASCII))
        assertEquals(0, encoded[33].toInt())
    }

    @Test
    fun buildsANameQuery() {
        val query = NetBios.nameQuery(0x1234, "MEDIA-PC")
        assertEquals(50, query.size)
        assertArrayEquals(short(0x1234) + short(0x0110) + short(1) + ByteArray(6), query.copyOfRange(0, 12))
        assertArrayEquals(NetBios.encodeName("MEDIA-PC", 0x20), query.copyOfRange(12, 46))
        assertArrayEquals(short(0x20) + short(1), query.copyOfRange(46, 50))
    }

    @Test
    fun buildsAStatusQuery() {
        val query = NetBios.statusQuery(7)
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(query, 13, 32, Charsets.US_ASCII))
        assertArrayEquals(short(0x21) + short(1), query.copyOfRange(46, 50))
    }

    @Test
    fun recognizesAPositiveNameAnswer() {
        val name = NetBios.encodeName("MEDIA-PC", 0x20)
        val data = short(0) + byteArrayOf(192.toByte(), 168.toByte(), 1, 20)
        val packet = answer(0x1234, 0x8500, name, 0x20, data)
        assertTrue(NetBios.isNameResponse(packet, packet.size, 0x1234))
        // Somebody else's answer, a truncated one, and "no such name".
        assertFalse(NetBios.isNameResponse(packet, packet.size, 0x4321))
        assertFalse(NetBios.isNameResponse(packet, packet.size - 3, 0x1234))
        val negative = answer(0x1234, 0x8503, name, 0x20, data)
        assertFalse(NetBios.isNameResponse(negative, negative.size, 0x1234))
        // A request is not an answer.
        val request = NetBios.nameQuery(0x1234, "MEDIA-PC")
        assertFalse(NetBios.isNameResponse(request, request.size, 0x1234))
    }

    @Test
    fun readsTheMachineNameFromAStatusAnswer() {
        val entries = statusEntry("WORKGROUP", 0x00, group = true) +
            statusEntry("MEDIA-PC", 0x00, group = false) +
            statusEntry("MEDIA-PC", 0x20, group = false)
        val data = byteArrayOf(3) + entries + ByteArray(46)
        val wildcard = NetBios.statusQuery(1).copyOfRange(12, 46)
        val packet = answer(99, 0x8400, wildcard, 0x21, data)
        assertEquals("MEDIA-PC", NetBios.statusName(packet, packet.size, 99))
        assertNull(NetBios.statusName(packet, packet.size, 98))
    }

    @Test
    fun fallsBackToTheWorkstationName() {
        val data = byteArrayOf(2) + statusEntry("HOME", 0x00, group = true) + statusEntry("LAPTOP", 0x00, group = false)
        val wildcard = NetBios.statusQuery(1).copyOfRange(12, 46)
        val packet = answer(5, 0x8400, wildcard, 0x21, data)
        assertEquals("LAPTOP", NetBios.statusName(packet, packet.size, 5))
    }

    @Test
    fun tellsNetBiosNamesFromOtherHosts() {
        assertTrue(NetBios.isValidName("MEDIA-PC"))
        assertTrue(NetBios.isValidName("nas"))
        assertFalse(NetBios.isValidName("nas.local"))
        assertFalse(NetBios.isValidName("192.168.1.10"))
        assertFalse(NetBios.isValidName("a-very-long-computer-name"))
        assertFalse(NetBios.isValidName(""))
    }

    @Test
    fun computesTheBroadcastAddress() {
        val phone = Subnet.toInt(InetAddress.getByName("192.168.1.37") as Inet4Address)
        assertEquals("192.168.1.255", Subnet.toAddress(Subnet.broadcast(phone, 24)).hostAddress)
        assertEquals("192.168.255.255", Subnet.toAddress(Subnet.broadcast(phone, 16)).hostAddress)
        assertEquals("255.255.255.255", Subnet.toAddress(-1).hostAddress)
    }

    @Test
    fun listsTheNeighborsOfAHomeNetwork() {
        val phone = Subnet.toInt(InetAddress.getByName("192.168.1.37") as Inet4Address)
        val neighbors = Subnet.neighbors(phone, 24).map { Subnet.toAddress(it).hostAddress }
        assertEquals(253, neighbors.size)
        assertEquals("192.168.1.1", neighbors.first())
        assertEquals("192.168.1.254", neighbors.last())
        assertFalse("192.168.1.37" in neighbors)
    }

    @Test
    fun limitsTheSearchOnBigNetworks() {
        val phone = Subnet.toInt(InetAddress.getByName("10.20.30.40") as Inet4Address)
        val neighbors = Subnet.neighbors(phone, 8).map { Subnet.toAddress(it).hostAddress }
        assertEquals(253, neighbors.size)
        assertTrue(neighbors.all { it!!.startsWith("10.20.30.") })
        assertTrue(Subnet.neighbors(phone, 31).isEmpty())
    }
}
