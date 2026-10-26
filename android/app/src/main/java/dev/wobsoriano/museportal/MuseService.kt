package dev.wobsoriano.museportal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.wobsoriano.museportal.overlay.OverlayBar

/** Keeps the process, and with it the Muse core, alive in the background. */
class MuseService : Service() {
  private var overlay: OverlayBar? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Muse", NotificationManager.IMPORTANCE_MIN))
    val open =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Muse is running")
            .setContentIntent(open)
            .build()
    // Declare the microphone foreground-service type at runtime too (the manifest
    // carries the same declaration). Without it, on API 29+ a foreground service
    // does not qualify for background microphone access: any AudioRecord the wake
    // listener opens while another app is resumed starts silenced and reads zeros.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    muse.start()
    overlay = OverlayBar(this, muse.state, avatars.load, onClose = muse::dismiss).also { it.start() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

  override fun onDestroy() {
    overlay?.stop()
    muse.stop()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL = "muse"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) = context.startForegroundService(Intent(context, MuseService::class.java))
  }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) MuseService.start(context)
  }
}
