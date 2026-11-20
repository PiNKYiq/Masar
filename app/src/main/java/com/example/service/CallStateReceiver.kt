package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import com.example.data.local.SettingsPreferences
import com.example.util.CallerAddressHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens for TelephonyManager PHONE_STATE transitions across all OEM dialers (Samsung, Xiaomi,
 * Huawei, Google Dialer, Dual-SIM) without requiring restricted CALL_LOG permissions.
 *
 * - On RINGING / OFFHOOK: inspects optional number extra if available AND schedules active-window
 *   call-screen recognition for incoming/outgoing SIM calls (including saved Contacts & Dual-SIM).
 * - On IDLE: immediately cancels any pending SIM screen scans so Recents/Call Log is never scanned
 *   after hangup, and intentionally leaves any currently visible address bar open until the user
 *   manually closes it.
 */
class CallStateReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context?, intent: Intent?) {
    if (context == null || intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
    val appCtx = context.applicationContext
    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

    when (state) {
      TelephonyManager.EXTRA_STATE_RINGING,
      TelephonyManager.EXTRA_STATE_OFFHOOK -> {
        if (!SettingsPreferences(appCtx).isCallerAddressBarEnabledSync()) return
        if (!Settings.canDrawOverlays(appCtx)) return

        mainHandler.removeCallbacksAndMessages(SCAN_TOKEN)

        @Suppress("DEPRECATION")
        val rawIncomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val pendingResult = goAsync()
        receiverScope.launch {
          try {
            if (rawIncomingNumber.isNotBlank()) {
              val result = CallerAddressHelper.lookupRegisteredCustomerAddress(appCtx, rawIncomingNumber)
              if (result != null) {
                CallAddressOverlayService.showForLookup(
                  context = appCtx,
                  result = result,
                  isPreview = false,
                  allowReplaceExisting = true
                )
              }
            }
          } finally {
            pendingResult.finish()
          }
        }

        // Trigger active-call screen inspection ONLY while the SIM call is genuinely active
        val delays = listOf(0L, 300L, 850L, 1500L)
        delays.forEach { delayMs ->
          mainHandler.postAtTime(
            {
              val simStillActive = CallerAddressHelper.isSimTelephonyCallActive(appCtx)
              if (simStillActive != false && !CallAddressOverlayService.isShowingFlow.value) {
                ScreenTextAccessibilityService.inspectActiveCallScreenNow(appCtx)
              }
            },
            SCAN_TOKEN,
            android.os.SystemClock.uptimeMillis() + delayMs
          )
        }
      }

      TelephonyManager.EXTRA_STATE_IDLE -> {
        // Cancel any pending post-hangup scans immediately so Dialer Recents never gets scanned,
        // and DO NOT dismiss CallAddressOverlayService (bar stays open until user closes it).
        mainHandler.removeCallbacksAndMessages(SCAN_TOKEN)
      }
    }
  }

  companion object {
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val SCAN_TOKEN = Any()
  }
}
