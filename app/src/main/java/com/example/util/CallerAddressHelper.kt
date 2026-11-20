package com.example.util

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.data.local.SettingsPreferences
import com.example.data.repository.DeliveryRepository
import com.example.model.CustomerRecord
import com.example.model.SuspiciousCustomer
import com.example.service.CallAddressOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class CallerAddressLookupResult(
  val phone: String,
  val normalizedPhone: String,
  val customer: CustomerRecord,
  val suspicious: SuspiciousCustomer? = null
)

object CallerAddressHelper {

  private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val CANDIDATE_PHONE_REGEX = Regex("""(?:\+|00)?\d[\d \t\-()]{5,18}\d""")
  private val BIDI_AND_INVISIBLE_CHARS_REGEX = Regex("""[\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]""")
  private val SPECIAL_SPACES_REGEX = Regex("""[\u00A0\u2000-\u200A\u202F\u205F\u3000]""")

  private val PURE_INCALL_UI_PACKAGES = setOf(
    "com.android.incallui",
    "com.samsung.android.incallui",
    "com.miui.incallui",
    "com.huawei.incallui",
    "com.oplus.incallui",
    "com.coloros.incallui",
    "com.vivo.incallui",
    "com.oneplus.incallui",
    "com.android.server.telecom"
  )

  private val VOIP_AND_DIALER_PACKAGES = setOf(
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.android.dialer",
    "com.oplus.dialer",
    "com.coloros.dialer",
    "com.miui.dialer",
    "com.asus.dialer",
    "com.oneplus.dialer",
    "com.vivo.dialer",
    "com.huawei.contacts",
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram",
    "org.telegram.plus",
    "com.viber.voip"
  )

  // Live call UI phrases ONLY (never Call Log row subtitles like bare "مكالمة صادرة" / "مكالمة واردة")
  private val INCOMING_OR_ACTIVE_CALL_PHRASES = listOf(
    "مكالمة صوتية عبر واتساب",
    "مكالمة فيديو عبر واتساب",
    "مكالمة صوتية واردة",
    "مكالمة فيديو واردة",
    "جاري الاتصال",
    "يتم الاتصال",
    "جارٍ الاتصال",
    "مرّر للأعلى للرد",
    "اسحب للرد",
    "تم الكتم",
    "مكبر الصوت",
    "لوحة المفاتيح قيد المكالمة",
    "whatsapp voice call",
    "whatsapp video call",
    "incoming voice call",
    "incoming video call",
    "incoming call",
    "slide to answer",
    "swipe up to accept",
    "swipe up to answer",
    "calling…",
    "calling...",
    "ringing…",
    "ringing..."
  )

  // Phrases indicating Dialer Recents / Contacts / WhatsApp Chat & Calls history lists
  private val CALL_HISTORY_OR_LIST_MARKERS = listOf(
    "سجل المكالمات",
    "المكالمات الأخيرة",
    "الأخيرة",
    "المكالمات الفائتة",
    "لم يُرد عليها",
    "لم يرد عليها",
    "المفضلة",
    "جهات الاتصال",
    "الدردشات",
    "الحالة",
    "المستجدات",
    "بحث في جهات الاتصال",
    "call history",
    "call log",
    "recents",
    "missed calls",
    "favorites",
    "contacts",
    "chats",
    "updates",
    "search contacts"
  )

  fun cleanInvisibleFormatting(text: String): String {
    if (text.isEmpty()) return ""
    return text
      .replace(BIDI_AND_INVISIBLE_CHARS_REGEX, "")
      .replace(SPECIAL_SPACES_REGEX, " ")
  }

  fun decodePhoneHandle(rawHandle: String?): String {
    if (rawHandle.isNullOrBlank()) return ""
    val decoded = runCatching { Uri.decode(rawHandle) }.getOrDefault(rawHandle)
    return cleanInvisibleFormatting(decoded)
      .removePrefix("tel:")
      .removePrefix("TEL:")
      .trim()
  }

  fun extractCandidatePhonesFromText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val cleanedText = cleanInvisibleFormatting(text)
    val asciiText = CustomerRecord.toAsciiDigits(cleanedText)
    val seen = LinkedHashSet<String>()
    CANDIDATE_PHONE_REGEX.findAll(asciiText).forEach { match ->
      val raw = match.value.trim()
      val norm = CustomerRecord.normalizePhoneNumber(raw)
      if (norm.length in 7..15) {
        seen.add(norm)
      }
    }
    return seen.toList()
  }

  fun containsIncomingOrActiveCallKeyword(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val lower = cleanInvisibleFormatting(text).lowercase()
    if (INCOMING_OR_ACTIVE_CALL_PHRASES.any { lower.contains(it) }) {
      return true
    }
    val hasAnswerDeclineAr = (lower.contains("الرد") || lower.contains("إجابة") || lower.contains("اجابة")) &&
      lower.contains("رفض")
    val hasAnswerDeclineEn = (lower.contains("answer") || lower.contains("accept")) &&
      (lower.contains("decline") || lower.contains("reject"))
    val hasActiveControlsAr = lower.contains("كتم") && (lower.contains("إنهاء") || lower.contains("انهاء") || lower.contains("سماعة"))
    val hasActiveControlsEn = lower.contains("mute") && (lower.contains("end call") || lower.contains("speaker"))
    return hasAnswerDeclineAr || hasAnswerDeclineEn || hasActiveControlsAr || hasActiveControlsEn
  }

  fun looksLikeCallHistoryOrListScreen(text: String?, candidates: List<String>): Boolean {
    if (candidates.size > 2) return true
    if (text.isNullOrBlank()) return false
    val lower = cleanInvisibleFormatting(text).lowercase()
    return CALL_HISTORY_OR_LIST_MARKERS.any { lower.contains(it) }
  }

  fun isPureInCallUiPackage(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    val lower = packageName.lowercase()
    return lower in PURE_INCALL_UI_PACKAGES ||
      lower.contains("incallui") ||
      lower.contains("server.telecom")
  }

  fun isDialerOrCallUiPackage(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    val lower = packageName.lowercase()
    return isPureInCallUiPackage(lower) ||
      lower in VOIP_AND_DIALER_PACKAGES ||
      lower.contains("dialer")
  }

  fun isCallNotification(
    packageName: String?,
    notification: Notification?,
    combinedText: String,
    isActiveCall: Boolean
  ): Boolean {
    if (notification?.category == Notification.CATEGORY_CALL) return true
    if (notification?.category == Notification.CATEGORY_MESSAGE) return false
    if (isPureInCallUiPackage(packageName)) return true
    if (isActiveCall && isDialerOrCallUiPackage(packageName) && !looksLikeCallHistoryOrListScreen(combinedText, emptyList())) {
      return containsIncomingOrActiveCallKeyword(combinedText) || isPureInCallUiPackage(packageName)
    }
    return containsIncomingOrActiveCallKeyword(combinedText)
  }

  fun extractNotificationCombinedText(
    notification: Notification?,
    eventTexts: List<CharSequence>?
  ): String {
    val parts = mutableListOf<String>()
    eventTexts?.forEach { cs ->
      val s = cs.toString().trim()
      if (s.isNotEmpty()) parts.add(s)
    }
    notification?.extras?.let { extras ->
      listOf(
        Notification.EXTRA_CONVERSATION_TITLE,
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_SUMMARY_TEXT
      ).forEach { key ->
        val value = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
        if (value.isNotEmpty()) parts.add(value)
      }
    }
    return parts.distinct().joinToString("\n")
  }

  fun isSimTelephonyCallActive(context: Context): Boolean? {
    val hasPhonePermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPhonePermission) return null
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    @Suppress("DEPRECATION")
    val callState = runCatching { tm.callState }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
    return callState == TelephonyManager.CALL_STATE_RINGING ||
      callState == TelephonyManager.CALL_STATE_OFFHOOK
  }

  fun isActiveCallNow(context: Context): Boolean {
    if (isSimTelephonyCallActive(context) == true) {
      return true
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
    return audioMode == AudioManager.MODE_IN_CALL ||
      audioMode == AudioManager.MODE_IN_COMMUNICATION ||
      audioMode == AudioManager.MODE_RINGTONE
  }

  suspend fun lookupRegisteredCustomerAddress(
    context: Context,
    rawPhone: String
  ): CallerAddressLookupResult? {
    val decoded = decodePhoneHandle(rawPhone)
    val normalized = CustomerRecord.normalizePhoneNumber(decoded)
    if (normalized.length < 7) return null
    val repo = DeliveryRepository.initialize(context.applicationContext)
    val customer = repo.findCustomerByPhone(decoded) ?: return null
    if (customer.writtenAddress.isBlank()) return null
    val suspicious = repo.findSuspiciousByPhone(decoded)
    return CallerAddressLookupResult(
      phone = decoded.ifBlank { customer.originalPhone.ifBlank { normalized } },
      normalizedPhone = normalized,
      customer = customer,
      suspicious = suspicious
    )
  }

  suspend fun lookupFirstRegisteredCustomerInCandidates(
    context: Context,
    candidates: List<String>
  ): CallerAddressLookupResult? {
    if (candidates.isEmpty()) return null
    for (candidate in candidates) {
      val result = lookupRegisteredCustomerAddress(context, candidate)
      if (result != null) return result
    }
    return null
  }

  fun formatFullAddressLine(customer: CustomerRecord): String {
    val addr = customer.writtenAddress.trim()
    val area = customer.areaSubtitle.trim()
    return when {
      area.isBlank() -> addr
      addr.contains(area, ignoreCase = true) -> addr
      else -> "$addr - $area"
    }
  }

  fun isCallScreeningRoleAvailable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
    return runCatching {
      roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    }.getOrDefault(false)
  }

  fun isCallScreeningRoleHeld(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
    return runCatching {
      roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
        roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }.getOrDefault(false)
  }

  fun createCallScreeningRoleIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
    return runCatching {
      if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
        !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
      ) {
        roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
      } else {
        null
      }
    }.getOrNull()
  }

  /**
   * Launches the system phone dialer for [rawPhone], and if the Caller Address Bar
   * feature is enabled with overlay permission, checks the database and displays
   * the slim floating address bar immediately for registered customers.
   */
  fun launchDialWithAddressOverlay(context: Context, rawPhone: String) {
    val cleanPhone = decodePhoneHandle(rawPhone)
    if (cleanPhone.isBlank()) return

    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
      if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
    runCatching { context.startActivity(dialIntent) }

    val appCtx = context.applicationContext
    val isEnabled = SettingsPreferences(appCtx).isCallerAddressBarEnabledSync()
    if (isEnabled && Settings.canDrawOverlays(appCtx)) {
      lookupScope.launch {
        val result = lookupRegisteredCustomerAddress(appCtx, cleanPhone)
        if (result != null) {
          CallAddressOverlayService.showForLookup(
            context = appCtx,
            result = result,
            isPreview = false,
            allowReplaceExisting = true
          )
        }
      }
    }
  }
}
