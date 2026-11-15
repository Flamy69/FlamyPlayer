package io.github.flamy69.flamyplayer.playback

import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer

/**
 * Makes quiet recordings louder than the volume buttons alone can: the sound is amplified
 * before it reaches the volume control, so every volume step, and the maximum, come out
 * louder. Android's own effect is used, which holds back the peaks instead of clipping them.
 */
class VolumeBoost {

    private var enhancer: LoudnessEnhancer? = null
    private var sessionId = NO_SESSION
    private var gainDb = 0

    /** Ties the effect to the audio session the player plays into. */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        apply()
    }

    /** Sets the amplification in decibels; zero switches the effect off. */
    fun setGain(decibels: Int) {
        if (decibels == gainDb) return
        gainDb = decibels
        apply()
    }

    fun release() {
        try {
            enhancer?.release()
        } catch (_: RuntimeException) {
        }
        enhancer = null
    }

    private fun apply() {
        try {
            if (gainDb <= 0) {
                enhancer?.enabled = false
                return
            }
            if (sessionId == NO_SESSION) return
            val effect = enhancer ?: LoudnessEnhancer(sessionId).also { enhancer = it }
            effect.setTargetGain(gainDb * MILLIBELS_PER_DECIBEL)
            effect.enabled = true
        } catch (_: RuntimeException) {
            // The device has no such effect, or took it away; playback goes on without it.
            release()
        }
    }

    private companion object {
        const val MILLIBELS_PER_DECIBEL = 100

        /** What a player reports before it has an audio session of its own. */
        const val NO_SESSION = AudioManager.AUDIO_SESSION_ID_GENERATE
    }
}
