package dev.wobsoriano.museportal.heymuse

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeMicSourceTest {
  @Test
  fun primaryIsVoiceCommunication() {
    assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, WakeMicSource.PRIMARY)
    assertEquals("VOICE_COMMUNICATION", WakeMicSource.nameOf(WakeMicSource.PRIMARY))
  }

  @Test
  fun openOrderIsVoiceCommunicationThenCamcorderThenMic() {
    assertEquals(
        listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
        ),
        WakeMicSource.OPEN_ORDER)
  }

  @Test
  fun everyOpenSourceHasItsOwnName() {
    val names = WakeMicSource.OPEN_ORDER.map { WakeMicSource.nameOf(it) }
    assertEquals(listOf("VOICE_COMMUNICATION", "CAMCORDER", "MIC"), names)
  }
}
