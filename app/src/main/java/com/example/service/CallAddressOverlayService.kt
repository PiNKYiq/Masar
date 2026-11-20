package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.CustomerRecord
import com.example.util.CallerAddressHelper
import com.example.util.CallerAddressLookupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Displays a slim, Truecaller-style floating address bar at the top of the screen
 * whenever the driver dials or receives an incoming/outgoing call (SIM or WhatsApp)
 * from a registered customer.
 *
 * Persistence Guarantee:
 * - Remains visible on screen until the user explicitly closes it via the (✕) button,
 *   the notification "إغلاق الشريط" action, or turning off the feature in Settings.
 * - Never auto-dismisses on hangup or timeout.
 * - Never lets passive background accessibility scans overwrite an already-displayed customer bar.
 * - Updates content cleanly in-place (without removing/re-adding the window) when a genuine
 *   new call arrives, eliminating any flicker or disappearance.
 */
class CallAddressOverlayService : Service() {

  private lateinit var windowManager: WindowManager

  private var screenHeight = 1920
  private var overlayView: View? = null
  private var overlayParams: WindowManager.LayoutParams? = null

  private var barLayoutView: LinearLayout? = null
  private var iconCircleView: TextView? = null
  private var headerLabelView: TextView? = null
  private var susChipView: TextView? = null
  private var addressTextView: TextView? = null
  private var mapBtnView: TextView? = null

  private var currentShownNormalizedPhone: String? = null
  private var currentIsPreview = false

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    updateScreenHeight()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val prefs = com.example.data.local.SettingsPreferences(applicationContext)
    if (intent?.action == ACTION_DISMISS) {
      prefs.clearActiveCallerBar()
      stopSelf()
      return START_NOT_STICKY
    }

    if (!Settings.canDrawOverlays(this)) {
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = buildNotification()
    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
      0
    }
    runCatching {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    val savedBar = if (intent == null) prefs.getActiveCallerBar() else null
    val phone = intent?.getStringExtra(EXTRA_PHONE) ?: savedBar?.phone.orEmpty()
    val normalizedPhone = (intent?.getStringExtra(EXTRA_NORMALIZED_PHONE) ?: savedBar?.normalizedPhone.orEmpty())
      .ifBlank { CustomerRecord.normalizePhoneNumber(phone) }
    val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: savedBar?.address.orEmpty()
    val mapUrl = intent?.getStringExtra(EXTRA_MAP_URL) ?: savedBar?.mapUrl.orEmpty()
    val isSuspicious = intent?.getBooleanExtra(EXTRA_IS_SUSPICIOUS, false) ?: (savedBar?.isSuspicious ?: false)
    val isPreview = intent?.getBooleanExtra(EXTRA_IS_PREVIEW, false) ?: false
    val allowReplaceExisting = intent?.getBooleanExtra(EXTRA_ALLOW_REPLACE_EXISTING, true) ?: true

    if (address.isBlank()) {
      if (overlayView == null) stopSelf()
      return START_NOT_STICKY
    }

    // If a live bar is already displayed:
    // 1) Same customer -> keep as-is without flicker.
    // 2) Passive background/accessibility trigger (!allowReplaceExisting) -> NEVER overwrite the active bar!
    val hasActiveLiveBar = overlayView != null && !currentIsPreview
    if (hasActiveLiveBar) {
      val sameCustomer = normalizedPhone.isNotBlank() && normalizedPhone == currentShownNormalizedPhone
      if (sameCustomer || (!isPreview && !allowReplaceExisting)) {
        _isShowingFlow.value = true
        return START_STICKY
      }
    }

    currentShownNormalizedPhone = normalizedPhone
    currentIsPreview = isPreview
    if (!isPreview) {
      prefs.saveActiveCallerBar(
        phone = phone,
        normalizedPhone = normalizedPhone,
        address = address,
        mapUrl = mapUrl,
        isSuspicious = isSuspicious
      )
    }

    if (overlayView != null) {
      // Update existing overlay views cleanly in-place so the bar never disappears or flickers
      updateSlimAddressBarInPlace(
        phone = phone,
        address = address,
        mapUrl = mapUrl,
        isSuspicious = isSuspicious
      )
    } else {
      createAndShowSlimAddressBar(
        phone = phone,
        address = address,
        mapUrl = mapUrl,
        isSuspicious = isSuspicious
      )
    }

    _isShowingFlow.value = true
    return START_STICKY
  }

  private fun updateScreenHeight() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      screenHeight = windowManager.maximumWindowMetrics.bounds.height().coerceAtLeast(1)
    } else {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(dm)
      screenHeight = dm.heightPixels.coerceAtLeast(1)
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun createAndShowSlimAddressBar(
    phone: String,
    address: String,
    mapUrl: String,
    isSuspicious: Boolean
  ) {
    removeOverlayViewOnly()

    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    @Suppress("DEPRECATION")
    val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      overlayType,
      windowFlags,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      x = 0
      y = dp(76)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }

    val outerContainer = FrameLayout(this).apply {
      setPadding(dp(12), dp(4), dp(12), dp(4))
      layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    val barLayout = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      setPadding(dp(12), dp(9), dp(10), dp(9))
      elevation = dp(10).toFloat()
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      )
    }

    val iconCircle = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
      val size = dp(36)
      layoutParams = LinearLayout.LayoutParams(size, size).apply {
        marginEnd = dp(10)
      }
    }
    barLayout.addView(iconCircle)

    val textColumn = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
      }
    }

    val headerRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    val headerLabel = TextView(this).apply {
      setTextColor(Color.parseColor("#7DD3FC"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.DEFAULT_BOLD
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerRow.addView(headerLabel)

    val susChip = TextView(this).apply {
      text = "مشبوه"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(6), dp(1), dp(6), dp(1))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(Color.parseColor("#DC2626"))
      }
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        marginStart = dp(6)
      }
    }
    headerRow.addView(susChip)
    textColumn.addView(headerRow)

    val addressText = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
      typeface = Typeface.DEFAULT_BOLD
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
      setPadding(0, dp(2), 0, 0)
    }
    textColumn.addView(addressText)
    barLayout.addView(textColumn)

    val mapBtn = TextView(this).apply {
      text = "خريطة"
      setTextColor(Color.parseColor("#E0F2FE"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(8), dp(6), dp(8), dp(6))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#1E3A8A"))
      }
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        marginEnd = dp(6)
      }
    }
    barLayout.addView(mapBtn)

    val closeBtn = TextView(this).apply {
      text = "✕"
      contentDescription = "إغلاق شريط عنوان المتصل"
      setTextColor(Color.parseColor("#E2E8F0"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      val size = dp(34)
      layoutParams = LinearLayout.LayoutParams(size, size)
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#334155"))
      }
      setOnClickListener {
        com.example.data.local.SettingsPreferences(applicationContext).clearActiveCallerBar()
        stopSelf()
      }
    }
    barLayout.addView(closeBtn)

    var initialY = 0
    var initialTouchY = 0f
    var dragging = false
    barLayout.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialY = params.y
          initialTouchY = event.rawY
          dragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dy = (event.rawY - initialTouchY).toInt()
          if (abs(dy) > dp(6)) dragging = true
          if (dragging) {
            params.y = (initialY + dy).coerceIn(dp(24), (screenHeight - dp(120)).coerceAtLeast(dp(24)))
            runCatching { windowManager.updateViewLayout(outerContainer, params) }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          dragging
        }
        else -> false
      }
    }

    barLayoutView = barLayout
    iconCircleView = iconCircle
    headerLabelView = headerLabel
    susChipView = susChip
    addressTextView = addressText
    mapBtnView = mapBtn

    updateSlimAddressBarInPlace(
      phone = phone,
      address = address,
      mapUrl = mapUrl,
      isSuspicious = isSuspicious
    )

    barLayout.alpha = 0f
    barLayout.translationY = -dp(24f).toFloat()
    outerContainer.addView(barLayout)
    overlayView = outerContainer
    overlayParams = params

    runCatching {
      windowManager.addView(outerContainer, params)
      barLayout.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(220L)
        .setInterpolator(DecelerateInterpolator())
        .start()
    }.onFailure {
      stopSelf()
    }
  }

  private fun updateSlimAddressBarInPlace(
    phone: String,
    address: String,
    mapUrl: String,
    isSuspicious: Boolean
  ) {
    val accentColor = if (isSuspicious) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8")
    barLayoutView?.background = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(16).toFloat()
      setColor(Color.parseColor("#0F172A"))
      setStroke(dp(1.5f), accentColor)
    }
    iconCircleView?.apply {
      text = if (isSuspicious) "⚠" else "📍"
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (isSuspicious) Color.parseColor("#DC2626") else Color.parseColor("#0284C7"))
      }
    }
    headerLabelView?.text = if (phone.isNotBlank()) "عنوان الزبون • $phone" else "عنوان الزبون المسجل"
    susChipView?.visibility = if (isSuspicious) View.VISIBLE else View.GONE
    addressTextView?.text = address
    mapBtnView?.apply {
      visibility = if (mapUrl.isNotBlank()) View.VISIBLE else View.GONE
      setOnClickListener {
        if (mapUrl.isNotBlank()) {
          runCatching {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mapIntent)
          }
        }
      }
    }
  }

  private fun removeOverlayViewOnly() {
    overlayView?.let { v ->
      runCatching { windowManager.removeView(v) }
    }
    overlayView = null
    overlayParams = null
    barLayoutView = null
    iconCircleView = null
    headerLabelView = null
    susChipView = null
    addressTextView = null
    mapBtnView = null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      val channel = NotificationChannel(
        CHANNEL_ID,
        "عنوان المتصل أثناء الاتصال",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "عرض شريط عنوان الزبون المسجل أثناء المكالمات"
      }
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      10,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val closeIntent = PendingIntent.getService(
      this,
      11,
      Intent(this, CallAddressOverlayService::class.java).apply {
        action = ACTION_DISMISS
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("عنوان الزبون أثناء المكالمة")
      .setContentText("يظل الشريط العائم ظاهراً حتى تقوم بإغلاقه بالضغط على ✕")
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentIntent(openAppIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق الشريط", closeIntent)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun dp(value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).toInt()
  }

  private fun dp(value: Float): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value,
      resources.displayMetrics
    ).toInt()
  }

  override fun onDestroy() {
    removeOverlayViewOnly()
    currentShownNormalizedPhone = null
    currentIsPreview = false
    _isShowingFlow.value = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL_ID = "call_address_overlay_channel"
    private const val NOTIFICATION_ID = 4301
    private const val ACTION_DISMISS = "com.example.ACTION_DISMISS_CALL_ADDRESS"

    const val EXTRA_PHONE = "extra_phone"
    const val EXTRA_NORMALIZED_PHONE = "extra_normalized_phone"
    const val EXTRA_ADDRESS = "extra_address"
    const val EXTRA_MAP_URL = "extra_map_url"
    const val EXTRA_IS_SUSPICIOUS = "extra_is_suspicious"
    const val EXTRA_IS_PREVIEW = "extra_is_preview"
    const val EXTRA_ALLOW_REPLACE_EXISTING = "extra_allow_replace_existing"

    private val _isShowingFlow = MutableStateFlow(false)
    val isShowingFlow: StateFlow<Boolean> = _isShowingFlow.asStateFlow()

    fun showForLookup(
      context: Context,
      result: CallerAddressLookupResult,
      isPreview: Boolean = false,
      allowReplaceExisting: Boolean = true
    ) {
      if (!Settings.canDrawOverlays(context)) return
      val addressLine = CallerAddressHelper.formatFullAddressLine(result.customer)
      if (addressLine.isBlank()) return
      val mapUrl = result.customer.mapUrl.ifBlank {
        CustomerRecord.buildGoogleMapsUrl(
          result.customer.latitude,
          result.customer.longitude
        )
      }
      val intent = Intent(context, CallAddressOverlayService::class.java).apply {
        putExtra(EXTRA_PHONE, result.phone)
        putExtra(EXTRA_NORMALIZED_PHONE, result.normalizedPhone)
        putExtra(EXTRA_ADDRESS, addressLine)
        putExtra(EXTRA_MAP_URL, mapUrl)
        putExtra(EXTRA_IS_SUSPICIOUS, result.suspicious != null)
        putExtra(EXTRA_IS_PREVIEW, isPreview)
        putExtra(EXTRA_ALLOW_REPLACE_EXISTING, allowReplaceExisting)
      }
      runCatching {
        ContextCompat.startForegroundService(context, intent)
      }.onFailure {
        runCatching { context.startService(intent) }
      }
    }

    fun dismiss(context: Context) {
      com.example.data.local.SettingsPreferences(context.applicationContext).clearActiveCallerBar()
      runCatching {
        context.stopService(Intent(context, CallAddressOverlayService::class.java))
      }
    }
  }
}
