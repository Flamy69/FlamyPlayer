package io.github.flamy69.flamyplayer.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Talks SMB 2/3 to Windows shares and NAS boxes. Connections are kept open and shared between
 * browsing and playback, and are quietly re-established when the server has dropped them.
 *
 * All methods block; call them from a background thread.
 *
 * @param password turns the stored secret of a server into its password
 * @param resolveHost turns the host of a server into an address a socket can connect to
 */
class SmbClient(
    private val password: (SmbServer) -> String,
    private val resolveHost: (String) -> String,
) {

    private val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Android has neither the JCE algorithms nor the Kerberos classes that the
                // library would pick on a desktop JVM.
                .withSecurityProvider(BCSecurityProvider())
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withDfsEnabled(false)
                .build(),
        )
    }

    private class Link(val server: SmbServer, val connection: Connection, val session: Session) {
        val shares = HashMap<String, DiskShare>()
    }

    private val links = HashMap<String, Link>()

    /** The names of the disk shares the server offers. */
    fun listShares(server: SmbServer): List<String> = withLink(server) { link ->
        val shares = try {
            ServerService(SMBTransportFactories.SRVSVC.getTransport(link.session)).shares1
        } catch (e: SMBApiException) {
            throw e
        } catch (e: Exception) {
            // The server answered, but not with a list of shares; some NAS boxes refuse guests.
            throw SmbException(SmbException.Reason.OTHER, e)
        } catch (e: LinkageError) {
            // The RPC library reports malformed answers with java.rmi exceptions, which do not
            // exist on Android, so such an answer surfaces as a missing class instead.
            throw SmbException(SmbException.Reason.OTHER, e)
        }
        shares
            .filter { (it.type and SHARE_TYPE_MASK) == SHARE_TYPE_DISK && (it.type and SHARE_TYPE_SPECIAL) == 0 }
            .mapNotNull { it.netName }
    }

    /** Lists a directory; [path] holds the folder names below the share. */
    fun list(server: SmbServer, share: String, path: List<String>): List<SmbEntry> = withLink(server) { link ->
        share(link, share).list(path.joinToString("\\"))
            .filter { it.fileName != "." && it.fileName != ".." }
            .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) == 0L }
            .map { info ->
                SmbEntry(
                    name = info.fileName,
                    isDirectory = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = info.endOfFile,
                )
            }
    }

    /** Opens a file for reading. The caller has to close it. */
    fun open(server: SmbServer, share: String, path: List<String>): OpenFile = withLink(server) { link ->
        val file = share(link, share).openFile(
            path.joinToString("\\"),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            OpenFile(file, file.fileInformation.standardInformation.endOfFile)
        } catch (e: Exception) {
            file.closeSilently()
            throw e
        }
    }

    /** Reads a whole small file, or returns `null` if it is bigger than [maxBytes]. */
    fun readFile(server: SmbServer, share: String, path: List<String>, maxBytes: Int): ByteArray? =
        open(server, share, path).use { file ->
            if (file.size > maxBytes) return null
            val result = ByteArrayOutputStream(file.size.toInt())
            val buffer = ByteArray(64 * 1024)
            var position = 0L
            while (position < file.size) {
                val read = file.read(position, buffer, 0, buffer.size)
                if (read <= 0) break
                result.write(buffer, 0, read)
                position += read
            }
            result.toByteArray()
        }

    /** Forgets the connection of a server, e.g. after its address or password was edited. */
    fun disconnect(serverId: String) {
        val link = synchronized(links) { links.remove(serverId) } ?: return
        close(link)
    }

    class OpenFile internal constructor(private val file: File, val size: Long) : Closeable {

        /** Reads up to [length] bytes from [position]; returns -1 at the end of the file. */
        @Throws(IOException::class)
        fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int = try {
            file.read(target, position, offset, length)
        } catch (e: RuntimeException) {
            throw translate(e)
        }

        override fun close() {
            try {
                // Does not wait for the server's answer, which keeps seeking snappy.
                file.closeNoWait()
            } catch (_: Exception) {
            }
        }
    }

    private fun <T> withLink(server: SmbServer, block: (Link) -> T): T {
        var retried = false
        while (true) {
            val (link, reused) = obtain(server)
            try {
                return block(link)
            } catch (e: Exception) {
                val failure = translate(e)
                val definite = failure.reason == SmbException.Reason.NOT_FOUND ||
                    failure.reason == SmbException.Reason.ACCESS_DENIED
                if (!definite) drop(link)
                // A connection that sat idle may have been closed by the server: try once more
                // with a fresh one before reporting anything.
                if (definite || !reused || retried) throw failure
                retried = true
            }
        }
    }

    private fun obtain(server: SmbServer): Pair<Link, Boolean> = synchronized(links) {
        val existing = links[server.id]
        if (existing != null && existing.server == server && existing.connection.isConnected) {
            return existing to true
        }
        if (existing != null) {
            links.remove(server.id)
            close(existing)
        }
        val link = try {
            connect(server)
        } catch (e: Exception) {
            throw translate(e)
        }
        links[server.id] = link
        link to false
    }

    private fun connect(server: SmbServer): Link {
        val connection = client.connect(resolveHost(server.host))
        try {
            val (domain, user) = SmbAddress.splitUser(server.user)
            val session = if (user.isEmpty()) {
                try {
                    connection.authenticate(AuthenticationContext.guest())
                } catch (_: SMBApiException) {
                    connection.authenticate(AuthenticationContext.anonymous())
                }
            } else {
                connection.authenticate(AuthenticationContext(user, password(server).toCharArray(), domain))
            }
            return Link(server, connection, session)
        } catch (e: Exception) {
            closeQuietly(connection)
            throw e
        }
    }

    private fun share(link: Link, name: String): DiskShare = synchronized(link) {
        link.shares[name]?.let { return it }
        val share = link.session.connectShare(name) as? DiskShare
            ?: throw SmbException(SmbException.Reason.NOT_FOUND)
        link.shares[name] = share
        share
    }

    private fun drop(link: Link) {
        synchronized(links) {
            if (links[link.server.id] === link) links.remove(link.server.id)
        }
        close(link)
    }

    private fun close(link: Link) = closeQuietly(link.connection)

    private fun closeQuietly(connection: Connection) {
        try {
            // Forced: do not wait for a server that may already be gone.
            connection.close(true)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 20L

        const val SHARE_TYPE_MASK = 0x0FFFFFFF
        const val SHARE_TYPE_DISK = 0
        const val SHARE_TYPE_SPECIAL = 0x80000000.toInt()

        fun translate(e: Throwable): SmbException {
            if (e is SmbException) return e
            val reason = if (e is SMBApiException) {
                when (e.status) {
                    NtStatus.STATUS_LOGON_FAILURE,
                    NtStatus.STATUS_ACCOUNT_DISABLED,
                    NtStatus.STATUS_PASSWORD_EXPIRED,
                    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
                    -> SmbException.Reason.LOGIN_FAILED

                    NtStatus.STATUS_ACCESS_DENIED -> SmbException.Reason.ACCESS_DENIED

                    NtStatus.STATUS_BAD_NETWORK_NAME,
                    NtStatus.STATUS_BAD_NETWORK_PATH,
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                    NtStatus.STATUS_NOT_FOUND,
                    -> SmbException.Reason.NOT_FOUND

                    else -> SmbException.Reason.OTHER
                }
            } else if (generateSequence(e) { it.cause }.take(8).any { it is UnknownHostException }) {
                SmbException.Reason.UNKNOWN_HOST
            } else if (generateSequence(e) { it.cause }.take(8).any { it is IOException }) {
                // Unknown host, refused or timed out connection, a link that broke mid-way.
                SmbException.Reason.UNREACHABLE
            } else {
                SmbException.Reason.OTHER
            }
            return SmbException(reason, e)
        }
    }
}
