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

    suspend fun playCinematicIntro() {
        withContext(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationSec = 1.6
                val totalSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(totalSamples)

                val random = Random(42)
                val impactTimeSec = 0.35
                val impactSample = (sampleRate * impactTimeSec).toInt()

                var noiseFilterState = 0.0

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    var sampleVal = 0.0

                    // 1. Whoosh / Riser: 0.0s to 0.45s
                    if (t <= 0.45) {
                        val riserProgress = (t / 0.45).coerceIn(0.0, 1.0)
                        val riserVol = riserProgress * riserProgress // exponential crescendo
                        // Frequency sweep from 80Hz to 600Hz
                        val currentFreq = 80.0 + 520.0 * riserProgress
                        val sineRiser = sin(2.0 * PI * currentFreq * t)

                        // Filtered noise for wind/whoosh texture
                        val rawNoise = (random.nextDouble() * 2.0 - 1.0)
                        val filterCoeff = 0.08 + 0.25 * riserProgress
                        noiseFilterState += filterCoeff * (rawNoise - noiseFilterState)

                        sampleVal += (sineRiser * 0.4 + noiseFilterState * 0.6) * riserVol * 12000.0
                    }

                    // 2. Cinematic Boom / Sub-bass impact: 0.35s to 1.6s
                    if (i >= impactSample) {
                        val boomT = (i - impactSample).toDouble() / sampleRate
                        // Heavy sub-bass pitch drop: starts at 85Hz dropping to 42Hz
                        val freq = 42.0 + 43.0 * exp(-boomT * 6.0)
                        val subBass = sin(2.0 * PI * freq * boomT)
                        val bodyHarmonic = sin(2.0 * PI * (freq * 2.0) * boomT) * 0.35
                        val midTexture = sin(2.0 * PI * (freq * 3.5) * boomT) * 0.15

                        // Exponential decay envelope
                        val boomEnvelope = exp(-boomT * 2.8)
                        val boomSound = (subBass + bodyHarmonic + midTexture) * boomEnvelope * 26000.0
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
                audioTrack.play()

                // Release after playing
                kotlinx.coroutines.delay((durationSec * 1000).toLong() + 200)
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Graceful fallback if device audio cannot initialize
                e.printStackTrace()
            }
        }
    }
}
