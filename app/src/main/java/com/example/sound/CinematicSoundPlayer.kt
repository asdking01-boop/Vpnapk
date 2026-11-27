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

    /**
     * Short (1.65s) original cinematic sub-bass swell synchronized to the 0.0–1.8s
     * K Zoom-Through startup animation (optional, disabled by default).
     */
    suspend fun playZoomThroughBass() {
        withContext(Dispatchers.IO) {
            try {
                stop()

                val sampleRate = 44100
                val durationSec = 1.65
                val totalSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(totalSamples)
                val random = Random(77)

                var lowPassAir = 0.0
                var phaseSub = 0.0
                var phaseHarm = 0.0

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    var sampleVal = 0.0

                    // 1. Subtle low-end warm pulse during 0.0–0.8s reveal & light sweep
                    if (t < 0.85) {
                        val env = (t / 0.40).coerceIn(0.0, 1.0) * (1.0 - ((t - 0.55) / 0.35).coerceIn(0.0, 0.45))
                        val f = 52.0 + 10.0 * (t / 0.85)
                        phaseSub += 2.0 * PI * f / sampleRate
                        phaseHarm += 2.0 * PI * (f * 2.0) / sampleRate
                        sampleVal += (sin(phaseSub) * 0.72 + sin(phaseHarm) * 0.18) * env * 9500.0
                    }

                    // 2. Deep cinema bass drop + velvety air-rush during 0.75–1.65s zoom-through dive
                    if (t >= 0.72) {
                        val dt = t - 0.72
                        val diveFreq = 36.0 + 48.0 * exp(-dt * 4.8)
                        val attack = (dt / 0.06).coerceIn(0.0, 1.0)
                        val decay = exp(-dt * 3.1)
                        val tailFade = if (t > 1.45) ((1.65 - t) / 0.20).coerceIn(0.0, 1.0) else 1.0
                        val sub = sin(2.0 * PI * diveFreq * dt)
                        val warmOctave = sin(2.0 * PI * (diveFreq * 2.0) * dt) * 0.28

                        // Silky filtered air whoosh peaking during camera fly-through
                        val raw = random.nextDouble() * 2.0 - 1.0
                        lowPassAir += 0.09 * (raw - lowPassAir)
                        val whooshEnv = sin(((dt / 0.93).coerceIn(0.0, 1.0)) * PI)

                        sampleVal += ((sub + warmOctave) * 21000.0 * attack * decay + lowPassAir * 6500.0 * whooshEnv) * tailFade
                    }

                    buffer[i] = sampleVal.coerceIn(-32767.0, 32767.0).toInt().toShort()
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

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(totalSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, totalSamples)
                activeTrack = audioTrack
                audioTrack.play()

                kotlinx.coroutines.delay((durationSec * 1000).toLong() + 150)
                if (activeTrack == audioTrack) {
                    try {
                        audioTrack.stop()
                        audioTrack.release()
                    } catch (_: Exception) {
                    }
                    activeTrack = null
                }
            } catch (_: Exception) {
                // Graceful fallback if audio output is unavailable
            }
        }
    }
}
