package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.local.SettingsPreferences
import com.example.util.CallerAddressHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AccessibilityService that reads exact Arabic & English screen text nodes
 * and incoming call notifications so:
 * 1) Floating Lens / Quick Add (+) can read exact screen numbers and prices.
 * 2) Caller Address Bar detects phone numbers on incoming & outgoing SIM card calls
 *    and WhatsApp / Telegram calls — while strictly avoiding post-call Recents/Call Log lists
 *    and never overwriting an already-visible caller bar from background window scans.
 */
class ScreenTextAccessibilityService : AccessibilityService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile
  private var lastCallScanTimeMs: Long = 0L

  @Volatile
  private var lastMatchedCandidateSignature: String = ""

  @Volatile
  private var lastMatchedCandidateTimeMs: Long = 0L

  override fun onServiceConnected() {
    super.onServiceConnected()
    activeInstance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val appCtx = applicationContext
    if (!SettingsPreferences(appCtx).isCallerAddressBarEnabledSync()) return
    if (!Settings.canDrawOverlays(appCtx)) return

    val eventPkg = event.packageName?.toString()
      ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
    if (eventPkg == appCtx.packageName) return

    val isActiveCall = CallerAddressHelper.isActiveCallNow(appCtx)

    // 1. Incoming call notifications (WhatsApp, WhatsApp Business, SIM Dialer)
    if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      val notification = event.parcelableData as? Notification
      val combinedText = CallerAddressHelper.extractNotificationCombinedText(notification, event.text)
      if (combinedText.isNotBlank() &&
        CallerAddressHelper.isCallNotification(eventPkg, notification, combinedText, isActiveCall)
      ) {
        val candidates = CallerAddressHelper.extractCandidatePhonesFromText(combinedText)
        val isGenuineIncomingCallNotif = notification?.category == Notification.CATEGORY_CALL
        // Only allow replacing an existing bar from a genuine CATEGORY_CALL notification
        if (CallAddressOverlayService.isShowingFlow.value && !isGenuineIncomingCallNotif) {
          return
        }
        triggerLookupForCandidates(
          appCtx = appCtx,
          candidates = candidates,
          allowReplaceExisting = isGenuineIncomingCallNotif
        )
      }
      return
    }

    // 2. Active call screen scans NEVER overwrite an already-open bar!
    // Once a bar is showing, it stays until the user taps ✕ (or a genuine new call role/notification replaces it).
    if (CallAddressOverlayService.isShowingFlow.value) return

    val now = System.currentTimeMillis()
    if (now - lastCallScanTimeMs < 300L) return

    val isPureInCallUi = CallerAddressHelper.isPureInCallUiPackage(eventPkg)
    val isDialerOrVoip = CallerAddressHelper.isDialerOrCallUiPackage(eventPkg)
    if (!isPureInCallUi && !isDialerOrVoip && !isActiveCall) return

    lastCallScanTimeMs = now
    scanActiveWindowForCallerAddress(
      requireCallKeywords = !isPureInCallUi
    )
  }

  fun scanActiveWindowForCallerAddress(requireCallKeywords: Boolean = true) {
    val appCtx = applicationContext
    if (!SettingsPreferences(appCtx).isCallerAddressBarEnabledSync()) return
    if (!Settings.canDrawOverlays(appCtx)) return
    if (CallAddressOverlayService.isShowingFlow.value) return

    val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
    val activePkg = activeRoot?.packageName?.toString()
    if (activePkg == appCtx.packageName) return

    // Do not scan SystemUI / Recents passively unless the phone is genuinely in a live call or ringing
    val isPureInCallUi = CallerAddressHelper.isPureInCallUiPackage(activePkg)
    val isActiveCall = CallerAddressHelper.isActiveCallNow(appCtx)
    if (!isPureInCallUi && !isActiveCall && !CallerAddressHelper.isDialerOrCallUiPackage(activePkg)) {
      return
    }

    // Read ONLY the foreground active window (never background/split/Call-Log windows)
    val screenText = captureActiveWindowLines(
      excludePackage = appCtx.packageName,
      lowerLeftOnly = false,
      allowSystemUi = isActiveCall || isPureInCallUi,
      activeWindowOnly = true
    )
    if (screenText.isBlank()) return

    val candidates = CallerAddressHelper.extractCandidatePhonesFromText(screenText)
    if (candidates.isEmpty()) return

    // Strictly reject Recents / Call History / Contacts / Chats lists even if AudioManager lingers post-hangup
    if (!isPureInCallUi && CallerAddressHelper.looksLikeCallHistoryOrListScreen(screenText, candidates)) {
      return
    }

    // Require active live call keywords whenever outside a pure InCallUI package,
    // so Dialer Recents or WhatsApp Chats cannot trigger during lingering audio transitions
    if (requireCallKeywords &&
      !isPureInCallUi &&
      !CallerAddressHelper.containsIncomingOrActiveCallKeyword(screenText)
    ) {
      return
    }

    triggerLookupForCandidates(
      appCtx = appCtx,
      candidates = candidates,
      allowReplaceExisting = false
    )
  }

  private fun triggerLookupForCandidates(
    appCtx: Context,
    candidates: List<String>,
    allowReplaceExisting: Boolean
  ) {
    if (candidates.isEmpty()) return
    if (CallAddressOverlayService.isShowingFlow.value && !allowReplaceExisting) return

    val signature = candidates.joinToString(",")
    val now = System.currentTimeMillis()
    if (signature == lastMatchedCandidateSignature && now - lastMatchedCandidateTimeMs < 2000L) {
      return
    }
    lastMatchedCandidateSignature = signature
    lastMatchedCandidateTimeMs = now

    serviceScope.launch {
      val result = CallerAddressHelper.lookupFirstRegisteredCustomerInCandidates(appCtx, candidates)
      if (result != null) {
        if (!CallAddressOverlayService.isShowingFlow.value || allowReplaceExisting) {
          CallAddressOverlayService.showForLookup(
            context = appCtx,
            result = result,
            isPreview = false,
            allowReplaceExisting = allowReplaceExisting
          )
        }
      }
    }
  }

  override fun onInterrupt() {
    // No ongoing audio/feedback interruption handling required.
  }

  override fun onDestroy() {
    if (activeInstance === this) {
      activeInstance = null
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  fun captureActiveWindowLines(
    excludePackage: String? = null,
    lowerLeftOnly: Boolean = false,
    allowSystemUi: Boolean = false,
    activeWindowOnly: Boolean = false
  ): String {
    val collected = mutableListOf<Pair<String, Rect>>()
    val rootBounds = Rect()
    try {
      if (activeWindowOnly) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val blockedSystemUi = !allowSystemUi && pkg == "com.android.systemui"
        if (root != null && (excludePackage.isNullOrBlank() || pkg != excludePackage) && !blockedSystemUi) {
          root.getBoundsInScreen(rootBounds)
          collectNodeTexts(root, collected)
        }
      } else {
        val rootList = windows?.mapNotNull { window ->
          val root = window.root ?: return@mapNotNull null
          val pkg = root.packageName?.toString()
          val blockedSystemUi = !allowSystemUi && pkg == "com.android.systemui"
          if ((!excludePackage.isNullOrBlank() && pkg == excludePackage) || blockedSystemUi) {
            null
          } else {
            root
          }
        }.orEmpty()

        if (rootList.isNotEmpty()) {
          rootList.forEach { root ->
            val r = Rect()
            root.getBoundsInScreen(r)
            if (r.width() > rootBounds.width()) rootBounds.set(r)
            collectNodeTexts(root, collected)
          }
        } else {
          rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString()
            val blockedSystemUi = !allowSystemUi && pkg == "com.android.systemui"
            if ((excludePackage.isNullOrBlank() || pkg != excludePackage) && !blockedSystemUi) {
              root.getBoundsInScreen(rootBounds)
              collectNodeTexts(root, collected)
            }
          }
        }
      }
    } catch (_: Exception) {
      return ""
    }

    if (collected.isEmpty()) return ""

    val filtered = if (lowerLeftOnly && rootBounds.width() > 0 && rootBounds.height() > 0) {
      val maxX = rootBounds.left + (rootBounds.width() * 0.64f)
      val minY = rootBounds.top + (rootBounds.height() * 0.44f)
      collected.filter { (_, rect) ->
        rect.centerX() <= maxX && rect.centerY() >= minY
      }
    } else {
      collected
    }

    if (filtered.isEmpty()) return ""

    val sortedByY = filtered.sortedBy { it.second.centerY() }
    val rows = mutableListOf<MutableList<Pair<String, Rect>>>()
    for (item in sortedByY) {
      val cy = item.second.centerY()
      val h = item.second.height().coerceAtLeast(18)
      val matchingRow = rows.lastOrNull()?.let { row ->
        val rowCy = row.map { it.second.centerY() }.average()
        val rowH = row.map { it.second.height().coerceAtLeast(18) }.average()
        val tolerance = maxOf(16.0, maxOf(h.toDouble(), rowH) * 0.70)
        if (abs(cy - rowCy) <= tolerance) row else null
      }
      if (matchingRow != null) {
        matchingRow.add(item)
      } else {
        rows.add(mutableListOf(item))
      }
    }

    return rows.joinToString("\n") { row ->
      row.sortedBy { it.second.left }
        .map { it.first }
        .distinct()
        .joinToString(" ")
    }
  }

  private fun collectNodeTexts(
    node: AccessibilityNodeInfo?,
    out: MutableList<Pair<String, Rect>>
  ) {
    if (node == null) return
    if (node.isVisibleToUser) {
      val rawText = node.text?.toString()?.trim().orEmpty()
      val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
      val textToRecord = when {
        rawText.isNotEmpty() -> rawText
        contentDesc.isNotEmpty() -> contentDesc
        else -> ""
      }
      if (textToRecord.isNotEmpty()) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
          out.add(textToRecord to bounds)
        }
      }
    }
    for (i in 0 until node.childCount) {
      collectNodeTexts(node.getChild(i), out)
    }
  }

  companion object {
    @Volatile
    private var activeInstance: ScreenTextAccessibilityService? = null

    fun captureScreenLines(excludePackage: String? = null): String {
      return activeInstance?.captureActiveWindowLines(excludePackage, lowerLeftOnly = false).orEmpty()
    }

    fun captureLowerLeftLines(excludePackage: String? = null): String {
      return activeInstance?.captureActiveWindowLines(excludePackage, lowerLeftOnly = true).orEmpty()
    }

    fun inspectActiveCallScreenNow(context: Context) {
      if (!SettingsPreferences(context.applicationContext).isCallerAddressBarEnabledSync()) return
      if (CallAddressOverlayService.isShowingFlow.value) return
      activeInstance?.scanActiveWindowForCallerAddress(requireCallKeywords = true)
    }

    fun isEnabled(context: Context): Boolean {
      if (activeInstance != null) return true
      return try {
        val enabledServices = Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expectedComponent = ComponentName(context, ScreenTextAccessibilityService::class.java)
        enabledServices.split(':').any { entry ->
          ComponentName.unflattenFromString(entry) == expectedComponent ||
            entry.equals(expectedComponent.flattenToShortString(), ignoreCase = true)
        }
      } catch (_: Exception) {
        false
      }
    }
  }
}
