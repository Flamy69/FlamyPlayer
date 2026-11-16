package io.github.flamy69.flamyplayer.playback

import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.Playlist
import io.github.flamy69.flamyplayer.data.RadioStation
import io.github.flamy69.flamyplayer.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTreeTest {

    private val root = "/storage/emulated/0/Music"

    private fun track(id: Long, path: String, artist: String? = null, title: String = path.substringAfterLast('/')) =
        Track(id, title, artist, album = null, durationMs = 180_000, path = path)

    private val music = listOf(
        track(1, "$root/Kabat/Dole v dole/01.mp3", "Kabát", "Dole v dole"),
        track(2, "$root/Kabat/Dole v dole/02.mp3", "Kabát", "Burlaci"),
        track(3, "$root/Kabat/Corrida/01.mp3", "Kabát", "Corrida"),
        track(4, "$root/Lucie/01.mp3", "Lucie", "Amerika"),
        track(5, "$root/loose.mp3", null, "Šrouby a matice"),
    )
    private val chapters = listOf(
        track(10, "/storage/emulated/0/Books/Hobit/01.mp3"),
        track(11, "/storage/emulated/0/Books/Hobit/02.mp3"),
    )
    private val book = Book("/storage/emulated/0/Books/Hobit", chapters)
    private val playlist = Playlist("$root/driving.m3u", "driving", listOf(music[3], music[0]))

    private fun station(id: String, name: String, tags: String = "") = RadioStation(id, name, "http://$id/", tags, "MP3", 128)
    private val beat = station("b", "Rádio Beat", "rock")
    private val evropa = station("e", "Evropa 2", "pop")
    private val zurnal = station("z", "ČRo Radiožurnál")
    private val stations = listOf(zurnal, evropa, beat)

    private val labels = BrowseLabels(
        music = "Music", books = "Audiobooks", radio = "Radio", shuffle = "Shuffle", playlists = "Playlists",
        allStations = "All stations", trackCount = { "$it tracks" }, chapterCount = { "$it chapters" },
    )

    private fun tree(
        music: List<Track> = this.music,
        playlists: List<Playlist> = listOf(playlist),
        books: List<Book> = listOf(book),
        favorites: Set<String> = setOf("b", "e"),
    ) = BrowseTree(Catalog(music, playlists, books, stations, favorites), labels)

    private fun BrowseTree.titles(parentId: String) = children(parentId)!!.map { it.title }

    @Test
    fun rootHasThreeTabsToOpen() {
        val tabs = tree().children(BrowseTree.ROOT)!!
        assertEquals(listOf("Music", "Audiobooks", "Radio"), tabs.map { it.title })
        assertTrue(tabs.all { it.kind.isBrowsable })
    }

    @Test
    fun musicStartsWithShuffleAndPlaylistsThenFollowsTheFolders() {
        val tree = tree()
        assertEquals(listOf("Shuffle", "Playlists", "Kabat", "Lucie", "Šrouby a matice"), tree.titles(BrowseTree.MUSIC))
        assertEquals("5 tracks", tree.children(BrowseTree.MUSIC)!![0].subtitle)

        val kabat = tree.children(BrowseTree.MUSIC)!![2]
        assertEquals("3 tracks", kabat.subtitle)
        assertEquals(listOf("Corrida", "Dole v dole"), tree.titles(kabat.id))
        val album = tree.children(kabat.id)!![1]
        assertEquals(listOf("Dole v dole", "Burlaci"), tree.titles(album.id))
        assertEquals("Kabát", tree.children(album.id)!![0].subtitle)
    }

    @Test
    fun emptyLibraryOffersNothingToShuffle() {
        val tree = tree(music = emptyList(), playlists = emptyList(), books = emptyList())
        assertEquals(emptyList<String>(), tree.titles(BrowseTree.MUSIC))
        assertEquals(emptyList<String>(), tree.titles(BrowseTree.BOOKS))
        assertNull(tree.target(BrowseTree.SHUFFLE))
    }

    @Test
    fun unknownPlacesAreNotOpened() {
        val tree = tree()
        assertNull(tree.children("folder:/somewhere/else"))
        assertNull(tree.children("nonsense"))
        assertNull(tree.node("track:999"))
        assertNull(tree.target("track:999"))
        assertNull(tree.target(BrowseTree.MUSIC))
    }

    @Test
    fun everyLineCanBeFoundAgainByItsId() {
        val tree = tree()
        val pending = ArrayDeque(tree.children(BrowseTree.ROOT)!!)
        var seen = 0
        while (pending.isNotEmpty()) {
            val line = pending.removeFirst()
            seen++
            assertEquals(line.title, tree.node(line.id)?.title)
            if (line.kind.isBrowsable) {
                pending += tree.children(line.id)!!
                assertNull(tree.target(line.id).takeIf { line.kind != BrowseNode.Kind.PLAYLIST })
            } else {
                assertNotNull(line.id, tree.target(line.id))
            }
        }
        assertTrue(seen > 15)
    }

    @Test
    fun aTrackPlaysTheBigPlaylistFromThere() {
        val target = tree().target(BrowseTree.trackId(4)) as BrowseTarget.Music
        assertSame(music, target.tracks)
        assertEquals(3, target.startIndex)
        assertNull(target.shuffle)
        assertNull(target.playlistPath)
    }

    @Test
    fun shuffleTurnsShufflingOn() {
        val target = tree().target(BrowseTree.SHUFFLE) as BrowseTarget.Music
        assertSame(music, target.tracks)
        assertNull(target.startIndex)
        assertEquals(true, target.shuffle)
    }

    @Test
    fun aPlaylistTrackPlaysThePlaylistOnly() {
        val tree = tree()
        val lines = tree.children(tree.children(BrowseTree.PLAYLISTS)!!.single().id)!!
        assertEquals(listOf("Amerika", "Dole v dole"), lines.map { it.title })
        val target = tree.target(lines[1].id) as BrowseTarget.Music
        assertEquals(playlist.tracks, target.tracks)
        assertEquals(1, target.startIndex)
        assertEquals(playlist.path, target.playlistPath)
        assertEquals(lines[1].id, BrowseTree.playlistTrackId(playlist.path, 1))
        assertNull(tree.target(BrowseTree.playlistTrackId(playlist.path, 2)))
    }

    @Test
    fun aBookIsPlayedNotOpened() {
        val tree = tree()
        val line = tree.children(BrowseTree.BOOKS)!!.single()
        assertEquals("Hobit", line.title)
        assertEquals("2 chapters", line.subtitle)
        assertFalse(line.kind.isBrowsable)
        assertSame(book, (tree.target(line.id) as BrowseTarget.Audiobook).book)
        assertEquals(line.id, BrowseTree.bookId(book.folder))
    }

    @Test
    fun radioShowsFavoritesFirstAndKeepsNextAmongThem() {
        val tree = tree()
        assertEquals(listOf("Evropa 2", "Rádio Beat", "All stations"), tree.titles(BrowseTree.RADIO))
        assertEquals(listOf("ČRo Radiožurnál", "Evropa 2", "Rádio Beat"), tree.titles(BrowseTree.ALL_STATIONS))

        val favorite = tree.target(beat.mediaId) as BrowseTarget.Radio
        assertEquals(listOf(evropa, beat), favorite.stations)
        assertEquals(1, favorite.index)

        val other = tree.target(zurnal.mediaId) as BrowseTarget.Radio
        assertEquals(stations, other.stations)
        assertEquals(0, other.index)
    }

    @Test
    fun withoutFavoritesTheRadioTabIsTheWholeList() {
        assertEquals(stations.map { it.name }, tree(favorites = emptySet()).titles(BrowseTree.RADIO))
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        val tree = tree()
        assertEquals(listOf("Rádio Beat"), tree.search("radio BEAT").map { it.title })
        assertEquals(listOf("Šrouby a matice"), tree.search("srouby").map { it.title })
        assertEquals(listOf("ČRo Radiožurnál"), tree.search("  radiozurnal ").map { it.title })
        assertEquals(emptyList<BrowseNode>(), tree.search(" "))
        assertEquals(emptyList<BrowseNode>(), tree.search("nothing like this"))
    }

    @Test
    fun searchPutsTheBestFitsFirst() {
        // An album and a track of the same name, and a station that only has it as a tag.
        assertEquals(listOf("Rádio Beat"), tree().search("rock").map { it.title })
        assertEquals(listOf("Dole v dole", "Burlaci", "Corrida"), tree().search("kabat").map { it.title })
        assertEquals("Hobit", tree().search("hob").first().title)
    }

    @Test
    fun askingForAStationABookOrAPlaylistPlaysIt() {
        val tree = tree()
        val radio = tree.searchTarget("Evropa 2") as BrowseTarget.Radio
        assertEquals(evropa, radio.stations[radio.index])
        assertSame(book, (tree.searchTarget("hobit") as BrowseTarget.Audiobook).book)
        val list = tree.searchTarget("Driving") as BrowseTarget.Music
        assertEquals(playlist.path, list.playlistPath)
        assertNull(list.startIndex)
    }

    @Test
    fun askingForAnArtistPlaysAllOfThatArtist() {
        val target = tree().searchTarget("kabát") as BrowseTarget.Music
        assertEquals(listOf(1L, 2L, 3L), target.tracks.map { it.id })
        assertNull(target.startIndex)
        assertNull(target.playlistPath)
    }

    @Test
    fun askingForOneTrackStartsWithIt() {
        val target = tree().searchTarget("amerika") as BrowseTarget.Music
        assertEquals(listOf(4L), target.tracks.map { it.id })
        assertEquals(0, target.startIndex)
    }

    @Test
    fun askingForNothingOrForSomethingUnknownPlaysNothing() {
        assertNull(tree().searchTarget(""))
        assertNull(tree().searchTarget("   "))
        assertNull(tree().searchTarget("nothing like this"))
    }
}
