package io.github.flamy69.flamyplayer.playback

import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.FolderNode
import io.github.flamy69.flamyplayer.data.FolderTree
import io.github.flamy69.flamyplayer.data.Playlist
import io.github.flamy69.flamyplayer.data.RadioStation
import io.github.flamy69.flamyplayer.data.Track
import java.text.Normalizer
import java.util.Locale

/** Everything the app can play without a screen of its own, as it was when it was read. */
class Catalog(
    /** The one big playlist: all tracks in the selected music folders. */
    val music: List<Track>,
    val playlists: List<Playlist>,
    val books: List<Book>,
    val stations: List<RadioStation>,
    /** Ids of the stations shown first. */
    val favorites: Set<String>,
)

/** The words the tree needs, in the language of the phone. */
class BrowseLabels(
    val music: String,
    val books: String,
    val radio: String,
    val shuffle: String,
    val playlists: String,
    val allStations: String,
    val trackCount: (Int) -> String,
    val chapterCount: (Int) -> String,
)

/** One line on the display of the car: something to open or something to play. */
class BrowseNode(val id: String, val title: String, val subtitle: String?, val kind: Kind) {
    enum class Kind(val isBrowsable: Boolean) {
        MUSIC(true), BOOKS(true), RADIO(true), FOLDER(true), PLAYLISTS(true), PLAYLIST(true), ALL_STATIONS(true),
        SHUFFLE(false), TRACK(false), BOOK(false), STATION(false),
    }
}

/** What choosing a line, or asking for something by voice, starts. */
sealed interface BrowseTarget {

    /**
     * [tracks] from [startIndex]; without one, from the first track or, when shuffled, from one
     * of the least played. [shuffle] overrides the listener's setting when it is not `null`.
     */
    class Music(
        val tracks: List<Track>,
        val startIndex: Int?,
        val shuffle: Boolean? = null,
        val playlistPath: String? = null,
    ) : BrowseTarget

    /** The book, continued where the listener stopped. */
    class Audiobook(val book: Book) : BrowseTarget

    class Radio(val stations: List<RadioStation>, val index: Int) : BrowseTarget
}

/**
 * The library as Android Auto shows it: three tabs (music, audiobooks, radio) and what is
 * inside them. Music is laid out the way the app thinks of it, by folders.
 *
 * Choosing a track plays the big playlist from that track on, exactly as in the app, so the
 * shuffle and its memory work the same in the car.
 */
class BrowseTree(private val catalog: Catalog, private val labels: BrowseLabels) {

    private val folders: FolderNode? by lazy {
        FolderTree.build(catalog.music.groupingBy { it.folder }.eachCount())
    }
    private val tracksByFolder: Map<String, List<Track>> by lazy { catalog.music.groupBy { it.folder } }

    private val favoriteStations: List<RadioStation> by lazy {
        catalog.stations.filter { it.id in catalog.favorites }
    }

    /** The lines under [parentId], or `null` if there is nothing of that name to open. */
    fun children(parentId: String): List<BrowseNode>? = when {
        parentId == ROOT -> listOf(sectionNode(MUSIC), sectionNode(BOOKS), sectionNode(RADIO))
        parentId == MUSIC -> buildList {
            if (catalog.music.isNotEmpty()) add(shuffleNode())
            if (catalog.playlists.isNotEmpty()) add(playlistsNode())
            folders?.let { addAll(contentsOf(it)) }
        }
        parentId == PLAYLISTS -> catalog.playlists.map(::playlistNode)
        parentId == BOOKS -> catalog.books.filter { it.tracks.isNotEmpty() }.map(::bookNode)
        parentId == RADIO -> buildList {
            favoriteStations.mapTo(this, ::stationNode)
            // With no favorites there is nothing to keep apart from the full list.
            if (favoriteStations.isEmpty()) catalog.stations.mapTo(this, ::stationNode) else add(allStationsNode())
        }
        parentId == ALL_STATIONS -> catalog.stations.map(::stationNode)
        parentId.startsWith(FOLDER) -> folder(parentId.removePrefix(FOLDER))?.let(::contentsOf)
        parentId.startsWith(PLAYLIST) -> playlist(parentId.removePrefix(PLAYLIST))?.let { playlist ->
            playlist.tracks.mapIndexed { index, track -> trackNode(track, playlistTrackId(playlist.path, index)) }
        }
        else -> null
    }

    /** The line with this id, or `null` if the library has no such thing (any more). */
    fun node(id: String): BrowseNode? = when {
        id == MUSIC || id == BOOKS || id == RADIO -> sectionNode(id)
        id == SHUFFLE -> shuffleNode().takeIf { catalog.music.isNotEmpty() }
        id == PLAYLISTS -> playlistsNode()
        id == ALL_STATIONS -> allStationsNode()
        id.startsWith(FOLDER) -> folder(id.removePrefix(FOLDER))?.let(::folderNode)
        id.startsWith(PLAYLIST) -> playlist(id.removePrefix(PLAYLIST))?.let(::playlistNode)
        id.startsWith(PLAYLIST_TRACK) -> playlistTrack(id)?.let { (playlist, index) -> trackNode(playlist.tracks[index], id) }
        id.startsWith(TRACK) -> track(id)?.let { trackNode(it) }
        id.startsWith(BOOK) -> book(id)?.let(::bookNode)
        else -> station(id)?.let(::stationNode)
    }

    /** What the line with this id plays, or `null` if it is not something to play. */
    fun target(id: String): BrowseTarget? = when {
        id == SHUFFLE -> BrowseTarget.Music(catalog.music, startIndex = null, shuffle = true)
            .takeIf { catalog.music.isNotEmpty() }
        id.startsWith(PLAYLIST_TRACK) -> playlistTrack(id)?.let { (playlist, index) ->
            BrowseTarget.Music(playlist.tracks, index, playlistPath = playlist.path)
        }
        id.startsWith(PLAYLIST) -> playlist(id.removePrefix(PLAYLIST))?.let(::playlistTarget)
        id.startsWith(TRACK) -> track(id)?.let { BrowseTarget.Music(catalog.music, catalog.music.indexOf(it)) }
        id.startsWith(BOOK) -> book(id)?.let(BrowseTarget::Audiobook)
        else -> station(id)?.let(::stationTarget)
    }

    /** Everything whose name fits [query], the best fits first. */
    fun search(query: String): List<BrowseNode> {
        val wanted = fold(query)
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, BrowseNode>>()
        for (station in catalog.stations) {
            val fit = minOf(fit(station.name, wanted), if (fold(station.tags).contains(wanted)) LOOSE else NONE)
            if (fit < NONE) found += fit to stationNode(station)
        }
        for (book in catalog.books) {
            if (book.tracks.isEmpty()) continue
            val fit = fit(book.title, wanted)
            if (fit < NONE) found += fit to bookNode(book)
        }
        for (playlist in catalog.playlists) {
            val fit = fit(playlist.name, wanted)
            if (fit < NONE) found += fit to playlistNode(playlist)
        }
        var tracks = 0
        for (track in catalog.music) {
            val fit = fit(track, wanted)
            if (fit < NONE && tracks++ < MAX_TRACKS_FOUND) found += fit to trackNode(track)
        }
        // A stable sort: among equally good fits stations stay before books, playlists and tracks.
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * What to play for a spoken wish ("play Evropa 2", "play Karel Gott"): the station, the
     * book or the playlist of that name, otherwise all the tracks that fit.
     */
    fun searchTarget(query: String): BrowseTarget? {
        val wanted = fold(query)
        if (wanted.isEmpty()) return null
        var best = NONE
        var target: BrowseTarget? = null
        fun offer(fit: Int, make: () -> BrowseTarget) {
            if (fit < best) {
                best = fit
                target = make()
            }
        }
        // Favorites first, so that of two stations that fit equally the familiar one wins.
        for (station in favoriteStations + catalog.stations) offer(fit(station.name, wanted)) { stationTarget(station) }
        for (book in catalog.books) {
            if (book.tracks.isNotEmpty()) offer(fit(book.title, wanted)) { BrowseTarget.Audiobook(book) }
        }
        for (playlist in catalog.playlists) offer(fit(playlist.name, wanted)) { playlistTarget(playlist) }

        val fits = catalog.music.map { fit(it, wanted) }
        val bestTrack = fits.minOrNull() ?: NONE
        if (bestTrack < best) {
            // "Play <artist>" means all of that artist, not the one track that comes first.
            val chosen = catalog.music.indices.filter { fits[it] <= maxOf(bestTrack, PARTIAL) }
            // One track fits better than all the others: that is the one that was asked for.
            val single = chosen.singleOrNull { fits[it] == bestTrack }
            target = BrowseTarget.Music(chosen.map(catalog.music::get), single?.let(chosen::indexOf))
        }
        return target
    }

    private fun contentsOf(folder: FolderNode): List<BrowseNode> =
        folder.children.map(::folderNode) + tracksByFolder[folder.path].orEmpty().map { trackNode(it) }

    private fun folder(path: String): FolderNode? = folders?.chainTo(path)?.lastOrNull()

    private fun playlist(path: String): Playlist? = catalog.playlists.firstOrNull { it.path == path }

    /** `ptrack:<index>:<path of the playlist>` */
    private fun playlistTrack(id: String): Pair<Playlist, Int>? {
        val rest = id.removePrefix(PLAYLIST_TRACK)
        val index = rest.substringBefore(':').toIntOrNull() ?: return null
        val playlist = playlist(rest.substringAfter(':')) ?: return null
        return if (index in playlist.tracks.indices) playlist to index else null
    }

    private fun track(id: String): Track? {
        val trackId = id.removePrefix(TRACK).toLongOrNull() ?: return null
        return catalog.music.firstOrNull { it.id == trackId }
    }

    private fun book(id: String): Book? {
        val folder = id.removePrefix(BOOK)
        return catalog.books.firstOrNull { it.folder == folder && it.tracks.isNotEmpty() }
    }

    private fun station(id: String): RadioStation? = catalog.stations.firstOrNull { it.mediaId == id }

    /** "Next" and "previous" stay among the favorites for a favorite, as they do in the app. */
    private fun stationTarget(station: RadioStation): BrowseTarget.Radio {
        val list = if (station.id in catalog.favorites) favoriteStations else catalog.stations
        return BrowseTarget.Radio(list, list.indexOf(station))
    }

    private fun playlistTarget(playlist: Playlist) =
        BrowseTarget.Music(playlist.tracks, startIndex = null, playlistPath = playlist.path)

    private fun sectionNode(id: String) = when (id) {
        MUSIC -> BrowseNode(MUSIC, labels.music, null, BrowseNode.Kind.MUSIC)
        BOOKS -> BrowseNode(BOOKS, labels.books, null, BrowseNode.Kind.BOOKS)
        else -> BrowseNode(RADIO, labels.radio, null, BrowseNode.Kind.RADIO)
    }

    private fun shuffleNode() =
        BrowseNode(SHUFFLE, labels.shuffle, labels.trackCount(catalog.music.size), BrowseNode.Kind.SHUFFLE)

    private fun playlistsNode() = BrowseNode(PLAYLISTS, labels.playlists, null, BrowseNode.Kind.PLAYLISTS)

    private fun allStationsNode() = BrowseNode(ALL_STATIONS, labels.allStations, null, BrowseNode.Kind.ALL_STATIONS)

    private fun folderNode(folder: FolderNode) =
        BrowseNode(FOLDER + folder.path, folder.name, labels.trackCount(folder.totalCount), BrowseNode.Kind.FOLDER)

    private fun playlistNode(playlist: Playlist) = BrowseNode(
        PLAYLIST + playlist.path, playlist.name, labels.trackCount(playlist.tracks.size), BrowseNode.Kind.PLAYLIST,
    )

    private fun trackNode(track: Track, id: String = trackId(track.id)) =
        BrowseNode(id, track.title, track.artist, BrowseNode.Kind.TRACK)

    private fun bookNode(book: Book) =
        BrowseNode(bookId(book.folder), book.title, labels.chapterCount(book.tracks.size), BrowseNode.Kind.BOOK)

    private fun stationNode(station: RadioStation) =
        BrowseNode(station.mediaId, station.name, station.tags.ifEmpty { null }, BrowseNode.Kind.STATION)

    /** How well a track fits: by its title or artist first, then its album, then its file. */
    private fun fit(track: Track, wanted: String): Int {
        var best = fit(track.title, wanted)
        track.artist?.let { best = minOf(best, fit(it, wanted)) }
        if (best > EXACT) track.album?.let { best = minOf(best, fit(it, wanted)) }
        if (best == NONE && fold(track.fileName).contains(wanted)) best = LOOSE
        return best
    }

    private fun fit(name: String, wanted: String): Int {
        val folded = fold(name)
        return when {
            folded == wanted -> EXACT
            folded.startsWith(wanted) -> PREFIX
            folded.contains(wanted) -> PARTIAL
            else -> NONE
        }
    }

    companion object {
        const val ROOT = "root"
        const val MUSIC = "music"
        const val BOOKS = "books"
        const val RADIO = "radio"
        const val SHUFFLE = "shuffle"
        const val PLAYLISTS = "playlists"
        const val ALL_STATIONS = "stations"

        private const val FOLDER = "folder:"
        private const val PLAYLIST = "playlist:"
        private const val PLAYLIST_TRACK = "ptrack:"
        private const val TRACK = "track:"
        private const val BOOK = "book:"

        private const val EXACT = 0
        private const val PREFIX = 1
        private const val PARTIAL = 2
        private const val LOOSE = 3
        private const val NONE = 4

        private const val MAX_TRACKS_FOUND = 100

        fun trackId(trackId: Long): String = TRACK + trackId

        fun bookId(bookFolder: String): String = BOOK + bookFolder

        fun playlistTrackId(playlistPath: String, index: Int): String = "$PLAYLIST_TRACK$index:$playlistPath"

        private val MARKS = Regex("\\p{Mn}+")

        /** Lower case and without accents: what is said in the car is rarely spelled exactly. */
        internal fun fold(text: String): String =
            MARKS.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
