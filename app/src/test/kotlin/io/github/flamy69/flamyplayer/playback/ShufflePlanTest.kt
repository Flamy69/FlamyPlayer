package io.github.flamy69.flamyplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShufflePlanTest {

    /** Ten tracks: four by "a", three by "b", two by "c" and one without a known artist. */
    private val artists = listOf("a", "a", "a", "a", "b", "b", "b", "c", "c", null)

    private fun plan(start: Int = 0, plays: IntArray = IntArray(artists.size), seed: Int = 1) =
        ShufflePlan(artists, { plays[it] }, start, Random(seed))

    /** Plays the plan to its end the way a listener would who hears every track out. */
    private fun ShufflePlan.playThrough(): List<Int> {
        val played = mutableListOf(current)
        while (true) {
            markListened()
            val following = next ?: break
            moveTo(following)
            played += following
        }
        return played
    }

    @Test
    fun playsEveryTrackExactlyOnce() {
        for (seed in 1..20) {
            val order = plan(seed = seed).snapshot()
            assertEquals(artists.indices.toList(), order.sorted())
            assertEquals(artists.indices.toList(), plan(seed = seed).playThrough().sorted())
        }
    }

    @Test
    fun startsWhereThePlayerIs() {
        val plan = plan(start = 7)
        assertEquals(7, plan.current)
        assertEquals(7, plan.snapshot().first())
    }

    @Test
    fun prefersTracksThatWerePlayedLeast() {
        // Only two tracks were never played; they come right after the start.
        val plays = IntArray(artists.size) { 3 }
        plays[5] = 0
        plays[8] = 0
        for (seed in 1..20) {
            val plan = plan(start = 0, plays = plays, seed = seed)
            val first = plan.next!!
            plan.moveTo(first)
            val second = plan.next!!
            assertEquals(setOf(5, 8), setOf(first, second))
        }
    }

    @Test
    fun staysWithTheArtistAfterATrackWasHeardOut() {
        for (seed in 1..20) {
            val plan = plan(start = 4, seed = seed)
            plan.markListened()
            assertEquals("b", artists[plan.next!!])
        }
    }

    @Test
    fun prefersTheLeastPlayedTrackOfTheArtist() {
        val plays = IntArray(artists.size)
        plays[1] = 2
        plays[2] = 2
        // Of the artist's other tracks only number 3 was never played.
        for (seed in 1..20) {
            val plan = plan(start = 0, plays = plays, seed = seed)
            plan.markListened()
            assertEquals(3, plan.next)
        }
    }

    @Test
    fun neverPlaysMoreThanThreeOfAnArtistInARow() {
        for (seed in 1..50) {
            val played = plan(start = 0, seed = seed).playThrough()
            var run = 0
            var previous: String? = null
            for (track in played) {
                val artist = artists[track]
                run = if (artist != null && artist == previous) run + 1 else 1
                assertTrue("seed $seed: $played", run <= ShufflePlan.MAX_RUN)
                previous = artist
            }
            // The first three are the artist of the start, the fourth is somebody else.
            assertEquals(listOf("a", "a", "a"), played.take(3).map { artists[it] })
            assertNotEquals("a", artists[played[3]])
        }
    }

    @Test
    fun movesOnToAnotherArtistWhenNothingOfThisOneIsLeft() {
        val plan = plan(start = 7)
        plan.markListened()
        assertEquals(8, plan.next)
        plan.moveTo(8)
        plan.markListened()
        assertNotEquals("c", artists[plan.next!!])
    }

    @Test
    fun aSkippedTrackDoesNotTieTheNextOneToItsArtist() {
        // Everything of artist "a" was played often; without hearing a track out, the plan
        // has no reason to stay with that artist.
        val plays = IntArray(artists.size)
        for (i in 0..3) plays[i] = 5
        for (seed in 1..20) {
            val plan = plan(start = 0, plays = plays, seed = seed)
            assertNotEquals("a", artists[plan.next!!])
        }
    }

    @Test
    fun goingBackLeadsThroughWhatWasPlayed() {
        val plan = plan(start = 0)
        val second = plan.next!!
        plan.moveTo(second)
        val third = plan.next!!
        plan.moveTo(third)

        // Back twice, then forward again: the same tracks, in the same order.
        assertFalse(plan.moveTo(second))
        assertFalse(plan.moveTo(0))
        assertEquals(second, plan.next)
        // Hearing an old track out does not rearrange what was played after it.
        assertFalse(plan.markListened())
        assertFalse(plan.moveTo(second))
        assertEquals(third, plan.next)
    }

    @Test
    fun aTrackPickedByHandIsPlayedNext() {
        val plan = plan(start = 0)
        val planned = plan.next!!
        val picked = plan.snapshot().last()
        assertTrue(plan.moveTo(picked))
        assertEquals(picked, plan.current)
        assertArrayEquals(intArrayOf(0, picked), plan.snapshot().copyOfRange(0, 2))
        // What was planned before is still to come.
        assertTrue(planned in plan.snapshot().drop(2))

        // The track before it is one step back.
        plan.moveTo(0)
        assertEquals(0, plan.current)
        assertEquals(picked, plan.next)
    }

    @Test
    fun copesWithTinyPlaylists() {
        val single = ShufflePlan(listOf("a"), { 0 }, 0)
        assertEquals(0, single.current)
        assertNull(single.next)
        assertFalse(single.markListened())
        assertFalse(single.moveTo(5))

        val pair = ShufflePlan(listOf("a", "a"), { 0 }, 1)
        assertEquals(1, pair.next?.let { 1 - it })
    }

    @Test
    fun comparesArtistsWithoutCaseAndSpaces() {
        assertEquals("the band", ShufflePlan.artistKey("  The Band "))
        assertNull(ShufflePlan.artistKey("  "))
        assertNull(ShufflePlan.artistKey(null))
    }

    @Test
    fun startsWithOneOfTheLeastPlayedTracks() {
        val plays = intArrayOf(4, 1, 7, 1, 1, 9)
        val picks = (1..200).map { ShufflePlan.leastPlayed(plays.size, { plays[it] }, Random(it)) }.toSet()
        assertEquals(setOf(1, 3, 4), picks)
        assertEquals(0, ShufflePlan.leastPlayed(0, { 0 }))
    }
}
