package com.transfer.flash.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.app.NotificationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * UI-040 semantic sound vocabulary for the whole app (opt-in, DEFAULT OFF — owner decision
 * 2026-08-22).
 *
 * Mirrors [FlashHaptic]: components must never touch `android.media` directly; they obtain
 * [rememberFlashSounds] and express *intent*. Each entry carries its own synthesis spec:
 * a list of [ToneSegment]s (frequency Hz × duration ms, `freqHz == 0.0` = silence) and a
 * peak amplitude relative to full scale.
 *
 * | [FlashSound] | Intent |
 * |---|---|
 * | [MessageSent] | Outgoing message accepted by send pipeline |
 * | [MessageDelivered] | Message confirmed delivered to peer |
 * | [MessageReceived] | Incoming message arrived |
 * | [RecordingStart] | Voice recording began |
 * | [RecordingStop] | Voice recording ended (commit or discard) |
 * | [TransferComplete] | File transfer finished successfully |
 * | [PairingSuccess] | Peer pairing completed |
 * | [Error] | Send/transfer/pairing failure |
 */
enum class FlashSound(
    val segments: List<ToneSegment>,
    val peakAmplitude: Double,
) {
    MessageSent(
        segments = listOf(ToneSegment(880.0, 60), ToneSegment(1320.0, 70)),
        peakAmplitude = 0.50,
    ),
    MessageDelivered(
        segments = listOf(ToneSegment(1174.66, 90)),
        peakAmplitude = 0.45,
    ),
    MessageReceived(
        segments = listOf(ToneSegment(659.25, 120)),
        peakAmplitude = 0.35,
    ),
    RecordingStart(
        segments = listOf(ToneSegment(740.0, 80)),
        peakAmplitude = 0.45,
    ),
    RecordingStop(
        segments = listOf(ToneSegment(493.88, 100)),
        peakAmplitude = 0.40,
    ),
    TransferComplete(
        segments = listOf(ToneSegment(1046.5, 60), ToneSegment(1318.5, 60), ToneSegment(1568.0, 110)),
        peakAmplitude = 0.50,
    ),
    PairingSuccess(
        segments = listOf(ToneSegment(587.33, 80), ToneSegment(880.0, 140)),
        peakAmplitude = 0.50,
    ),
    Error(
        segments = listOf(ToneSegment(196.0, 130), ToneSegment.SILENCE_60MS, ToneSegment(196.0, 130)),
        peakAmplitude = 0.55,
    ),
}

/**
 * One tone piece: sine at [freqHz] for [durationMs], or silence when [isSilence].
 * Pure data — safe on the JVM.
 */
data class ToneSegment(val freqHz: Double, val durationMs: Int) {
    val isSilence: Boolean get() = freqHz <= 0.0

    companion object {
        val SILENCE_60MS = ToneSegment(0.0, 60)
    }
}

/**
 * Pure decision logic for UI sounds (unit-tested in `FlashSoundsTest`) — primitive types only.
 *
 * Sounds play only when ALL of these hold:
 * 1. The user opted in ([soundsEnabled]; default OFF by owner decision).
 * 2. The device is not in SILENT/VIBRATE ringer mode (values from
 *    `AudioManager.RINGER_MODE_*`, compile-time constants so JVM tests need no Android runtime).
 * 3. DND is not active — interruption filter must be ALL or UNKNOWN (values from
 *    `NotificationManager.INTERRUPTION_FILTER_*`). UNKNOWN means the filter could not be read;
 *    we allow playback because `USAGE_ASSISTANCE_SONIFICATION` is OS-classified
 *    SUPPRESSIBLE_SYSTEM and is muted under restrictive Zen modes anyway.
 *
 * The policy is evaluated at every play attempt (never cached) so flipping silent mode or
 * the opt-in toggle takes effect immediately.
 */
object FlashSoundPolicy {

    fun shouldPlay(
        soundsEnabled: Boolean,
        ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    ): Boolean {
        if (!soundsEnabled) return false
        if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE
        ) {
            return false
        }
        if (interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            return false
        }
        return true
    }
}

/**
 * Temporary opt-in state bridge until DataStore persistence lands in core phase C1.5.
 *
 * Backed by `mutableStateOf` so Compose reads reactively. **Default FALSE** (opt-in feature,
 * owner decision 2026-08-22). In-memory only: resets to OFF on process death — documented
 * known limitation in `docs/ui/motion-system.md` (UI-040 section), intentional.
 */
object FlashSoundSettings {
    var soundsEnabled: Boolean by mutableStateOf(false)
}

/**
 * Pure PCM synthesis math — no Android imports at runtime, fully JVM-testable.
 *
 * Each non-silent segment renders as a sine wave shaped by:
 * - linear attack over ~[ATTACK_MS] ms (removes onset click),
 * - exponential decay with tau = segmentDuration/3 (ends ≈ −21 dB),
 * - linear fade-out over ~[FADE_MS] ms (removes tail click),
 * scaled by the event's [FlashSound.peakAmplitude].
 */
object FlashSoundSynth {

    const val SAMPLE_RATE_HZ = 44_100
    private const val ATTACK_MS = 5.0
    private const val FADE_MS = 3.0

    fun totalDurationMs(sound: FlashSound): Long =
        sound.segments.sumOf { it.durationMs.toLong() }

    fun render(sound: FlashSound, sampleRateHz: Int = SAMPLE_RATE_HZ): ShortArray {
        val out = ShortArray((totalDurationMs(sound) * sampleRateHz / 1000L).toInt())
        var offset = 0
        for (segment in sound.segments) {
            val frames = segment.durationMs * sampleRateHz / 1000
            if (!segment.isSilence) {
                renderSegment(segment.freqHz, frames, sampleRateHz, sound.peakAmplitude, out, offset)
            }
            offset += frames
        }
        return out
    }

    fun dominantFrequencyHz(sound: FlashSound): Double =
        sound.segments.firstOrNull { !it.isSilence }?.freqHz ?: 0.0

    private fun renderSegment(
        freqHz: Double,
        frames: Int,
        sampleRateHz: Int,
        peakAmplitude: Double,
        out: ShortArray,
        offset: Int,
    ) {
        val attackFrames = (ATTACK_MS * sampleRateHz / 1000.0).toInt().coerceIn(1, frames)
        val fadeFrames = (FADE_MS * sampleRateHz / 1000.0).toInt().coerceIn(1, frames)
        val durationSec = frames.toDouble() / sampleRateHz
        val tauSec = durationSec / 3.0
        val scale = peakAmplitude * Short.MAX_VALUE

        for (i in 0 until frames) {
            val t = i / sampleRateHz.toDouble()
            val attack = if (i < attackFrames) i / attackFrames.toDouble() else 1.0
            val decay = exp(-(t - attackFrames / sampleRateHz.toDouble()) / tauSec)
                .coerceAtMost(1.0)
            val fade = min(1.0, (frames - i) / fadeFrames.toDouble())
            val envelope = attack * decay * fade
            val value = sin(2.0 * PI * freqHz * t) * envelope * scale
            out[offset + i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}

/**
 * UI-040 single choke point for all chat sounds, mirroring [rememberFlashHaptics].
 *
 * Returns a stable lambda so call sites read e.g. `sound(FlashSound.MessageSent)`.
 * Playback is gated by [FlashSoundPolicy] (opt-in flag + system silent/DND state checked at
 * call time); when disabled this does zero work — no AudioTrack is ever created.
 */
@Composable
fun rememberFlashSounds(): (FlashSound) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return remember { { sound -> FlashSoundPlayer.play(context, sound) } }
}

/**
 * Internal AudioTrack backend. One MODE_STATIC track per event, created lazily on first
 * enabled play and reused via stop/reloadStaticData/play. USAGE_ASSISTANCE_SONIFICATION +
 * CONTENT_TYPE_SONIFICATION route tones to the system volume group and let the OS mute them
 * under Zen modes that disallow system sounds (belt-and-braces with [FlashSoundPolicy]).
 */
internal object FlashSoundPlayer {

    private val lock = Any()
    private var appContextRef: Context? = null
    private val tracks = HashMap<FlashSound, AudioTrack>()

    fun play(context: Context, sound: FlashSound) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val allowed = FlashSoundPolicy.shouldPlay(
            soundsEnabled = FlashSoundSettings.soundsEnabled,
            ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            interruptionFilter = notificationManager?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        if (!allowed) return

        synchronized(lock) {
            appContextRef = context.applicationContext
            try {
                val track = tracks.getOrPut(sound) { createTrack(sound) }
                track.stop()
                track.reloadStaticData()
                track.play()
            } catch (_: IllegalStateException) {
                releaseLocked(sound)
            } catch (_: IllegalArgumentException) {
                releaseLocked(sound)
            }
        }
    }

    private fun createTrack(sound: FlashSound): AudioTrack {
        val pcm = FlashSoundSynth.render(sound)
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(FlashSoundSynth.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private fun releaseLocked(sound: FlashSound) {
        tracks.remove(sound)?.release()
        if (tracks.isEmpty()) appContextRef = null
    }
}
