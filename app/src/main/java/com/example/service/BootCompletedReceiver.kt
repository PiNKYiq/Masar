package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.local.SettingsPreferences
import com.example.data.repository.DeliveryRepository

/**
 * Restores background services and active overlays automatically after device reboot
 * or app package update so background features continue seamlessly without reopening the UI.
 */
class BootCompletedReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context?, intent: Intent?) {
    if (context == null || intent == null) return
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
      return
    }

    val appCtx = context.applicationContext
    DeliveryRepository.initialize(appCtx)
    val prefs = SettingsPreferences(appCtx)

    if (prefs.isBackgroundKeepAliveEnabledSync()) {
      BackgroundKeepAliveService.start(appCtx)
    }

    if (Settings.canDrawOverlays(appCtx)) {
      if (prefs.isFloatingQuickAddEnabledSync()) {
        runCatching {
          ContextCompat.startForegroundService(
            appCtx,
            Intent(appCtx, FloatingQuickAddService::class.java)
          )
        }
      }
      val savedBar = prefs.getActiveCallerBar()
      if (savedBar != null && prefs.isCallerAddressBarEnabledSync()) {
        runCatching {
          val barIntent = Intent(appCtx, CallAddressOverlayService::class.java).apply {
            putExtra(CallAddressOverlayService.EXTRA_PHONE, savedBar.phone)
            putExtra(CallAddressOverlayService.EXTRA_NORMALIZED_PHONE, savedBar.normalizedPhone)
            putExtra(CallAddressOverlayService.EXTRA_ADDRESS, savedBar.address)
            putExtra(CallAddressOverlayService.EXTRA_MAP_URL, savedBar.mapUrl)
            putExtra(CallAddressOverlayService.EXTRA_IS_SUSPICIOUS, savedBar.isSuspicious)
            putExtra(CallAddressOverlayService.EXTRA_IS_PREVIEW, false)
            putExtra(CallAddressOverlayService.EXTRA_ALLOW_REPLACE_EXISTING, true)
          }
          ContextCompat.startForegroundService(appCtx, barIntent)
        }
      }
    }
  }
}
