package io.github.flamy69.flamyplayer.network

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A media server on the local network: "media streaming" of a Windows computer, or the media
 * server of a NAS. It offers its library to everybody on the network, without signing in.
 */
data class MediaServer(
    /** Stays the same when the server gets a new address. */
    val id: String,
    /** Where the server describes itself. */
    val location: String,
    val address: String,
    val name: String,
    /** Where to send requests for its folders; `null` if the description could not be read. */
    val controlUrl: String?,
    val serviceType: String?,
)

/** A folder of a media server, as the server calls it. */
data class MediaFolder(val id: String, val title: String)

/** One line of a folder of a media server: another folder, or a video. */
class MediaEntry(
    val id: String,
    val title: String,
    val isFolder: Boolean,
    /** Where the video can be played from. */
    val url: String?,
    val sizeBytes: Long,
    val durationMs: Long,
)

/** A media server refused a request or answered with something that makes no sense. */
class MediaServerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Talks to media servers the way a TV does (UPnP AV, known as DLNA). All methods block. */
object Dlna {

    /** The folder everything else is in. */
    const val ROOT = "0"

    private const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:"
    private const val PAGE_SIZE = 200
    private const val MAX_ENTRIES = 5000
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 15000

    /** Some servers start their answers with this invisible character. */
    private const val BYTE_ORDER_MARK = '\uFEFF'

    internal class Page(val entries: List<MediaEntry>, val returned: Int, val total: Int)

    /** Reads what the server at [location] says about itself. */
    @Throws(IOException::class)
    fun describe(location: String, address: String): MediaServer =
        parseDescription(exchange(location, action = null, body = null), location, address)

    /** Lists the folders and videos in the folder [folderId] of [server]. */
    @Throws(IOException::class)
    fun browse(server: MediaServer, folderId: String): List<MediaEntry> {
        // A server whose description could not be read during the search gets another chance.
        val known = if (server.controlUrl == null) describe(server.location, server.address) else server
        val controlUrl = known.controlUrl ?: throw MediaServerException("The server has no content directory")
        val serviceType = known.serviceType ?: (CONTENT_DIRECTORY + "1")

        val entries = ArrayList<MediaEntry>()
        var start = 0
        while (entries.size < MAX_ENTRIES) {
            val response = exchange(controlUrl, "\"$serviceType#Browse\"", browseRequest(serviceType, folderId, start, PAGE_SIZE))
            val page = parseBrowseResponse(response, controlUrl)
            entries += page.entries
            start += page.returned
            if (page.returned == 0 || start >= page.total) break
        }
        return entries
    }

    internal fun browseRequest(serviceType: String, folderId: String, start: Int, count: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:Browse xmlns:u="$serviceType">""" +
            "<ObjectID>${escape(folderId)}</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount>" +
            "<SortCriteria></SortCriteria>" +
            "</u:Browse></s:Body></s:Envelope>"

    internal fun parseDescription(xml: String, location: String, address: String): MediaServer {
        val elements = elements(parse(xml))
        val service = elements.firstOrNull { element ->
            element.name == "service" && element.child("serviceType")?.text.orEmpty().startsWith(CONTENT_DIRECTORY)
        }
        // The device the service belongs to; a router may describe several devices in one file.
        val device = service?.parentNode?.parentNode as? Element
        val name = device?.child("friendlyName")?.text
            ?: elements.firstOrNull { it.name == "friendlyName" }?.text
        val id = device?.child("UDN")?.text ?: elements.firstOrNull { it.name == "UDN" }?.text
        val base = elements.firstOrNull { it.name == "URLBase" }?.text?.takeIf { it.isNotEmpty() } ?: location
        val control = service?.child("controlURL")?.text?.takeIf { it.isNotEmpty() }
        return MediaServer(
            id = id?.takeIf { it.isNotEmpty() } ?: location,
            location = location,
            address = address,
            name = name?.takeIf { it.isNotEmpty() } ?: address,
            controlUrl = control?.let { resolve(base, it) },
            serviceType = service?.child("serviceType")?.text,
        )
    }

    internal fun parseBrowseResponse(xml: String, baseUrl: String): Page {
        val elements = elements(parse(xml))
        val result = elements.firstOrNull { it.name == "Result" }?.text
        if (result == null) {
            val reason = elements.firstOrNull { it.name == "errorDescription" || it.name == "faultstring" }?.text
            throw MediaServerException(reason?.takeIf { it.isNotEmpty() } ?: "Unexpected answer")
        }
        val entries = ArrayList<MediaEntry>()
        if (result.isNotBlank()) {
            val root = parse(result).documentElement
            var node: Node? = root.firstChild
            while (node != null) {
                if (node is Element) entry(node, baseUrl)?.let { entries += it }
                node = node.nextSibling
            }
        }
        val returned = elements.firstOrNull { it.name == "NumberReturned" }?.text?.toIntOrNull() ?: entries.size
        val total = elements.firstOrNull { it.name == "TotalMatches" }?.text?.toIntOrNull() ?: returned
        return Page(entries, returned, total)
    }

    /** `1:23:45.500` as milliseconds; zero if [text] is not a duration. */
    internal fun parseDuration(text: String?): Long {
        val parts = text?.trim()?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val hours = parts[0].toLongOrNull() ?: return 0
        val minutes = parts[1].toLongOrNull() ?: return 0
        val seconds = parts[2].toDoubleOrNull() ?: return 0
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    private fun entry(element: Element, baseUrl: String): MediaEntry? {
        val id = element.getAttribute("id").takeIf { it.isNotEmpty() } ?: return null
        val title = element.child("title")?.text?.takeIf { it.isNotEmpty() } ?: id
        if (element.name == "container") return MediaEntry(id, title, isFolder = true, url = null, sizeBytes = 0, durationMs = 0)
        if (element.name != "item") return null

        val isVideoItem = element.child("class")?.text.orEmpty().startsWith("object.item.videoItem")
        var best: Element? = null
        var node: Node? = element.firstChild
        while (node != null) {
            if (node is Element && node.name == "res" && isVideo(node, isVideoItem)) {
                // Servers offer the file as it is and versions converted for old TVs; the phone
                // wants the original.
                if (best == null || (best.isConverted && !node.isConverted)) best = node
            }
            node = node.nextSibling
        }
        val resource = best ?: return null
        val url = resource.text.takeIf { it.isNotEmpty() } ?: return null
        return MediaEntry(
            id = id,
            title = title,
            isFolder = false,
            url = resolve(baseUrl, url),
            sizeBytes = resource.getAttribute("size").toLongOrNull() ?: 0,
            durationMs = parseDuration(resource.getAttribute("duration")),
        )
    }

    /** Whether the resource [res] can be played as a video over plain HTTP. */
    private fun isVideo(res: Element, isVideoItem: Boolean): Boolean {
        val info = res.getAttribute("protocolInfo").split(':')
        if (!info[0].equals("http-get", ignoreCase = true)) return false
        val mimeType = info.getOrNull(2).orEmpty().lowercase()
        if (mimeType.startsWith("video/")) return true
        // Thumbnails and subtitles of a video are listed next to it.
        return isVideoItem && OTHER_KINDS.none { mimeType.startsWith(it) }
    }

    private val OTHER_KINDS = listOf("audio/", "image/", "text/")

    private val Element.isConverted: Boolean get() = "DLNA.ORG_CI=1" in getAttribute("protocolInfo")

    /** The tag name without its namespace prefix. */
    private val Element.name: String get() = tagName.substringAfter(':')

    private val Element.text: String get() = textContent.orEmpty().trim()

    private fun Element.child(name: String): Element? {
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element && node.name == name) return node
            node = node.nextSibling
        }
        return null
    }

    private fun elements(document: Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun parse(xml: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart(BYTE_ORDER_MARK, ' ', '\r', '\n', '\t'))))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw MediaServerException("Unreadable answer", e)
    }

    private fun resolve(base: String, url: String): String = try {
        URL(URL(base), url).toString()
    } catch (_: IOException) {
        url
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Fetches [url], or posts the request [body] for [action] to it, and returns the answer. */
    private fun exchange(url: String, action: String?, body: String?): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: ClassCastException) {
            throw MediaServerException("Not an HTTP address", e)
        }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "FlamyPlayer UPnP/1.0 DLNADOC/1.50")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPACTION", action)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) return connection.inputStream.use(::read)
            // Servers explain a refusal in the body of the error.
            val explanation = try {
                connection.errorStream?.use(::read)?.let { text ->
                    elements(parse(text)).firstOrNull { it.name == "errorDescription" || it.name == "faultstring" }?.text
                }
            } catch (_: IOException) {
                null
            }
            throw MediaServerException("HTTP $status" + (explanation?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            throw MediaServerException("Not allowed to reach the local network", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > MAX_RESPONSE_BYTES) throw MediaServerException("The answer is too long")
        }
        return output.toString("UTF-8")
    }
}
