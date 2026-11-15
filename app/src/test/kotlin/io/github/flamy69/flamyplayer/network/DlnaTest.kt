package io.github.flamy69.flamyplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {

    private val location = "http://192.168.1.20:2869/upnphost/udhisapi.dll?content=uuid:1234"

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>MEDIA-PC: films:</friendlyName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/upnphost/udhisapi.dll?control=uuid:1234+urn:upnp-org:serviceId:ConnectionManager</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>/upnphost/udhisapi.dll?control=uuid:1234+urn:upnp-org:serviceId:ContentDirectory</controlURL>
              </service>
            </serviceList>
          </device>
        </root>"""

    private fun envelope(didl: String, returned: Int, total: Int): String {
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>$escaped</Result>
            <NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>7</UpdateID>
            </u:BrowseResponse></s:Body></s:Envelope>"""
    }

    private val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
        <container id="2" parentID="0" childCount="5">
          <dc:title>Videos</dc:title><upnp:class>object.container</upnp:class>
        </container>
        <item id="{A1}.0.8" parentID="2">
          <dc:title>Holiday &amp; more</dc:title><upnp:class>object.item.videoItem</upnp:class>
          <res protocolInfo="http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_PAL;DLNA.ORG_CI=1" duration="0:01:05.000">http://192.168.1.20:10243/WMPNSSv4/1/converted.mpg</res>
          <res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_OP=01" size="1500000000" duration="1:23:45.500">http://192.168.1.20:10243/WMPNSSv4/1/original.mkv</res>
          <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.1.20:10243/WMPNSSv4/1/thumb.jpg</res>
          <res protocolInfo="rtsp-rtp-udp:*:video/x-ms-wmv:*">rtsp://192.168.1.20/stream</res>
        </item>
        <item id="song" parentID="2">
          <dc:title>A song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>
          <res protocolInfo="http-get:*:audio/mpeg:*">http://192.168.1.20:10243/song.mp3</res>
        </item>
        <item id="relative" parentID="2">
          <dc:title>Clip</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>
          <res protocolInfo="http-get:*:application/octet-stream:*">/media/clip.avi</res>
        </item>
        </DIDL-Lite>"""

    @Test
    fun readsTheDescriptionOfAServer() {
        val server = Dlna.parseDescription(description, location, "192.168.1.20")
        assertEquals("MEDIA-PC: films:", server.name)
        assertEquals("uuid:1234", server.id)
        assertEquals("192.168.1.20", server.address)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:1", server.serviceType)
        assertEquals(
            "http://192.168.1.20:2869/upnphost/udhisapi.dll?control=uuid:1234+urn:upnp-org:serviceId:ContentDirectory",
            server.controlUrl,
        )
    }

    @Test
    fun honorsTheBaseAddressOfADescription() {
        val withBase = description.replace("<device>", "<URLBase>http://192.168.1.20:8200/</URLBase><device>")
            .replace("/upnphost/udhisapi.dll?control=uuid:1234+urn:upnp-org:serviceId:ContentDirectory", "ctl/ContentDir")
        assertEquals("http://192.168.1.20:8200/ctl/ContentDir", Dlna.parseDescription(withBase, location, "x").controlUrl)
    }

    @Test
    fun aDeviceThatIsNotAMediaServerHasNothingToBrowse() {
        val router = """<root><device><friendlyName>Router</friendlyName><UDN>uuid:9</UDN></device></root>"""
        val server = Dlna.parseDescription(router, location, "192.168.1.1")
        assertEquals("Router", server.name)
        assertNull(server.controlUrl)
    }

    @Test
    fun listsFoldersAndVideos() {
        val page = Dlna.parseBrowseResponse(envelope(didl, returned = 4, total = 9), "http://192.168.1.20:2869/control")
        assertEquals(4, page.returned)
        assertEquals(9, page.total)
        // The song is not a video and is left out.
        assertEquals(listOf("Videos", "Holiday & more", "Clip"), page.entries.map { it.title })

        val folder = page.entries[0]
        assertTrue(folder.isFolder)
        assertEquals("2", folder.id)
        assertNull(folder.url)

        val film = page.entries[1]
        assertFalse(film.isFolder)
        assertEquals("{A1}.0.8", film.id)
        // The original file wins over the converted one, the thumbnail and the RTSP stream.
        assertEquals("http://192.168.1.20:10243/WMPNSSv4/1/original.mkv", film.url)
        assertEquals(1_500_000_000L, film.sizeBytes)
        assertEquals(5_025_500L, film.durationMs)

        // An address without a host is relative to the server.
        assertEquals("http://192.168.1.20:2869/media/clip.avi", page.entries[2].url)
    }

    @Test
    fun copesWithAnEmptyFolder() {
        val empty = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"></DIDL-Lite>"""
        val page = Dlna.parseBrowseResponse(envelope(empty, returned = 0, total = 0), "http://host/control")
        assertTrue(page.entries.isEmpty())
        assertEquals(0, page.returned)
    }

    @Test
    fun reportsTheReasonOfARefusal() {
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
            <errorCode>701</errorCode><errorDescription>No such object</errorDescription>
            </UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        val error = assertThrows(MediaServerException::class.java) { Dlna.parseBrowseResponse(fault, "http://host/control") }
        assertTrue(error.message, error.message == "No such object" || error.message == "UPnPError")
        assertThrows(MediaServerException::class.java) { Dlna.parseBrowseResponse("not xml at all", "http://host/control") }
    }

    @Test
    fun asksForTheChildrenOfAFolder() {
        val request = Dlna.browseRequest("urn:schemas-upnp-org:service:ContentDirectory:1", "a<b&c", 40, 200)
        assertTrue("<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" in request)
        assertTrue("<ObjectID>a&lt;b&amp;c</ObjectID>" in request)
        assertTrue("<BrowseFlag>BrowseDirectChildren</BrowseFlag>" in request)
        assertTrue("<StartingIndex>40</StartingIndex><RequestedCount>200</RequestedCount>" in request)
    }

    @Test
    fun readsDurations() {
        assertEquals(65_000L, Dlna.parseDuration("0:01:05"))
        assertEquals(3_723_250L, Dlna.parseDuration("1:02:03.250"))
        assertEquals(0L, Dlna.parseDuration(""))
        assertEquals(0L, Dlna.parseDuration(null))
        assertEquals(0L, Dlna.parseDuration("soon"))
    }
}
