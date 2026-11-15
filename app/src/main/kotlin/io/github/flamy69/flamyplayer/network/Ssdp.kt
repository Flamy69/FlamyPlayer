package io.github.flamy69.flamyplayer.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * The search a TV does to find media servers ("media streaming" in Windows, the media server
 * of a NAS): a question sent to the whole network, answered by every server with the address
 * of its description.
 */
object Ssdp {

    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900
    private const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"

    /** A media server that answered: where it is and where it describes itself. */
    class Answer(val address: InetAddress, val location: String)

    /**
     * Asks the local network which machines stream media. Blocks for [timeoutMs].
     *
     * The question goes out to everybody at once, several times, because single packets get
     * lost easily on Wi-Fi. Networks that do not pass such broadcasts on between wireless
     * devices are covered by asking each of the [neighbors] directly as well.
     *
     * @param prepare may tie the socket to a particular network first
     */
    fun mediaServers(
        neighbors: Collection<InetAddress> = emptyList(),
        timeoutMs: Int = 2500,
        prepare: (DatagramSocket) -> Unit = {},
    ): List<Answer> {
        val found = LinkedHashMap<String, Answer>()
        try {
            DatagramSocket().use { socket ->
                prepare(socket)
                val group = InetAddress.getByName(GROUP)
                val started = System.nanoTime()
                val rounds = ArrayDeque(SEND_AT_MS)
                val buffer = ByteArray(4096)
                while (true) {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (elapsedMs >= timeoutMs) break
                    if (rounds.isNotEmpty() && elapsedMs >= rounds.first()) {
                        rounds.removeFirst()
                        send(socket, request(), group)
                        for (neighbor in neighbors) send(socket, request(neighbor.hostAddress), neighbor)
                        continue
                    }
                    val waitUntilMs = minOf(timeoutMs.toLong(), rounds.firstOrNull() ?: Long.MAX_VALUE)
                    socket.soTimeout = (waitUntilMs - elapsedMs).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val sender = packet.address as? Inet4Address ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val location = if (isAnswer(text)) location(text) else null
                    if (location != null) found.getOrPut(location) { Answer(sender, location) }
                }
            }
        } catch (_: IOException) {
            // Whatever answered before the failure still counts.
        } catch (_: SecurityException) {
        }
        return found.values.toList()
    }

    private fun send(socket: DatagramSocket, request: ByteArray, target: InetAddress) {
        try {
            socket.send(DatagramPacket(request, request.size, target, PORT))
        } catch (_: IOException) {
            // One address that cannot be reached must not end the search.
        }
    }

    /** The question for everybody, or for the single machine [host] if one is given. */
    internal fun request(host: String? = null): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: ${host ?: GROUP}:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            // How long the machines may take to answer; only meaningful when asking everybody.
            (if (host == null) "MX: 1\r\n" else "") +
            "ST: $MEDIA_SERVER\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Whether [text] is a media server answering the search, not some other announcement. */
    internal fun isAnswer(text: String): Boolean {
        val status = text.lineSequence().firstOrNull().orEmpty().trim().split(' ')
        if (status.size < 2 || !status[0].startsWith("HTTP/") || status[1] != "200") return false
        return header(text, "ST")?.startsWith("urn:schemas-upnp-org:device:MediaServer:", ignoreCase = true) == true
    }

    /** The address of the description of the server that sent [text]. */
    internal fun location(text: String): String? =
        header(text, "LOCATION")?.takeIf { it.startsWith("http://", ignoreCase = true) }

    private fun header(text: String, name: String): String? = text.lineSequence()
        .firstOrNull { ':' in it && it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

    /** When the question is sent, counted from the start of the search. */
    private val SEND_AT_MS = listOf(0L, 400L, 1000L)
}
