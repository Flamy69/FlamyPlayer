package io.github.flamy69.flamyplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class MediaFilesTest {

    private val cp1250 = Charset.forName("windows-1250")

    @Test
    fun recognizesVideosByExtension() {
        assertTrue(VideoFiles.isVideo("Film.mkv"))
        assertTrue(VideoFiles.isVideo("holiday.MP4"))
        assertTrue(VideoFiles.isVideo("camera.MJPG"))
        assertTrue(VideoFiles.isVideo("clip.mjpeg"))
        assertFalse(VideoFiles.isVideo("Film.srt"))
        assertFalse(VideoFiles.isVideo("mkv"))
        assertFalse(VideoFiles.isVideo("notes"))
    }

    @Test
    fun baseNameDropsOnlyTheExtension() {
        assertEquals("Show.S01E02", VideoFiles.baseName("Show.S01E02.mkv"))
        assertEquals("notes", VideoFiles.baseName("notes"))
    }

    @Test
    fun findsSubtitlesOfAVideo() {
        val folder = listOf("Film.mkv", "Film.srt", "Film.cs.srt", "Film.en.forced.ass", "Other.srt", "Film.nfo")
        assertEquals(
            listOf("Film.srt", "Film.cs.srt", "Film.en.forced.ass"),
            SubtitleFiles.matching("Film.mkv", folder),
        )
    }

    @Test
    fun subtitleNamesAreMatchedIgnoringCase() {
        assertEquals(listOf("FILM.SRT"), SubtitleFiles.matching("film.mkv", listOf("film.mkv", "FILM.SRT")))
    }

    @Test
    fun subtitlesGoToTheVideoWithTheLongestMatchingName() {
        val folder = listOf("Film.mkv", "Film 2.mkv", "Film.srt", "Film 2.srt", "Film 2.cs.srt")
        assertEquals(listOf("Film.srt"), SubtitleFiles.matching("Film.mkv", folder))
        assertEquals(listOf("Film 2.srt", "Film 2.cs.srt"), SubtitleFiles.matching("Film 2.mkv", folder))
    }

    @Test
    fun episodeNumbersAreNotMistakenForTags() {
        val folder = listOf("Show.E1.mkv", "Show.E10.mkv", "Show.E1.srt", "Show.E10.srt")
        assertEquals(listOf("Show.E1.srt"), SubtitleFiles.matching("Show.E1.mkv", folder))
    }

    @Test
    fun readsTheLanguageFromTheFileName() {
        assertEquals("cs", SubtitleFiles.tag("Film.mkv", "Film.cs.srt"))
        assertEquals("", SubtitleFiles.tag("Film.mkv", "Film.srt"))
        assertEquals("en.forced", SubtitleFiles.tag("Film.mkv", "Film.en.forced.srt"))

        assertEquals("cs", SubtitleFiles.language("cs"))
        assertEquals("eng", SubtitleFiles.language("ENG.forced"))
        assertNull(SubtitleFiles.language(""))
        assertNull(SubtitleFiles.language("forced"))
        assertNull(SubtitleFiles.language("720p"))
    }

    @Test
    fun knowsTheSubtitleFormats() {
        assertEquals(SubtitleFiles.Format.SRT, SubtitleFiles.format("a.srt"))
        assertEquals(SubtitleFiles.Format.SSA, SubtitleFiles.format("a.ASS"))
        assertEquals(SubtitleFiles.Format.VTT, SubtitleFiles.format("a.vtt"))
        assertNull(SubtitleFiles.format("a.sub"))
    }

    @Test
    fun decodesUtf8WithAndWithoutByteOrderMark() {
        val text = "Příliš žluťoučký kůň"
        assertEquals(text, SubtitleFiles.decode(text.toByteArray(Charsets.UTF_8), cp1250))
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, SubtitleFiles.decode(withBom, cp1250))
    }

    @Test
    fun decodesUtf16() {
        val text = "Příliš žluťoučký kůň"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, SubtitleFiles.decode(littleEndian, cp1250))
        assertEquals(text, SubtitleFiles.decode(bigEndian, cp1250))
    }

    @Test
    fun fallsBackToTheLegacyCodePage() {
        val text = "Příliš žluťoučký kůň úpěl ďábelské ódy"
        assertEquals(text, SubtitleFiles.decode(text.toByteArray(cp1250), cp1250))
    }

    @Test
    fun picksTheCodePageByLanguage() {
        assertEquals("windows-1250", SubtitleFiles.legacyCharset("cs").name())
        assertEquals("windows-1250", SubtitleFiles.legacyCharset("SK").name())
        assertEquals("windows-1251", SubtitleFiles.legacyCharset("ru").name())
        assertEquals("windows-1252", SubtitleFiles.legacyCharset("en").name())
    }

    @Test
    fun groupsVideosByFolderInNaturalOrder() {
        val videos = listOf(
            Video(1, "/storage/Movies/Show/Episode 10.mkv", 0, 0),
            Video(2, "/storage/Movies/Show/Episode 2.mkv", 0, 0),
            Video(3, "/storage/DCIM/Camera/clip.mp4", 0, 0),
        )
        val folders = groupVideosByFolder(videos)
        assertEquals(listOf("Camera", "Show"), folders.map { it.name })
        assertEquals(listOf("Episode 2.mkv", "Episode 10.mkv"), folders[1].videos.map { it.fileName })
    }
}
