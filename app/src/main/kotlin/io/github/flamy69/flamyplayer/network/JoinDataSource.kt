package io.github.flamy69.flamyplayer.network

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Where a position in several files laid end to end falls. */
class JoinedParts(private val sizes: List<Long>) {

    val total: Long = sizes.sum()

    /**
     * The part that holds the byte at [position] and the offset inside that part. A position
     * at the very end belongs to the end of the last part.
     */
    fun locate(position: Long): Pair<Int, Long> {
        var start = 0L
        for ((index, size) in sizes.withIndex()) {
            if (position < start + size) return index to position - start
            start += size
        }
        return (sizes.size - 1).coerceAtLeast(0) to (sizes.lastOrNull() ?: 0L)
    }
}

/** Addresses of the form `join:` that stand for several files played as one. */
object JoinUri {

    const val SCHEME = "join"
    private const val PART = "part"
    private const val SIZE = "size"

    class Part(val uri: Uri, val size: Long)

    fun build(parts: List<Part>): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority("files")
        for (part in parts) {
            builder.appendQueryParameter(PART, part.uri.toString())
            builder.appendQueryParameter(SIZE, part.size.toString())
        }
        return builder.build()
    }

    fun parse(uri: Uri): List<Part> {
        val uris = uri.getQueryParameters(PART)
        val sizes = uri.getQueryParameters(SIZE)
        return uris.indices.map { Part(uris[it].toUri(), sizes.getOrNull(it)?.toLongOrNull() ?: 0L) }
    }
}

/**
 * Reads several files as if they were one. A film on a DVD is cut into files of one gigabyte;
 * read one after the other they are a single film that can be seeked in from start to end.
 *
 * Addresses that are not `join:` addresses go straight to [upstream].
 */
@OptIn(UnstableApi::class)
class JoinDataSource(private val upstream: DataSource) : DataSource {

    private var joined: Uri? = null
    private var parts: List<JoinUri.Part> = emptyList()
    private var index = 0

    /** Bytes left in the part that is open, and in the whole request. */
    private var partRemaining = 0L
    private var remaining = 0L
    private var partOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != JoinUri.SCHEME) {
            joined = null
            return upstream.open(dataSpec)
        }
        parts = JoinUri.parse(dataSpec.uri)
        val layout = JoinedParts(parts.map { it.size })
        if (parts.isEmpty() || dataSpec.position > layout.total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        joined = dataSpec.uri
        val available = layout.total - dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        val (part, offset) = layout.locate(dataSpec.position)
        openPart(part, offset)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (joined == null) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (remaining > 0) {
            if (partRemaining == 0L) {
                if (index + 1 >= parts.size) break
                closePart()
                openPart(index + 1, 0)
                continue
            }
            val wanted = minOf(length.toLong(), partRemaining, remaining).toInt()
            val read = upstream.read(buffer, offset, wanted)
            if (read == C.RESULT_END_OF_INPUT) {
                // Shorter than it was said to be: go on with the next file.
                partRemaining = 0
                continue
            }
            partRemaining -= read
            remaining -= read
            return read
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = joined ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (joined == null) upstream.responseHeaders else emptyMap()

    override fun close() {
        try {
            if (joined == null || partOpen) upstream.close()
        } finally {
            joined = null
            partOpen = false
            parts = emptyList()
        }
    }

    private fun openPart(part: Int, offset: Long) {
        index = part
        val target = parts[part]
        partRemaining = (target.size - offset).coerceAtLeast(0)
        if (partRemaining == 0L) return
        upstream.open(DataSpec.Builder().setUri(target.uri).setPosition(offset).setLength(partRemaining).build())
        partOpen = true
    }

    private fun closePart() {
        if (!partOpen) return
        partOpen = false
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = JoinDataSource(upstream.createDataSource())
    }
}
