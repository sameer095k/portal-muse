package dev.wobsoriano.museportal.heymuse

import android.app.Activity
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An invisible activity whose only job is to be resumed for a moment.
 *
 * On this Portal (Gen 2, Android 10) the audio policy keys a recording's fate to what the
 * app is doing at the moment the record is opened: opened while the app has a resumed
 * activity, the record is audible and stays audible after the app leaves the foreground;
 * opened while another app is resumed, the record starts silenced (reads exact zeros) and
 * never recovers. That holds for MIC and VOICE_RECOGNITION sources alike, and declaring a
 * microphone-type foreground service does not change it (all verified on-device
 * 2026-10-25) — so the wake listener's silence-reopen recovery, which assumes a reopened
 * background mic is heard, could never recover here.
 *
 * [MicPrime.prime] therefore brings this activity up before the mic is opened in the
 * background; the caller opens the mic, then [MicPrime.release] takes the activity away.
 * It draws nothing and lives in a task of its own, so whatever was in front (the
 * dashboard) comes back untouched.
 */
class MicPrimeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MicPrime.attach(this)
  }

  override fun onResume() {
    super.onResume()
    MicPrime.resumed()
  }

  override fun onDestroy() {
    MicPrime.detach(this)
    super.onDestroy()
  }
}

object MicPrime {
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var activity: Activity? = null

  @Volatile private var latch: CountDownLatch? = null

  fun attach(a: Activity) {
    activity = a
  }

  fun resumed() {
    latch?.countDown()
  }

  fun detach(a: Activity) {
    if (activity === a) activity = null
  }

  /**
   * Packages whose foreground time priming must never interrupt. Wormhole Display (the
   * AirPlay receiver) ends its stream the moment another activity takes the screen — it
   * reads the focus loss as the user pressing Home — so a prime landing mid-stream kills
   * whatever is being mirrored. Verified on-device 2026-10-26: three streams, three
   * "Disconnecting client: home" lines, each within 30 ms of a prime launch. While one
   * of these is in front, the wake mic simply stays as it is; priming resumes when the
   * stream ends and something else comes to the front.
   */
  private val STREAMING_PACKAGES = setOf("io.github.pgodlews.wormhole")

  /**
   * The package most recently brought to the foreground, or null when usage stats are
   * unavailable (the PACKAGE_USAGE_STATS appop not granted) — callers then keep the
   * pre-patch behaviour rather than guessing. The window is deliberately wide: a
   * stream can sit in front for minutes without generating any new usage event, and
   * the first version of this check looked back only 30 s — a stream that had been up
   * 30.5 s read as "not streaming" and was killed by the next prime (2026-10-26).
   */
  fun foregroundPackage(context: Context): String? {
    return try {
      val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
      val now = System.currentTimeMillis()
      val events = usm.queryEvents(now - 900_000, now) ?: return null
      val e = UsageEvents.Event()
      var pkg: String? = null
      while (events.hasNextEvent()) {
        events.getNextEvent(e)
        if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
      }
      pkg
    } catch (ex: Exception) {
      null
    }
  }

  /**
   * True while something is streaming on screen. Two signals, either one enough: the
   * receiver app is the foreground package (usage stats — covers a paused stream), or
   * music-stream audio is playing on the device (real-time, no permission needed —
   * covers the stats window missing an event). Our own spoken replies also count as
   * music for their few seconds; standing down then is harmless.
   */
  fun streamingForeground(context: Context): Boolean {
    if (foregroundPackage(context) in STREAMING_PACKAGES) return true
    val am = context.getSystemService(AudioManager::class.java) ?: return false
    return am.isMusicActive
  }

  private fun isForeground(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java) ?: return false
    val me =
        am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
            ?: return false
    return me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }

  /**
   * Launches the prime activity unless the app is already in front. Returns true when an
   * activity was launched (the caller should [release] once the mic is open). Blocks the
   * calling thread until the activity resumes, at most ~2.5 s; on any failure the caller
   * just opens the mic as before.
   */
  fun prime(context: Context): Boolean {
    if (isForeground(context)) return false
    if (streamingForeground(context)) {
      Log.i("MicPrime", "streaming in front (fg=${foregroundPackage(context)}); not priming")
      return false
    }
    val l = CountDownLatch(1)
    latch = l
    return try {
      context.startActivity(
          Intent(context, MicPrimeActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      l.await(2_500, TimeUnit.MILLISECONDS)
      true
    } catch (e: Exception) {
      false
    }
  }

  fun release() {
    val a = activity ?: return
    main.post { a.finish() }
  }
}
