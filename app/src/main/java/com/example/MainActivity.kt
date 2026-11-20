package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.core.view.WindowCompat
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.service.FloatingLensService
import com.example.ui.components.AddNewAddressDialog
import com.example.ui.components.CustomerOrderDetailsScreen
import com.example.ui.components.DeliveryLocationCard
import com.example.ui.components.ManageCategoriesDialog
import com.example.ui.components.QrScannerModal
import com.example.ui.components.RefreshSyncButton
import com.example.ui.components.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.OcrHelper
import com.example.util.TrashBackupHelper
import com.example.util.configureAppCheck
import com.example.viewmodel.DeliveryViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels

enum class MainTab {
  ORDERS,
  SETTINGS
}

class MainActivity : ComponentActivity() {
  private val deliveryViewModel: DeliveryViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge()
    handleIncomingIntent(intent)

    setContent {
      val rootUiState by deliveryViewModel.uiState.collectAsStateWithLifecycle()
      SideEffect {
        val window = this@MainActivity.window
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !rootUiState.isDarkMode
      }
      MyApplicationTheme(darkTheme = rootUiState.isDarkMode) {
        DeliveryAppNavigation(viewModel = deliveryViewModel)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    deliveryViewModel.loadRegisteredCustomers()
    deliveryViewModel.syncFirestore(silent = true)
    com.example.service.BackgroundKeepAliveService.syncWithPreferences(this)
  }

  override fun onPause() {
    super.onPause()
    deliveryViewModel.persistAndFlushBlocking()
  }

  override fun onStop() {
    super.onStop()
    deliveryViewModel.persistAndFlushBlocking()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(intent: Intent?) {
    if (intent == null) return
    val prefillPhone = intent.getStringExtra(FloatingLensService.EXTRA_PREFILL_CUSTOMER_PHONE)
    if (!prefillPhone.isNullOrBlank()) {
      deliveryViewModel.setPrefillCustomerPhone(prefillPhone)
      intent.removeExtra(FloatingLensService.EXTRA_PREFILL_CUSTOMER_PHONE)
    }
    val prefillOrderPrice = intent.getStringExtra(FloatingLensService.EXTRA_PREFILL_ORDER_PRICE)
    val prefillOrderPhone = intent.getStringExtra(FloatingLensService.EXTRA_PREFILL_ORDER_PHONE).orEmpty()
    if (!prefillOrderPrice.isNullOrBlank()) {
      deliveryViewModel.openNewOrderWithPrefill(prefillOrderPrice, prefillOrderPhone)
      intent.removeExtra(FloatingLensService.EXTRA_PREFILL_ORDER_PRICE)
      intent.removeExtra(FloatingLensService.EXTRA_PREFILL_ORDER_PHONE)
    }
    if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
      val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      } ?: intent.clipData?.getItemAt(0)?.uri

      if (uri != null) {
        val hdUri = try {
          val imagesDir = java.io.File(filesDir, "order_images")
          if (!imagesDir.exists()) imagesDir.mkdirs()
          val file = java.io.File(imagesDir, "hd_shared_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}.jpg")
          val copiedBytes = contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(file).use { output ->
              input.copyTo(output)
            }
          } ?: 0L
          if (copiedBytes <= 0L || !file.exists() || file.length() == 0L) {
            if (file.exists()) file.delete()
            uri
          } else {
            androidx.core.content.FileProvider.getUriForFile(
              this,
              "$packageName.fileprovider",
              file
            )
          }
        } catch (_: Exception) {
          uri
        }
        deliveryViewModel.handleSharedScreenshot(hdUri.toString())
      }
      intent.action = null
      intent.removeExtra(Intent.EXTRA_STREAM)
      intent.clipData = null
    }
  }
}

@Composable
fun DeliveryAppNavigation(
  viewModel: DeliveryViewModel
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val listState = rememberSaveable(saver = LazyListState.Saver) {
    LazyListState()
  }
  var lastInteractedStopId by rememberSaveable { mutableStateOf<String?>(null) }

  // Top-level Activity Result launchers stay alive even if Activity recreates while Camera/Gallery is open
  var activePickerStopId by rememberSaveable { mutableStateOf<String?>(null) }
  var activePickerTargetName by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCameraUriStr by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

  val topGalleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    val targetStopId = activePickerStopId
    val targetName = activePickerTargetName.orEmpty()
    activePickerStopId = null
    activePickerTargetName = null
    if (uri != null) {
      val hdUri = com.example.ui.components.copyUriToHdFile(context, uri)
      viewModel.applyPickedPhotoFromExternalPicker(
        stopId = targetStopId,
        targetName = targetName,
        uriString = hdUri.toString()
      )
      val toastMsg = when (targetName) {
        "CONTENTS" -> "تم استيراد صورة محتويات الطلب بجودة HD كاملة بنجاح!"
        "EXTERNAL" -> "تم استيراد صورة شكل الطلب الخارجي بجودة HD كاملة بنجاح!"
        "SCREENSHOT" -> "تم إرفاق لقطة الشاشة بجودة HD الكاملة بنجاح!"
        else -> "تم استيراد الصورة بجودة HD كاملة بنجاح!"
      }
      Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    } else {
      viewModel.consumePendingGalleryPick()
    }
  }

  val topCameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.TakePicture()
  ) { isSuccess: Boolean ->
    val targetStopId = activePickerStopId
    val targetName = activePickerTargetName.orEmpty()
    val capturedUriStr = pendingCameraUriStr
    val capturedPath = pendingCameraFilePath
    activePickerStopId = null
    activePickerTargetName = null
    pendingCameraUriStr = null
    pendingCameraFilePath = null

    val hasValidFile = capturedPath.isNullOrBlank() ||
      java.io.File(capturedPath).let { it.exists() && it.length() > 0L }

    if (isSuccess && capturedUriStr != null && hasValidFile) {
      viewModel.applyPickedPhotoFromExternalPicker(
        stopId = targetStopId,
        targetName = targetName,
        uriString = capturedUriStr
      )
      viewModel.clearPendingPhotoCapture(false)
      val toastMsg = when (targetName) {
        "CONTENTS" -> "تم التقاط وحفظ صورة محتويات الطلب بدقة HD بنجاح!"
        "EXTERNAL" -> "تم التقاط وحفظ صورة شكل الطلب الخارجي بدقة HD بنجاح!"
        else -> "تم التقاط وحفظ صورة المشكلة بدقة HD بنجاح!"
      }
      Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    } else {
      if (!capturedPath.isNullOrBlank()) {
        runCatching {
          val emptyFile = java.io.File(capturedPath)
          if (emptyFile.exists() && emptyFile.length() == 0L) emptyFile.delete()
        }
      }
      viewModel.clearPendingPhotoCapture(true)
    }
  }

  // When returning from details screen, guarantee we remain anchored at the exact order
  LaunchedEffect(uiState.isDetailSheetOpen) {
    if (!uiState.isDetailSheetOpen && lastInteractedStopId != null) {
      val targetIndex = uiState.filteredStops.indexOfFirst { it.id == lastInteractedStopId }
      if (targetIndex >= 0) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val isVisible = visibleItems.any { it.index == targetIndex }
        if (!isVisible) {
          listState.scrollToItem(targetIndex)
        }
      }
    }
  }

  // Pure RTL Arabic Layout matching the screenshot exactly
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Box(modifier = Modifier.fillMaxSize()) {
      // 1. Base List Screen (Kept alive in composition tree so scroll position is NEVER lost!)
      DeliveryAddressesScreen(
        viewModel = viewModel,
        listState = listState,
        onOpenStop = { stop ->
          lastInteractedStopId = stop.id
          viewModel.openDetailSheet(stop)
        }
      )

      // 2. Details Screen with ultra-smooth, fluid spring & fade transition
      AnimatedVisibility(
        visible = uiState.isDetailSheetOpen && uiState.selectedStop != null,
        enter = slideInVertically(
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
          )
        ) { fullHeight -> (fullHeight * 0.20f).toInt() } + fadeIn(
          animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
        ) + scaleIn(
          initialScale = 0.96f,
          animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
        ),
        exit = slideOutVertically(
          animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing)
        ) { fullHeight -> (fullHeight * 0.20f).toInt() } + fadeOut(
          animationSpec = tween(durationMillis = 200, easing = LinearEasing)
        ) + scaleOut(
          targetScale = 0.96f,
          animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing)
        ),
        modifier = Modifier.fillMaxSize()
      ) {
        val currentStop = uiState.selectedStop
        if (currentStop != null) {
          val activeStopId = currentStop.id
          BackHandler {
            viewModel.closeDetailSheet()
          }
          CustomerOrderDetailsScreen(
            stop = currentStop,
            onBack = { viewModel.closeDetailSheet() },
            onConfirmDelivery = { updatedStop ->
              viewModel.saveOrConfirmStop(updatedStop)
            },
            onScreenshotChanged = { newUri ->
              viewModel.updateScreenshotForStop(uiState.selectedStop?.id ?: activeStopId, newUri)
            },
            onApplyOcr = { ocrResult ->
              viewModel.applyOcrResult(uiState.selectedStop?.id ?: activeStopId, ocrResult)
            },
            onSaveOrderFields = { orderCode, p1, p2, notes, price ->
              viewModel.updateOrderFields(uiState.selectedStop?.id ?: activeStopId, orderCode, p1, p2, notes, price)
            },
            onIssuePhotoCaptured = { photoUri ->
              viewModel.updateCapturedIssuePhoto(uiState.selectedStop?.id ?: activeStopId, photoUri)
            },
            onContentsPhotoCaptured = { photoUri ->
              viewModel.updateCapturedContentsPhoto(uiState.selectedStop?.id ?: activeStopId, photoUri)
            },
            onExternalPhotoCaptured = { photoUri ->
              viewModel.updateCapturedExternalPhoto(uiState.selectedStop?.id ?: activeStopId, photoUri)
            },
            onPreparePendingPhotoCapture = { targetName, uriStr, filePath ->
              viewModel.preparePendingPhotoCapture(
                stopId = uiState.selectedStop?.id ?: activeStopId,
                targetName = targetName,
                outputUriString = uriStr,
                outputFilePath = filePath
              )
            },
            onClearPendingPhotoCapture = { deleteEmptyFile ->
              viewModel.clearPendingPhotoCapture(deleteEmptyFile)
            },
            onLaunchExternalCamera = { targetName ->
              val stopId = uiState.selectedStop?.id ?: activeStopId
              val (uri, filePath) = com.example.ui.components.createHdOutputTarget(context, "hd_camera")
              activePickerStopId = stopId
              activePickerTargetName = targetName
              pendingCameraUriStr = uri.toString()
              pendingCameraFilePath = filePath
              viewModel.preparePendingPhotoCapture(
                stopId = stopId,
                targetName = targetName,
                outputUriString = uri.toString(),
                outputFilePath = filePath
              )
              topCameraLauncher.launch(uri)
            },
            onLaunchExternalGallery = { targetName ->
              val stopId = uiState.selectedStop?.id ?: activeStopId
              activePickerStopId = stopId
              activePickerTargetName = targetName
              viewModel.savePendingGalleryPick(stopId, targetName)
              topGalleryLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                  androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                )
              )
            },
            onCustomerAddressSaved = { addr, area ->
              viewModel.updateCustomerAddress(uiState.selectedStop?.id ?: activeStopId, addr, area)
            },
            onDeleteOrder = { id ->
              viewModel.deleteStop(id)
            },
            categories = uiState.categories,
            onFindCustomerByPhone = { phone -> viewModel.findCustomerByPhone(phone) },
            suspiciousCustomers = uiState.suspiciousCustomers,
            onCategorySelected = { cat -> viewModel.updateStopCategory(uiState.selectedStop?.id ?: activeStopId, cat) },
            onStatusSelected = { status -> viewModel.updateStopStatus(uiState.selectedStop?.id ?: activeStopId, status) },
            isAppRefreshing = uiState.isAppRefreshing,
            refreshGeneration = uiState.refreshGeneration,
            onRefreshAndSave = { viewModel.refreshAndSaveApp(manual = true) }
          )
        }
      }
    }
  }
}

@Composable
fun DeliveryAddressesScreen(
  viewModel: DeliveryViewModel,
  listState: LazyListState = rememberLazyListState(),
  onOpenStop: ((DeliveryStop) -> Unit)? = null
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var showEmptyTrashConfirmDialog by remember { mutableStateOf(false) }
  var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }
  var showBulkCategoryDialog by remember { mutableStateOf(false) }
  var isBackingUpTrash by remember { mutableStateOf(false) }
  var selectedTab by rememberSaveable { mutableStateOf(MainTab.ORDERS) }

  LaunchedEffect(uiState.prefillCustomerPhone) {
    if (uiState.prefillCustomerPhone.isNotBlank()) {
      selectedTab = MainTab.SETTINGS
    }
  }

  LaunchedEffect(uiState.userMessage) {
    uiState.userMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.clearUserMessage()
    }
  }

  if (selectedTab == MainTab.SETTINGS) {
    BackHandler {
      selectedTab = MainTab.ORDERS
    }
  } else if (uiState.isSelectionMode) {
    BackHandler {
      viewModel.exitSelectionMode()
    }
  }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .testTag("delivery_addresses_screen"),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = MaterialTheme.colorScheme.background
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
      ) {
        // ==========================================
        // 1. Clean Top Tabs Bar (Orders & Settings)
        // ==========================================
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Tab 1: الطلبات (Orders)
          val isOrdersSelected = selectedTab == MainTab.ORDERS
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .width(IntrinsicSize.Max)
              .clip(RoundedCornerShape(8.dp))
              .clickable { selectedTab = MainTab.ORDERS }
              .padding(horizontal = 4.dp, vertical = 4.dp)
              .testTag("tab_orders")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              Text(
                text = "الطلبات",
                fontSize = 13.5.sp,
                fontWeight = if (isOrdersSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isOrdersSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
              Icon(
                imageVector = Icons.Outlined.LocalShipping,
                contentDescription = "الطلبات",
                tint = if (isOrdersSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isOrdersSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
          }

          // Tab 2: الإعدادات (Settings)
          val isSettingsSelected = selectedTab == MainTab.SETTINGS
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .width(IntrinsicSize.Max)
              .clip(RoundedCornerShape(8.dp))
              .clickable { selectedTab = MainTab.SETTINGS }
              .padding(horizontal = 4.dp, vertical = 4.dp)
              .testTag("tab_settings")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              Text(
                text = "الإعدادات",
                fontSize = 13.5.sp,
                fontWeight = if (isSettingsSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSettingsSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
              Icon(
                imageVector = if (isSettingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
                contentDescription = "الإعدادات",
                tint = if (isSettingsSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isSettingsSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
          }

          Spacer(modifier = Modifier.weight(1f))

          // Small dual-semicircle-arrow refresh & save button in the top-left corner (End in RTL)
          RefreshSyncButton(
            isRefreshing = uiState.isAppRefreshing,
            refreshGeneration = uiState.refreshGeneration,
            onClick = { viewModel.refreshAndSaveApp(manual = true) },
            testTagName = "btn_refresh_app",
            buttonDiameter = 30.dp,
            iconSize = 17.dp
          )
        }

        when (selectedTab) {
          MainTab.SETTINGS -> {
            SettingsScreen(
              isDarkMode = uiState.isDarkMode,
              onToggleDarkMode = { viewModel.toggleDarkMode(it) },
              isCallerAddressBarEnabled = uiState.isCallerAddressBarEnabled,
              onToggleCallerAddressBar = { viewModel.setCallerAddressBarEnabled(it) },
              isBackgroundKeepAliveEnabled = uiState.isBackgroundKeepAliveEnabled,
              onToggleBackgroundKeepAlive = { viewModel.setBackgroundKeepAliveEnabled(it) },
              registeredCustomers = uiState.registeredCustomers,
              prefillPhone = uiState.prefillCustomerPhone,
              onClearPrefillPhone = { viewModel.clearPrefillCustomerPhone() },
              onRegisterCustomer = { phone, address, area ->
                viewModel.registerCustomer(phone, address, area)
              },
              onLookupCustomerByPhone = { phone -> viewModel.findCustomerByPhone(phone) },
              isFirestoreSyncing = uiState.isFirestoreSyncing,
              onSyncFirestore = { viewModel.syncFirestore(silent = false) },
              suspiciousCustomers = uiState.suspiciousCustomers,
              onAddSuspiciousCustomer = { phone, note ->
                viewModel.addSuspiciousCustomer(phone, note)
              },
              onRemoveSuspiciousCustomer = { phone ->
                viewModel.removeSuspiciousCustomer(phone)
              },
              updateSourceUrl = uiState.updateSourceUrl,
              lastUpdateCheckTime = uiState.lastUpdateCheckTime,
              appUpdateState = uiState.appUpdateState,
              onSaveUpdateSourceUrl = { url -> viewModel.saveUpdateSourceUrl(url) },
              onCheckForAppUpdates = { viewModel.checkForAppUpdates() },
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
            )
          }
          MainTab.ORDERS -> {
        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 2. Search Bar with Scanner Button
        // ==========================================
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 6.dp),
          contentAlignment = Alignment.CenterStart
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Search Icon (on the right in RTL)
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Input field
            Box(modifier = Modifier.weight(1f)) {
              if (uiState.searchQuery.isEmpty()) {
                Text(
                  text = "البحث برقم الزبون، رقم الطلب، أو العنوان...",
                  fontSize = 12.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
              }
              BasicTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                textStyle = TextStyle(
                  fontSize = 12.5.sp,
                  color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("search_input")
              )
            }

            // Clear Search Query button if text entered
            if (uiState.searchQuery.isNotEmpty()) {
              IconButton(
                onClick = { viewModel.setSearchQuery("") },
                modifier = Modifier
                  .size(32.dp)
                  .testTag("btn_clear_search")
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "مسح البحث",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(16.dp)
                )
              }
              Spacer(modifier = Modifier.width(4.dp))
            }

            // QR / Barcode Scanner Button inside the search bar widget
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(0xFFEFF6FF),
              border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
              modifier = Modifier
                .height(34.dp)
                .clickable { viewModel.openQrScanner() }
                .testTag("btn_barcode_scanner")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.QrCodeScanner,
                  contentDescription = "مسح باركود الطلب",
                  tint = Color(0xFF0052CC),
                  modifier = Modifier.size(17.dp)
                )
                Text(
                  text = "مسح باركود",
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF0052CC)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 3. Horizontal Filter Chips Row
        // ==========================================
        val totalCount = uiState.activeCount

        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Dynamic category chips in user-configured display order (placed on the right in RTL)
          items(uiState.categories, key = { it.id }) { category ->
            val count = uiState.countForCategoryId(category.id)
            val isSelected = !uiState.isShowingDeleted && uiState.selectedCategoryId == category.id
            FilterStatusChip(
              label = "${category.name} ($count)",
              dotColor = category.dotColor,
              isSelected = isSelected,
              onClick = {
                viewModel.setCategoryFilter(
                  if (uiState.selectedCategoryId == category.id) null else category.id
                )
              }
            )
          }

          // "الكل" (All) chip - Moved to the left side directly beside "الطلبات المحذوفة"
          item {
            val isSelected = !uiState.isShowingDeleted && uiState.selectedCategoryId == null
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = if (isSelected) Color(0xFF0043A8) else MaterialTheme.colorScheme.surface,
              border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
              modifier = Modifier
                .height(32.dp)
                .clickable { viewModel.setCategoryFilter(null) }
                .testTag("chip_filter_all")
            ) {
              Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = "الكل ($totalCount)",
                  fontSize = 12.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }

          // "الطلبات المحذوفة" (Deleted Orders) chip
          item {
            val isDeletedSelected = uiState.isShowingDeleted
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = if (isDeletedSelected) Color(0xFFDC2626) else MaterialTheme.colorScheme.surface,
              border = if (isDeletedSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
              modifier = Modifier
                .height(32.dp)
                .clickable { viewModel.setShowingDeleted(!isDeletedSelected) }
                .testTag("chip_deleted_orders")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = if (isDeletedSelected) Icons.Filled.Delete else Icons.Outlined.Delete,
                  contentDescription = null,
                  tint = if (isDeletedSelected) Color.White else Color(0xFFDC2626),
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "الطلبات المحذوفة (${uiState.deletedCount})",
                  fontSize = 12.sp,
                  fontWeight = if (isDeletedSelected) FontWeight.Bold else FontWeight.Medium,
                  color = if (isDeletedSelected) Color.White else Color(0xFFDC2626)
                )
              }
            }
          }

          // Button chip: "+ تخصيص القوائم" (Manage & Customize Categories)
          item {
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = Color(0xFFEFF6FF),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBFDBFE)),
              modifier = Modifier
                .height(32.dp)
                .clickable { viewModel.openManageCategoriesDialog() }
                .testTag("chip_manage_categories")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Tune,
                  contentDescription = "تخصيص القوائم",
                  tint = Color(0xFF0052CC),
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "+ تخصيص القوائم",
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF0052CC)
                )
              }
            }
          }
        }

        // Deleted Mode Top Banner
        if (uiState.isShowingDeleted) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFEF2F2),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA)),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .testTag("banner_deleted_mode")
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Outlined.DeleteSweep,
                  contentDescription = null,
                  tint = Color(0xFFDC2626),
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = "سلة الطلبات المحذوفة (${uiState.deletedCount})",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF991B1B)
                )
              }

              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (uiState.deletedCount > 0) {
                  TextButton(
                    onClick = { showEmptyTrashConfirmDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp).testTag("btn_empty_trash")
                  ) {
                    Text("إفراغ السلة", color = Color(0xFFDC2626), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                  }
                }

                TextButton(
                  onClick = { viewModel.setShowingDeleted(false) },
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  modifier = Modifier.height(30.dp)
                ) {
                  Text("العودة", color = Color(0xFF64748B), fontSize = 11.5.sp)
                }
              }
            }
          }
        }

        // Selection Mode Banner with "تحديد الكل" & Selected Count/Total Price + Exit Button
        AnimatedVisibility(visible = uiState.isSelectionMode) {
          val selectedStops = uiState.filteredStops.filter { uiState.selectedStopIds.contains(it.id) }
          val hasAnyPrice = selectedStops.any { OcrHelper.cleanPriceNumber(it.price).isNotBlank() }
          val totalSelectedPrice = selectedStops.sumOf { stop ->
            OcrHelper.cleanPriceNumber(stop.price).replace(",", "").toDoubleOrNull()?.toLong() ?: 0L
          }
          val formattedTotal = NumberFormat.getIntegerInstance(Locale.US).format(totalSelectedPrice)

          Column {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("selection_toolbar")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "تم تحديد ${uiState.selectedCount} من ${uiState.filteredStops.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                  )
                  if (hasAnyPrice) {
                    Text(
                      text = "مجموع الأسعار: $formattedTotal",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (totalSelectedPrice < 0L) Color(0xFFDC2626) else Color(0xFF047857)
                    )
                  }
                }

                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  // Select All / Deselect All button (تحديد الكل)
                  val allSelected = uiState.areAllFilteredSelected
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .clickable { viewModel.toggleSelectAllFilteredStops() }
                      .testTag("btn_select_all_orders")
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                      Icon(
                        imageVector = if (allSelected) Icons.Default.CheckBox else Icons.Default.SelectAll,
                        contentDescription = "تحديد الكل",
                        tint = if (allSelected) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                      )
                      Text(
                        text = if (allSelected) "إلغاء تحديد الكل" else "تحديد الكل",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (allSelected) Color.White else MaterialTheme.colorScheme.primary
                      )
                    }
                  }

                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .clickable { viewModel.exitSelectionMode() }
                      .testTag("btn_exit_selection_mode")
                  ) {
                    Box(
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إلغاء التحديد",
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(16.dp)
                      )
                    }
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ==========================================
        // 4. Cards List (Matching screenshot)
        // ==========================================
        val stops = uiState.filteredStops

        if (stops.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Box(
                modifier = Modifier
                  .size(72.dp)
                  .clip(CircleShape)
                  .background(if (uiState.isShowingDeleted) Color(0xFFFEE2E2) else Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (uiState.isShowingDeleted) Icons.Outlined.Delete else Icons.Outlined.LocalShipping,
                  contentDescription = null,
                  tint = if (uiState.isShowingDeleted) Color(0xFFDC2626) else Color(0xFF0052CC),
                  modifier = Modifier.size(34.dp)
                )
              }
              Spacer(modifier = Modifier.height(14.dp))
              Text(
                text = when {
                  uiState.isShowingDeleted -> if (uiState.searchQuery.isNotBlank()) "لا توجد نتائج بحث في المحذوفات" else "سلة الطلبات المحذوفة فارغة"
                  uiState.searchQuery.isNotBlank() -> "لا توجد نتائج بحث مطابقة"
                  uiState.selectedFilter != null -> "لا توجد طلبات بحالة (${uiState.selectedFilter?.labelAr}) حالياً"
                  else -> "لا توجد طلبات حالياً"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = when {
                  uiState.isShowingDeleted -> "الطلبات التي تقوم بحذفها من البطاقة أو داخل تفاصيل الطلب تظهر هنا، ويمكنك استعادتها بأي وقت."
                  uiState.searchQuery.isNotBlank() -> "تحقق من رقم الطلب المدخل أو امسح الباركود مجدداً"
                  uiState.selectedFilter != null -> "يمكنك الضغط على 'الكل' بالأعلى لعرض جميع الطلبات وتصنيفاتها"
                  else -> "اضغط على زر القلم (+) لإضافة طلبك، أو شارك لقطة شاشة من تطبيق العمل لتعبئة الطلب تلقائياً عبر OCR"
                },
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            items(stops, key = { it.id }) { stop ->
              val stopSuspiciousMatch = remember(stop.phone1, stop.phone2, uiState.suspiciousCustomers) {
                viewModel.findSuspiciousCustomer(stop.phone1, stop.phone2)
              }
              DeliveryLocationCard(
                stop = stop,
                categories = uiState.categories,
                isSelectionMode = uiState.isSelectionMode,
                isSelected = uiState.selectedStopIds.contains(stop.id),
                onToggleSelection = { viewModel.toggleStopSelection(stop.id) },
                onLongClick = { viewModel.enterSelectionMode(stop.id) },
                matchedSuspicious = stopSuspiciousMatch,
                onClick = {
                  // Clicking on an order opens details while preserving the exact scroll position
                  if (onOpenStop != null) {
                    onOpenStop(stop)
                  } else {
                    viewModel.openDetailSheet(stop)
                  }
                },
                onDelete = { viewModel.deleteStop(stop.id) },
                onRestore = { viewModel.restoreStop(stop.id) },
                onPermanentDelete = { viewModel.permanentlyDeleteStop(stop.id) },
                onStatusChange = { newStatus -> viewModel.updateStopStatus(stop.id, newStatus) },
                onCategoryChange = { newCategory -> viewModel.updateStopCategory(stop.id, newCategory) }
              )
            }
          }
        }
        }
      }
      }

      // ==========================================
      // 5. Floating Action Dock OR Bulk Selection Action Bar (Orders Tab only)
      // ==========================================
      if (selectedTab == MainTab.ORDERS) {
        if (uiState.isSelectionMode) {
          Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(horizontal = 16.dp, vertical = 16.dp)
              .fillMaxWidth()
              .testTag("bulk_actions_bar")
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val hasSelected = uiState.selectedCount > 0
              if (uiState.isShowingDeleted) {
                Button(
                  enabled = hasSelected,
                  onClick = { viewModel.bulkRestoreSelectedStops() },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                  shape = RoundedCornerShape(10.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("btn_bulk_restore")
                ) {
                  Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("استعادة (${uiState.selectedCount})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                  enabled = hasSelected,
                  onClick = { showBulkDeleteConfirmDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                  shape = RoundedCornerShape(10.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("btn_bulk_perm_delete")
                ) {
                  Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("حذف نهائي (${uiState.selectedCount})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
              } else {
                Button(
                  enabled = hasSelected,
                  onClick = { showBulkCategoryDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
                  shape = RoundedCornerShape(10.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("btn_bulk_move_category")
                ) {
                  Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("تغيير الحالة (${uiState.selectedCount})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                  enabled = hasSelected,
                  onClick = { showBulkDeleteConfirmDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                  shape = RoundedCornerShape(10.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("btn_bulk_delete")
                ) {
                  Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("حذف (${uiState.selectedCount})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
              }
            }
          }
        } else {
          Box(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(bottom = 20.dp)
          ) {
            // Pill dock background with shadow
            Surface(
              shape = RoundedCornerShape(26.dp),
              color = MaterialTheme.colorScheme.surface,
              shadowElevation = 8.dp,
              modifier = Modifier.height(56.dp)
            ) {
              Box(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
              ) {
                // Circular FAB button in deep blue with edit pen icon
                Box(
                  modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0043A8))
                    .clickable { viewModel.openNewOrderScreen() }
                    .testTag("fab_add_address"),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "إضافة أو تعديل",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                  )
                }
              }
            }
          }
        }
      }

      // ==========================================
      // 6. Interactive Dialogs
      // ==========================================
      // Add New Address Dialog
      if (uiState.isAddDialogOpen) {
        AddNewAddressDialog(
          onDismiss = { viewModel.closeAddDialog() },
          onSave = { newStop -> viewModel.addNewStop(newStop) },
          onFindCustomerByPhone = { phone -> viewModel.findCustomerByPhone(phone) },
          suspiciousCustomers = uiState.suspiciousCustomers
        )
      }

      // QR / Barcode Scanner Modal with Live Camera & Direct Search Insertion
      if (uiState.isQrScannerOpen) {
        QrScannerModal(
          onDismiss = { viewModel.closeQrScanner() },
          onScanResult = { code ->
            val scannedCode = code.trim()
            viewModel.closeQrScanner()
            viewModel.setSearchQuery(scannedCode)
            Toast.makeText(context, "تم مسح الباركود وإدراجه في البحث: $scannedCode", Toast.LENGTH_SHORT).show()
          }
        )
      }

      // Empty Trash Confirmation Dialog (Creates ZIP per order named by orderNumber + sends to Telegram)
      if (showEmptyTrashConfirmDialog) {
        AlertDialog(
          onDismissRequest = { if (!isBackingUpTrash) showEmptyTrashConfirmDialog = false },
          icon = {
            Icon(
              imageVector = Icons.Outlined.DeleteForever,
              contentDescription = null,
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(32.dp)
            )
          },
          title = {
            Text(
              text = "إفراغ السلة والنسخ الاحتياطي لتليجرام",
              fontWeight = FontWeight.Bold,
              fontSize = 17.5.sp,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              modifier = Modifier.fillMaxWidth()
            )
          },
          text = {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "سيتم حفظ نسخة احتياطية لكل طلب محذوف (${uiState.deletedCount} طلب) كملف ZIP منفصل يحمل رقم الطلب مع جميع المعلومات والصور وإرسالها لتطبيق تليجرام، ثم إفراغ السلة نهائياً.",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
              )
              if (isBackingUpTrash) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                  Text(
                    text = "جاري تجهيز ملفات ZIP والإرسال لتليجرام...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
              }
            }
          },
          confirmButton = {
            Button(
              enabled = !isBackingUpTrash,
              onClick = {
                val deletedList = uiState.deletedStops
                if (deletedList.isEmpty()) {
                  showEmptyTrashConfirmDialog = false
                  viewModel.emptyDeletedStops()
                  return@Button
                }
                isBackingUpTrash = true
                coroutineScope.launch {
                  try {
                    val zipFiles = TrashBackupHelper.createDeletedOrdersBackupZips(context, deletedList)
                    val shareIntent = TrashBackupHelper.buildTelegramShareIntent(context, zipFiles)
                    showEmptyTrashConfirmDialog = false
                    isBackingUpTrash = false
                    if (shareIntent != null) {
                      try {
                        context.startActivity(shareIntent)
                      } catch (_: Exception) {
                        val fallbackChooser = Intent.createChooser(
                          shareIntent.setPackage(null),
                          "إرسال النسخة الاحتياطية إلى تليجرام"
                        )
                        context.startActivity(fallbackChooser)
                      }
                    }
                    viewModel.emptyDeletedStops()
                    Toast.makeText(
                      context,
                      "تم تجهيز ${zipFiles.size} ملف ZIP وإفراغ السلة بنجاح",
                      Toast.LENGTH_SHORT
                    ).show()
                  } catch (e: Exception) {
                    isBackingUpTrash = false
                    Toast.makeText(
                      context,
                      "حدث خطأ أثناء تجهيز النسخة الاحتياطية: ${e.message}",
                      Toast.LENGTH_LONG
                    ).show()
                  }
                }
              },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.testTag("btn_confirm_empty_trash_dialog")
            ) {
              Text("إفراغ وإرسال لتليجرام", fontWeight = FontWeight.Bold, color = Color.White)
            }
          },
          dismissButton = {
            OutlinedButton(
              enabled = !isBackingUpTrash,
              onClick = { showEmptyTrashConfirmDialog = false },
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("إلغاء", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          },
          containerColor = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(18.dp)
        )
      }

      // Manage & Customize Categories Dialog
      if (uiState.isManageCategoriesDialogOpen) {
        ManageCategoriesDialog(
          categories = uiState.categories,
          onAddCategory = { name, colorHex -> viewModel.addCategory(name, colorHex) },
          onRenameCategory = { id, newName -> viewModel.renameCategory(id, newName) },
          onDeleteCategory = { id -> viewModel.deleteCategory(id) },
          onMoveUp = { id -> viewModel.moveCategoryUp(id) },
          onMoveDown = { id -> viewModel.moveCategoryDown(id) },
          onResetDefaults = { viewModel.resetCategoriesToDefault() },
          onDismiss = { viewModel.closeManageCategoriesDialog() }
        )
      }

      // Bulk Category Change Dialog for Selected Orders
      if (showBulkCategoryDialog) {
        AlertDialog(
          onDismissRequest = { showBulkCategoryDialog = false },
          title = {
            Text(
              text = "نقل ${uiState.selectedCount} طلب محدد إلى قائمة:",
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
          },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              uiState.categories.forEach { cat ->
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = cat.containerColor,
                  border = BorderStroke(1.dp, cat.borderColor),
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                      showBulkCategoryDialog = false
                      viewModel.bulkUpdateCategoryForSelectedStops(cat)
                    }
                    .testTag("bulk_category_choice_${cat.id}")
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(cat.dotColor)
                    )
                    Text(
                      text = cat.name,
                      fontSize = 13.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = cat.primaryColor
                    )
                  }
                }
              }
            }
          },
          confirmButton = {},
          dismissButton = {
            TextButton(onClick = { showBulkCategoryDialog = false }) {
              Text("إلغاء", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          },
          containerColor = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(16.dp)
        )
      }

      // Bulk Delete Confirmation Dialog
      if (showBulkDeleteConfirmDialog) {
        val isPerm = uiState.isShowingDeleted
        AlertDialog(
          onDismissRequest = { showBulkDeleteConfirmDialog = false },
          icon = {
            Icon(
              imageVector = if (isPerm) Icons.Outlined.DeleteForever else Icons.Outlined.Delete,
              contentDescription = null,
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(30.dp)
            )
          },
          title = {
            Text(
              text = if (isPerm) "تأكيد الحذف النهائي المجمع" else "تأكيد حذف الطلبات المحددة",
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
          },
          text = {
            Text(
              text = if (isPerm) {
                "هل أنت متأكد من حذف (${uiState.selectedCount}) طلب نهائياً؟ لن تتمكن من استعادتها."
              } else {
                "هل أنت متأكد من نقل (${uiState.selectedCount}) طلب إلى سلة المحذوفات؟"
              },
              fontSize = 13.5.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          },
          confirmButton = {
            Button(
              onClick = {
                showBulkDeleteConfirmDialog = false
                if (isPerm) {
                  viewModel.bulkPermanentlyDeleteSelectedStops()
                } else {
                  viewModel.bulkDeleteSelectedStops()
                }
              },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.testTag("btn_confirm_bulk_delete")
            ) {
              Text(if (isPerm) "حذف نهائي" else "نعم، حذف", fontWeight = FontWeight.Bold, color = Color.White)
            }
          },
          dismissButton = {
            OutlinedButton(
              onClick = { showBulkDeleteConfirmDialog = false },
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("إلغاء", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          },
          containerColor = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(16.dp)
        )
      }
    }
  }
}

@Composable
fun FilterStatusChip(
  label: String,
  dotColor: Color,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = if (isSelected) dotColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(
      width = 1.dp,
      color = if (isSelected) dotColor else MaterialTheme.colorScheme.outline
    ),
    modifier = Modifier
      .height(32.dp)
      .clickable { onClick() }
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(dotColor)
      )
      Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color = if (isSelected) dotColor else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false
      )
    }
  }
}
