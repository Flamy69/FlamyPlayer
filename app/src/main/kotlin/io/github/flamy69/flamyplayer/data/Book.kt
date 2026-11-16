package io.github.flamy69.flamyplayer.data

/** Where the listener stopped in a book. */
data class BookProgress(val trackId: Long, val positionMs: Long)

/** An audiobook: one selected folder whose files are played in order. */
class Book(val folder: String, val tracks: List<Track>) {

    val title: String = folder.substringAfterLast('/')

    /** Start of every chapter measured from the beginning of the book. */
    private val offsets = LongArray(tracks.size).also { offsets ->
        var total = 0L
        for (i in tracks.indices) {
            offsets[i] = total
            total += tracks[i].durationMs
        }
    }

    val durationMs: Long = tracks.sumOf { it.durationMs }

    /** Chapter index and position to continue from; the very beginning if there is no progress yet. */
    fun resumePoint(progress: BookProgress?): Pair<Int, Long> {
        val index = progress?.let { p -> tracks.indexOfFirst { it.id == p.trackId } } ?: -1
        return if (index < 0) 0 to 0L else index to progress!!.positionMs
    }

    /** How much of the book has been heard, from 0 to 1. */
    fun fraction(progress: BookProgress?): Float {
        if (progress == null || durationMs <= 0) return 0f
        val (index, position) = resumePoint(progress)
        return ((offsets[index] + position).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    companion object {
        /** One book per folder of [folders], with the tracks of the library that are inside it. */
        fun inFolders(tracks: List<Track>, folders: Set<String>): List<Book> =
            folders.map { folder -> Book(folder, FolderSelection.tracksIn(tracks, setOf(folder))) }
                .sortedWith(compareBy(NaturalOrder) { it.title })
    }
}
