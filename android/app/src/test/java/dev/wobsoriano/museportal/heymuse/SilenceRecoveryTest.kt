package dev.wobsoriano.museportal.heymuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceRecoveryTest {
  /** Drives a [SilenceRecovery] with 100 ms chunks on a fake uptime clock. */
  private class Drive(val r: SilenceRecovery = SilenceRecovery()) {
    var now = 0L

    init {
      r.onMicOpened(0)
    }

    fun chunk(peak: Int): Boolean {
      now += 100
      return r.onChunk(peak, 100, now)
    }

    /** Feeds [seconds] of chunks at [peak]; returns how many reopens came due. */
    fun feed(peak: Int, seconds: Int): Int {
      var due = 0
      repeat(seconds * 10) { if (chunk(peak)) due++ }
      return due
    }

    /** Past the settle window, listening to a live room. */
    fun settled(): Drive {
      feed(LOUD, 15)
      return this
    }
  }

  @Test
  fun realAudioNeverTriggers() {
    val d = Drive().settled()
    assertEquals(0, d.feed(LOUD, 600))
  }

  @Test
  fun sustainedSilenceTriggersOnceAtTheDeadThreshold() {
    val d = Drive()
    // 10 s of settle (nothing accrues) + 23 s of zeros: not yet dead.
    assertEquals(0, d.feed(0, 33))
    // The next second crosses 24 s of unbroken silence.
    assertEquals(1, d.feed(0, 1))
  }

  @Test
  fun aLoudChunkBreaksTheSilenceRun() {
    val d = Drive().settled()
    assertEquals(0, d.feed(0, 20))
    d.chunk(LOUD)
    assertEquals(0, d.feed(0, 23))
    assertEquals(1, d.feed(0, 2))
  }

  @Test
  fun sparseTicksNeitherBreakTheRunNorHastenTheBackstop() {
    val d = Drive()
    // A silenced record leaking sub-threshold ticks still counts as silence.
    var due = 0
    repeat(33) {
      due += d.feed(TICK, 1)
    }
    assertEquals(0, due)
    assertEquals(1, d.feed(0, 1))
    // In the dead phase the same ticks change nothing: the next attempt is the backstop's.
    due = 0
    repeat(110) {
      due += d.feed(0, 5)
      d.chunk(LOUD)
    }
    assertEquals(0, due)
    assertEquals(1, d.feed(0, 45))
  }

  @Test
  fun realAudioEndsTheDeadPhase() {
    val d = Drive()
    assertEquals(1, d.feed(0, 34))
    assertEquals(0, d.feed(LOUD, 3))
    // Listening again: a fresh full silence is needed for the next attempt.
    assertEquals(0, d.feed(0, 23))
    assertEquals(1, d.feed(0, 2))
  }

  @Test
  fun aForegroundChangeAttemptsOnlyWhenTheFeedIsFlat() {
    val d = Drive().settled()
    assertFalse(d.r.onForegroundChange(d.now))
    assertEquals(0, d.feed(0, 3))
    assertTrue(d.r.onForegroundChange(d.now))
    // In the dead phase a change is itself a retry.
    assertEquals(0, d.feed(0, 3))
    assertTrue(d.r.onForegroundChange(d.now))
  }

  @Test
  fun aDeferWhileListeningRestartsDetection() {
    val d = Drive().settled()
    assertEquals(0, d.feed(0, 20))
    d.r.defer(d.now)
    assertEquals(0, d.feed(0, 20))
    assertEquals(1, d.feed(0, 5))
  }

  private companion object {
    /** A clearly audible chunk. */
    const val LOUD = 300

    /** A leaked tick on a silenced record: present, but far below anything audible. */
    const val TICK = 8
  }
}
