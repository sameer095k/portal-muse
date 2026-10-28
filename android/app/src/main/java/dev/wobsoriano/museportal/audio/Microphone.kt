package dev.wobsoriano.museportal.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs

sealed interface Capture {
  data class Wav(val bytes: ByteArray) : Capture

  data class Failed(val reason: String) : Capture
}

/**
 * Question capture at 16 kHz mono PCM16. Stops after trailing silence, at a 12 s cap, or when
 * [shouldStop] turns true. Leaves out the first [skipMs], where the wake chime plays. Blocks; call it
 * off the main thread.
 */
object Microphone {
  private const val TAG = "MuseMic"
  private const val RATE = 16_000
  private const val MAX_MS = 12_000
  private const val TRAIL_SILENCE_MS = 1_600
  private const val MIN_SPEECH_MS = 300
  private const val SPEECH_PEAK = 1_600

  /** [waitForSpeechMs]: give up when nobody has started talking by then. */
  fun record(skipMs: Int, shouldStop: () -> Boolean, waitForSpeechMs: Int = MAX_MS, onLevel: (Float) -> Unit): Capture {
    val rec =
        try {
          // Same order as the wake mic (see WakeMicSource): the communications path is the
          // one the Portal's presence gate leaves open, camcorder next, plain mic last.
          open(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
              ?: open(MediaRecorder.AudioSource.CAMCORDER)
              ?: open(MediaRecorder.AudioSource.MIC)
        } catch (e: SecurityException) {
          return Capture.Failed("Microphone permission is off")
        } catch (e: IllegalArgumentException) {
          null
        } ?: return Capture.Failed("The microphone is not available")
    try {
      rec.startRecording()
      if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        return Capture.Failed("Another app is using the microphone")
      }
      return capture(rec, skipMs, shouldStop, waitForSpeechMs, onLevel)
    } catch (e: IllegalStateException) {
      Log.w(TAG, "recording failed", e)
      return Capture.Failed("Another app is using the microphone")
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
  }

  private fun open(source: Int): AudioRecord? {
    val minBuf =
        AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return null
    val rec =
        AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2))
    if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
    Log.w(TAG, "audio source $source did not initialize")
    rec.release()
    return null
  }

  /** Captures from [rec], which is already recording at 16 kHz mono; the caller stops it. */
  fun capture(
      rec: AudioRecord,
      skipMs: Int,
      shouldStop: () -> Boolean,
      waitForSpeechMs: Int = MAX_MS,
      onLevel: (Float) -> Unit,
  ): Capture {
    val pcm = ByteArrayOutputStream()
    val chunk = ShortArray(RATE / 10)
    var skipped = 0
    while (skipped < skipMs * RATE / 1000 && !shouldStop()) {
      val n = rec.read(chunk, 0, minOf(chunk.size, skipMs * RATE / 1000 - skipped))
      if (n <= 0) break
      skipped += n
    }
    var elapsedMs = 0
    var speechMs = 0
    var silenceMs = 0
    while (!shouldStop() && elapsedMs < MAX_MS && (speechMs > 0 || elapsedMs < waitForSpeechMs)) {
      val n = rec.read(chunk, 0, chunk.size)
      if (n <= 0) break
      var peak = 0
      for (i in 0 until n) {
        val s = chunk[i].toInt()
        peak = maxOf(peak, abs(s))
        pcm.write(s and 0xff)
        pcm.write((s shr 8) and 0xff)
      }
      onLevel((peak / 8000f).coerceIn(0f, 1f))
      val ms = n * 1000 / RATE
      elapsedMs += ms
      if (peak >= SPEECH_PEAK) {
        speechMs += ms
        silenceMs = 0
      } else if (speechMs > 0) {
        silenceMs += ms
        if (silenceMs >= TRAIL_SILENCE_MS) break
      }
    }
    if (speechMs < MIN_SPEECH_MS) return Capture.Failed("I didn't hear anything")
    return Capture.Wav(Wav.wrap(pcm.toByteArray(), RATE))
  }
}
