package io.github.flamy69.flamyplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun numbersAreComparedByValue() {
        val names = listOf("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3", "Chapter 21.mp3")
        assertEquals(
            listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3", "Chapter 21.mp3"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun leadingZerosDoNotChangeTheOrder() {
        val names = listOf("010 - c.mp3", "9 - b.mp3", "0001 - a.mp3")
        assertEquals(listOf("0001 - a.mp3", "9 - b.mp3", "010 - c.mp3"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(NaturalOrder.compare("abba", "Beatles") < 0)
        assertTrue(NaturalOrder.compare("Zappa", "abba") > 0)
    }

    @Test
    fun shorterNameComesFirst() {
        assertTrue(NaturalOrder.compare("Track", "Track 1") < 0)
    }

    @Test
    fun onlyIdenticalStringsAreEqual() {
        assertEquals(0, NaturalOrder.compare("Track 01", "Track 01"))
        assertTrue(NaturalOrder.compare("Track 01", "Track 1") != 0)
    }

    @Test
    fun veryLongNumbersDoNotOverflow() {
        assertTrue(NaturalOrder.compare("99999999999999999999998", "99999999999999999999999") < 0)
    }

    @Test
    fun foldersComeBeforeTheirSubfoldersAndSiblingsInNaturalOrder() {
        val folders = listOf("/m/Album 10", "/m/Album 2/CD 2", "/m/Album 2", "/m/Album 2/CD 1", "/m", "/m/Album 2 Live")
        assertEquals(
            listOf("/m", "/m/Album 2", "/m/Album 2/CD 1", "/m/Album 2/CD 2", "/m/Album 2 Live", "/m/Album 10"),
            folders.sortedWith(PathOrder),
        )
    }
}
