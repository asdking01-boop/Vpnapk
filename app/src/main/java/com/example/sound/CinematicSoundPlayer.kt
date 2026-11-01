package com.example.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object CinematicSoundPlayer {

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun stop() {
        try {
            activeTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            activeTrack = null
        }
    }

    suspend fun playCinematicIntro() {
        withContext(Dispatchers.IO) {
            try {
                // Ensure any previous track is stopped
                stop()

                val sampleRate = 44100
                val durationSec = 2.2
                val totalSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(totalSamples)

                val random = Random(42)
                val impactTimeSec = 0.50
                val impactSample = (sampleRate * impactTimeSec).toInt()

                var noiseFilterState = 0.0

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    var sampleVal = 0.0

                    // 1. Rising Whoosh: 0.0s to 0.55s (synchronized with K zoom-in)
                    if (t <= 0.55) {
                        val riserProgress = (t / 0.55).coerceIn(0.0, 1.0)
                        val riserVol = riserProgress * riserProgress // exponential crescendo
                        // Frequency sweep from 70Hz to 650Hz
                        val currentFreq = 70.0 + 580.0 * riserProgress
                        val sineRiser = sin(2.0 * PI * currentFreq * t)

                        // Filtered noise for wind/air rushing texture
                        val rawNoise = (random.nextDouble() * 2.0 - 1.0)
                        val filterCoeff = 0.07 + 0.28 * riserProgress
                        noiseFilterState += filterCoeff * (rawNoise - noiseFilterState)

                        sampleVal += (sineRiser * 0.45 + noiseFilterState * 0.55) * riserVol * 14000.0
                    }

                    // 2. Cinematic Boom & Resonant Sub-bass: 0.50s to 2.2s (synchronized with light sweep & ribbon beams)
                    if (i >= impactSample) {
                        val boomT = (i - impactSample).toDouble() / sampleRate
                        // Heavy sub-bass pitch drop: starts at 90Hz dropping to 38Hz
                        val freq = 38.0 + 52.0 * exp(-boomT * 5.5)
                        val subBass = sin(2.0 * PI * freq * boomT)
                        val bodyHarmonic = sin(2.0 * PI * (freq * 2.0) * boomT) * 0.38
                        val midTexture = sin(2.0 * PI * (freq * 3.5) * boomT) * 0.16

                        // Exponential decay envelope
                        val boomEnvelope = exp(-boomT * 2.2)
                        val boomSound = (subBass + bodyHarmonic + midTexture) * boomEnvelope * 28000.0
                        sampleVal += boomSound
                    }

                    // Soft clip to 16-bit range
                    val clamped = sampleVal.coerceIn(-32767.0, 32767.0)
                    buffer[i] = clamped.toInt().toShort()
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val bufferSizeBytes = totalSamples * 2
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, totalSamples)
                activeTrack = audioTrack
                audioTrack.play()

                // Release after playing
                kotlinx.coroutines.delay((durationSec * 1000).toLong() + 200)
                if (activeTrack == audioTrack) {
                    try {
                        audioTrack.stop()
                        audioTrack.release()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    activeTrack = null
                }
            } catch (e: Exception) {
                // Graceful fallback if device audio cannot initialize
                e.printStackTrace()
            }
        }
    }
}
