package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object VibrationHelper {

  /**
   * Triggers a distinct double-pulse warning vibration when a suspicious customer phone
   * number is entered or detected on screen.
   */
  fun vibrateSuspiciousAlert(context: Context?) {
    if (context == null) return
    runCatching {
      val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }

      if (vibrator == null) return@runCatching
      // Wait 0ms, vibrate 260ms, pause 110ms, vibrate 360ms
      val timings = longArrayOf(0L, 260L, 110L, 360L)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val amplitudes = intArrayOf(0, 255, 0, 255)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(timings, -1)
      }
    }
  }
}
