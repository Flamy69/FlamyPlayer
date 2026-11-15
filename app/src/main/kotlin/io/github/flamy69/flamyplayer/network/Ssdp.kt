package io.github.flamy69.flamyplayer.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * The search a TV does to find media servers ("media streaming" in Windows, the media server
 * of a NAS). The app does not play from those servers; it only uses the answers as a hint
 * where to look for shared folders.
 */
object Ssdp {

    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900
    private const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"

    /**
     * Asks the local network which machines stream media and returns their addresses.
     * Blocks for [timeoutMs]. [prepare] may tie the socket to a particular network first.
     */
    fun mediaServers(timeoutMs: Int = 1500, prepare: (DatagramSocket) -> Unit = {}): Set<InetAddress> {
        val found = LinkedHashSet<InetAddress>()
        try {
            DatagramSocket().use { socket ->
                prepare(socket)
                val request = request()
                val group = InetAddress.getByName(GROUP)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                // Sent twice, a moment apart: a single packet gets lost easily on Wi-Fi.
                var sendAgainAt = System.nanoTime() + RESEND_AFTER_MS * 1_000_000L
                socket.send(DatagramPacket(request, request.size, group, PORT))

                val buffer = ByteArray(2048)
                while (true) {
                    val now = System.nanoTime()
                    if (now >= deadline) break
                    if (now >= sendAgainAt) {
                        socket.send(DatagramPacket(request, request.size, group, PORT))
                        sendAgainAt = Long.MAX_VALUE
                    }
                    val waitUntil = minOf(deadline, sendAgainAt)
                    socket.soTimeout = ((waitUntil - now) / 1_000_000L).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val sender = packet.address
                    if (sender is Inet4Address && isAnswer(String(packet.data, 0, packet.length, Charsets.ISO_8859_1))) {
                        found += sender
                    }
                }
            }
        } catch (_: IOException) {
            // Whatever answered before the failure still counts.
        } catch (_: SecurityException) {
        }
        return found
    }

    internal fun request(): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $GROUP:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $MEDIA_SERVER\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Whether [text] is a media server answering the search, not some other announcement. */
    internal fun isAnswer(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val status = lines.firstOrNull().orEmpty().split(' ')
        if (status.size < 2 || !status[0].startsWith("HTTP/") || status[1] != "200") return false
        return lines.any { line ->
            line.substringBefore(':').equals("ST", ignoreCase = true) &&
                line.substringAfter(':').trim().startsWith("urn:schemas-upnp-org:device:MediaServer:", ignoreCase = true)
        }
    }

    private const val RESEND_AFTER_MS = 300L
}
