package io.github.flamy69.flamyplayer.network

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Reads a video from wherever on the network it is: from a shared folder (`smb://`) or from a
 * media server, which hands its files out over plain HTTP.
 */
@OptIn(UnstableApi::class)
class NetworkDataSource(private val smb: DataSource, private val http: DataSource) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        http.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SmbUri.SCHEME) smb else http
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val smb: DataSource.Factory, private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = NetworkDataSource(smb.createDataSource(), http.createDataSource())
    }
}
