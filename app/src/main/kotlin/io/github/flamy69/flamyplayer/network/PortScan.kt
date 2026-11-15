package io.github.flamy69.flamyplayer.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/** Finds out which machines accept connections on a port, trying all of them at the same time. */
object PortScan {

    /**
     * Returns the [targets] that accepted a connection on [port] within [timeoutMs]. All
     * attempts run at once on the calling thread, so the whole search takes no longer than
     * one timeout, however many addresses there are.
     */
    fun open(targets: Collection<InetAddress>, port: Int, timeoutMs: Int): List<InetAddress> {
        if (targets.isEmpty()) return emptyList()
        val open = ArrayList<InetAddress>()
        try {
            Selector.open().use { selector ->
                try {
                    var pending = 0
                    for (target in targets) {
                        val channel = SocketChannel.open()
                        try {
                            channel.configureBlocking(false)
                            if (channel.connect(InetSocketAddress(target, port))) {
                                open += target
                                channel.close()
                            } else {
                                channel.register(selector, SelectionKey.OP_CONNECT, target)
                                pending++
                            }
                        } catch (_: IOException) {
                            channel.close() // refused or unreachable right away
                        } catch (_: SecurityException) {
                            channel.close()
                        }
                    }

                    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                    while (pending > 0) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0) break
                        selector.select(remainingMs)
                        val keys = selector.selectedKeys().iterator()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            keys.remove()
                            val channel = key.channel() as SocketChannel
                            try {
                                if (channel.finishConnect()) open += key.attachment() as InetAddress
                            } catch (_: IOException) {
                                // refused
                            }
                            key.cancel()
                            channel.close()
                            pending--
                        }
                    }
                } finally {
                    for (key in selector.keys()) {
                        try {
                            key.channel().close()
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Whatever answered before the failure still counts.
        }
        return open
    }
}
