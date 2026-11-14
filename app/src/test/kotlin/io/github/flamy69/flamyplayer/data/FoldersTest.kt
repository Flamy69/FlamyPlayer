package io.github.flamy69.flamyplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {

    private val storage = "/storage/emulated/0"

    private fun track(id: Long, path: String, durationMs: Long = 1000) =
        Track(id = id, title = "t$id", artist = null, album = null, durationMs = durationMs, path = path)

    @Test
    fun trackKnowsItsFolderAndNames() {
        val track = track(1, "$storage/Music/Album/01 Song.name.mp3")
        assertEquals("$storage/Music/Album", track.folder)
        assertEquals("01 Song.name.mp3", track.fileName)
        assertEquals("01 Song.name", track.displayName)
    }

    @Test
    fun emptyLibraryHasNoTree() {
        assertNull(FolderTree.build(emptyMap()))
    }

    @Test
    fun uninterestingPrefixIsFoldedIntoTheRoot() {
        val root = FolderTree.build(
            mapOf(
                "$storage/Music/Album 2" to 10,
                "$storage/Music/Album 10" to 12,
                "$storage/Music" to 1,
                "$storage/Audiobooks/Book/CD1" to 5,
                "$storage/Audiobooks/Book/CD2" to 6,
            ),
        )!!

        assertEquals(storage, root.path)
        assertEquals(34, root.totalCount)
        assertEquals(listOf("Audiobooks/Book", "Music"), root.children.map { it.name })

        val book = root.children[0]
        assertEquals("$storage/Audiobooks/Book", book.path)
        assertEquals(11, book.totalCount)
        assertEquals(listOf("CD1", "CD2"), book.children.map { it.name })

        val music = root.children[1]
        assertEquals(1, music.directCount)
        assertEquals(23, music.totalCount)
        assertEquals(listOf("Album 2", "Album 10"), music.children.map { it.name })
    }

    @Test
    fun separateVolumesStayApart() {
        val root = FolderTree.build(mapOf("$storage/Music" to 3, "/storage/1A2B-3C4D/Music" to 4))!!
        assertEquals("/storage", root.path)
        assertEquals(listOf("/storage/1A2B-3C4D/Music", "$storage/Music"), root.children.map { it.path })
    }

    @Test
    fun chainLeadsFromRootToFolder() {
        val root = FolderTree.build(mapOf("$storage/Music/A/B" to 1, "$storage/Music/C" to 1, "$storage/Other" to 1))!!
        assertEquals(
            listOf(storage, "$storage/Music", "$storage/Music/A/B"),
            root.chainTo("$storage/Music/A/B").map { it.path },
        )
        assertTrue(root.chainTo("$storage/Music/A").isEmpty())
        assertTrue(root.chainTo("$storage/Nope").isEmpty())
    }

    @Test
    fun countUnderWorksForFoldersThatAreNotNodes() {
        val root = FolderTree.build(mapOf("$storage/Music/A/B" to 2, "$storage/Music/C" to 3, "$storage/Other" to 4))!!
        assertEquals(2, root.countUnder("$storage/Music/A"))
        assertEquals(5, root.countUnder("$storage/Music"))
        assertEquals(9, root.countUnder("/storage"))
        assertEquals(0, root.countUnder("$storage/Mus"))
        assertEquals(0, root.countUnder("$storage/Gone"))
    }

    @Test
    fun selectionCoversSubfoldersButNotLookalikes() {
        val selected = setOf("$storage/Music")
        assertTrue(FolderSelection.covers(selected, "$storage/Music"))
        assertTrue(FolderSelection.covers(selected, "$storage/Music/Album"))
        assertFalse(FolderSelection.covers(selected, "$storage/Music2"))
        assertFalse(FolderSelection.covers(selected, storage))

        assertTrue(FolderSelection.coveredByAncestor(selected, "$storage/Music/Album"))
        assertFalse(FolderSelection.coveredByAncestor(selected, "$storage/Music"))
    }

    @Test
    fun selectingAFolderReplacesItsSelectedSubfolders() {
        val selected = setOf("$storage/Music/A", "$storage/Music/B", "$storage/Books")
        assertEquals(
            setOf("$storage/Music", "$storage/Books"),
            FolderSelection.toggle(selected, "$storage/Music"),
        )
        assertEquals(setOf("$storage/Music/B", "$storage/Books"), FolderSelection.toggle(selected, "$storage/Music/A"))
    }

    @Test
    fun tracksAreSortedFolderByFolderAndFilteredBySelection() {
        val tracks = sortByFolderAndName(
            listOf(
                track(1, "$storage/Music/Album 10/02 b.mp3"),
                track(2, "$storage/Music/Album 10/01 a.mp3"),
                track(3, "$storage/Music/Album 2/10 z.flac"),
                track(4, "$storage/Music/Album 2/9 y.flac"),
                track(5, "$storage/Music/loose.mp3"),
                track(6, "$storage/Podcasts/p.mp3"),
            ),
        )
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L, 6L), tracks.map { it.id })

        assertEquals(
            listOf(5L, 4L, 3L, 2L, 1L),
            FolderSelection.tracksIn(tracks, setOf("$storage/Music")).map { it.id },
        )
        assertEquals(
            listOf(4L, 3L, 6L),
            FolderSelection.tracksIn(tracks, setOf("$storage/Music/Album 2", "$storage/Podcasts")).map { it.id },
        )
        assertTrue(FolderSelection.tracksIn(tracks, emptySet()).isEmpty())
    }

    @Test
    fun bookResumesWhereTheListenerStopped() {
        val book = Book(
            "$storage/Audiobooks/Dune",
            listOf(
                track(1, "$storage/Audiobooks/Dune/01.mp3", durationMs = 1000),
                track(2, "$storage/Audiobooks/Dune/02.mp3", durationMs = 3000),
            ),
        )
        assertEquals("Dune", book.title)
        assertEquals(4000, book.durationMs)

        assertEquals(0 to 0L, book.resumePoint(null))
        assertEquals(0f, book.fraction(null), 0f)

        val progress = BookProgress(trackId = 2, positionMs = 1000)
        assertEquals(1 to 1000L, book.resumePoint(progress))
        assertEquals(0.5f, book.fraction(progress), 0.0001f)

        // A chapter that has disappeared sends the listener back to the beginning.
        assertEquals(0 to 0L, book.resumePoint(BookProgress(trackId = 99, positionMs = 500)))
    }
}
