package io.github.flamy69.flamyplayer.playback

import kotlin.random.Random

/**
 * Decides in which order a shuffled playlist is played.
 *
 * The next track is always one of those that were played the fewest times, so nothing comes
 * around again before everything else had its turn. A track that was listened to the end is
 * followed by another one of the same artist, but no artist gets more than [MAX_RUN] tracks
 * in a row.
 *
 * The plan is a full order of the playlist: what was played, the current track, the track
 * chosen to follow it, and everything else. Only the part after the current track ever
 * changes, so going back still leads through the tracks that were actually played.
 *
 * @param artists the artist of every track of the playlist, as made by [artistKey]
 * @param plays how many times the track at a position of the playlist was played so far
 */
class ShufflePlan(
    private val artists: List<String?>,
    private val plays: (index: Int) -> Int,
    startIndex: Int,
    random: Random = Random.Default,
) {
    private val order = IntArray(artists.size)

    /** Where in [order] the current track is. */
    private var position = 0

    /** The furthest [position] that was reached; behind it lies what was played already. */
    private var frontier = 0

    /** Whether the current track was listened to (nearly) the end. */
    private var listened = false

    init {
        val rest = artists.indices.filter { it != startIndex }.shuffled(random).sortedBy(plays)
        order[0] = startIndex
        rest.forEachIndexed { index, track -> order[index + 1] = track }
        planNext()
    }

    /** The order to hand to the player. */
    fun snapshot(): IntArray = order.copyOf()

    val current: Int get() = order[position]

    /** The track that follows the current one, if there is any left. */
    val next: Int? get() = order.getOrNull(position + 1)

    /**
     * Tells the plan that the player is now at the track [index] of the playlist. Returns
     * whether the order changed and has to be handed to the player again.
     */
    fun moveTo(index: Int): Boolean {
        if (index !in order.indices) return false
        if (index == order[position]) return planNext()

        listened = false
        var changed = false
        when {
            position + 1 < order.size && order[position + 1] == index -> position++
            position > 0 && order[position - 1] == index -> position--
            else -> {
                // A track picked by hand: it is played now, whatever the plan said.
                val from = order.indexOf(index)
                if (from > position) {
                    move(from, position + 1)
                    position++
                } else {
                    move(from, position)
                }
                frontier = position
                changed = true
            }
        }
        if (position > frontier) frontier = position
        return planNext() || changed
    }

    /**
     * Tells the plan that the current track was listened to the end, or nearly. Returns
     * whether the order changed.
     */
    fun markListened(): Boolean {
        if (listened) return false
        listened = true
        return planNext()
    }

    /** Chooses the track that follows the current one. Returns whether the order changed. */
    private fun planNext(): Boolean {
        // While going back over what was played, the way forward stays as it was.
        if (position < frontier) return false
        val first = position + 1
        if (first >= order.size) return false

        val artist = artists[order[position]]
        val run = if (artist == null) 0 else runLength(artist)
        val wanted: Boolean? = when {
            artist == null -> null
            run >= MAX_RUN -> false
            listened -> true
            else -> null
        }

        var best = -1
        var bestPlays = Int.MAX_VALUE
        var other = -1
        var otherPlays = Int.MAX_VALUE
        for (i in first until order.size) {
            val count = plays(order[i])
            val suits = wanted == null || (artists[order[i]] == artist) == wanted
            if (suits) {
                if (count < bestPlays) {
                    best = i
                    bestPlays = count
                }
            } else if (count < otherPlays) {
                other = i
                otherPlays = count
            }
        }
        // Nothing of the wanted kind is left: anything is better than silence.
        val pick = if (best >= 0) best else other
        if (pick == first) return false
        move(pick, first)
        return true
    }

    /** How many tracks of [artist] in a row end with the current one. */
    private fun runLength(artist: String): Int {
        var length = 1
        var i = position - 1
        while (i >= 0 && artists[order[i]] == artist) {
            length++
            i--
        }
        return length
    }

    /** Moves the entry at [from] to [to]; the entries in between close the gap. */
    private fun move(from: Int, to: Int) {
        val value = order[from]
        if (from < to) {
            System.arraycopy(order, from + 1, order, from, to - from)
        } else {
            System.arraycopy(order, to, order, to + 1, from - to)
        }
        order[to] = value
    }

    companion object {
        /** How many tracks of one artist may follow each other. */
        const val MAX_RUN = 3

        /** Above which part of its length a track counts as listened to the end, in percent. */
        const val LISTENED_PERCENT = 90

        /** The form in which artists are compared; `null` when the artist is not known. */
        fun artistKey(artist: String?): String? = artist?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        /** The position of one of the least played of [size] tracks, to start a playlist with. */
        fun leastPlayed(size: Int, plays: (index: Int) -> Int, random: Random = Random.Default): Int {
            var pick = 0
            var fewest = Int.MAX_VALUE
            var ties = 0
            for (i in 0 until size) {
                val count = plays(i)
                if (count < fewest) {
                    fewest = count
                    pick = i
                    ties = 1
                } else if (count == fewest) {
                    // Every track with the fewest plays gets the same chance.
                    ties++
                    if (random.nextInt(ties) == 0) pick = i
                }
            }
            return pick
        }
    }
}
