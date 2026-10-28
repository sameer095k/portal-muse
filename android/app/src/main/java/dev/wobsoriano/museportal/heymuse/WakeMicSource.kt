package dev.wobsoriano.museportal.heymuse

import android.media.MediaRecorder

/**
 * Which audio sources the wake mic opens with — settled by the v11 sweep.
 *
 * The Portal's presence-idle gate zeroes a VOICE_RECOGNITION record once it latches, and an
 * UNPROCESSED one too — but the sweep measured VOICE_COMMUNICATION delivering real audio
 * while latched (minute peaks 968 and 3,121), and CAMCORDER passing as well (408/570). So
 * the mic lives on VOICE_COMMUNICATION for good. CAMCORDER and MIC remain only as fallbacks
 * for a device where the communications record will not even open; they are never a rotation.
 *
 * All calls happen on the wake thread.
 */
object WakeMicSource {
  /** The standing source for the wake mic. */
  const val PRIMARY: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION

  /** Sources to try, in order, when opening the mic; the first that opens wins. */
  val OPEN_ORDER: List<Int> =
      listOf(
          PRIMARY,
          MediaRecorder.AudioSource.CAMCORDER,
          MediaRecorder.AudioSource.MIC,
      )

  fun nameOf(source: Int): String =
      when (source) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SOURCE_$source"
      }
}
