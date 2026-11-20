package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.SettingsPreferences
import com.example.data.repository.DeliveryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight Foreground Service (dataSync) that keeps the app process,
 * call listeners (SIM + WhatsApp), and customer lookup active in the background
 * even when the user closes/swipes away the app or locks the screen.
 */
class BackgroundKeepAliveService : Service() {

  override fun onCreate() {
    super.onCreate()
    DeliveryRepository.initialize(applicationContext)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_KEEP_ALIVE) {
      SettingsPreferences(applicationContext).setBackgroundKeepAliveEnabledSync(false)
      stopSelf()
      return START_NOT_STICKY
    }

    val prefs = SettingsPreferences(applicationContext)
    if (!shouldRunInBackground(prefs)) {
      stopSelf()
      return START_NOT_STICKY
    }

    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
      0
    }
    runCatching {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
    }.onFailure {
      stopSelf()
      return START_NOT_STICKY
    }

    _isRunningFlow.value = true
    restoreDependentOverlaysIfNeeded(prefs)
    return START_STICKY
  }

  private fun restoreDependentOverlaysIfNeeded(prefs: SettingsPreferences) {
    if (!Settings.canDrawOverlays(applicationContext)) return

    if (prefs.isFloatingQuickAddEnabledSync() && !FloatingQuickAddService.isRunningFlow.value) {
      runCatching {
        ContextCompat.startForegroundService(
          applicationContext,
          Intent(applicationContext, FloatingQuickAddService::class.java)
        )
      }
    }

    val savedBar = prefs.getActiveCallerBar()
    if (savedBar != null && prefs.isCallerAddressBarEnabledSync() && !CallAddressOverlayService.isShowingFlow.value) {
      runCatching {
        val barIntent = Intent(applicationContext, CallAddressOverlayService::class.java).apply {
          putExtra(CallAddressOverlayService.EXTRA_PHONE, savedBar.phone)
          putExtra(CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, savedBar.normalizedPhone)
          putExtra(CallAddressOverlayService.EXTRA_ADDRESS, savedBar.address)
          putExtra(CallAddressOverlayService.EXTRA_MAP_URL, savedBar.mapUrl)
          putExtra(CallAddressOverlayService.EXTRA_IS_SUSPICIOUS, savedBar.isSuspicious)
          putExtra(CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
          putExtra(CallAddressOverlayService.EXTRA_ALLOW_REPLACE_EXISTING, true)
        }
        ContextCompat.startForegroundService(applicationContext, barIntent)
      }
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    val prefs = SettingsPreferences(applicationContext)
    if (!shouldRunInBackground(prefs)) return

    // Schedule a non-intrusive self-recovery check in case OEM ROMs pause task-coupled services
    runCatching {
      val restartIntent = Intent(applicationContext, BackgroundKeepAliveService::class.java)
      val pending = PendingIntent.getService(
        applicationContext,
        REQUEST_CODE_RESTART,
        restartIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val alarmManager = getSystemService(ALARM_SERVICE) as? AlarmManager
      alarmManager?.set(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + 1200L,
        pending
      )
    }
    restoreDependentOverlaysIfNeeded(prefs)
  }

  override fun onDestroy() {
    _isRunningFlow.value = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        "استمرار العمل بالخلفية",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "يحافظ على جاهزية التطبيق لقراءة المكالمات والبيانات بالخلفية حتى عند إغلاق الواجهة"
        setShowBadge(false)
      }
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, BackgroundKeepAliveService::class.java).apply {
        action = ACTION_STOP_KEEP_ALIVE
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher_round)
      .setContentTitle("العمل المستمر بالخلفية نشط")
      .setContentText("التطبيق جاهز لاستقبال المكالمات وإظهار العنوان حتى عند إغلاق التطبيق أو القفل")
      .setOngoing(true)
      .setSilent(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(openAppIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف الخلفية", stopIntent)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "masar_background_keep_alive_channel"
    private const val NOTIFICATION_ID = 4401
    private const val REQUEST_CODE_RESTART = 4402
    const val ACTION_STOP_KEEP_ALIVE = "com.example.service.ACTION_STOP_KEEP_ALIVE"

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    fun shouldRunInBackground(prefs: SettingsPreferences): Boolean {
      return prefs.isBackgroundKeepAliveEnabledSync()
    }

    fun start(context: Context) {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, BackgroundKeepAliveService::class.java)
      runCatching {
        ContextCompat.startForegroundService(appCtx, intent)
      }
    }

    fun stop(context: Context) {
      val appCtx = context.applicationContext
      runCatching {
        appCtx.stopService(Intent(appCtx, BackgroundKeepAliveService::class.java))
      }
    }

    fun syncWithPreferences(context: Context) {
      val appCtx = context.applicationContext
      val prefs = SettingsPreferences(appCtx)
      if (shouldRunInBackground(prefs)) {
        start(appCtx)
      } else {
        stop(appCtx)
      }
    }
  }
}
