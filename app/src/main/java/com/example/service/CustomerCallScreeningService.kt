package com.example.service

import android.os.Build
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.example.data.local.SettingsPreferences
import com.example.util.CallerAddressHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android 10+ CallScreeningService (Truecaller-style Caller ID role) that receives
 * outgoing and incoming phone numbers without requiring restricted CALL_LOG permissions.
 * Displays [CallAddressOverlayService] when the number belongs to a registered customer.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CustomerCallScreeningService : CallScreeningService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onScreenCall(callDetails: Call.Details) {
    // Always allow the call to proceed immediately with zero delay or blocking
    val passThroughResponse = CallResponse.Builder()
      .setDisallowCall(false)
      .setRejectCall(false)
      .setSilenceCall(false)
      .setSkipCallLog(false)
      .setSkipNotification(false)
      .build()
    respondToCall(callDetails, passThroughResponse)

    val appCtx = applicationContext
    if (!SettingsPreferences(appCtx).isCallerAddressBarEnabledSync()) return
    if (!Settings.canDrawOverlays(appCtx)) return

    val decodedHandle = CallerAddressHelper.decodePhoneHandle(callDetails.handle?.schemeSpecificPart)
    val displayNameCandidate = callDetails.callerDisplayName?.trim().orEmpty()
    val candidates = buildList {
      if (decodedHandle.isNotBlank()) {
        addAll(CallerAddressHelper.extractCandidatePhonesFromText(decodedHandle))
        add(decodedHandle)
      }
      if (displayNameCandidate.isNotBlank()) {
        addAll(CallerAddressHelper.extractCandidatePhonesFromText(displayNameCandidate))
      }
    }.distinct()

    if (candidates.isEmpty()) return

    serviceScope.launch {
      val result = CallerAddressHelper.lookupFirstRegisteredCustomerInCandidates(appCtx, candidates)
      if (result != null) {
        CallAddressOverlayService.showForLookup(appCtx, result, isPreview = false)
      }
    }
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }
}
