package dev.wobsoriano.museportal.heymuse

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSweepTest {
  @Test
  fun startsOnTheHomeSource() {
    val s = SourceSweep()
    assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, s.current)
    assertEquals("VOICE_RECOGNITION", s.currentName)
  }

  @Test
  fun advancesThroughTheRotationInOrderThenWrapsHome() {
    val s = SourceSweep()
    assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, s.advance())
    assertEquals(MediaRecorder.AudioSource.UNPROCESSED, s.advance())
    assertEquals(MediaRecorder.AudioSource.CAMCORDER, s.advance())
    assertEquals(MediaRecorder.AudioSource.MIC, s.advance())
    assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, s.advance())
    // And the next rotation starts over.
    assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, s.advance())
  }

  @Test
  fun resetReturnsToTheHomeSource() {
    val s = SourceSweep()
    s.advance()
    s.advance()
    s.reset()
    assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, s.current)
  }

  @Test
  fun everyRotationSourceHasItsOwnName() {
    val names = SourceSweep.ROTATION.map { SourceSweep.nameOf(it) }
    assertEquals(
        listOf("VOICE_RECOGNITION", "VOICE_COMMUNICATION", "UNPROCESSED", "CAMCORDER", "MIC"),
        names)
  }
}
