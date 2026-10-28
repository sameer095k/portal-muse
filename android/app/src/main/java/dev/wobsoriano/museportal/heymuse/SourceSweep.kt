package dev.wobsoriano.museportal.heymuse

import android.media.MediaRecorder

/**
 * Which audio source the wake mic opens with — and the v11 experiment built on it.
 *
 * While the feed is healthy the mic records from [HOME], exactly as before. The open question
 * the sweep answers: the Portal's presence-idle gate zeroes a [HOME] record after ~10 idle
 * minutes and nothing app-side revives it — but is the gate applied per recording *source*?
 * A communications or unprocessed capture might be routed (and gated) differently.
 *
 * So each dead-phase reopen advances to the next source in [ROTATION] and stays on it until
 * the next reopen, while [WakeListener] logs the per-minute peaks under that source's name.
 * If a source delivers real audio — the dead phase ends on it — the sweep is over: [reset]
 * puts the mic back on [HOME] for good. If none does, the rotation wraps to [HOME] and the
 * cycle repeats on the usual backstop cadence, so a latched mic keeps re-testing every source.
 *
 * All calls happen on the wake thread.
 */
class SourceSweep {
  private var index = 0

  /** The source the next mic open should use. */
  val current: Int
    get() = ROTATION[index]

  val currentName: String
    get() = nameOf(current)

  /** A dead-phase reopen is due: move to the next source in the rotation and return it. */
  fun advance(): Int {
    index = (index + 1) % ROTATION.size
    return current
  }

  /** The current source delivered real audio: sweep over, back to the home source. */
  fun reset() {
    index = 0
  }

  companion object {
    /** Where the mic lives while the feed is healthy; also the last stop of each rotation. */
    const val HOME: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** [HOME] first, then the sweep order; advancing wraps from MIC back to [HOME]. */
    val ROTATION: List<Int> =
        listOf(
            HOME,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED,
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
}
