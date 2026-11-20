package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.DeliveryRepository
import com.example.model.CustomerRecord
import com.example.util.ParsedCustomerEntry
import com.example.util.QuickCustomerNoteParser
import com.example.util.VibrationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Background floating (+) service displayed above Notes and other apps
 * so the driver can capture customer phones and addresses directly from the screen
 * in one tap and save them into Room + Firestore.
 */
class FloatingQuickAddService : Service() {

  private lateinit var windowManager: WindowManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private var screenWidth = 1080
  private var screenHeight = 1920

  private var bubbleView: View? = null
  private var bubbleParams: WindowManager.LayoutParams? = null
  private var quickAddOverlayView: View? = null

  private var detectedEntries: List<ParsedCustomerEntry> = emptyList()
  private var currentEntryIndex: Int = 0
  private var lastVibratedPhone: String? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    updateScreenMetrics()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val prefs = com.example.data.local.SettingsPreferences(applicationContext)
    if (intent?.action == ACTION_STOP_QUICK_ADD) {
      prefs.setFloatingQuickAddEnabledSync(false)
      stopSelf()
      return START_NOT_STICKY
    }

    if (!Settings.canDrawOverlays(this)) {
      Toast.makeText(this, "يرجى تفعيل صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = buildNotification()
    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    prefs.setFloatingQuickAddEnabledSync(true)
    BackgroundKeepAliveService.syncWithPreferences(applicationContext)

    if (bubbleView == null) {
      showFloatingPlusButton()
    }

    _isRunningFlow.value = true
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    if (com.example.data.local.SettingsPreferences(applicationContext).isFloatingQuickAddEnabledSync()) {
      BackgroundKeepAliveService.syncWithPreferences(applicationContext)
    }
  }

  private fun updateScreenMetrics() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val metrics = windowManager.maximumWindowMetrics
      screenWidth = metrics.bounds.width().coerceAtLeast(1)
      screenHeight = metrics.bounds.height().coerceAtLeast(1)
    } else {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(dm)
      screenWidth = dm.widthPixels.coerceAtLeast(1)
      screenHeight = dm.heightPixels.coerceAtLeast(1)
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun showFloatingPlusButton() {
    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = dp(20)
      y = dp(420)
    }

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    // Main circular (+) button
    val plusButton = FrameLayout(this).apply {
      val size = dp(58)
      layoutParams = LinearLayout.LayoutParams(size, size)
      background = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#059669"), Color.parseColor("#0D9488"))
      ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2.5f), Color.WHITE)
      }
      elevation = dp(8).toFloat()

      val plusSign = TextView(this@FloatingQuickAddService).apply {
        text = "+"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT
        )
      }
      addView(plusSign)
    }

    // Small close badge
    val closeBadge = TextView(this).apply {
      text = "✕"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      val badgeSize = dp(24)
      layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
        marginStart = dp(4)
      }
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#DC2626"))
        setStroke(dp(1), Color.WHITE)
      }
      elevation = dp(6).toFloat()
      setOnClickListener { stopSelf() }
    }

    container.addView(plusButton)
    container.addView(closeBadge)

    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var downTime = 0L

    plusButton.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = params.x
          initialY = params.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          downTime = System.currentTimeMillis()
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - initialTouchX).toInt()
          val dy = (event.rawY - initialTouchY).toInt()
          params.x = (initialX + dx).coerceIn(0, (screenWidth - dp(92)).coerceAtLeast(0))
          params.y = (initialY + dy).coerceIn(dp(24), (screenHeight - dp(92)).coerceAtLeast(dp(24)))
          runCatching { windowManager.updateViewLayout(container, params) }
          true
        }
        MotionEvent.ACTION_UP -> {
          val dx = abs(event.rawX - initialTouchX)
          val dy = abs(event.rawY - initialTouchY)
          val elapsed = System.currentTimeMillis() - downTime
          if (dx < dp(10) && dy < dp(10) && elapsed < 380L) {
            captureScreenAndOpenQuickAdd()
          }
          true
        }
        else -> false
      }
    }

    bubbleView = container
    bubbleParams = params
    runCatching {
      windowManager.addView(container, params)
    }.onFailure {
      Toast.makeText(this, "تعذر إظهار زر الإضافة العائم على الشاشة", Toast.LENGTH_SHORT).show()
      stopSelf()
    }
  }

  private fun captureScreenAndOpenQuickAdd() {
    val accessibilityActive = ScreenTextAccessibilityService.isEnabled(this)
    val screenText = if (accessibilityActive) {
      ScreenTextAccessibilityService.captureScreenLines(excludePackage = packageName)
    } else {
      ""
    }
    var parseResult = QuickCustomerNoteParser.parseFromScreenText(screenText)
    var sourceLabel = when {
      parseResult.entries.isNotEmpty() -> "تم التعرف على ${parseResult.entries.size} رقم هاتف من الشاشة"
      else -> null
    }

    // Fallback to clipboard if screen read yielded nothing
    if (parseResult.entries.isEmpty()) {
      val clipText = readClipboardText()
      if (clipText.isNotBlank()) {
        val clipParsed = QuickCustomerNoteParser.parseFromScreenText(clipText)
        if (clipParsed.entries.isNotEmpty()) {
          parseResult = clipParsed
          sourceLabel = "تم استخراج رقم الهاتف من الحافظة"
        }
      }
    }

    detectedEntries = parseResult.entries
    currentEntryIndex = 0
    showQuickAddOverlay(
      entries = parseResult.entries,
      isAccessibilityEnabled = accessibilityActive,
      sourceLabel = sourceLabel
    )
  }

  private fun readClipboardText(): String {
    return runCatching {
      val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
      cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }.getOrDefault("")
  }

  private fun showQuickAddOverlay(
    entries: List<ParsedCustomerEntry>,
    isAccessibilityEnabled: Boolean,
    sourceLabel: String?
  ) {
    dismissQuickAddOverlay()

    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val cardWidth = (screenWidth * 0.92f).toInt().coerceAtMost(dp(420))
    val maxHeight = (screenHeight * 0.80f).toInt()
    val overlayParams = WindowManager.LayoutParams(
      cardWidth,
      WindowManager.LayoutParams.WRAP_CONTENT,
      overlayType,
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.CENTER
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    val scrollContainer = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
      isVerticalScrollBarEnabled = true
    }

    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      setPadding(dp(16), dp(14), dp(16), dp(16))
      background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1.5f), Color.parseColor("#CBD5E1"))
      }
      elevation = dp(12).toFloat()
    }

    // Header row
    val headerRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(8) }
    }

    val titleBlock = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titleView = TextView(this).apply {
      text = "إضافة زبون سريع (+)"
      setTextColor(Color.parseColor("#0F172A"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
      typeface = Typeface.DEFAULT_BOLD
    }

    val statusPill = TextView(this).apply {
      text = sourceLabel ?: if (isAccessibilityEnabled) {
        "لم يُعثر على رقم (11 رقم أو بمفتاح بلد)، أدخله أو الصقه"
      } else {
        "فعّل قراءة الشاشة لتعبئة رقم الهاتف تلقائياً"
      }
      setTextColor(
        if (sourceLabel != null) Color.parseColor("#047857") else Color.parseColor("#B45309")
      )
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
      typeface = Typeface.DEFAULT_BOLD
    }

    titleBlock.addView(titleView)
    titleBlock.addView(statusPill)

    val closeBtn = TextView(this).apply {
      text = "✕"
      setTextColor(Color.parseColor("#DC2626"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(10), dp(6), dp(10), dp(6))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#FEE2E2"))
      }
      setOnClickListener { dismissQuickAddOverlay() }
    }

    headerRow.addView(titleBlock)
    headerRow.addView(closeBtn)
    card.addView(headerRow)

    // Utility quick toolbar: Re-scan screen / Paste / Enable Screen Reading
    val utilRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(10) }
    }

    val phoneInput = EditText(this)
    val addressInput = EditText(this)
    val areaInput = EditText(this)
    val suspiciousBanner = TextView(this)
    val existingHintBanner = TextView(this)
    val saveBtn = TextView(this)

    fun refreshLiveLookupForPhone(raw: String) {
      val norm = CustomerRecord.normalizePhoneNumber(QuickCustomerNoteParser.normalizeDigits(raw))
      if (norm.length < 7) {
        suspiciousBanner.visibility = View.GONE
        existingHintBanner.visibility = View.GONE
        lastVibratedPhone = null
        return
      }
      val repo = DeliveryRepository.getInstance(applicationContext) ?: return
      val suspicious = repo.findSuspiciousByPhone(norm)
      if (suspicious != null) {
        suspiciousBanner.visibility = View.VISIBLE
        suspiciousBanner.text = "⚠️ تحذير: هذا الزبون مشبوه بسرقة الطلبات!\nالملاحظة: ${suspicious.note}"
        if (lastVibratedPhone != norm) {
          VibrationHelper.vibrateSuspiciousAlert(this@FloatingQuickAddService)
          lastVibratedPhone = norm
        }
      } else {
        suspiciousBanner.visibility = View.GONE
        lastVibratedPhone = null
      }

      serviceScope.launch {
        val existing = repo.findCustomerByPhone(norm)
        if (existing != null && existing.writtenAddress.isNotBlank()) {
          existingHintBanner.visibility = View.VISIBLE
          existingHintBanner.text = "✓ مسجل مسبقاً بعنوان: ${existing.writtenAddress} (الحفظ سيحدّث البيانات)"
        } else {
          existingHintBanner.visibility = View.GONE
        }
      }
    }

    fun applyEntry(entry: ParsedCustomerEntry) {
      phoneInput.setText(entry.phone)
      // Keep customer address and area empty for manual entry as requested
      addressInput.setText("")
      areaInput.setText("")
      refreshLiveLookupForPhone(entry.phone)
      addressInput.requestFocus()
      saveBtn.text = if (detectedEntries.size > 1 && currentEntryIndex < detectedEntries.lastIndex) {
        "حفظ والانتقال للتالي (${currentEntryIndex + 1}/${detectedEntries.size}) ✓"
      } else {
        "حفظ رقم وعنوان الزبون ✓"
      }
    }

    val rescanBtn = TextView(this).apply {
      text = "🔄 قراءة الشاشة"
      setTextColor(Color.parseColor("#0369A1"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(10), dp(7), dp(10), dp(7))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#E0F2FE"))
        setStroke(dp(1), Color.parseColor("#BAE6FD"))
      }
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(6)
      }
      setOnClickListener {
        captureScreenAndOpenQuickAdd()
      }
    }

    val pasteBtn = TextView(this).apply {
      text = "📋 لصق الحافظة"
      setTextColor(Color.parseColor("#047857"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(10), dp(7), dp(10), dp(7))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#ECFDF5"))
        setStroke(dp(1), Color.parseColor("#A7F3D0"))
      }
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      setOnClickListener {
        val clip = readClipboardText()
        if (clip.isBlank()) {
          Toast.makeText(this@FloatingQuickAddService, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }
        val parsed = QuickCustomerNoteParser.parseFromScreenText(clip)
        if (parsed.entries.isNotEmpty()) {
          detectedEntries = parsed.entries
          currentEntryIndex = 0
          applyEntry(parsed.entries.first())
          statusPill.text = "تم لصق البيانات بنجاح ✓"
          statusPill.setTextColor(Color.parseColor("#047857"))
        } else {
          addressInput.setText(clip.trim())
        }
      }
    }

    utilRow.addView(rescanBtn)
    utilRow.addView(pasteBtn)
    card.addView(utilRow)

    if (!isAccessibilityEnabled) {
      val enableAccBtn = TextView(this).apply {
        text = "⚙️ فعّل قراءة الشاشة لاستخراج أرقام الهواتف تلقائياً"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
          cornerRadius = dp(10).toFloat()
          setColor(Color.parseColor("#D97706"))
        }
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        setOnClickListener {
          dismissQuickAddOverlay()
          val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          runCatching { startActivity(intent) }
        }
      }
      card.addView(enableAccBtn)
    }

    // Multiple detected entries selector chips (for notes with multiple customers)
    if (entries.size > 1) {
      val entriesLabel = TextView(this).apply {
        text = "تم العثور على ${entries.size} زبون في الشاشة (اختر أو احفظ بالتتابع):"
        setTextColor(Color.parseColor("#334155"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
      }
      card.addView(entriesLabel)

      val chipScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
      }
      val chipRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
      }
      entries.forEachIndexed { idx, entry ->
        val chip = TextView(this).apply {
          text = "${idx + 1} • ${entry.phone}"
          setTextColor(Color.parseColor("#0F172A"))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
          typeface = Typeface.DEFAULT_BOLD
          setPadding(dp(10), dp(6), dp(10), dp(6))
          background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#F1F5F9"))
            setStroke(dp(1), Color.parseColor("#94A3B8"))
          }
          layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
          ).apply { marginEnd = dp(6) }
          setOnClickListener {
            currentEntryIndex = idx
            applyEntry(entry)
          }
        }
        chipRow.addView(chip)
      }
      chipScroll.addView(chipRow)
      card.addView(chipScroll)
    }

    fun styleField(field: EditText, hintText: String, inputTypeFlag: Int) {
      field.apply {
        hint = hintText
        setTextColor(Color.parseColor("#0F172A"))
        setHintTextColor(Color.parseColor("#94A3B8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        inputType = inputTypeFlag
        imeOptions = EditorInfo.IME_ACTION_NEXT
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
          cornerRadius = dp(12).toFloat()
          setColor(Color.parseColor("#F8FAFC"))
          setStroke(dp(1.2f), Color.parseColor("#CBD5E1"))
        }
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
      }
    }

    val phoneLabel = TextView(this).apply {
      text = "رقم هاتف الزبون:"
      setTextColor(Color.parseColor("#334155"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(3) }
    }
    styleField(phoneInput, "مثال: 07710266414", InputType.TYPE_CLASS_PHONE)

    phoneInput.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        refreshLiveLookupForPhone(s?.toString().orEmpty())
      }
    })

    suspiciousBanner.apply {
      visibility = View.GONE
      setTextColor(Color.parseColor("#991B1B"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(10), dp(8), dp(10), dp(8))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#FEF2F2"))
        setStroke(dp(1.5f), Color.parseColor("#DC2626"))
      }
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(8) }
    }

    existingHintBanner.apply {
      visibility = View.GONE
      setTextColor(Color.parseColor("#1E40AF"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(10), dp(7), dp(10), dp(7))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#EFF6FF"))
        setStroke(dp(1), Color.parseColor("#93C5FD"))
      }
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(8) }
    }

    val addressLabel = TextView(this).apply {
      text = "عنوان الزبون:"
      setTextColor(Color.parseColor("#334155"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(3) }
    }
    styleField(
      addressInput,
      "مثال: بغداد - المنصور - شارع 14 رمضان",
      InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    )

    val areaLabel = TextView(this).apply {
      text = "نقطة دالة / تفاصيل إضافية (اختياري):"
      setTextColor(Color.parseColor("#334155"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { bottomMargin = dp(3) }
    }
    styleField(
      areaInput,
      "اختياري...",
      InputType.TYPE_CLASS_TEXT
    )
    areaInput.imeOptions = EditorInfo.IME_ACTION_DONE

    card.addView(phoneLabel)
    card.addView(phoneInput)
    card.addView(suspiciousBanner)
    card.addView(existingHintBanner)
    card.addView(addressLabel)
    card.addView(addressInput)
    card.addView(areaLabel)
    card.addView(areaInput)

    // Primary Save Button
    saveBtn.apply {
      text = if (entries.size > 1) {
        "حفظ والانتقال للتالي (1/${entries.size}) ✓"
      } else {
        "حفظ رقم وعنوان الزبون ✓"
      }
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(14), dp(12), dp(14), dp(12))
      background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#059669"))
      }
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(2) }
      setOnClickListener {
        val rawPhone = QuickCustomerNoteParser.normalizeDigits(phoneInput.text?.toString().orEmpty()).trim()
        val cleanAddr = addressInput.text?.toString().orEmpty().trim()
        val cleanArea = areaInput.text?.toString().orEmpty().trim()
        val normPhone = CustomerRecord.normalizePhoneNumber(rawPhone)

        if (normPhone.length < 7) {
          Toast.makeText(this@FloatingQuickAddService, "يرجى إدخال رقم هاتف صحيح", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }
        if (cleanAddr.isBlank()) {
          Toast.makeText(this@FloatingQuickAddService, "يرجى إدخال عنوان الزبون", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }

        val repo = DeliveryRepository.getInstance(applicationContext)
        if (repo == null) {
          Toast.makeText(this@FloatingQuickAddService, "تعذر الوصول لقاعدة البيانات", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }

        serviceScope.launch {
          val existing = repo.findCustomerByPhone(normPhone)
          val record = CustomerRecord(
            phone = normPhone,
            originalPhone = rawPhone,
            writtenAddress = cleanAddr,
            areaSubtitle = cleanArea,
            locationCoordinates = existing?.locationCoordinates,
            latitude = existing?.latitude,
            longitude = existing?.longitude,
            mapUrl = existing?.mapUrl ?: "",
            lastUpdated = "الآن"
          )
          repo.saveCustomer(record)

          Toast.makeText(
            this@FloatingQuickAddService,
            "✓ تم حفظ الزبون ($rawPhone)",
            Toast.LENGTH_SHORT
          ).show()

          if (currentEntryIndex + 1 < detectedEntries.size) {
            currentEntryIndex += 1
            applyEntry(detectedEntries[currentEntryIndex])
          } else {
            dismissQuickAddOverlay()
          }
        }
      }
    }
    card.addView(saveBtn)

    if (entries.isNotEmpty()) {
      applyEntry(entries.first())
    }

    scrollContainer.addView(card)
    quickAddOverlayView = scrollContainer
    runCatching {
      windowManager.addView(scrollContainer, overlayParams)
      scrollContainer.post {
        if (scrollContainer.height > maxHeight) {
          overlayParams.height = maxHeight
          runCatching { windowManager.updateViewLayout(scrollContainer, overlayParams) }
        }
      }
    }
  }

  private fun dismissQuickAddOverlay() {
    quickAddOverlayView?.let { v ->
      runCatching { windowManager.removeView(v) }
    }
    quickAddOverlayView = null
  }

  private fun buildNotification(): Notification {
    val openIntent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val openPending = PendingIntent.getActivity(
      this, 20, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val stopIntent = Intent(this, FloatingQuickAddService::class.java).apply {
      action = ACTION_STOP_QUICK_ADD
    }
    val stopPending = PendingIntent.getService(
      this, 21, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("زر الإضافة السريعة (+) نشط")
      .setContentText("اضغط على علامة (+) العائمة فوق الملاحظات لإضافة أرقام وعناوين الزبائن")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(openPending)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف الزر العائم", stopPending)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "زر الإضافة السريعة العائم (+)",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "إظهار زر (+) فوق التطبيقات لقراءة وإضافة الزبائن من الملاحظات بسرعة"
      }
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
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
    _isRunningFlow.value = false
    dismissQuickAddOverlay()
    bubbleView?.let { v -> runCatching { windowManager.removeView(v) } }
    bubbleView = null
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val CHANNEL_ID = "floating_quick_add_channel"
    const val NOTIFICATION_ID = 4093
    const val ACTION_STOP_QUICK_ADD = "com.example.action.STOP_FLOATING_QUICK_ADD"

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()
  }
}
