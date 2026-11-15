package io.github.flamy69.flamyplayer.data

import io.github.flamy69.flamyplayer.network.JoinedParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFilesTest {

    private val music = "/storage/emulated/0/Music"

    private fun track(id: Long, path: String) = Track(id, path.substringAfterLast('/'), null, null, 1000, path)

    private val tracks = listOf(
        track(1, "$music/Band/First/01 Intro.mp3"),
        track(2, "$music/Band/First/02 Song.mp3"),
        track(3, "$music/Band/Second/01 Intro.mp3"),
        track(4, "$music/Other/Tune.flac"),
        track(5, "$music/loose.mp3"),
    )

    private val matcher = PlaylistFiles.Matcher(tracks)

    private fun find(entry: String, folder: String = music) = matcher.find(entry, folder)?.id

    @Test
    fun recognizesPlaylistFiles() {
        assertTrue(PlaylistFiles.isPlaylist("Favourites.m3u"))
        assertTrue(PlaylistFiles.isPlaylist("Favourites.M3U8"))
        assertTrue(PlaylistFiles.isPlaylist("radio.pls"))
        assertFalse(PlaylistFiles.isPlaylist("song.mp3"))
        assertFalse(PlaylistFiles.isPlaylist("m3u"))
    }

    @Test
    fun readsM3u() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:123,Band - Song\r\nBand/First/02 Song.mp3\r\n\r\n  loose.mp3  \r\n# a comment\r\n"
        assertEquals(listOf("Band/First/02 Song.mp3", "loose.mp3"), PlaylistFiles.parse("list.m3u", text))
    }

    @Test
    fun readsPlsInTheOrderOfItsNumbers() {
        val text = "[playlist]\nFile2=second.mp3\nTitle2=Second\nFile1=first.mp3\nLength1=-1\nNumberOfEntries=2\nVersion=2\n"
        assertEquals(listOf("first.mp3", "second.mp3"), PlaylistFiles.parse("list.PLS", text))
    }

    @Test
    fun resolvesPathsLikeAFileSystemWould() {
        assertEquals("$music/Band/a.mp3", PlaylistFiles.absolutePath("Band/a.mp3", music))
        assertEquals("$music/Band/a.mp3", PlaylistFiles.absolutePath("./Band/../Band/a.mp3", music))
        assertEquals("/storage/emulated/0/a.mp3", PlaylistFiles.absolutePath("..\\a.mp3", music))
        assertEquals("/sdcard/a.mp3", PlaylistFiles.absolutePath("/sdcard/a.mp3", music))
        assertEquals("/C:/Music/a b.mp3", PlaylistFiles.absolutePath("C:\\Music\\a b.mp3", music))
        assertEquals("/C:/Music/a b+c.mp3", PlaylistFiles.absolutePath("file:///C:/Music/a%20b+c.mp3", music))
        assertEquals("/sdcard/a.mp3", PlaylistFiles.absolutePath("file:///sdcard/a.mp3", music))
        // An internet stream is not a file of the library.
        assertNull(PlaylistFiles.absolutePath("http://example.com/stream.mp3", music))
    }

    @Test
    fun findsTracksByTheirExactPath() {
        assertEquals(2L, find("Band/First/02 Song.mp3"))
        assertEquals(2L, find("$music/Band/First/02 Song.mp3", "/somewhere/else"))
        assertEquals(4L, find("../Other/Tune.flac", "$music/Band"))
        // Windows does not care about upper and lower case, and neither do its playlists.
        assertEquals(4L, find("other/TUNE.FLAC"))
    }

    @Test
    fun findsTracksOfAPlaylistMadeOnAComputer() {
        assertEquals(2L, find("C:\\Users\\me\\Music\\Band\\First\\02 Song.mp3"))
        // The folders tell the two intros apart.
        assertEquals(3L, find("D:\\Music\\Band\\Second\\01 Intro.mp3"))
        assertEquals(1L, find("D:\\Music\\Band\\First\\01 Intro.mp3"))
        // The only file of that name, wherever the computer kept it.
        assertEquals(5L, find("E:\\Stuff\\loose.mp3"))
    }

    @Test
    fun leavesOutWhatItCannotTellApart() {
        // Two intros, and nothing in the entry says which one.
        assertNull(find("X:\\01 Intro.mp3"))
        assertNull(find("missing.mp3"))
        assertNull(find("https://example.com/01 Intro.mp3"))
    }

    @Test
    fun keepsTheOrderAndTheRepeatsOfAPlaylist() {
        val file = PlaylistFile("$music/Mix.m3u", listOf("loose.mp3", "nothing.mp3", "Other/Tune.flac", "loose.mp3"))
        val playlist = matcher.resolve(file)
        assertEquals("Mix", playlist.name)
        assertEquals(music, file.folder)
        assertEquals(listOf(5L, 4L, 5L), playlist.tracks.map { it.id })
    }

    @Test
    fun groupsThePiecesOfDvdTitles() {
        val names = listOf(
            "VIDEO_TS.IFO", "VIDEO_TS.VOB", "VTS_01_0.IFO", "VTS_01_0.VOB", "VTS_01_2.VOB", "VTS_01_1.VOB",
            "vts_02_1.vob", "VTS_10_1.VOB", "holiday.vob",
        )
        val titles = DvdFiles.titles(names)
        assertEquals(listOf(1, 2, 10), titles.map { it.number })
        assertEquals(listOf("VTS_01_1.VOB", "VTS_01_2.VOB"), titles[0].parts)
        assertEquals(listOf("vts_02_1.vob"), titles[1].parts)

        assertTrue(DvdFiles.isMenu("VIDEO_TS.VOB"))
        assertTrue(DvdFiles.isMenu("VTS_01_0.VOB"))
        assertFalse(DvdFiles.isMenu("VTS_01_1.VOB"))
        assertFalse(DvdFiles.isMenu("holiday.vob"))
        assertTrue(DvdFiles.titles(listOf("film.mkv", "holiday.vob")).isEmpty())
    }

    @Test
    fun findsAPositionInFilesLaidEndToEnd() {
        val parts = JoinedParts(listOf(100L, 50L, 200L))
        assertEquals(350L, parts.total)
        assertEquals(0 to 0L, parts.locate(0))
        assertEquals(0 to 99L, parts.locate(99))
        assertEquals(1 to 0L, parts.locate(100))
        assertEquals(2 to 0L, parts.locate(150))
        assertEquals(2 to 199L, parts.locate(349))
        // The very end is the end of the last file.
        assertEquals(2 to 200L, parts.locate(350))
        assertEquals(0L, JoinedParts(emptyList()).total)
    }
}
