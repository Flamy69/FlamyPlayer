package io.github.flamy69.flamyplayer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** IPv4 address arithmetic for the network the phone is connected to. */
object Subnet {

    private fun mask(prefixLength: Int): Int = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength)

    /** The address that reaches every machine of the network. */
    fun broadcast(address: Int, prefixLength: Int): Int = address or mask(prefixLength).inv()

    /**
     * The addresses worth trying when looking for other machines. Home networks have at most
     * 254 of them; on a bigger network only the phone's own neighborhood of that size is used.
     */
    fun neighbors(address: Int, prefixLength: Int): List<Int> {
        val prefix = prefixLength.coerceAtLeast(24)
        if (prefix > 30) return emptyList()
        val network = address and mask(prefix)
        val size = 1 shl (32 - prefix)
        return (1 until size - 1).map { network + it }.filter { it != address }
    }

    fun toInt(address: Inet4Address): Int =
        address.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xFF) }

    fun toAddress(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
    )
}

/** A computer or NAS on the local network that offers shared folders. */
class FoundDevice(
    /** The name it calls itself, if it told us. */
    val name: String?,
    val address: String,
) {
    /** What to save as the address: the name survives the device getting a new IP address. */
    val host: String get() = name ?: address
}

/**
 * Knows the phone's Wi-Fi network: finds the machines on it that share folders, and turns
 * their names into addresses.
 */
class LocalNetworkScanner(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Name → address of machines seen recently, for when a name cannot be looked up again. */
    private val known = ConcurrentHashMap<String, String>()

    /**
     * Looks for machines with the file sharing port open. Returns `null` when the phone is not
     * on Wi-Fi (or a wired network). Takes about two seconds.
     */
    suspend fun scan(): List<FoundDevice>? {
        val (address, prefixLength) = wifiAddress() ?: return null
        val candidates = Subnet.neighbors(address, prefixLength)
        val limit = Semaphore(PARALLEL_PROBES)
        val found = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    limit.withPermit {
                        val target = Subnet.toAddress(candidate)
                        if (isSharingFiles(target)) FoundDevice(NetBios.nameOf(target), target.hostAddress.orEmpty()) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        for (device in found) {
            device.name?.let { known[it.uppercase()] = device.address }
        }
        return found
    }

    /**
     * Turns what the user typed as the host into something a socket can connect to. Tries, in
     * this order: a literal IP address, the Windows name service, DNS, and the `.local` name.
     */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): String {
        if (IPV4.matches(host)) return host

        if (NetBios.isValidName(host)) {
            val broadcast = wifiAddress()?.let { (address, prefix) -> Subnet.toAddress(Subnet.broadcast(address, prefix)) }
                ?: Subnet.toAddress(-1)
            NetBios.resolve(host, broadcast)?.hostAddress?.let { address ->
                known[host.uppercase()] = address
                return address
            }
        }

        val names = if ('.' in host) listOf(host) else listOf(host, "$host.local")
        for (name in names) {
            try {
                val addresses = InetAddress.getAllByName(name)
                // An IPv4 address goes into the share path as is; other answers keep the name.
                return addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: name
            } catch (_: UnknownHostException) {
            }
        }
        return known[host.uppercase()] ?: throw UnknownHostException(host)
    }

    /** The phone's IPv4 address on Wi-Fi or Ethernet and the length of its network prefix. */
    private fun wifiAddress(): Pair<Int, Int>? {
        val manager = connectivity ?: return null
        @Suppress("DEPRECATION") // The replacement only reports the default network, which may be a VPN.
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val link = manager.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?: continue
            return Subnet.toInt(link.address as Inet4Address) to link.prefixLength
        }
        return null
    }

    private fun isSharingFiles(address: InetAddress): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, SMB_PORT), PROBE_TIMEOUT_MS)
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val SMB_PORT = 445
        const val PROBE_TIMEOUT_MS = 400
        const val PARALLEL_PROBES = 64
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
