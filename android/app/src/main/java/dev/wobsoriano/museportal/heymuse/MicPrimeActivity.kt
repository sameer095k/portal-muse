package dev.wobsoriano.museportal.heymuse

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
