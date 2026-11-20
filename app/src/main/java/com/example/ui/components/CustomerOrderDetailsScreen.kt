package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.CustomerRecord
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.model.NoteIconType
import com.example.model.SuspiciousCustomer
import com.example.util.OcrHelper
import com.example.util.OcrResult
import com.example.util.VibrationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class PhotoTarget {
  ISSUE,
  CONTENTS,
  EXTERNAL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerOrderDetailsScreen(
  stop: DeliveryStop,
  onBack: () -> Unit,
  onConfirmDelivery: (DeliveryStop) -> Unit,
  onScreenshotChanged: (String?) -> Unit,
  onApplyOcr: (OcrResult) -> Unit,
  onSaveOrderFields: (orderCode: String, p1: String, p2: String, notes: String, price: String) -> Unit,
  onIssuePhotoCaptured: (String?) -> Unit,
  onContentsPhotoCaptured: (String?) -> Unit = {},
  onExternalPhotoCaptured: (String?) -> Unit = {},
  onPreparePendingPhotoCapture: (targetName: String, uriString: String, filePath: String) -> Unit = { _, _, _ -> },
  onClearPendingPhotoCapture: (deleteEmptyFile: Boolean) -> Unit = {},
  onLaunchExternalCamera: ((targetName: String) -> Unit)? = null,
  onLaunchExternalGallery: ((targetName: String) -> Unit)? = null,
  onCustomerAddressSaved: (String, String) -> Unit = { _, _ -> },
  onDeleteOrder: (String) -> Unit = {},
  categories: List<DeliveryCategory> = emptyList(),
  onFindCustomerByPhone: (suspend (String) -> CustomerRecord?)? = null,
  suspiciousCustomers: List<SuspiciousCustomer> = emptyList(),
  onCategorySelected: ((DeliveryCategory) -> Unit)? = null,
  onStatusSelected: ((DeliveryStatus) -> Unit)? = null,
  isAppRefreshing: Boolean = false,
  refreshGeneration: Int = 0,
  onRefreshAndSave: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val coroutineScope = rememberCoroutineScope()

  // Editable fields: start empty or with stop's data
  var orderCodeNumber by remember(stop.id) { mutableStateOf(stop.orderCodeNumber) }
  var orderPrice by remember(stop.id) { mutableStateOf(OcrHelper.cleanPriceNumber(stop.price)) }
  var isEditPriceDialogOpen by remember { mutableStateOf(false) }
  var tempPriceInput by remember { mutableStateOf("") }
  var phone1 by remember(stop.id) { mutableStateOf(stop.phone1) }
  var phone2 by remember(stop.id) { mutableStateOf(stop.phone2) }
  var issueNotes by remember(stop.id) { mutableStateOf(stop.issueNotes) }
  var currentSelectedStatus by remember(stop.id) { mutableStateOf(stop.status) }
  var currentSelectedCategoryId by remember(stop.id) {
    mutableStateOf(if (stop.categoryId.isNotBlank()) stop.categoryId else stop.status.name)
  }

  LaunchedEffect(stop.id, stop.orderCodeNumber) {
    if (stop.orderCodeNumber.isNotBlank() && orderCodeNumber.trim() != stop.orderCodeNumber.trim()) {
      orderCodeNumber = stop.orderCodeNumber
    }
  }
  LaunchedEffect(stop.id, stop.price) {
    val cleanExternal = OcrHelper.cleanPriceNumber(stop.price)
    if (cleanExternal != OcrHelper.cleanPriceNumber(orderPrice)) {
      orderPrice = cleanExternal
    }
  }
  LaunchedEffect(stop.id, stop.phone1) {
    if (stop.phone1.isNotBlank() && phone1.trim() != stop.phone1.trim()) {
      phone1 = stop.phone1
    }
  }
  LaunchedEffect(stop.id, stop.phone2) {
    if (stop.phone2.isNotBlank() && phone2.trim() != stop.phone2.trim()) {
      phone2 = stop.phone2
    }
  }
  LaunchedEffect(stop.id, stop.issueNotes) {
    if (stop.issueNotes.isNotBlank() && issueNotes.trim() != stop.issueNotes.trim()) {
      issueNotes = stop.issueNotes
    }
  }
  LaunchedEffect(stop.id, stop.status) {
    if (currentSelectedStatus != stop.status) {
      currentSelectedStatus = stop.status
    }
  }
  LaunchedEffect(stop.id, stop.categoryId) {
    val resolvedCatId = if (stop.categoryId.isNotBlank()) stop.categoryId else stop.status.name
    if (currentSelectedCategoryId != resolvedCatId) {
      currentSelectedCategoryId = resolvedCatId
    }
  }

  // Customer Address: Starts from stop.title (empty on new order) and persists across recompositions
  var customerAddress by remember(stop.id) {
    mutableStateOf(stop.title)
  }
  var customerAreaSubtitle by remember(stop.id) {
    mutableStateOf(stop.subtitle)
  }
  LaunchedEffect(stop.id, stop.title) {
    if (stop.title.isNotBlank() && customerAddress != stop.title) {
      customerAddress = stop.title
    }
  }
  LaunchedEffect(stop.id, stop.subtitle) {
    if (stop.subtitle.isNotBlank() && customerAreaSubtitle != stop.subtitle) {
      customerAreaSubtitle = stop.subtitle
    }
  }
  var isEditAddressDialogOpen by remember { mutableStateOf(false) }
  var tempAddressInput by remember { mutableStateOf("") }
  var tempAreaInput by remember { mutableStateOf("") }

  // Matched customer from Firestore / database sync
  var matchedCustomer by remember { mutableStateOf<CustomerRecord?>(null) }
  var hasAutoFilledForPhone by remember(stop.id) { mutableStateOf(stop.title.isNotBlank()) }
  var isScanningPhone1Qr by remember { mutableStateOf(false) }
  var isScanningPhone2Qr by remember { mutableStateOf(false) }

  // Function to search customer database and auto-fill address if found
  fun searchCustomerAndAutoPull(phoneToSearch: String) {
    if (onFindCustomerByPhone == null) return
    val clean = CustomerRecord.normalizePhoneNumber(phoneToSearch)
    if (clean.length < 7) {
      matchedCustomer = null
      return
    }

    coroutineScope.launch {
      val found = onFindCustomerByPhone(clean)
      matchedCustomer = found
      if (found != null && customerAddress.isBlank()) {
        customerAddress = found.writtenAddress
        if (found.areaSubtitle.isNotBlank()) {
          customerAreaSubtitle = found.areaSubtitle
        }
        onCustomerAddressSaved(found.writtenAddress, found.areaSubtitle)
        hasAutoFilledForPhone = true
        Toast.makeText(context, "🌟 تم سحب عنوان الزبون تلقائياً من قاعدة البيانات!", Toast.LENGTH_LONG).show()
      }
    }
  }

  // Auto-search on phone change
  LaunchedEffect(phone1, phone2) {
    val targetPhone = if (phone1.isNotBlank()) phone1 else phone2
    if (targetPhone.isNotBlank()) {
      searchCustomerAndAutoPull(targetPhone)
    } else {
      matchedCustomer = null
    }
  }

  // Suspicious customer detection & phone vibration alert
  val matchedSuspiciousCustomer = remember(phone1, phone2, suspiciousCustomers) {
    val norm1 = CustomerRecord.normalizePhoneNumber(phone1)
    val norm2 = CustomerRecord.normalizePhoneNumber(phone2)
    suspiciousCustomers.firstOrNull { sus ->
      (norm1.length >= 7 && sus.phone == norm1) ||
        (norm2.length >= 7 && sus.phone == norm2)
    }
  }
  var lastVibratedSuspiciousPhone by remember(stop.id) { mutableStateOf<String?>(null) }
  LaunchedEffect(matchedSuspiciousCustomer?.phone) {
    val susPhone = matchedSuspiciousCustomer?.phone
    if (susPhone != null && susPhone != lastVibratedSuspiciousPhone) {
      VibrationHelper.vibrateSuspiciousAlert(context)
      lastVibratedSuspiciousPhone = susPhone
    } else if (susPhone == null) {
      lastVibratedSuspiciousPhone = null
    }
  }

  var isOcrLoading by remember { mutableStateOf(false) }

  // Automatically read attached/shared screenshot once on attachment/change (runs off main thread)
  LaunchedEffect(stop.id, stop.screenshotUriString, stop.isOcrProcessed) {
    val uriStr = stop.screenshotUriString
    if (!uriStr.isNullOrBlank() && !stop.isOcrProcessed && !isOcrLoading) {
      isOcrLoading = true
      val ocrResult = withContext(Dispatchers.Default) {
        OcrHelper.extractFromImage(context, Uri.parse(uriStr))
      }
      isOcrLoading = false
      ocrResult.orderNumber?.takeIf { orderCodeNumber.isBlank() }?.let { orderCodeNumber = it }
      ocrResult.phone1?.takeIf { phone1.isBlank() }?.let { phone1 = it }
      ocrResult.phone2?.takeIf { phone2.isBlank() }?.let { phone2 = it }
      ocrResult.price?.takeIf { orderPrice.isBlank() }?.let { extracted ->
        orderPrice = extracted
        tempPriceInput = extracted
        Toast.makeText(context, "✓ تم سحب سعر الطلب ($extracted) تلقائياً إلى حقل السعر", Toast.LENGTH_SHORT).show()
      }
      onSaveOrderFields(orderCodeNumber, phone1, phone2, issueNotes, orderPrice)
      onApplyOcr(ocrResult)
    }
  }

  // Screenshot Picker (Gallery or external shared files) with full HD preservation
  val screenshotPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      val hdUri = copyUriToHdFile(context, uri)
      onScreenshotChanged(hdUri.toString())
      Toast.makeText(context, "تم إرفاق لقطة الشاشة بجودة HD الكاملة بنجاح!", Toast.LENGTH_SHORT).show()
    }
  }

  // Dialog state for sending report via WhatsApp
  var isSendReportDialogOpen by remember { mutableStateOf(false) }
  var reportRecipientPhone by remember { mutableStateOf("") }

  // Dialog state for editing order number
  var isEditOrderNumberDialogOpen by remember { mutableStateOf(false) }
  var tempOrderNumberInput by remember { mutableStateOf("") }

  // Dialog state for photo selection (Camera vs Gallery) - persisted across Activity recreation
  var isPhotoSourceDialogOpen by remember { mutableStateOf(false) }
  var currentPhotoTarget by rememberSaveable { mutableStateOf(PhotoTarget.ISSUE) }

  // State for Full Screen Image Preview Modal
  var fullScreenImagePreview by remember { mutableStateOf<Pair<Any, String>?>(null) }

  // State for Delete Order & Delete Photo confirmation dialogs
  var isConfirmDeleteDialogOpen by remember { mutableStateOf(false) }
  var pendingDeletePhotoType by remember { mutableStateOf<String?>(null) }

  // Temporary storage URI & path for Full Resolution HD Camera capture (survives Activity recreation)
  var pendingHdCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingHdCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

  // 2. Full Resolution HD Camera Photo capture for issues, contents, and external package
  val takePhotoLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { isSuccess: Boolean ->
    val capturedUriStr = pendingHdCameraUriString
    val capturedPath = pendingHdCameraFilePath
    pendingHdCameraUriString = null
    pendingHdCameraFilePath = null

    val hasValidFile = capturedPath.isNullOrBlank() ||
      File(capturedPath).let { it.exists() && it.length() > 0L }

    if (isSuccess && capturedUriStr != null && hasValidFile) {
      when (currentPhotoTarget) {
        PhotoTarget.CONTENTS -> {
          onContentsPhotoCaptured(capturedUriStr)
          Toast.makeText(context, "تم التقاط وحفظ صورة محتويات الطلب بدقة HD بنجاح!", Toast.LENGTH_SHORT).show()
        }
        PhotoTarget.EXTERNAL -> {
          onExternalPhotoCaptured(capturedUriStr)
          Toast.makeText(context, "تم التقاط وحفظ صورة شكل الطلب الخارجي بدقة HD بنجاح!", Toast.LENGTH_SHORT).show()
        }
        PhotoTarget.ISSUE -> {
          onIssuePhotoCaptured(capturedUriStr)
          Toast.makeText(context, "تم التقاط وحفظ صورة المشكلة بدقة HD بنجاح!", Toast.LENGTH_SHORT).show()
        }
      }
      onClearPendingPhotoCapture(false)
    } else {
      if (!capturedPath.isNullOrBlank()) {
        runCatching {
          val emptyFile = File(capturedPath)
          if (emptyFile.exists() && emptyFile.length() == 0L) emptyFile.delete()
        }
      }
      onClearPendingPhotoCapture(true)
    }
  }

  fun launchHdCamera() {
    val externalLauncher = onLaunchExternalCamera
    if (externalLauncher != null) {
      externalLauncher.invoke(currentPhotoTarget.name)
      return
    }
    try {
      val (uri, filePath) = createHdOutputTarget(context, "hd_camera")
      pendingHdCameraUriString = uri.toString()
      pendingHdCameraFilePath = filePath
      onPreparePendingPhotoCapture(currentPhotoTarget.name, uri.toString(), filePath)
      takePhotoLauncher.launch(uri)
    } catch (e: Exception) {
      onClearPendingPhotoCapture(true)
      Toast.makeText(context, "تعذر تشغيل الكاميرا بدقة HD: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  // Camera permission launcher
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    if (isGranted) {
      launchHdCamera()
    } else {
      Toast.makeText(context, "يرجى منح إذن استخدام الكاميرا لالتقاط الصور بدقة HD", Toast.LENGTH_SHORT).show()
    }
  }

  // Gallery / Studio photo picker with full HD preservation
  val issueGalleryPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      val hdUri = copyUriToHdFile(context, uri)
      when (currentPhotoTarget) {
        PhotoTarget.CONTENTS -> {
          onContentsPhotoCaptured(hdUri.toString())
          Toast.makeText(context, "تم استيراد صورة محتويات الطلب بجودة HD كاملة من الاستوديو", Toast.LENGTH_SHORT).show()
        }
        PhotoTarget.EXTERNAL -> {
          onExternalPhotoCaptured(hdUri.toString())
          Toast.makeText(context, "تم استيراد صورة شكل الطلب الخارجي بجودة HD كاملة من الاستوديو", Toast.LENGTH_SHORT).show()
        }
        PhotoTarget.ISSUE -> {
          onIssuePhotoCaptured(hdUri.toString())
          Toast.makeText(context, "تم استيراد صورة المشكلة بجودة HD كاملة من الاستوديو", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  fun launchGalleryForTarget(targetName: String) {
    val externalGallery = onLaunchExternalGallery
    if (externalGallery != null) {
      externalGallery.invoke(targetName)
    } else if (targetName == "SCREENSHOT") {
      screenshotPickerLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
      )
    } else {
      issueGalleryPickerLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
      )
    }
  }

  // Sync edits when fields change
  fun syncFields(
    newOrder: String = orderCodeNumber,
    newP1: String = phone1,
    newP2: String = phone2,
    newNotes: String = issueNotes,
    newPrice: String = orderPrice
  ) {
    onSaveOrderFields(newOrder, newP1, newP2, newNotes, newPrice)
  }

  // ==========================================
  // UI Structure
  // ==========================================
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFFF8FAFC))
      .statusBarsPadding()
  ) {
    // 1. Clean Top Bar with Back Button and Title
    Surface(
      color = Color.White,
      shadowElevation = 1.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = {
            syncFields()
            if (customerAddress != stop.title || customerAreaSubtitle != stop.subtitle) {
              onCustomerAddressSaved(customerAddress, customerAreaSubtitle)
            }
            onBack()
          },
          modifier = Modifier
            .size(40.dp)
            .testTag("btn_back_from_details")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "رجوع",
            tint = Color(0xFF0F172A),
            modifier = Modifier.size(22.dp)
          )
        }

        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 6.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = if (customerAddress.isNotBlank()) customerAddress else "عنوان الزبون",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          if (customerAreaSubtitle.isNotBlank()) {
            Text(
              text = customerAreaSubtitle,
              fontSize = 12.sp,
              color = Color(0xFF64748B),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        // Top-left actions (Delete + Small Dual-Semicircle Refresh Button at outermost left in RTL)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          IconButton(
            onClick = { isConfirmDeleteDialogOpen = true },
            modifier = Modifier
              .size(40.dp)
              .testTag("btn_delete_order_top")
          ) {
            Icon(
              imageVector = Icons.Outlined.Delete,
              contentDescription = "حذف الطلب",
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(21.dp)
            )
          }

          RefreshSyncButton(
            isRefreshing = isAppRefreshing,
            refreshGeneration = refreshGeneration,
            onClick = {
              syncFields()
              if (customerAddress != stop.title || customerAreaSubtitle != stop.subtitle) {
                onCustomerAddressSaved(customerAddress, customerAreaSubtitle)
              }
              onRefreshAndSave()
            },
            testTagName = "btn_refresh_order_details",
            buttonDiameter = 30.dp,
            iconSize = 17.dp
          )
        }
      }
    }

    // 2. Order Details Content (Starts immediately with Order Number and Fields)
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // ==========================================
        // 1. مكان لقطة الشاشة (بدون أزرار إضافية — قراءة OCR تلقائية فور وضع الصورة)
        // ==========================================
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color.White,
          shadowElevation = 1.dp,
          border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
          modifier = Modifier.fillMaxWidth().testTag("container_work_screenshot")
        ) {
          Box(modifier = Modifier.padding(10.dp)) {
            // Image Area: Real Image or Empty Placeholder
            if (stop.screenshotUriString != null) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(180.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(Color(0xFF0F172A))
                  .clickable {
                    fullScreenImagePreview = (Uri.parse(stop.screenshotUriString) to "لقطة الشاشة من تطبيق العمل")
                  }
                  .testTag("preview_work_screenshot"),
                contentAlignment = Alignment.Center
              ) {
                val screenshotRequest = remember(stop.screenshotUriString) {
                  ImageRequest.Builder(context)
                    .data(Uri.parse(stop.screenshotUriString))
                    .size(1024, 1024)
                    .crossfade(true)
                    .build()
                }
                AsyncImage(
                  model = screenshotRequest,
                  contentDescription = "لقطة الشاشة من تطبيق العمل",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize()
                )

                // HD Badge on Screenshot
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color(0xFF0284C7).copy(alpha = 0.9f),
                  modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                  ) {
                    Icon(Icons.Default.Hd, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text("دقة HD كاملة", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                  }
                }

                // Subtle inline indicator ONLY while automatic OCR is running
                if (isOcrLoading) {
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF00875A).copy(alpha = 0.92f),
                    modifier = Modifier
                      .align(Alignment.TopCenter)
                      .padding(top = 6.dp)
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                      CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.8.dp
                      )
                      Text(
                        text = "جاري القراءة تلقائياً...",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                      )
                    }
                  }
                }

                Surface(
                  shape = CircleShape,
                  color = Color.Black.copy(alpha = 0.65f),
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { pendingDeletePhotoType = "SCREENSHOT" }
                    .testTag("btn_delete_screenshot_photo")
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.Close,
                      contentDescription = "حذف الصورة",
                      tint = Color.White,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }

                Surface(
                  color = Color.Black.copy(alpha = 0.7f),
                  shape = RoundedCornerShape(6.dp),
                  modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Icon(
                      imageVector = Icons.Default.Fullscreen,
                      contentDescription = null,
                      tint = Color.White,
                      modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = "اضغط لفتح الصورة بحجمها الكامل 🔍",
                      color = Color.White,
                      fontSize = 10.5.sp,
                      fontWeight = FontWeight.Medium
                    )
                  }
                }
              }
            } else {
              // Empty Placeholder Box
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(130.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(Color(0xFFF8FAFC))
                  .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(12.dp))
                  .clickable { launchGalleryForTarget("SCREENSHOT") }
                  .testTag("placeholder_empty_screenshot"),
                contentAlignment = Alignment.Center
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center,
                  modifier = Modifier.padding(12.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .size(40.dp)
                      .clip(CircleShape)
                      .background(Color(0xFFE2E8F0)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.AddPhotoAlternate,
                      contentDescription = null,
                      tint = Color(0xFF2563EB),
                      modifier = Modifier.size(22.dp)
                    )
                  }
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text = "مكان الصورة فارغ (بانتظار سكرين شوت)",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF334155),
                    textAlign = TextAlign.Center
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = "شارك لقطة شاشة من تطبيق العمل أو اضغط هنا للاختيار",
                    fontSize = 10.5.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center
                  )
                }
              }
            }
          }
        }

        // ==========================================
        // 1. Order Number Field (رقم الطلب - أفقي في الأعلى)
        // ==========================================
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("row_order_number"),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Action Buttons: Copy and Edit
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = {
                if (orderCodeNumber.isNotBlank()) {
                  clipboardManager.setText(AnnotatedString(orderCodeNumber))
                  Toast.makeText(context, "تم نسخ رقم الطلب: $orderCodeNumber", Toast.LENGTH_SHORT).show()
                } else {
                  Toast.makeText(context, "رقم الطلب فارغ، يرجى كتابته أولاً", Toast.LENGTH_SHORT).show()
                }
              },
              modifier = Modifier.size(28.dp).testTag("btn_copy_order_number")
            ) {
              Icon(Icons.Outlined.ContentCopy, contentDescription = "نسخ رقم الطلب", tint = Color(0xFF0052CC), modifier = Modifier.size(16.dp))
            }

            IconButton(
              onClick = {
                tempOrderNumberInput = orderCodeNumber
                isEditOrderNumberDialogOpen = true
              },
              modifier = Modifier.size(28.dp).testTag("btn_edit_order_number")
            ) {
              Icon(Icons.Outlined.Edit, contentDescription = "تعديل رقم الطلب", tint = Color(0xFF0052CC), modifier = Modifier.size(16.dp))
            }
          }

          // Order Number Badge and Label
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = if (orderCodeNumber.isNotBlank()) Color(0xFF0052CC) else Color(0xFFE2E8F0),
              modifier = Modifier.clickable {
                tempOrderNumberInput = orderCodeNumber
                isEditOrderNumberDialogOpen = true
              }
            ) {
              Text(
                text = if (orderCodeNumber.isNotBlank()) orderCodeNumber else "أدخل رقم الطلب",
                color = if (orderCodeNumber.isNotBlank()) Color.White else Color(0xFF64748B),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
              )
            }

            Text(
              text = "رقم الطلب",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1E293B)
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 2. Order Price Field (سعر الطلب - أفقي أسفل رقم الطلب)
        // ==========================================
        val isNegativePrice = orderPrice.startsWith("-")
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("row_order_price"),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Action Button: Edit only
          IconButton(
            onClick = {
              tempPriceInput = orderPrice
              isEditPriceDialogOpen = true
            },
            modifier = Modifier.size(28.dp).testTag("btn_edit_price")
          ) {
            Icon(
              imageVector = Icons.Outlined.Edit,
              contentDescription = "تعديل سعر الطلب",
              tint = if (isNegativePrice) Color(0xFFDC2626) else Color(0xFF16A34A),
              modifier = Modifier.size(16.dp)
            )
          }

          // Order Price Badge and Label
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = when {
                isNegativePrice -> Color(0xFFDC2626)
                orderPrice.isNotBlank() -> Color(0xFF16A34A)
                else -> Color(0xFFF1F5F9)
              },
              border = BorderStroke(
                1.dp,
                when {
                  isNegativePrice -> Color(0xFFB91C1C)
                  orderPrice.isNotBlank() -> Color(0xFF15803D)
                  else -> Color(0xFFCBD5E1)
                }
              ),
              modifier = Modifier
                .testTag("badge_order_price")
                .clickable {
                  tempPriceInput = orderPrice
                  isEditPriceDialogOpen = true
                }
            ) {
              Text(
                text = if (orderPrice.isNotBlank()) orderPrice else "أدخل السعر",
                color = if (orderPrice.isNotBlank()) Color.White else Color(0xFF64748B),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                style = if (isNegativePrice) {
                  LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
                } else {
                  LocalTextStyle.current
                },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
              )
            }

            Text(
              text = if (isNegativePrice) "مبلغ للزبون (-)" else "سعر الطلب",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = if (isNegativePrice) Color(0xFFB91C1C) else Color(0xFF1E293B)
            )
          }
        }

        // ==========================================
        // Customer Address (عنوان الزبون: فارغ والإدخال يدوي فقط)
        // ==========================================
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFFF8FAFC),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f)
            ) {
              // Copy Button
              IconButton(
                onClick = {
                  if (customerAddress.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString(customerAddress))
                    Toast.makeText(context, "تم نسخ عنوان الزبون: $customerAddress", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(context, "عنوان الزبون فارغ، يرجى كتابته أولاً", Toast.LENGTH_SHORT).show()
                  }
                },
                modifier = Modifier.size(28.dp).testTag("btn_copy_address")
              ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "نسخ العنوان", tint = Color(0xFF0052CC), modifier = Modifier.size(16.dp))
              }

              // Edit Button (Manual Input Only)
              IconButton(
                onClick = {
                  tempAddressInput = customerAddress
                  isEditAddressDialogOpen = true
                },
                modifier = Modifier.size(28.dp).testTag("btn_edit_address")
              ) {
                Icon(Icons.Outlined.Edit, contentDescription = "تعديل العنوان", tint = Color(0xFF0052CC), modifier = Modifier.size(16.dp))
              }

              Spacer(modifier = Modifier.width(6.dp))

              Column(
                modifier = Modifier
                  .weight(1f)
                  .clickable {
                    tempAddressInput = customerAddress
                    isEditAddressDialogOpen = true
                  }
              ) {
                Text(
                  text = if (customerAddress.isNotBlank()) customerAddress else "أدخل عنوان الزبون يدوياً...",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (customerAddress.isNotBlank()) Color(0xFFBE123C) else Color(0xFF94A3B8),
                  modifier = Modifier.testTag("text_customer_address_value")
                )
                if (customerAddress.isBlank()) {
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = "انقر لتحديد أو كتابة عنوان الزبون",
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1)
                  )
                }
              }
            }

            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "عنوان الزبون",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "(يدوي فقط)",
                fontSize = 10.sp,
                color = Color(0xFF94A3B8)
              )
            }
          }
        }


        // Suspicious Customer Alert Banner (تحذير: زبون مشبوه بسرقة الطلبات)
        matchedSuspiciousCustomer?.let { suspicious ->
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFEF2F2),
            border = BorderStroke(1.5.dp, Color(0xFFDC2626)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("banner_suspicious_customer_warning")
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(Color(0xFFDC2626)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Filled.Warning,
                  contentDescription = "تحذير زبون مشبوه",
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
              }
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
              ) {
                Text(
                  text = "تحذير: هذا الزبون مشبوه بسرقة الطلبات!",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFF991B1B)
                )
                Text(
                  text = "الرقم المشبوه: ${suspicious.originalPhone.ifBlank { suspicious.phone }}",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFB91C1C)
                )
                if (suspicious.note.isNotBlank()) {
                  Text(
                    text = "الملاحظة: ${suspicious.note}",
                    fontSize = 12.sp,
                    color = Color(0xFF7F1D1D)
                  )
                }
              }
            }
          }
        }

        // ==========================================
        // Phone Number 1 Card (Empty by default, Editable, Copyable)
        // ==========================================
        EditablePhoneContactCard(
          label = "رقم الهاتف 1",
          phoneNumber = phone1,
          onPhoneNumberChanged = {
            phone1 = it
            syncFields(newP1 = it)
          },
          onCopy = {
            if (phone1.isNotBlank()) {
              clipboardManager.setText(AnnotatedString(phone1))
              Toast.makeText(context, "تم نسخ رقم الهاتف 1: $phone1", Toast.LENGTH_SHORT).show()
            } else {
              Toast.makeText(context, "رقم الهاتف 1 فارغ", Toast.LENGTH_SHORT).show()
            }
          },
          onCall = {
            if (phone1.isNotBlank()) {
              com.example.util.CallerAddressHelper.launchDialWithAddressOverlay(context, phone1)
            } else {
              Toast.makeText(context, "يرجى كتابة رقم الهاتف 1 للاتصال", Toast.LENGTH_SHORT).show()
            }
          },
          onWhatsApp = {
            if (phone1.isNotBlank()) {
              val cleanPhone = phone1.replace(" ", "").replace("+", "")
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanPhone"))
              try { context.startActivity(intent) } catch (_: Exception) {}
            } else {
              Toast.makeText(context, "يرجى كتابة رقم الهاتف 1 لفتح الواتساب", Toast.LENGTH_SHORT).show()
            }
          },
          onScanBarcode = { isScanningPhone1Qr = true }
        )

        // ==========================================
        // Phone Number 2 Card (Empty by default, Editable, Copyable)
        // ==========================================
        EditablePhoneContactCard(
          label = "رقم الهاتف 2",
          phoneNumber = phone2,
          onPhoneNumberChanged = {
            phone2 = it
            syncFields(newP2 = it)
          },
          onCopy = {
            if (phone2.isNotBlank()) {
              clipboardManager.setText(AnnotatedString(phone2))
              Toast.makeText(context, "تم نسخ رقم الهاتف 2: $phone2", Toast.LENGTH_SHORT).show()
            } else {
              Toast.makeText(context, "رقم الهاتف 2 فارغ", Toast.LENGTH_SHORT).show()
            }
          },
          onCall = {
            if (phone2.isNotBlank()) {
              com.example.util.CallerAddressHelper.launchDialWithAddressOverlay(context, phone2)
            } else {
              Toast.makeText(context, "يرجى كتابة رقم الهاتف 2 للاتصال", Toast.LENGTH_SHORT).show()
            }
          },
          onWhatsApp = {
            if (phone2.isNotBlank()) {
              val cleanPhone = phone2.replace(" ", "").replace("+", "")
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanPhone"))
              try { context.startActivity(intent) } catch (_: Exception) {}
            } else {
              Toast.makeText(context, "يرجى كتابة رقم الهاتف 2 لفتح الواتساب", Toast.LENGTH_SHORT).show()
            }
          },
          onScanBarcode = { isScanningPhone2Qr = true }
        )

        // ==========================================
        // Apply Matched Customer Action Button
        // ==========================================
        matchedCustomer?.let { customer ->
          Button(
            onClick = {
              customerAddress = customer.writtenAddress
              customerAreaSubtitle = customer.areaSubtitle
              onCustomerAddressSaved(customer.writtenAddress, customer.areaSubtitle)
              Toast.makeText(context, "تم تطبيق عنوان الزبون بنجاح!", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF00875A),
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 44.dp)
              .testTag("btn_apply_matched_customer")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "تطبيق العنوان الان",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }
          }
        }

        // ==========================================
        // Field Cargo Photos Section (صور الشحنة الميدانية)
        // ==========================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val cargoAttachedCount = listOfNotNull(
            stop.capturedContentsPhotoUri,
            stop.capturedExternalPhotoUri
          ).size
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = when (cargoAttachedCount) {
                2 -> Color(0xFFDCFCE7)
                1 -> Color(0xFFE0F2FE)
                else -> Color(0xFFF1F5F9)
              }
            ) {
              Text(
                text = when (cargoAttachedCount) {
                  2 -> "✓ مرفق صورتين"
                  1 -> "✓ مرفق صورة واحدة (1/2)"
                  else -> "لم يتم إرفاق صور"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = when (cargoAttachedCount) {
                  2 -> Color(0xFF16A34A)
                  1 -> Color(0xFF0284C7)
                  else -> Color(0xFF64748B)
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
              )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "صور الشحنة الميدانية",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Icon(Icons.Outlined.Image, contentDescription = null, tint = Color(0xFF1D4ED8), modifier = Modifier.size(16.dp))
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Photo 1: محتويات الطلب (Package Contents) - Camera Capture Allowed
            Box(
              modifier = Modifier
                .weight(1f)
                .height(118.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                .clickable {
                  val model = stop.capturedContentsPhotoUri?.let { Uri.parse(it) } ?: R.drawable.img_package_contents
                  fullScreenImagePreview = (model to "صورة محتويات الطلب")
                }
                .testTag("box_photo_contents")
            ) {
              if (stop.capturedContentsPhotoUri != null) {
                val contentsRequest = remember(stop.capturedContentsPhotoUri) {
                  ImageRequest.Builder(context)
                    .data(Uri.parse(stop.capturedContentsPhotoUri))
                    .size(1024, 1024)
                    .crossfade(true)
                    .build()
                }
                AsyncImage(
                  model = contentsRequest,
                  contentDescription = "محتويات الطلب الملتقطة",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )

                // HD Badge
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = Color(0xFF0284C7).copy(alpha = 0.9f),
                  modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 26.dp)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                  ) {
                    Icon(Icons.Default.Hd, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text("HD", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                  }
                }

                Surface(
                  shape = CircleShape,
                  color = Color.Black.copy(alpha = 0.65f),
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { pendingDeletePhotoType = "CONTENTS" }
                    .testTag("btn_delete_contents_photo")
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "حذف صورة المحتويات", tint = Color.White, modifier = Modifier.size(14.dp))
                  }
                }
              } else {
                Image(
                  painter = painterResource(id = R.drawable.img_package_contents),
                  contentDescription = "محتويات الطلب",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )
              }

              // Top camera badge button
              Surface(
                color = Color(0xFF0052CC).copy(alpha = 0.85f),
                shape = CircleShape,
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(6.dp)
                  .size(26.dp)
                  .clickable {
                    currentPhotoTarget = PhotoTarget.CONTENTS
                    isPhotoSourceDialogOpen = true
                  }
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(Icons.Default.CameraAlt, contentDescription = "التقاط محتويات بالكاميرا", tint = Color.White, modifier = Modifier.size(14.dp))
                }
              }

              // Bottom label
              Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(Icons.Outlined.ShoppingBag, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                  Spacer(modifier = Modifier.width(3.dp))
                  Text(
                    text = if (stop.capturedContentsPhotoUri != null) "محتويات (HD 🔍)" else "محتويات الطلب (HD 📷)",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }

            // Photo 2: شكل الطلب الخارجي (External Package) - Camera Capture Allowed
            Box(
              modifier = Modifier
                .weight(1f)
                .height(118.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                .clickable {
                  val model = stop.capturedExternalPhotoUri?.let { Uri.parse(it) } ?: R.drawable.img_package_external
                  fullScreenImagePreview = (model to "صورة شكل الطلب الخارجي")
                }
                .testTag("box_photo_external")
            ) {
              if (stop.capturedExternalPhotoUri != null) {
                val externalRequest = remember(stop.capturedExternalPhotoUri) {
                  ImageRequest.Builder(context)
                    .data(Uri.parse(stop.capturedExternalPhotoUri))
                    .size(1024, 1024)
                    .crossfade(true)
                    .build()
                }
                AsyncImage(
                  model = externalRequest,
                  contentDescription = "شكل الطلب الخارجي الملتقط",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )

                // HD Badge
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = Color(0xFF0284C7).copy(alpha = 0.9f),
                  modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 26.dp)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                  ) {
                    Icon(Icons.Default.Hd, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text("HD", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                  }
                }

                Surface(
                  shape = CircleShape,
                  color = Color.Black.copy(alpha = 0.65f),
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { pendingDeletePhotoType = "EXTERNAL" }
                    .testTag("btn_delete_external_photo")
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "حذف صورة الشكل الخارجي", tint = Color.White, modifier = Modifier.size(14.dp))
                  }
                }
              } else {
                Image(
                  painter = painterResource(id = R.drawable.img_package_external),
                  contentDescription = "شكل الطلب الخارجي",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )
              }

              // Top camera badge button
              Surface(
                color = Color(0xFF0052CC).copy(alpha = 0.85f),
                shape = CircleShape,
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(6.dp)
                  .size(26.dp)
                  .clickable {
                    currentPhotoTarget = PhotoTarget.EXTERNAL
                    isPhotoSourceDialogOpen = true
                  }
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(Icons.Default.CameraAlt, contentDescription = "التقاط شكل خارجي بالكاميرا", tint = Color.White, modifier = Modifier.size(14.dp))
                }
              }

              // Bottom label
              Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                  Spacer(modifier = Modifier.width(3.dp))
                  Text(
                    text = if (stop.capturedExternalPhotoUri != null) "شكل خارجي (HD 🔍)" else "شكل خارجي (HD 📷)",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }
        }

        // ==========================================
        // Issues Notes Section + Dedicated Empty Photo Field (Camera or Studio)
        // ==========================================
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "(اختياري)",
              fontSize = 11.sp,
              color = Color(0xFF94A3B8)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "ملاحظات المشاكل",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Icon(Icons.Outlined.EditNote, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
            }
          }

          OutlinedTextField(
            value = issueNotes,
            onValueChange = {
              issueNotes = it
              syncFields(newNotes = it)
            },
            placeholder = { Text("اكتب ملاحظات المشكلة أو طلب الزبون...") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            minLines = 2,
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color(0xFF1E293B),
              unfocusedTextColor = Color(0xFF1E293B),
              cursorColor = Color(0xFF1E293B),
              focusedBorderColor = Color(0xFF0052CC),
              unfocusedBorderColor = Color(0xFFCBD5E1)
            ),
            textStyle = TextStyle(
              color = Color(0xFF1E293B),
              fontSize = 13.5.sp,
              fontWeight = FontWeight.Medium
            )
          )

          // ==========================================
          // حقل صورة المشكلة المرفقة (فارغ - يمكن إضافة صورة فيه من خلال الكاميرا أو الاستوديو)
          // ==========================================
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF8FAFC),
            border = BorderStroke(1.dp, if (stop.capturedIssuePhotoUri != null) Color(0xFFBAE6FD) else Color(0xFFCBD5E1)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("container_issue_photo_field")
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              // Header
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    tint = if (stop.capturedIssuePhotoUri != null) Color(0xFF0052CC) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "صورة المشكلة المرفقة مع التقرير",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
                  )
                }

                if (stop.capturedIssuePhotoUri != null) {
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFDCFCE7)
                  ) {
                    Text(
                      text = "تم إرفاق صورة ✓",
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF16A34A),
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                  }
                } else {
                  Text(
                    text = "حقل صورة فارغ",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                  )
                }
              }

              if (stop.capturedIssuePhotoUri != null) {
                // Photo is captured: preview with full-screen click, change actions, and delete
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F172A))
                    .clickable {
                      fullScreenImagePreview = (Uri.parse(stop.capturedIssuePhotoUri) to "صورة المشكلة المرفقة")
                    }
                    .testTag("preview_issue_photo")
                ) {
                  val issueRequest = remember(stop.capturedIssuePhotoUri) {
                    ImageRequest.Builder(context)
                      .data(Uri.parse(stop.capturedIssuePhotoUri))
                      .size(1024, 1024)
                      .crossfade(true)
                      .build()
                  }
                  AsyncImage(
                    model = issueRequest,
                    contentDescription = "صورة المشكلة الملتقطة",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                  )

                  // HD Badge
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF0284C7).copy(alpha = 0.9f),
                    modifier = Modifier
                      .align(Alignment.TopStart)
                      .padding(6.dp)
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                      Icon(Icons.Default.Hd, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                      Text("دقة HD كاملة", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                  }

                  // Delete button (with confirmation)
                  Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                      .align(Alignment.TopEnd)
                      .padding(6.dp)
                      .size(28.dp)
                      .clip(CircleShape)
                      .clickable { pendingDeletePhotoType = "ISSUE" }
                      .testTag("btn_delete_issue_photo")
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "حذف الصورة",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                      )
                    }
                  }

                  // Click to view full size badge
                  Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                      .align(Alignment.BottomCenter)
                      .padding(bottom = 6.dp)
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text(
                        text = "اضغط لفتح الصورة بحجمها الكامل 🔍",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                      )
                    }
                  }
                }

                // Actions row to change from Camera or Studio
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  OutlinedButton(
                    onClick = {
                      currentPhotoTarget = PhotoTarget.ISSUE
                      cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                  ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تغيير (كاميرا HD)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                  }

                  OutlinedButton(
                    onClick = {
                      currentPhotoTarget = PhotoTarget.ISSUE
                      launchGalleryForTarget(PhotoTarget.ISSUE.name)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                  ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تغيير (استوديو HD)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                  }
                }
              } else {
                // Empty Photo Box with Direct Camera & Studio Buttons
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    Box(
                      modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFF6FF)),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(22.dp)
                      )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                      text = "حقل صورة فارغ لملاحظات المشكلة",
                      fontSize = 12.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF334155)
                    )

                    Text(
                      text = "أضف صورة للمشكلة من الكاميرا أو الاستوديو لإرفاقها بالتقرير",
                      fontSize = 11.sp,
                      color = Color(0xFF64748B),
                      textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      // Button 1: الكاميرا
                      Button(
                        onClick = {
                          currentPhotoTarget = PhotoTarget.ISSUE
                          cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(38.dp).testTag("btn_issue_camera")
                      ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("كاميرا HD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                      }

                      // Button 2: الاستوديو
                      Button(
                        onClick = {
                          currentPhotoTarget = PhotoTarget.ISSUE
                          launchGalleryForTarget(PhotoTarget.ISSUE.name)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(38.dp).testTag("btn_issue_studio")
                      ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("استوديو HD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                      }
                    }
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 9. Order Status Classification Section (نظام تصنيف حالة الطلب)
        // ==========================================
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color.White,
          border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("container_status_classification")
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Outlined.CheckCircle,
                  contentDescription = null,
                  tint = currentSelectedStatus.primaryColor,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = "نظام تصنيف وتوزيع الطلب",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF0F172A)
                )
              }

              val activeCat = categories.firstOrNull { it.id == currentSelectedCategoryId }
              val currentBadgeText = activeCat?.name ?: currentSelectedStatus.labelAr
              val currentBadgePrimaryColor = activeCat?.primaryColor ?: currentSelectedStatus.primaryColor
              val currentBadgeContainerColor = activeCat?.containerColor ?: currentSelectedStatus.containerColor
              val currentBadgeBorderColor = activeCat?.borderColor ?: currentSelectedStatus.borderColor

              // Active category tag badge
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = currentBadgeContainerColor,
                border = BorderStroke(1.dp, currentBadgeBorderColor)
              ) {
                Text(
                  text = currentBadgeText,
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = currentBadgePrimaryColor,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (categories.isNotEmpty()) {
              categories.chunked(3).forEachIndexed { rowIndex, rowCats ->
                if (rowIndex > 0) Spacer(modifier = Modifier.height(8.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  rowCats.forEach { cat ->
                    val isSelected = currentSelectedCategoryId == cat.id
                    Surface(
                      shape = RoundedCornerShape(10.dp),
                      color = if (isSelected) cat.containerColor else Color.White,
                      border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) cat.primaryColor else Color(0xFFE2E8F0)
                      ),
                      modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                          syncFields()
                          currentSelectedCategoryId = cat.id
                          val matchingStatus = runCatching { DeliveryStatus.valueOf(cat.id) }.getOrNull()
                          if (matchingStatus != null) {
                            currentSelectedStatus = matchingStatus
                          }
                          onCategorySelected?.invoke(cat)
                        }
                        .testTag("category_chip_${cat.id}")
                    ) {
                      Row(
                        modifier = Modifier
                          .fillMaxSize()
                          .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                      ) {
                        Box(
                          modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) cat.primaryColor else Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = cat.name,
                          fontSize = 11.5.sp,
                          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                          color = if (isSelected) cat.primaryColor else Color(0xFF334155),
                          maxLines = 1
                        )
                      }
                    }
                  }
                  val remaining = 3 - rowCats.size
                  repeat(remaining) {
                    Spacer(modifier = Modifier.weight(1f))
                  }
                }
              }
            } else {
              // Status Options Row 1 (3 items)
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                StatusClassificationChip(
                  status = DeliveryStatus.IN_TRANSIT,
                  isSelected = currentSelectedStatus == DeliveryStatus.IN_TRANSIT,
                  onClick = {
                    syncFields()
                    currentSelectedStatus = DeliveryStatus.IN_TRANSIT
                    currentSelectedCategoryId = DeliveryStatus.IN_TRANSIT.name
                    onStatusSelected?.invoke(DeliveryStatus.IN_TRANSIT)
                  },
                  modifier = Modifier.weight(1f)
                )
                StatusClassificationChip(
                  status = DeliveryStatus.DELIVERED,
                  isSelected = currentSelectedStatus == DeliveryStatus.DELIVERED,
                  onClick = {
                    syncFields()
                    currentSelectedStatus = DeliveryStatus.DELIVERED
                    currentSelectedCategoryId = DeliveryStatus.DELIVERED.name
                    onStatusSelected?.invoke(DeliveryStatus.DELIVERED)
                  },
                  modifier = Modifier.weight(1f)
                )
                StatusClassificationChip(
                  status = DeliveryStatus.DELAYED,
                  isSelected = currentSelectedStatus == DeliveryStatus.DELAYED,
                  onClick = {
                    syncFields()
                    currentSelectedStatus = DeliveryStatus.DELAYED
                    currentSelectedCategoryId = DeliveryStatus.DELAYED.name
                    onStatusSelected?.invoke(DeliveryStatus.DELAYED)
                  },
                  modifier = Modifier.weight(1f)
                )
              }

              Spacer(modifier = Modifier.height(8.dp))

              // Status Options Row 2 (2 items)
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                StatusClassificationChip(
                  status = DeliveryStatus.LINE_ISSUES,
                  isSelected = currentSelectedStatus == DeliveryStatus.LINE_ISSUES,
                  onClick = {
                    syncFields()
                    currentSelectedStatus = DeliveryStatus.LINE_ISSUES
                    currentSelectedCategoryId = DeliveryStatus.LINE_ISSUES.name
                    onStatusSelected?.invoke(DeliveryStatus.LINE_ISSUES)
                  },
                  modifier = Modifier.weight(1f)
                )
                StatusClassificationChip(
                  status = DeliveryStatus.RETURNED,
                  isSelected = currentSelectedStatus == DeliveryStatus.RETURNED,
                  onClick = {
                    syncFields()
                    currentSelectedStatus = DeliveryStatus.RETURNED
                    currentSelectedCategoryId = DeliveryStatus.RETURNED.name
                    onStatusSelected?.invoke(DeliveryStatus.RETURNED)
                  },
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons Row: Delete Order Button + Dynamic Status-Aware Confirm Delivery Button
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Delete Order Button inside Order
          Button(
            onClick = { isConfirmDeleteDialogOpen = true },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFFFEE2E2),
              contentColor = Color(0xFFDC2626)
            ),
            border = BorderStroke(1.2.dp, Color(0xFFFCA5A5)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("btn_delete_order_inside")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "حذف الطلب",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFDC2626)
              )
            }
          }

          val activeConfirmCat = categories.firstOrNull { it.id == currentSelectedCategoryId }
          val confirmLabel = activeConfirmCat?.name ?: currentSelectedStatus.labelAr
          val confirmBgColor = when {
            currentSelectedCategoryId == DeliveryStatus.DELIVERED.name -> Color(0xFF00875A)
            activeConfirmCat != null -> activeConfirmCat.primaryColor
            else -> currentSelectedStatus.primaryColor
          }
          // Big Confirm Delivery Button with dynamic status-adapted color and label
          Button(
            onClick = {
              if (orderCodeNumber.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(orderCodeNumber))
              }
              val finalStatus = currentSelectedStatus
              val finalNote = when (finalStatus) {
                DeliveryStatus.DELIVERED -> if (stop.note.isBlank() || stop.status != DeliveryStatus.DELIVERED) "تم التسليم بنجاح في يد المستلم" else stop.note
                DeliveryStatus.DELAYED -> if (stop.note.isBlank() || stop.status != DeliveryStatus.DELAYED) (if (issueNotes.isNotBlank()) "مؤجل: $issueNotes" else "مؤجل بطلب من العميل") else stop.note
                DeliveryStatus.RETURNED -> if (stop.note.isBlank() || stop.status != DeliveryStatus.RETURNED) (if (issueNotes.isNotBlank()) "راجع للشركة: $issueNotes" else "راجع للشركة / في طريق العودة") else stop.note
                DeliveryStatus.LINE_ISSUES -> if (stop.note.isBlank() || stop.status != DeliveryStatus.LINE_ISSUES) (if (issueNotes.isNotBlank()) "مشاكل خط: $issueNotes" else "مشاكل في خط السير والتسليم") else stop.note
                DeliveryStatus.IN_TRANSIT -> if (stop.note.isBlank() || stop.status != DeliveryStatus.IN_TRANSIT) "قيد التوصيل لدى المندوب" else stop.note
              }
              val finalNoteIcon = when (finalStatus) {
                DeliveryStatus.DELIVERED -> NoteIconType.CHECK
                DeliveryStatus.DELAYED -> NoteIconType.HISTORY
                DeliveryStatus.RETURNED -> NoteIconType.INFO
                DeliveryStatus.LINE_ISSUES -> NoteIconType.ROUTE
                DeliveryStatus.IN_TRANSIT -> NoteIconType.NAVIGATION
              }
              val chosenCat = categories.firstOrNull { it.id == currentSelectedCategoryId }
              val finalCategoryName = chosenCat?.name ?: finalStatus.labelAr
              val finalCategoryColor = chosenCat?.primaryColorHex

              onConfirmDelivery(
                stop.copy(
                  title = if (customerAddress.isNotBlank()) customerAddress else stop.title,
                  subtitle = if (customerAreaSubtitle.isNotBlank()) customerAreaSubtitle else stop.subtitle,
                  governorate = if (customerAddress.isNotBlank()) customerAddress else stop.governorate,
                  region = if (customerAreaSubtitle.isNotBlank()) customerAreaSubtitle else stop.region,
                  status = finalStatus,
                  categoryId = currentSelectedCategoryId,
                  categoryName = finalCategoryName,
                  categoryColorHex = finalCategoryColor,
                  note = finalNote,
                  noteIconType = finalNoteIcon,
                  orderCodeNumber = orderCodeNumber,
                  price = orderPrice,
                  phone1 = phone1,
                  phone2 = phone2,
                  issueNotes = issueNotes,
                  isNewOrder = false
                )
              )
              val msg = if (stop.isNewOrder) {
                "تمت إضافة الطلب الجديد بقائمة ($finalCategoryName) بنجاح!"
              } else {
                "تم حفظ وتحديث تصنيف الطلب ($finalCategoryName) بنجاح!"
              }
              Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = confirmBgColor
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1.8f)
              .height(48.dp)
              .testTag("btn_confirm_delivered_big")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (stop.isNewOrder) "✓ حفظ وإضافة ($confirmLabel)" else "✓ تأكيد وحفظ ($confirmLabel)",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }


        Spacer(modifier = Modifier.height(16.dp))
      }

    // 3. Bottom Bar
    Surface(
      color = Color(0xFF000000),
      modifier = Modifier.fillMaxWidth().navigationBarsPadding()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable {
            Toast.makeText(context, "تصدير بيانات الطلب للشركة", Toast.LENGTH_SHORT).show()
          }
        ) {
          Box {
            Icon(Icons.Outlined.Share, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(24.dp))
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = Color(0xFF2563EB),
              modifier = Modifier.offset(x = 10.dp, y = (-4).dp)
            ) {
              Text("نقل", color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text("تصدير الطلب", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .clickable {
              syncFields()
              isSendReportDialogOpen = true
            }
            .testTag("btn_send_report")
        ) {
          Box(
            modifier = Modifier
              .size(46.dp)
              .clip(CircleShape)
              .background(Color(0xFF22C55E)),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.Chat, contentDescription = "إرسال تقرير", tint = Color.White, modifier = Modifier.size(24.dp))
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text("إرسال تقرير", color = Color(0xFF16A34A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable {
            Toast.makeText(context, "استيراد طلبات جديدة من الإدارة", Toast.LENGTH_SHORT).show()
          }
        ) {
          Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(24.dp))
          Spacer(modifier = Modifier.height(4.dp))
          Text("استيراد طلب", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
      }
    }
  }

  // ==========================================
  // Dialogs
  // ==========================================
  // Send Report via WhatsApp Dialog
  if (isSendReportDialogOpen) {
    Dialog(onDismissRequest = { isSendReportDialogOpen = false }) {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
      ) {
        Column(
          modifier = Modifier.padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Header Row
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            IconButton(
              onClick = { isSendReportDialogOpen = false },
              modifier = Modifier.size(28.dp)
            ) {
              Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "إرسال تقرير عبر واتساب",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(CircleShape)
                  .background(Color(0xFF22C55E)),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
              }
            }
          }

          // Content Details Summary Box (Only order number, issue notes, high res photos)
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFF8FAFC),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              Text(
                text = "محتويات التقرير المرسلة:",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              )
              Text(
                text = "• رقم الطلب: ${if (orderCodeNumber.isNotBlank()) orderCodeNumber else stop.orderNumber}",
                fontSize = 11.5.sp,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "• ملاحظات المشاكل: ${if (issueNotes.isNotBlank()) issueNotes else "لا توجد ملاحظات مشاكل"}",
                fontSize = 11.sp,
                color = Color(0xFF475569)
              )
              val photoCount = listOfNotNull(
                stop.capturedExternalPhotoUri,
                stop.capturedContentsPhotoUri,
                stop.capturedIssuePhotoUri
              ).size
              Text(
                text = "• الصور المرفقة بدقة عالية: $photoCount صور (شكل الطلب، محتويات الطلب، صورة المشكلة)",
                fontSize = 11.sp,
                color = Color(0xFF16A34A),
                fontWeight = FontWeight.Medium
              )
              Text(
                text = "🔒 بدون إرسال عنوان الزبون وبدون أرقام الهواتف",
                fontSize = 10.5.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Phone Number Input for WhatsApp
          OutlinedTextField(
            value = reportRecipientPhone,
            onValueChange = { reportRecipientPhone = it },
            label = { Text("رقم الواتساب المستلم") },
            placeholder = { Text("مثال: 07701234567 أو 964...") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_report_whatsapp_number"),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color(0xFF1E293B),
              unfocusedTextColor = Color(0xFF1E293B),
              cursorColor = Color(0xFF1E293B),
              focusedBorderColor = Color(0xFF22C55E),
              focusedLabelColor = Color(0xFF16A34A)
            ),
            textStyle = TextStyle(
              color = Color(0xFF1E293B),
              fontSize = 13.5.sp,
              fontWeight = FontWeight.Medium
            ),
            leadingIcon = {
              Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
            }
          )

          // Button 1: Send directly to the specified number
          Button(
            onClick = {
              isSendReportDialogOpen = false
              launchWhatsAppReport(
                context = context,
                recipientPhone = reportRecipientPhone,
                orderNumber = orderCodeNumber.ifBlank { stop.orderCodeNumber.ifBlank { stop.orderNumber } },
                issueNotes = issueNotes.ifBlank { stop.issueNotes },
                externalPhotoUriString = stop.capturedExternalPhotoUri,
                contentsPhotoUriString = stop.capturedContentsPhotoUri,
                issuePhotoUriString = stop.capturedIssuePhotoUri
              )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp).testTag("btn_confirm_send_whatsapp")
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (reportRecipientPhone.isNotBlank()) "إرسال إلى هذا الرقم عبر واتساب" else "فتح واتساب وإرسال التقرير",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Button 2: Choose any contact/group from WhatsApp directly
          OutlinedButton(
            onClick = {
              isSendReportDialogOpen = false
              launchWhatsAppReport(
                context = context,
                recipientPhone = "", // Empty opens chat selector in WhatsApp
                orderNumber = orderCodeNumber.ifBlank { stop.orderCodeNumber.ifBlank { stop.orderNumber } },
                issueNotes = issueNotes.ifBlank { stop.issueNotes },
                externalPhotoUriString = stop.capturedExternalPhotoUri,
                contentsPhotoUriString = stop.capturedContentsPhotoUri,
                issuePhotoUriString = stop.capturedIssuePhotoUri
              )
            },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("btn_pick_whatsapp_contact")
          ) {
            Text(
              text = "أو اختيار محادثة من قائمة واتساب مباشرة",
              fontSize = 11.5.sp,
              color = Color(0xFF334155),
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }

  // Edit Order Number Dialog
  if (isEditOrderNumberDialogOpen) {
    Dialog(onDismissRequest = { isEditOrderNumberDialogOpen = false }) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Text("تعديل رقم الطلب", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))

          OutlinedTextField(
            value = tempOrderNumberInput,
            onValueChange = { tempOrderNumberInput = it },
            label = { Text("رقم الطلب") },
            placeholder = { Text("مثال: 163601793") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color(0xFF1E293B),
              unfocusedTextColor = Color(0xFF1E293B),
              cursorColor = Color(0xFF1E293B),
              focusedBorderColor = Color(0xFF0052CC),
              unfocusedBorderColor = Color(0xFFCBD5E1),
              focusedLabelColor = Color(0xFF0052CC),
              unfocusedLabelColor = Color(0xFF64748B),
              focusedPlaceholderColor = Color(0xFF94A3B8),
              unfocusedPlaceholderColor = Color(0xFF94A3B8)
            ),
            textStyle = TextStyle(
              color = Color(0xFF1E293B),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium
            )
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
          ) {
            TextButton(onClick = { isEditOrderNumberDialogOpen = false }) {
              Text("إلغاء", color = Color(0xFF64748B))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                orderCodeNumber = tempOrderNumberInput.trim()
                syncFields(newOrder = orderCodeNumber)
                isEditOrderNumberDialogOpen = false
                Toast.makeText(context, "تم حفظ رقم الطلب: $orderCodeNumber", Toast.LENGTH_SHORT).show()
              },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC))
            ) {
              Text("حفظ")
            }
          }
        }
      }
    }
  }

  // Edit Order Price Dialog
  if (isEditPriceDialogOpen) {
    val isDialogNegative = tempPriceInput.trim().startsWith("-")
    Dialog(onDismissRequest = { isEditPriceDialogOpen = false }) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("تعديل سعر الطلب", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))

          OutlinedTextField(
            value = tempPriceInput,
            onValueChange = { tempPriceInput = it },
            label = { Text("سعر الطلب") },
            placeholder = { Text("مثال: 25000 أو 0 أو -15000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_edit_order_price"),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF1E293B),
              unfocusedTextColor = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF1E293B),
              cursorColor = Color(0xFF1E293B),
              focusedBorderColor = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF16A34A),
              unfocusedBorderColor = Color(0xFFCBD5E1),
              focusedLabelColor = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF16A34A),
              unfocusedLabelColor = Color(0xFF64748B),
              focusedPlaceholderColor = Color(0xFF94A3B8),
              unfocusedPlaceholderColor = Color(0xFF94A3B8)
            ),
            textStyle = TextStyle(
              color = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF1E293B),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              textDirection = TextDirection.Ltr
            )
          )

          FilterChip(
            selected = isDialogNegative,
            onClick = {
              val trimmed = tempPriceInput.trim()
              tempPriceInput = if (trimmed.startsWith("-")) {
                trimmed.removePrefix("-").trimStart()
              } else if (trimmed.isEmpty()) {
                "-"
              } else {
                "-$trimmed"
              }
            },
            label = {
              Text(
                text = "- سالب (مبلغ يُعطى للزبون)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = Color(0xFFFEE2E2),
              selectedLabelColor = Color(0xFFDC2626)
            ),
            modifier = Modifier.testTag("chip_toggle_negative_price")
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
          ) {
            TextButton(onClick = { isEditPriceDialogOpen = false }) {
              Text("إلغاء", color = Color(0xFF64748B))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                orderPrice = OcrHelper.cleanPriceNumber(tempPriceInput.trim())
                syncFields(newPrice = orderPrice)
                isEditPriceDialogOpen = false
                Toast.makeText(
                  context,
                  if (orderPrice.isNotBlank()) "تم حفظ سعر الطلب: $orderPrice" else "تم مسح سعر الطلب",
                  Toast.LENGTH_SHORT
                ).show()
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isDialogNegative) Color(0xFFDC2626) else Color(0xFF16A34A)
              ),
              modifier = Modifier.testTag("btn_save_order_price")
            ) {
              Text("حفظ")
            }
          }
        }
      }
    }
  }

  // Edit Customer Address Dialog (Manual entry only)
  if (isEditAddressDialogOpen) {
    Dialog(onDismissRequest = { isEditAddressDialogOpen = false }) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Text("تعديل عنوان الزبون (يدوياً)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))

          OutlinedTextField(
            value = tempAddressInput,
            onValueChange = { tempAddressInput = it },
            label = { Text("عنوان الزبون (المحافظة / الحي / الشارع)") },
            placeholder = { Text("مثال: البصرة - محطة الزبير أو حي الروضة") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_edit_customer_address"),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color(0xFF1E293B),
              unfocusedTextColor = Color(0xFF1E293B),
              cursorColor = Color(0xFF1E293B),
              focusedBorderColor = Color(0xFF0052CC),
              unfocusedBorderColor = Color(0xFFCBD5E1),
              focusedLabelColor = Color(0xFF0052CC),
              unfocusedLabelColor = Color(0xFF64748B),
              focusedPlaceholderColor = Color(0xFF94A3B8),
              unfocusedPlaceholderColor = Color(0xFF94A3B8)
            ),
            textStyle = TextStyle(
              color = Color(0xFF1E293B),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium
            )
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
          ) {
            TextButton(onClick = { isEditAddressDialogOpen = false }) {
              Text("إلغاء", color = Color(0xFF64748B))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                val trimmed = tempAddressInput.trim()
                customerAddress = trimmed
                if (trimmed.isNotBlank()) {
                  hasAutoFilledForPhone = true
                }
                onCustomerAddressSaved(trimmed, customerAreaSubtitle)
                isEditAddressDialogOpen = false
                Toast.makeText(context, "تم حفظ عنوان الزبون يدوياً بنجاح", Toast.LENGTH_SHORT).show()
              },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
              modifier = Modifier.testTag("btn_save_customer_address")
            ) {
              Text("حفظ")
            }
          }
        }
      }
    }
  }

  // Camera or Gallery Source Dialog
  if (isPhotoSourceDialogOpen) {
    Dialog(onDismissRequest = { isPhotoSourceDialogOpen = false }) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          val dialogTitle = when (currentPhotoTarget) {
            PhotoTarget.CONTENTS -> "التقاط صورة لمحتويات الطلب بدقة HD"
            PhotoTarget.EXTERNAL -> "التقاط صورة لشكل الطلب الخارجي بدقة HD"
            PhotoTarget.ISSUE -> "إرفاق صورة للمشكلة بدقة HD"
          }
          Text(dialogTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
          Text("اختر طريقة التقاط أو اختيار الصورة بجودة فائقة:", fontSize = 12.sp, color = Color(0xFF64748B))

          // Option 1: Camera
          Button(
            onClick = {
              isPhotoSourceDialogOpen = false
              cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(42.dp)
          ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("فتح الكاميرا والتقاط صورة بدقة HD")
          }

          // Option 2: Gallery
          OutlinedButton(
            onClick = {
              isPhotoSourceDialogOpen = false
              launchGalleryForTarget(currentPhotoTarget.name)
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(42.dp)
          ) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("اختيار صورة من الاستوديو بجودة HD")
          }

          TextButton(
            onClick = { isPhotoSourceDialogOpen = false },
            modifier = Modifier.align(Alignment.End)
          ) {
            Text("إلغاء", color = Color(0xFF64748B))
          }
        }
      }
    }
  }

  // Full Screen Image Preview Modal when any image is tapped
  fullScreenImagePreview?.let { (model, title) ->
    FullScreenImageModal(
      imageModel = model,
      title = title,
      onDismiss = { fullScreenImagePreview = null }
    )
  }

  // Delete Order Confirmation Dialog
  if (isConfirmDeleteDialogOpen) {
    AlertDialog(
      onDismissRequest = { isConfirmDeleteDialogOpen = false },
      icon = {
        Box(
          modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xFFFEE2E2)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Outlined.DeleteForever,
            contentDescription = null,
            tint = Color(0xFFDC2626),
            modifier = Modifier.size(28.dp)
          )
        }
      },
      title = {
        Text(
          text = "تأكيد حذف الطلب",
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          color = Color(0xFF0F172A),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Text(
          text = "هل أنت متأكد من رغبتك في حذف هذا الطلب؟\nسيتم نقله إلى سلة \"الطلبات المحذوفة\" ويمكنك استعادته في أي وقت.",
          fontSize = 13.5.sp,
          color = Color(0xFF475569),
          textAlign = TextAlign.Center,
          lineHeight = 20.sp,
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            isConfirmDeleteDialogOpen = false
            onDeleteOrder(stop.id)
            Toast.makeText(context, "تم نقل الطلب إلى سلة المحذوفات", Toast.LENGTH_SHORT).show()
            onBack()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.testTag("btn_confirm_delete_dialog")
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("نعم، حذف الطلب", fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { isConfirmDeleteDialogOpen = false },
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(18.dp)
    )
  }

  // Confirmation Dialog before deleting any attached photo (prevents accidental tap deletion)
  pendingDeletePhotoType?.let { deleteType ->
    val photoLabel = when (deleteType) {
      "CONTENTS" -> "صورة محتويات الطلب"
      "EXTERNAL" -> "صورة شكل الطلب الخارجي"
      "ISSUE" -> "صورة المشكلة"
      "SCREENSHOT" -> "لقطة الشاشة"
      else -> "هذه الصورة"
    }
    AlertDialog(
      onDismissRequest = { pendingDeletePhotoType = null },
      title = {
        Text(
          text = "تأكيد حذف الصورة",
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
          color = Color(0xFF0F172A),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Text(
          text = "هل تريد حذف $photoLabel؟",
          fontSize = 14.sp,
          color = Color(0xFF475569),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            when (deleteType) {
              "CONTENTS" -> onContentsPhotoCaptured(null)
              "EXTERNAL" -> onExternalPhotoCaptured(null)
              "ISSUE" -> onIssuePhotoCaptured(null)
              "SCREENSHOT" -> onScreenshotChanged(null)
            }
            pendingDeletePhotoType = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.testTag("btn_confirm_delete_photo")
        ) {
          Text("نعم، حذف", fontWeight = FontWeight.Bold, color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { pendingDeletePhotoType = null },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.testTag("btn_cancel_delete_photo")
        ) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(18.dp)
    )
  }

  // Barcode / QR Scanner for Phone 1
  if (isScanningPhone1Qr) {
    QrScannerModal(
      onDismiss = { isScanningPhone1Qr = false },
      onScanResult = { code ->
        isScanningPhone1Qr = false
        val clean = code.trim()
        phone1 = clean
        syncFields(newP1 = clean)
        searchCustomerAndAutoPull(clean)
        Toast.makeText(context, "تم مسح الباركود وجلب بيانات الزبون: $clean", Toast.LENGTH_SHORT).show()
      }
    )
  }

  // Barcode / QR Scanner for Phone 2
  if (isScanningPhone2Qr) {
    QrScannerModal(
      onDismiss = { isScanningPhone2Qr = false },
      onScanResult = { code ->
        isScanningPhone2Qr = false
        val clean = code.trim()
        phone2 = clean
        syncFields(newP2 = clean)
        searchCustomerAndAutoPull(clean)
        Toast.makeText(context, "تم مسح الباركود وجلب بيانات الزبون: $clean", Toast.LENGTH_SHORT).show()
      }
    )
  }
}


@Composable
private fun EditablePhoneContactCard(
  label: String,
  phoneNumber: String,
  onPhoneNumberChanged: (String) -> Unit,
  onCopy: () -> Unit,
  onCall: () -> Unit,
  onWhatsApp: () -> Unit,
  onScanBarcode: (() -> Unit)? = null
) {
  var isEditing by remember { mutableStateOf(false) }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = Color.White,
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          // Copy Button
          IconButton(
            onClick = onCopy,
            modifier = Modifier.size(28.dp).testTag("copy_${label}")
          ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "نسخ", tint = Color(0xFF0052CC), modifier = Modifier.size(16.dp))
          }

          // Edit Button
          IconButton(
            onClick = { isEditing = !isEditing },
            modifier = Modifier.size(28.dp).testTag("edit_${label}")
          ) {
            Icon(
              imageVector = if (isEditing) Icons.Default.Check else Icons.Outlined.Edit,
              contentDescription = "تعديل",
              tint = if (isEditing) Color(0xFF16A34A) else Color(0xFF0052CC),
              modifier = Modifier.size(16.dp)
            )
          }

          // Barcode / QR Scanner Button
          if (onScanBarcode != null) {
            IconButton(
              onClick = onScanBarcode,
              modifier = Modifier.size(28.dp).testTag("scan_barcode_${label}")
            ) {
              Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = "مسح باركود",
                tint = Color(0xFF0052CC),
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(6.dp))

          // Editable or Display Phone Text
          if (isEditing) {
            BasicTextField(
              value = phoneNumber,
              onValueChange = onPhoneNumberChanged,
              textStyle = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              ),
              cursorBrush = SolidColor(Color(0xFF1E293B)),
              singleLine = true,
              modifier = Modifier
                .weight(1f)
                .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("input_${label}")
            )
          } else {
            Text(
              text = if (phoneNumber.isNotBlank()) phoneNumber else "أدخل رقم الهاتف...",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = if (phoneNumber.isNotBlank()) Color(0xFF1E293B) else Color(0xFF94A3B8),
              modifier = Modifier
                .weight(1f)
                .clickable { isEditing = true }
            )
          }
        }

        Text(
          text = label,
          fontSize = 11.5.sp,
          color = Color(0xFF64748B)
        )
      }

      // Buttons row: Call (Pink) and WhatsApp (Green)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = onWhatsApp,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.weight(1f).height(36.dp)
        ) {
          Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("واتساب", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Button(
          onClick = onCall,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.weight(1f).height(36.dp)
        ) {
          Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("اتصال", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

fun createHdOutputTarget(context: Context, prefix: String = "hd"): Pair<Uri, String> {
  val imagesDir = File(context.filesDir, "order_images")
  if (!imagesDir.exists()) imagesDir.mkdirs()
  val uniqueSuffix = UUID.randomUUID().toString().take(6)
  val file = File(imagesDir, "${prefix}_${System.currentTimeMillis()}_${uniqueSuffix}.jpg")
  val uri = try {
    androidx.core.content.FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )
  } catch (_: Exception) {
    Uri.fromFile(file)
  }
  return uri to file.absolutePath
}

fun createHdOutputFileUri(context: Context, filename: String = "hd_${System.currentTimeMillis()}.jpg"): Uri {
  val imagesDir = File(context.filesDir, "order_images")
  if (!imagesDir.exists()) imagesDir.mkdirs()
  val file = File(imagesDir, filename)
  return try {
    androidx.core.content.FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )
  } catch (_: Exception) {
    Uri.fromFile(file)
  }
}

fun copyUriToHdFile(context: Context, sourceUri: Uri): Uri {
  val imagesDir = File(context.filesDir, "order_images")
  if (!imagesDir.exists()) imagesDir.mkdirs()
  val uniqueSuffix = UUID.randomUUID().toString().take(6)
  val file = File(imagesDir, "hd_studio_${System.currentTimeMillis()}_${uniqueSuffix}.jpg")

  try {
    val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
      FileOutputStream(file).use { output ->
        input.copyTo(output)
      }
    } ?: 0L
    if (copied > 0L && file.exists() && file.length() > 0L) {
      return try {
        androidx.core.content.FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          file
        )
      } catch (_: Exception) {
        Uri.fromFile(file)
      }
    }
    if (file.exists() && file.length() == 0L) {
      file.delete()
    }
  } catch (_: Exception) {
    if (file.exists() && file.length() == 0L) {
      file.delete()
    }
  }

  // Fallback decode via ImageDecoder / MediaStore / BitmapFactory for strict Photo Picker providers
  return try {
    val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val src = ImageDecoder.createSource(context.contentResolver, sourceUri)
      ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } else {
      @Suppress("DEPRECATION")
      MediaStore.Images.Media.getBitmap(context.contentResolver, sourceUri)
    } ?: context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }

    if (bitmap != null) {
      saveBitmapToCache(context, bitmap)
    } else {
      sourceUri
    }
  } catch (_: Exception) {
    sourceUri
  }
}

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
  val imagesDir = File(context.filesDir, "order_images")
  if (!imagesDir.exists()) imagesDir.mkdirs()
  val uniqueSuffix = UUID.randomUUID().toString().take(6)
  val file = File(imagesDir, "hd_photo_${System.currentTimeMillis()}_${uniqueSuffix}.jpg")
  FileOutputStream(file).use { out ->
    // 100% quality: Maximum resolution and clarity without compression loss
    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
  }
  return try {
    androidx.core.content.FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )
  } catch (_: Exception) {
    Uri.fromFile(file)
  }
}

private fun normalizePhoneForWhatsApp(input: String): String {
  var digits = input.replace(Regex("[^0-9]"), "")
  if (digits.startsWith("00")) digits = digits.substring(2)
  if (digits.startsWith("07") && digits.length == 11) {
    digits = "964" + digits.substring(1)
  } else if (digits.startsWith("7") && digits.length == 10) {
    digits = "964$digits"
  }
  return digits
}

private fun launchWhatsAppReport(
  context: Context,
  recipientPhone: String,
  orderNumber: String,
  issueNotes: String,
  externalPhotoUriString: String?,
  contentsPhotoUriString: String?,
  issuePhotoUriString: String?
) {
  val cleanPhone = normalizePhoneForWhatsApp(recipientPhone)

  val shareableUris = ArrayList<Uri>()
  listOfNotNull(externalPhotoUriString, contentsPhotoUriString, issuePhotoUriString).forEach { uriStr ->
    try {
      val rawUri = Uri.parse(uriStr)
      val resolved = if (rawUri.scheme == "file") {
        val file = File(rawUri.path ?: "")
        if (file.exists()) {
          try {
            androidx.core.content.FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              file
            )
          } catch (_: Exception) {
            rawUri
          }
        } else rawUri
      } else {
        rawUri
      }
      if (!shareableUris.contains(resolved)) {
        shareableUris.add(resolved)
      }
    } catch (_: Exception) {
      // Ignore invalid URIs
    }
  }

  // Report strictly contains ONLY: Order Number + Issue Notes (NO customer address, NO phones)
  val reportText = buildString {
    appendLine("📦 تقرير مشكلة الطلب")
    appendLine("━━━━━━━━━━━━━━━━━━━")
    appendLine("🔢 رقم الطلب: ${if (orderNumber.isNotBlank()) orderNumber else "غير محدد"}")
    appendLine("━━━━━━━━━━━━━━━━━━━")
    appendLine("📝 ملاحظات المشاكل:")
    if (issueNotes.isNotBlank()) {
      appendLine(issueNotes)
    } else {
      appendLine("لا توجد ملاحظات مشاكل مسجلة.")
    }
    if (shareableUris.isNotEmpty()) {
      appendLine("━━━━━━━━━━━━━━━━━━━")
      appendLine("📸 مرفق (${shareableUris.size}) صور بدقة عالية:")
      if (externalPhotoUriString != null) appendLine("• صورة شكل الطلب الخارجي")
      if (contentsPhotoUriString != null) appendLine("• صورة محتويات الطلب")
      if (issuePhotoUriString != null) appendLine("• صورة المشكلة الميدانية")
    }
  }

  val intent = if (shareableUris.isEmpty()) {
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, reportText)
    }
  } else if (shareableUris.size == 1) {
    Intent(Intent.ACTION_SEND).apply {
      type = "image/*"
      putExtra(Intent.EXTRA_TEXT, reportText)
      putExtra(Intent.EXTRA_STREAM, shareableUris[0])
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  } else {
    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
      type = "image/*"
      putExtra(Intent.EXTRA_TEXT, reportText)
      putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareableUris)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  if (cleanPhone.isNotBlank()) {
    intent.putExtra("jid", "$cleanPhone@s.whatsapp.net")
  }

  val isWhatsAppInstalled = try {
    context.packageManager.getPackageInfo("com.whatsapp", 0)
    intent.setPackage("com.whatsapp")
    true
  } catch (_: Exception) {
    try {
      context.packageManager.getPackageInfo("com.whatsapp.w4b", 0)
      intent.setPackage("com.whatsapp.w4b")
      true
    } catch (_: Exception) {
      false
    }
  }

  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  try {
    if (isWhatsAppInstalled) {
      context.startActivity(intent)
    } else {
      if (cleanPhone.isNotBlank() && shareableUris.isEmpty()) {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(reportText)}"))
        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(webIntent)
      } else {
        val chooser = Intent.createChooser(intent, "إرسال تقرير الطلب عبر واتساب...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
      }
    }
  } catch (e: Exception) {
    Toast.makeText(context, "تعذر فتح واتساب: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}

@Composable
private fun StatusClassificationChip(
  status: DeliveryStatus,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = if (isSelected) status.containerColor else Color.White,
    border = BorderStroke(
      width = if (isSelected) 1.5.dp else 1.dp,
      color = if (isSelected) status.primaryColor else Color(0xFFE2E8F0)
    ),
    modifier = modifier
      .height(38.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable { onClick() }
      .testTag("status_chip_${status.name}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(if (isSelected) status.primaryColor else Color(0xFF94A3B8))
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = status.labelAr,
        fontSize = 11.5.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color = if (isSelected) status.primaryColor else Color(0xFF334155),
        maxLines = 1
      )
    }
  }
}

