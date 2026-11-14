package io.github.flamy69.flamyplayer.network

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlin.math.min

/** `smb://<server id>/<share>/<folders>/<file>`: how the player refers to a file on a share. */
object SmbUri {

    const val SCHEME = "smb"

    /** [segments] starts with the share name. */
    fun build(serverId: String, segments: List<String>): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority(serverId)
        segments.forEach(builder::appendPath)
        return builder.build()
    }

    /** Returns the server id and the path segments, or `null` if [uri] is not one of ours. */
    fun parse(uri: Uri): Pair<String, List<String>>? {
        if (uri.scheme != SCHEME) return null
        val serverId = uri.authority?.takeIf { it.isNotEmpty() } ?: return null
        val segments = uri.pathSegments.takeIf { it.size >= 2 } ?: return null
        return serverId to segments
    }
}

/**
 * Lets the player read a file straight from a share, with seeking. Data is fetched in large
 * requests: a round trip per 64 KB, which is what the player asks for, would waste most of the
 * bandwidth of a Wi-Fi link.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val client: SmbClient,
    private val servers: (String) -> SmbServer?,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: SmbClient.OpenFile? = null
    private var opened = false

    private var position = 0L
    private var bytesRemaining = 0L

    private var buffer = ByteArray(0)
    private var bufferOffset = 0
    private var bufferLength = 0
    private var requestSize = FIRST_REQUEST_SIZE

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val (serverId, segments) = SmbUri.parse(dataSpec.uri)
            ?: throw IOException("Not a network file: ${dataSpec.uri}")
        val server = servers(serverId) ?: throw SmbException(SmbException.Reason.NOT_FOUND)
        val source = client.open(server, segments.first(), segments.drop(1))
        file = source

        if (dataSpec.position > source.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            min(dataSpec.length, source.size - position)
        } else {
            source.size - position
        }
        bufferOffset = 0
        bufferLength = 0
        // Right after opening or seeking the player often wants just a few bytes (a header, an
        // index), so start small and grow while it keeps reading.
        requestSize = FIRST_REQUEST_SIZE

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (bufferOffset == bufferLength) {
            val source = file ?: throw IOException("Not open")
            val wanted = min(requestSize.toLong(), bytesRemaining).toInt()
            if (buffer.size < wanted) buffer = ByteArray(wanted)
            val read = source.read(position, buffer, 0, wanted)
            if (read <= 0) return C.RESULT_END_OF_INPUT
            bufferOffset = 0
            bufferLength = read
            requestSize = min(requestSize * 2, MAX_REQUEST_SIZE)
        }

        val count = min(length, bufferLength - bufferOffset)
        System.arraycopy(buffer, bufferOffset, target, offset, count)
        bufferOffset += count
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val client: SmbClient,
        private val servers: (String) -> SmbServer?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(client, servers)
    }

    private companion object {
        const val FIRST_REQUEST_SIZE = 128 * 1024
        const val MAX_REQUEST_SIZE = 1024 * 1024
    }
}
