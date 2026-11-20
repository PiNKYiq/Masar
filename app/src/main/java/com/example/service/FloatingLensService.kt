package com.example.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.DeliveryRepository
import com.example.model.CustomerRecord
import com.example.model.SuspiciousCustomer
import com.example.util.OcrHelper
import com.example.util.VibrationHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingLensService : Service() {

  private lateinit var windowManager: WindowManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null

  private var screenWidth = 1080
  private var screenHeight = 1920
  private var screenDensity = 320

  private var bubbleView: View? = null
  private var bubbleParams: WindowManager.LayoutParams? = null
  private var resultOverlayView: View? = null

  private val textRecognizer by lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  }

  private val projectionCallback = object : MediaProjection.Callback() {
    override fun onStop() {
      mainHandler.post {
        stopSelf()
      }
    }

    override fun onCapturedContentResize(width: Int, height: Int) {
      if (width > 0 && height > 0 && (width != screenWidth || height != screenHeight)) {
        screenWidth = width
        screenHeight = height
        mainHandler.post {
          recreateVirtualDisplay()
        }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    updateScreenMetrics()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_LENS) {
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
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)

    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
      ?: Activity.RESULT_CANCELED
    val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent?.getParcelableExtra(EXTRA_RESULT_DATA)
    }

    if (resultCode != Activity.RESULT_OK || resultData == null) {
      Toast.makeText(this, "تعذر تفعيل التقاط الشاشة للعدسة العائمة", Toast.LENGTH_SHORT).show()
      stopSelf()
      return START_NOT_STICKY
    }

    if (mediaProjection == null) {
      val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)?.apply {
        registerCallback(projectionCallback, mainHandler)
      }
      recreateVirtualDisplay()
    }

    if (bubbleView == null) {
      showFloatingBubble()
    }

    _isRunningFlow.value = true
    return START_NOT_STICKY
  }

  private fun updateScreenMetrics() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val metrics = windowManager.maximumWindowMetrics
      screenWidth = metrics.bounds.width().coerceAtLeast(1)
      screenHeight = metrics.bounds.height().coerceAtLeast(1)
      screenDensity = resources.displayMetrics.densityDpi
    } else {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(dm)
      screenWidth = dm.widthPixels.coerceAtLeast(1)
      screenHeight = dm.heightPixels.coerceAtLeast(1)
      screenDensity = dm.densityDpi
    }
  }

  private fun recreateVirtualDisplay() {
    virtualDisplay?.release()
    virtualDisplay = null
    imageReader?.close()
    imageReader = null

    val projection = mediaProjection ?: return
    val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
    imageReader = reader
    virtualDisplay = projection.createVirtualDisplay(
      "MasarFloatingLensDisplay",
      screenWidth,
      screenHeight,
      screenDensity,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      reader.surface,
      null,
      mainHandler
    )
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun showFloatingBubble() {
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
      y = dp(240)
    }

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    // Main circular floating lens button
    val lensButton = FrameLayout(this).apply {
      val size = dp(58)
      layoutParams = LinearLayout.LayoutParams(size, size)
      background = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#0052CC"), Color.parseColor("#0284C7"))
      ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2), Color.WHITE)
      }
      elevation = dp(8).toFloat()

      val icon = ImageView(this@FloatingLensService).apply {
        setImageResource(android.R.drawable.ic_menu_search)
        setColorFilter(Color.WHITE)
        val iconParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        layoutParams = iconParams
      }
      addView(icon)
    }

    // Compact close pill beside the lens
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
      setOnClickListener {
        stopSelf()
      }
    }

    container.addView(lensButton)
    container.addView(closeBadge)

    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var downTime = 0L

    lensButton.setOnTouchListener { _, event ->
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
          params.x = (initialX + dx).coerceIn(0, (screenWidth - dp(90)).coerceAtLeast(0))
          params.y = (initialY + dy).coerceIn(dp(24), (screenHeight - dp(90)).coerceAtLeast(dp(24)))
          runCatching { windowManager.updateViewLayout(container, params) }
          true
        }
        MotionEvent.ACTION_UP -> {
          val dx = abs(event.rawX - initialTouchX)
          val dy = abs(event.rawY - initialTouchY)
          val elapsed = System.currentTimeMillis() - downTime
          if (dx < dp(10) && dy < dp(10) && elapsed < 350L) {
            triggerScreenScan()
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
      Toast.makeText(this, "تعذر عرض العدسة العائمة على الشاشة", Toast.LENGTH_SHORT).show()
      stopSelf()
    }
  }

  private fun triggerScreenScan() {
    dismissResultOverlay()
    bubbleView?.visibility = View.INVISIBLE

    mainHandler.postDelayed({
      val bitmap = captureScreenBitmap()
      bubbleView?.visibility = View.VISIBLE

      if (bitmap == null) {
        showLoadingOrMessageOverlay(
          isLoading = false,
          message = "تعذر التقاط محتوى الشاشة الحالية. تأكد من استمرار مشاركة الشاشة ثم حاول مرة أخرى."
        )
        return@postDelayed
      }

      showLoadingOrMessageOverlay(
        isLoading = true,
        message = "جاري قراءة الأرقام والبحث في قاعدة البيانات..."
      )
      processCapturedBitmap(bitmap)
    }, 180L)
  }

  private fun captureScreenBitmap(): Bitmap? {
    val reader = imageReader ?: return null
    var image: Image? = null
    return try {
      image = reader.acquireLatestImage()
      if (image == null) {
        Thread.sleep(70L)
        image = reader.acquireLatestImage()
      }
      val img = image ?: return null
      val planes = img.planes
      if (planes.isEmpty()) return null
      val buffer = planes[0].buffer
      val pixelStride = planes[0].pixelStride
      val rowStride = planes[0].rowStride
      val rowPadding = rowStride - pixelStride * img.width

      val fullBitmap = Bitmap.createBitmap(
        img.width + rowPadding / pixelStride,
        img.height,
        Bitmap.Config.ARGB_8888
      )
      fullBitmap.copyPixelsFromBuffer(buffer)
      val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, img.width, img.height)
      if (cropped != fullBitmap) {
        fullBitmap.recycle()
      }
      cropped
    } catch (_: Exception) {
      null
    } finally {
      try {
        image?.close()
      } catch (_: Exception) {}
    }
  }

  private fun processCapturedBitmap(bitmap: Bitmap) {
    val accessibilityScreenText = ScreenTextAccessibilityService.captureScreenLines(excludePackage = packageName)
    val inputImage = InputImage.fromBitmap(bitmap, 0)
    val contrastBitmap = OcrHelper.createHighContrastOcrBitmap(bitmap)

    fun recycleBitmaps() {
      contrastBitmap?.let { if (!it.isRecycled) it.recycle() }
      if (!bitmap.isRecycled) bitmap.recycle()
    }

    fun finishWithText(visionPrimary: String?, visionContrast: String?) {
      recycleBitmaps()
      val combinedText = listOfNotNull(
        visionPrimary?.takeIf { it.isNotBlank() },
        visionContrast?.takeIf { it.isNotBlank() },
        accessibilityScreenText.takeIf { it.isNotBlank() }
      ).joinToString("\n")
      val repaired = OcrHelper.repairNumericLookalikes(OcrHelper.normalizeArabicNumerals(combinedText))
      val detectedPhones = OcrHelper.extractAllPhoneNumbers(repaired)
      lookupPhonesAndShowResult(detectedPhones)
    }

    textRecognizer.process(inputImage)
      .addOnSuccessListener { visionText ->
        if (contrastBitmap != null && !contrastBitmap.isRecycled) {
          textRecognizer.process(InputImage.fromBitmap(contrastBitmap, 0))
            .addOnSuccessListener { contrastVision ->
              finishWithText(visionText.text, contrastVision.text)
            }
            .addOnFailureListener {
              finishWithText(visionText.text, null)
            }
        } else {
          finishWithText(visionText.text, null)
        }
      }
      .addOnFailureListener {
        if (contrastBitmap != null && !contrastBitmap.isRecycled) {
          textRecognizer.process(InputImage.fromBitmap(contrastBitmap, 0))
            .addOnSuccessListener { contrastVision ->
              finishWithText(null, contrastVision.text)
            }
            .addOnFailureListener {
              recycleBitmaps()
              if (accessibilityScreenText.isNotBlank()) {
                val detectedPhones = OcrHelper.extractAllPhoneNumbers(accessibilityScreenText)
                if (detectedPhones.isNotEmpty()) {
                  lookupPhonesAndShowResult(detectedPhones)
                  return@addOnFailureListener
                }
              }
              showLoadingOrMessageOverlay(
                isLoading = false,
                message = "تعذر قراءة الأرقام من الشاشة، حاول الاقتراب من الرقم وإعادة المحاولة."
              )
            }
        } else {
          recycleBitmaps()
          if (accessibilityScreenText.isNotBlank()) {
            val detectedPhones = OcrHelper.extractAllPhoneNumbers(accessibilityScreenText)
            if (detectedPhones.isNotEmpty()) {
              lookupPhonesAndShowResult(detectedPhones)
              return@addOnFailureListener
            }
          }
          showLoadingOrMessageOverlay(
            isLoading = false,
            message = "تعذر قراءة الأرقام من الشاشة، حاول الاقتراب من الرقم وإعادة المحاولة."
          )
        }
      }
  }

  private fun lookupPhonesAndShowResult(phones: List<String>) {
    if (phones.isEmpty()) {
      showLoadingOrMessageOverlay(
        isLoading = false,
        message = "لم يتم العثور على أي رقم هاتف زبون واضح في هذه الشاشة."
      )
      return
    }

    serviceScope.launch {
      val repo = DeliveryRepository.getInstance(applicationContext)
      val matchedCustomers = mutableListOf<CustomerRecord>()
      val unregisteredPhones = mutableListOf<String>()
      val suspiciousMatches = mutableListOf<SuspiciousCustomer>()

      withContext(Dispatchers.IO) {
        for (phone in phones) {
          repo?.findSuspiciousByPhone(phone)?.let { sus ->
            if (suspiciousMatches.none { it.phone == sus.phone }) {
              suspiciousMatches.add(sus)
            }
          }
          val found = repo?.findCustomerByPhone(phone)
          if (found != null && found.writtenAddress.isNotBlank()) {
            matchedCustomers.add(found)
          } else {
            unregisteredPhones.add(phone)
          }
        }
      }

      if (suspiciousMatches.isNotEmpty()) {
        VibrationHelper.vibrateSuspiciousAlert(this@FloatingLensService)
      }

      showCustomerResultsOverlay(
        suspicious = suspiciousMatches,
        matched = matchedCustomers,
        unregistered = unregisteredPhones
      )
    }
  }

  private fun showLoadingOrMessageOverlay(isLoading: Boolean, message: String) {
    val card = buildBaseResultCard { contentLayout ->
      if (isLoading) {
        val row = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          layoutDirection = View.LAYOUT_DIRECTION_RTL
          setPadding(0, dp(8), 0, dp(8))
        }
        val spinner = ProgressBar(this).apply {
          layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginEnd = dp(10)
          }
        }
        val label = TextView(this).apply {
          text = message
          setTextColor(Color.parseColor("#1E293B"))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        row.addView(spinner)
        row.addView(label)
        contentLayout.addView(row)
      } else {
        val msgView = TextView(this).apply {
          text = message
          setTextColor(Color.parseColor("#475569"))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
          setPadding(0, dp(4), 0, dp(10))
        }
        contentLayout.addView(msgView)

        val retryBtn = createActionButton(
          title = "إعادة الفحص الآن",
          bgColorHex = "#0052CC",
          textColorHex = "#FFFFFF"
        ) {
          triggerScreenScan()
        }
        contentLayout.addView(retryBtn)
      }
    }
    displayResultOverlay(card)
  }

  private fun showCustomerResultsOverlay(
    suspicious: List<SuspiciousCustomer>,
    matched: List<CustomerRecord>,
    unregistered: List<String>
  ) {
    val card = buildBaseResultCard { contentLayout ->

      if (suspicious.isNotEmpty()) {
        suspicious.forEach { sus ->
          val alertBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
              cornerRadius = dp(12).toFloat()
              setColor(Color.parseColor("#FEF2F2"))
              setStroke(dp(2), Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
              bottomMargin = dp(8)
            }
          }

          val alertTitle = TextView(this).apply {
            text = "⚠️ تحذير: زبون مشبوه بسرقة الطلبات!"
            setTextColor(Color.parseColor("#991B1B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
          }
          alertBox.addView(alertTitle)

          val alertPhone = TextView(this).apply {
            text = "الرقم: ${sus.originalPhone.ifBlank { sus.phone }}"
            setTextColor(Color.parseColor("#B91C1C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(2))
          }
          alertBox.addView(alertPhone)

          val alertNote = TextView(this).apply {
            text = "الملاحظة: ${sus.note}"
            setTextColor(Color.parseColor("#7F1D1D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
          }
          alertBox.addView(alertNote)

          contentLayout.addView(alertBox)
        }
      }

      if (matched.isNotEmpty()) {
        matched.forEach { customer ->
          val itemBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
              cornerRadius = dp(12).toFloat()
              setColor(Color.parseColor("#ECFDF5"))
              setStroke(dp(1), Color.parseColor("#6EE7B7"))
            }
            layoutParams = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
              bottomMargin = dp(8)
            }
          }

          val badge = TextView(this).apply {
            text = "✓ رقم مسجل مسبقاً في قاعدة البيانات"
            setTextColor(Color.parseColor("#047857"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
          }
          itemBox.addView(badge)

          val phoneView = TextView(this).apply {
            text = customer.originalPhone.ifBlank { customer.phone }
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
          }
          itemBox.addView(phoneView)

          val fullAddr = buildString {
            append(customer.writtenAddress)
            if (customer.areaSubtitle.isNotBlank()) {
              append(" — ")
              append(customer.areaSubtitle)
            }
          }
          val addrView = TextView(this).apply {
            text = "العنوان: $fullAddr"
            setTextColor(Color.parseColor("#064E3B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(8))
          }
          itemBox.addView(addrView)

          val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT
            )
          }

          val copyBtn = createActionButton(
            title = "نسخ العنوان",
            bgColorHex = "#059669",
            textColorHex = "#FFFFFF",
            layoutWeight = 1f
          ) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("customer_address", fullAddr))
            Toast.makeText(this, "تم نسخ العنوان بنجاح", Toast.LENGTH_SHORT).show()
          }
          actionsRow.addView(copyBtn)

          if (customer.latitude != null && customer.longitude != null) {
            val spacer = View(this).apply {
              layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
            }
            actionsRow.addView(spacer)

            val mapBtn = createActionButton(
              title = "فتح الخريطة",
              bgColorHex = "#0284C7",
              textColorHex = "#FFFFFF",
              layoutWeight = 1f
            ) {
              val uri = Uri.parse("geo:${customer.latitude},${customer.longitude}?q=${customer.latitude},${customer.longitude}")
              val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              runCatching { startActivity(mapIntent) }
              dismissResultOverlay()
            }
            actionsRow.addView(mapBtn)
          }

          itemBox.addView(actionsRow)
          contentLayout.addView(itemBox)
        }
      }

      if (unregistered.isNotEmpty()) {
        unregistered.forEach { rawPhone ->
          val unregBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
              cornerRadius = dp(12).toFloat()
              setColor(Color.parseColor("#FFFBEB"))
              setStroke(dp(1), Color.parseColor("#FCD34D"))
            }
            layoutParams = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
              bottomMargin = dp(8)
            }
          }

          val statusLabel = TextView(this).apply {
            text = "رقم غير مسجل مسبقاً في قاعدة البيانات:"
            setTextColor(Color.parseColor("#B45309"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
          }
          unregBox.addView(statusLabel)

          val phoneLabel = TextView(this).apply {
            text = rawPhone
            setTextColor(Color.parseColor("#1E293B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
          }
          unregBox.addView(phoneLabel)

          val registerBtn = createActionButton(
            title = "تسجيل رقم وعنوان هذا الزبون",
            bgColorHex = "#0052CC",
            textColorHex = "#FFFFFF"
          ) {
            dismissResultOverlay()
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
              putExtra(EXTRA_PREFILL_CUSTOMER_PHONE, rawPhone)
            }
            startActivity(openAppIntent)
          }
          unregBox.addView(registerBtn)
          contentLayout.addView(unregBox)
        }
      }

      val rescanBtn = createActionButton(
        title = "فحص شاشة أخرى",
        bgColorHex = "#E2E8F0",
        textColorHex = "#1E293B"
      ) {
        triggerScreenScan()
      }
      contentLayout.addView(rescanBtn)
    }

    displayResultOverlay(card)
  }

  private fun buildBaseResultCard(populateContent: (LinearLayout) -> Unit): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      setPadding(dp(14), dp(12), dp(14), dp(14))
      background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.parseColor("#CBD5E1"))
      }
      elevation = dp(12).toFloat()
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        bottomMargin = dp(10)
      }
    }

    val titleView = TextView(this).apply {
      text = "العدسة العائمة • قاعدة البيانات"
      setTextColor(Color.parseColor("#0F172A"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    header.addView(titleView)

    val closeBtn = TextView(this).apply {
      text = "✕ إغلاق"
      setTextColor(Color.parseColor("#DC2626"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(10), dp(6), dp(10), dp(6))
      background = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.parseColor("#FEE2E2"))
      }
      setOnClickListener { dismissResultOverlay() }
    }
    header.addView(closeBtn)
    root.addView(header)

    val scrollView = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      )
    }
    val contentLayout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    populateContent(contentLayout)
    scrollView.addView(contentLayout)
    root.addView(scrollView)
    return root
  }

  private fun createActionButton(
    title: String,
    bgColorHex: String,
    textColorHex: String,
    layoutWeight: Float? = null,
    onClick: () -> Unit
  ): TextView {
    return TextView(this).apply {
      text = title
      setTextColor(Color.parseColor(textColorHex))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(12), dp(9), dp(12), dp(9))
      background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor(bgColorHex))
      }
      layoutParams = if (layoutWeight != null) {
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, layoutWeight)
      } else {
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        )
      }
      setOnClickListener { onClick() }
    }
  }

  private fun displayResultOverlay(view: View) {
    dismissResultOverlay()
    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val width = (screenWidth * 0.90f).toInt().coerceAtMost(dp(420))
    val params = WindowManager.LayoutParams(
      width,
      WindowManager.LayoutParams.WRAP_CONTENT,
      overlayType,
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.CENTER
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }
    resultOverlayView = view
    runCatching { windowManager.addView(view, params) }
  }

  private fun dismissResultOverlay() {
    resultOverlayView?.let { view ->
      runCatching { windowManager.removeView(view) }
    }
    resultOverlayView = null
  }

  private fun buildNotification(): Notification {
    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val openPending = PendingIntent.getActivity(
      this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val stopIntent = Intent(this, FloatingLensService::class.java).apply {
      action = ACTION_STOP_LENS
    }
    val stopPending = PendingIntent.getService(
      this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("العدسة العائمة نشطة")
      .setContentText("اضغط على العدسة العائمة فوق أي تطبيق للتعرف على الأرقام وعرض العنوان")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(openPending)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف العدسة", stopPending)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "العدسة العائمة للتعرف على الأرقام",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "إشعار استمرار عمل العدسة العائمة فوق التطبيقات"
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

  override fun onDestroy() {
    _isRunningFlow.value = false
    dismissResultOverlay()
    bubbleView?.let { view ->
      runCatching { windowManager.removeView(view) }
    }
    bubbleView = null
    virtualDisplay?.release()
    virtualDisplay = null
    imageReader?.close()
    imageReader = null
    mediaProjection?.unregisterCallback(projectionCallback)
    mediaProjection?.stop()
    mediaProjection = null
    textRecognizer.close()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val CHANNEL_ID = "floating_lens_channel"
    const val NOTIFICATION_ID = 4041
    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_PREFILL_CUSTOMER_PHONE = "extra_prefill_customer_phone"
    const val EXTRA_PREFILL_ORDER_PRICE = "extra_prefill_order_price"
    const val EXTRA_PREFILL_ORDER_PHONE = "extra_prefill_order_phone"
    const val ACTION_STOP_LENS = "com.example.action.STOP_FLOATING_LENS"

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()
  }
}
