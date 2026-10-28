package dev.wobsoriano.museportal.heymuse

/**
 * When a silenced wake mic should be reopened (and with it primed — see [MicPrimeActivity]).
 *
 * The Portal's audio policy silences a recording opened while another app is resumed, and the
 * record then reads exact zeros, which no real room produces (verified on-device 2026-10-25).
 * The first version of this recovery reopened after 2 s of zeros and doubled the wait after each
 * still-silent reopen — but any chunk with a single non-zero sample reset the wait to its 2 s
 * floor, so a silenced record leaking sparse ticks defeated the backoff entirely: with the 10 s
 * settle exempting each fresh record, the mic was re-primed every ~12 s in bursts all evening
 * (observed 2026-10-27), each prime flashing an activity over the dashboard.
 *
 * This policy is event-driven instead. A reopen is due only when:
 *  - the feed has read as digital silence — every chunk peaking at or below [silencePeak] — for
 *    [deadMs] without a break (a cut matches no foreground event we saw), or
 *  - the foreground app changed while the feed was already flat (see [onForegroundChange]), or
 *  - the feed is in the dead phase and the [backstopMs] cadence comes round.
 *
 * A chunk peaking above [silencePeak] is real audio: it breaks the silence run, and [healthyMs]
 * of it in the dead phase proves the reopen worked and ends the phase. Sparse ticks at or below
 * [silencePeak] neither break the run nor hasten the backstop — that asymmetry is the fix. The
 * peak threshold is functional, not acoustical: audio that never peaks above it cannot carry a
 * wake word to the recogniser anyway, so treating the feed as dead costs nothing.
 *
 * Callers pass the time, so this is testable. All calls happen on the wake thread.
 */
class SilenceRecovery(
    private val deadMs: Long = DEAD_MS,
    private val backstopMs: Long = BACKSTOP_MS,
    private val settleMs: Long = SETTLE_MS,
    private val healthyMs: Long = HEALTHY_MS,
    private val fgDeadMs: Long = FG_DEAD_MS,
    private val deferMs: Long = DEFER_MS,
    private val silencePeak: Int = SILENCE_PEAK,
) {
  private enum class Phase { LISTENING, DEAD }

  private var phase = Phase.LISTENING
  private var silentRunMs = 0L
  private var healthyRunMs = 0L
  private var settleUntilMs = Long.MIN_VALUE
  private var nextAttemptAtMs = Long.MAX_VALUE

  /** A record was just opened; its first [settleMs] of audio proves nothing either way. */
  fun onMicOpened(nowMs: Long) {
    settleUntilMs = nowMs + settleMs
    silentRunMs = 0
    healthyRunMs = 0
  }

  /**
   * One decoded chunk: its peak sample and duration. Returns true when a reopen attempt is due
   * now; the caller attempts it (subject to the streaming stand-down) and reports a stand-down
   * through [defer].
   */
  fun onChunk(peak: Int, durationMs: Long, nowMs: Long): Boolean {
    if (nowMs < settleUntilMs) {
      silentRunMs = 0
      healthyRunMs = 0
      return false
    }
    if (peak > silencePeak) {
      silentRunMs = 0
      healthyRunMs += durationMs
      if (phase == Phase.DEAD && healthyRunMs >= healthyMs) {
        phase = Phase.LISTENING
        nextAttemptAtMs = Long.MAX_VALUE
      }
      return false
    }
    healthyRunMs = 0
    silentRunMs += durationMs
    return when (phase) {
      Phase.LISTENING -> {
        if (silentRunMs >= deadMs) {
          attempt(nowMs)
          true
        } else {
          false
        }
      }
      Phase.DEAD -> {
        if (nowMs >= nextAttemptAtMs) {
          attempt(nowMs)
          true
        } else {
          false
        }
      }
    }
  }

  /**
   * The foreground app just changed — the moment the Portal applies its mic cut. An attempt is
   * due at once when the feed is already flat ([fgDeadMs] of unbroken silence, or the dead
   * phase); a live feed rides out the change, because a record opened in front stays audible.
   */
  fun onForegroundChange(nowMs: Long): Boolean {
    if (nowMs < settleUntilMs) return false
    if (phase != Phase.DEAD && silentRunMs < fgDeadMs) return false
    attempt(nowMs)
    return true
  }

  /**
   * A due attempt was stood down (a stream is in front): while listening, forget the current
   * silence run so detection starts afresh when the stream ends; while dead, at least [deferMs]
   * passes before the next attempt. The foreground change at the stream's end is itself a
   * trigger, so a still-dead mic is retried then rather than here.
   */
  fun defer(nowMs: Long) {
    if (phase == Phase.DEAD) {
      nextAttemptAtMs = maxOf(nextAttemptAtMs, nowMs + deferMs)
    } else {
      silentRunMs = 0
    }
  }

  private fun attempt(nowMs: Long) {
    phase = Phase.DEAD
    nextAttemptAtMs = nowMs + backstopMs
    silentRunMs = 0
    healthyRunMs = 0
  }

  companion object {
    /**
     * Unbroken digital silence that declares the mic cut. A cut reads zeros indefinitely, so
     * 24 s can never be a transient (audio route changes and HAL warm-up take a second or two);
     * slower only delays recovery, and the foreground trigger catches real cuts in ~2 s anyway.
     */
    const val DEAD_MS = 24_000L

    /** While the feed stays dead, attempts settle to this cadence and never run faster. */
    const val BACKSTOP_MS = 600_000L

    /** A freshly opened record is exempt from judgement for this long (unchanged from before). */
    const val SETTLE_MS = 10_000L

    /** Real audio for this long in the dead phase proves a reopen worked. */
    const val HEALTHY_MS = 3_000L

    /** Silence already this old when the foreground changes is treated as the cut landing. */
    const val FG_DEAD_MS = 2_000L

    /** A stood-down attempt is not retried for at least this long. */
    const val DEFER_MS = 30_000L

    /** Chunks peaking at or below this (of 32767) count as digital silence. */
    const val SILENCE_PEAK = 16
  }
}
