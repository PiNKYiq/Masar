package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.CustomerRecord
import com.example.model.SuspiciousCustomer
import com.example.service.CallAddressOverlayService
import com.example.service.FloatingLensService
import com.example.service.FloatingQuickAddService
import com.example.service.ScreenTextAccessibilityService
import com.example.ui.theme.darkSlateTextFieldColors
import com.example.util.AppUpdateCheckState
import com.example.util.CallerAddressHelper
import com.example.util.CallerAddressLookupResult
import com.example.util.VibrationHelper
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  isDarkMode: Boolean,
  onToggleDarkMode: (Boolean) -> Unit,
  isCallerAddressBarEnabled: Boolean = false,
  onToggleCallerAddressBar: (Boolean) -> Unit = {},
  isBackgroundKeepAliveEnabled: Boolean = true,
  onToggleBackgroundKeepAlive: (Boolean) -> Unit = {},
  registeredCustomers: List<CustomerRecord>,
  prefillPhone: String,
  onClearPrefillPhone: () -> Unit,
  onRegisterCustomer: (phone: String, address: String, area: String) -> Unit,
  onLookupCustomerByPhone: suspend (String) -> CustomerRecord?,
  isFirestoreSyncing: Boolean,
  onSyncFirestore: () -> Unit,
  suspiciousCustomers: List<SuspiciousCustomer> = emptyList(),
  onAddSuspiciousCustomer: (phone: String, note: String) -> Unit = { _, _ -> },
  onRemoveSuspiciousCustomer: (phone: String) -> Unit = {},
  updateSourceUrl: String = "",
  lastUpdateCheckTime: String = "",
  appUpdateState: AppUpdateCheckState = AppUpdateCheckState.Idle,
  onSaveUpdateSourceUrl: (String) -> Unit = {},
  onCheckForAppUpdates: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()

  fun checkIgnoringBatteryOptimizations(): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
  }

  var hasOverlayPermission by remember {
    mutableStateOf(Settings.canDrawOverlays(context))
  }
  var hasAccessibilityPermission by remember {
    mutableStateOf(ScreenTextAccessibilityService.isEnabled(context))
  }
  var hasCallScreeningRole by remember {
    mutableStateOf(CallerAddressHelper.isCallScreeningRoleHeld(context))
  }
  var hasPhoneStatePermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  var isBatteryUnrestricted by remember {
    mutableStateOf(checkIgnoringBatteryOptimizations())
  }
  val isLensRunning by FloatingLensService.isRunningFlow.collectAsStateWithLifecycle()
  val isQuickAddRunning by FloatingQuickAddService.isRunningFlow.collectAsStateWithLifecycle()
  val isKeepAliveServiceRunning by com.example.service.BackgroundKeepAliveService.isRunningFlow.collectAsStateWithLifecycle()

  // Refresh permissions whenever returning from Android Settings
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityPermission = ScreenTextAccessibilityService.isEnabled(context)
        hasCallScreeningRole = CallerAddressHelper.isCallScreeningRoleHeld(context)
        hasPhoneStatePermission = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        isBatteryUnrestricted = checkIgnoringBatteryOptimizations()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // MediaProjection launcher to start FloatingLensService
  val projectionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      val serviceIntent = Intent(context, FloatingLensService::class.java).apply {
        putExtra(FloatingLensService.EXTRA_RESULT_CODE, result.resultCode)
        putExtra(FloatingLensService.EXTRA_RESULT_DATA, result.data)
      }
      ContextCompat.startForegroundService(context, serviceIntent)
      Toast.makeText(
        context,
        "تم تشغيل العدسة العائمة! انتقل لأي تطبيق واضغط على العدسة لفحص الأرقام",
        Toast.LENGTH_LONG
      ).show()
    } else {
      Toast.makeText(context, "يلزم السماح بقراءة الشاشة لتعمل العدسة العائمة", Toast.LENGTH_SHORT).show()
    }
  }

  fun launchScreenCaptureForLens() {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
      mpm.createScreenCaptureIntent()
    }
    projectionLauncher.launch(captureIntent)
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) {
    // Launch screen capture regardless so user isn't blocked even if notifications denied
    launchScreenCaptureForLens()
  }

  fun openOverlayPermissionSettings() {
    val intent = Intent(
      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
      Uri.parse("package:${context.packageName}")
    ).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
      val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      runCatching { context.startActivity(fallback) }
    }
    Toast.makeText(
      context,
      "فعّل خيار (الظهور فوق التطبيقات الأخرى) ثم عُد للتطبيق",
      Toast.LENGTH_LONG
    ).show()
  }

  fun openAccessibilitySettings() {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
    Toast.makeText(
      context,
      "اختر (مسار Masar) وفعّله لقراءة أرقام الزبائن من الشاشة بدقة",
      Toast.LENGTH_LONG
    ).show()
  }

  fun openBatteryUnrestrictedSettings() {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
      val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
      ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      runCatching { context.startActivity(appDetails) }
    }
    Toast.makeText(
      context,
      "ابحث عن (مسار Masar) واختر «بدون قيود / غير مقيّد» ليستمر العمل عند إغلاق التطبيق أو القفل",
      Toast.LENGTH_LONG
    ).show()
  }

  fun startQuickAddServiceNow() {
    com.example.data.local.SettingsPreferences(context.applicationContext).setFloatingQuickAddEnabledSync(true)
    val serviceIntent = Intent(context, FloatingQuickAddService::class.java)
    ContextCompat.startForegroundService(context, serviceIntent)
    Toast.makeText(
      context,
      "تم تشغيل زر (+) العائم! سيستمر بالعمل حتى عند إغلاق التطبيق",
      Toast.LENGTH_LONG
    ).show()
  }

  val quickAddNotifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) {
    startQuickAddServiceNow()
  }

  fun handleToggleFloatingQuickAdd(enable: Boolean) {
    if (!enable) {
      com.example.data.local.SettingsPreferences(context.applicationContext).setFloatingQuickAddEnabledSync(false)
      context.stopService(Intent(context, FloatingQuickAddService::class.java))
      Toast.makeText(context, "تم إيقاف زر الإضافة السريعة العائم (+)", Toast.LENGTH_SHORT).show()
      return
    }
    if (!Settings.canDrawOverlays(context)) {
      openOverlayPermissionSettings()
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      quickAddNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      startQuickAddServiceNow()
    }
  }

  fun handleToggleFloatingLens(enable: Boolean) {
    if (!enable) {
      context.stopService(Intent(context, FloatingLensService::class.java))
      Toast.makeText(context, "تم إيقاف العدسة العائمة", Toast.LENGTH_SHORT).show()
      return
    }
    if (!Settings.canDrawOverlays(context)) {
      openOverlayPermissionSettings()
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      launchScreenCaptureForLens()
    }
  }

  val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasPhoneStatePermission = granted
  }

  val callerIdRoleLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    hasCallScreeningRole = CallerAddressHelper.isCallScreeningRoleHeld(context)
    if (!hasPhoneStatePermission) {
      phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }
  }

  fun requestCallerIdRole() {
    val roleIntent = CallerAddressHelper.createCallScreeningRoleIntent(context)
    if (roleIntent != null) {
      runCatching { callerIdRoleLauncher.launch(roleIntent) }
    } else {
      hasCallScreeningRole = CallerAddressHelper.isCallScreeningRoleHeld(context)
    }
  }

  fun handleToggleCallerAddressBar(enable: Boolean) {
    if (!enable) {
      onToggleCallerAddressBar(false)
      CallAddressOverlayService.dismiss(context)
      return
    }
    if (!Settings.canDrawOverlays(context)) {
      openOverlayPermissionSettings()
      return
    }
    onToggleCallerAddressBar(true)
    val roleIntent = CallerAddressHelper.createCallScreeningRoleIntent(context)
    if (roleIntent != null) {
      runCatching { callerIdRoleLauncher.launch(roleIntent) }
    } else if (!hasPhoneStatePermission) {
      phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }
  }

  // Customer registration form state
  var regPhone by remember { mutableStateOf("") }
  var regAddress by remember { mutableStateOf("") }
  var regArea by remember { mutableStateOf("") }
  var existingCustomerHint by remember { mutableStateOf<CustomerRecord?>(null) }
  var lastVibratedRegSuspiciousPhone by remember { mutableStateOf<String?>(null) }

  // Suspicious customer watchlist form state
  var suspiciousPhoneInput by remember { mutableStateOf("") }
  var suspiciousNoteInput by remember { mutableStateOf("") }

  val matchedSuspiciousForRegister = remember(regPhone, suspiciousCustomers) {
    val norm = CustomerRecord.normalizePhoneNumber(regPhone)
    if (norm.length >= 7) suspiciousCustomers.firstOrNull { it.phone == norm } else null
  }

  LaunchedEffect(matchedSuspiciousForRegister?.phone) {
    val phone = matchedSuspiciousForRegister?.phone
    if (phone != null && phone != lastVibratedRegSuspiciousPhone) {
      VibrationHelper.vibrateSuspiciousAlert(context)
      lastVibratedRegSuspiciousPhone = phone
    } else if (phone == null) {
      lastVibratedRegSuspiciousPhone = null
    }
  }

  LaunchedEffect(prefillPhone) {
    if (prefillPhone.isNotBlank()) {
      regPhone = prefillPhone
      existingCustomerHint = onLookupCustomerByPhone(prefillPhone)
      onClearPrefillPhone()
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
      .testTag("settings_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {

    // ==========================================
    // 1. Dark / Light Mode Toggle Card
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_theme_settings")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                  if (isDarkMode) MaterialTheme.colorScheme.primaryContainer
                  else Color(0xFFFFF7ED)
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = if (isDarkMode) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "الوضع الداكن والفاتح",
                tint = if (isDarkMode) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFD97706),
                modifier = Modifier.size(22.dp)
              )
            }

            Column {
              Text(
                text = "الوضع الداكن والفاتح",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = if (isDarkMode) "الوضع الداكن قيد التشغيل" else "الوضع الفاتح قيد التشغيل",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Switch(
            checked = isDarkMode,
            onCheckedChange = { onToggleDarkMode(it) },
            modifier = Modifier.testTag("switch_dark_mode")
          )
        }

        // Quick Light / Dark visual mode status bar
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilledTonalButton(
            onClick = { onToggleDarkMode(false) },
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
              contentColor = if (!isDarkMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 42.dp)
              .testTag("btn_light_mode")
          ) {
            Icon(Icons.Filled.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("فاتح", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }

          FilledTonalButton(
            onClick = { onToggleDarkMode(true) },
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
              contentColor = if (isDarkMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 42.dp)
              .testTag("btn_dark_mode")
          ) {
            Icon(Icons.Filled.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("داكن", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }
      }
    }

    // ==========================================
    // 2. Floating Lens & Overlay Permission Card
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_floating_lens_settings")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Outlined.CenterFocusStrong,
                contentDescription = "العدسة العائمة",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
              )
            }

            Column {
              Text(
                text = "العدسة العائمة",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "التعرف على أرقام الزبائن المسجلة فوق التطبيقات وإظهار العنوان فوراً",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Switch(
            checked = isLensRunning,
            onCheckedChange = { handleToggleFloatingLens(it) },
            modifier = Modifier.testTag("switch_floating_lens")
          )
        }

        // Overlay Permission Status Box
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (hasOverlayPermission) {
            Color(0xFF16A34A).copy(alpha = 0.10f)
          } else {
            Color(0xFFD97706).copy(alpha = 0.12f)
          },
          border = BorderStroke(
            1.dp,
            if (hasOverlayPermission) Color(0xFF16A34A).copy(alpha = 0.45f)
            else Color(0xFFD97706).copy(alpha = 0.5f)
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = if (hasOverlayPermission) Icons.Filled.CheckCircle else Icons.Outlined.Layers,
                contentDescription = null,
                tint = if (hasOverlayPermission) Color(0xFF16A34A) else Color(0xFFD97706),
                modifier = Modifier.size(20.dp)
              )
              Column {
                Text(
                  text = "صلاحية الظهور فوق التطبيقات الأخرى",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = if (hasOverlayPermission) "الصلاحية مفعّلة وجاهزة" else "مطلوبة لإظهار العدسة فوق باقي التطبيقات",
                  fontSize = 11.5.sp,
                  color = if (hasOverlayPermission) Color(0xFF16A34A) else Color(0xFFB45309)
                )
              }
            }

            if (!hasOverlayPermission) {
              Button(
                onClick = { openOverlayPermissionSettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFFD97706),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_overlay_permission")
              ) {
                Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("منح الصلاحية", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        // Accessibility Service Status Box for reading Arabic screen texts (السعر / السعر الكلي / المبلغ + رمز العملة)
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (hasAccessibilityPermission) {
            Color(0xFF16A34A).copy(alpha = 0.10f)
          } else {
            Color(0xFF0284C7).copy(alpha = 0.08f)
          },
          border = BorderStroke(
            1.dp,
            if (hasAccessibilityPermission) Color(0xFF16A34A).copy(alpha = 0.45f)
            else Color(0xFF0284C7).copy(alpha = 0.40f)
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = if (hasAccessibilityPermission) Icons.Filled.CheckCircle else Icons.Outlined.CenterFocusStrong,
                contentDescription = null,
                tint = if (hasAccessibilityPermission) Color(0xFF16A34A) else Color(0xFF0284C7),
                modifier = Modifier.size(20.dp)
              )
              Column {
                Text(
                  text = "مساعد قراءة الشاشة لأرقام الزبائن",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = if (hasAccessibilityPermission) {
                    "مفعّلة • تقرأ رقم الزبون من الشاشة بدقة عند استخدام العدسة"
                  } else {
                    "فعّلها لقراءة أرقام الزبائن بدقة من تطبيقات التوصيل"
                  },
                  fontSize = 11.5.sp,
                  color = if (hasAccessibilityPermission) Color(0xFF16A34A) else Color(0xFF0369A1)
                )
              }
            }

            if (!hasAccessibilityPermission) {
              Button(
                onClick = { openAccessibilitySettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF0284C7),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_accessibility_permission")
              ) {
                Text("تفعيل القراءة", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        // Main Floating Lens Action Button
        Button(
          onClick = { handleToggleFloatingLens(!isLensRunning) },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isLensRunning) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary,
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("btn_floating_lens")
        ) {
          Icon(
            imageVector = if (isLensRunning) Icons.Outlined.Layers else Icons.Filled.RemoveRedEye,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (isLensRunning) "إيقاف العدسة العائمة" else "تشغيل العدسة العائمة الآن",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // ==========================================
    // 2.5. Floating Quick Add (+) Overlay Tool Card
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(
        width = 1.dp,
        color = if (isQuickAddRunning) Color(0xFF059669) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_floating_quick_add_settings")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF059669).copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Filled.AddCircle,
                contentDescription = "زر الإضافة السريعة العائم (+)",
                tint = Color(0xFF059669),
                modifier = Modifier.size(24.dp)
              )
            }

            Column {
              Text(
                text = "زر الإضافة السريعة العائم (+)",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "زر (+) عائم فوق تطبيق الملاحظات لقراءة وحفظ أرقام وعناوين الزبائن من الشاشة بسرعة",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Switch(
            checked = isQuickAddRunning,
            onCheckedChange = { handleToggleFloatingQuickAdd(it) },
            modifier = Modifier.testTag("switch_floating_quick_add")
          )
        }

        if (!hasAccessibilityPermission) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF059669).copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Color(0xFF059669).copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(
                  imageVector = Icons.Outlined.NoteAdd,
                  contentDescription = null,
                  tint = Color(0xFF059669),
                  modifier = Modifier.size(20.dp)
                )
                Column {
                  Text(
                    text = "القراءة التلقائية من تطبيق الملاحظات",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = "فعّل مساعد قراءة الشاشة ليسحب زر (+) الرقم والعنوان من الشاشة بضغطة واحدة",
                    fontSize = 11.5.sp,
                    color = Color(0xFF047857)
                  )
                }
              }

              Button(
                onClick = { openAccessibilitySettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF059669),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_quick_add_accessibility")
              ) {
                Text("تفعيل القراءة", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        Button(
          onClick = { handleToggleFloatingQuickAdd(!isQuickAddRunning) },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isQuickAddRunning) Color(0xFFDC2626) else Color(0xFF059669),
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("btn_floating_quick_add")
        ) {
          Icon(
            imageVector = if (isQuickAddRunning) Icons.Outlined.Layers else Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(19.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (isQuickAddRunning) "إيقاف زر الإضافة العائم (+)" else "تشغيل زر الإضافة العائم (+)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // ==========================================
    // 2.6. Caller ID Floating Address Bar Card (شريط عنوان الزبون أثناء الاتصال - مثل ترو كلر)
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(
        width = if (isCallerAddressBarEnabled) 1.5.dp else 1.dp,
        color = if (isCallerAddressBarEnabled) Color(0xFF0284C7) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_caller_address_bar_settings")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF0284C7).copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Filled.Phone,
                contentDescription = "شريط عنوان المتصل العائم",
                tint = Color(0xFF0284C7),
                modifier = Modifier.size(22.dp)
              )
            }

            Column {
              Text(
                text = "شريط عنوان المتصل العائم (مثل ترو كلر)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "يظهر شريط رفيع يعرض عنوان الزبون عند الاتصال أو ورود مكالمات السيم كارد وواتساب، ولا يختفي إلا عند إغلاقه بيدك",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Switch(
            checked = isCallerAddressBarEnabled,
            onCheckedChange = { handleToggleCallerAddressBar(it) },
            modifier = Modifier.testTag("switch_caller_address_bar")
          )
        }

        if (!hasOverlayPermission) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFD97706).copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color(0xFFD97706).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = "صلاحية الظهور فوق التطبيقات",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "مطلوبة لإظهار الشريط الرفيع فوق شاشة المكالمة",
                  fontSize = 11.5.sp,
                  color = Color(0xFFB45309)
                )
              }
              Button(
                onClick = { openOverlayPermissionSettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFFD97706),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_caller_overlay_permission")
              ) {
                Text("منح الصلاحية", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        if (CallerAddressHelper.isCallScreeningRoleAvailable(context)) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (hasCallScreeningRole) {
              Color(0xFF16A34A).copy(alpha = 0.10f)
            } else {
              Color(0xFF0284C7).copy(alpha = 0.08f)
            },
            border = BorderStroke(
              1.dp,
              if (hasCallScreeningRole) Color(0xFF16A34A).copy(alpha = 0.45f)
              else Color(0xFF0284C7).copy(alpha = 0.40f)
            ),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
              ) {
                Icon(
                  imageVector = if (hasCallScreeningRole) Icons.Filled.CheckCircle else Icons.Outlined.Phone,
                  contentDescription = null,
                  tint = if (hasCallScreeningRole) Color(0xFF16A34A) else Color(0xFF0284C7),
                  modifier = Modifier.size(20.dp)
                )
                Column {
                  Text(
                    text = "تحديد المتصل (معرّف المكالمات)",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = if (hasCallScreeningRole) {
                      "مفعّل • يقرأ رقم المكالمات الصادرة والواردة تلقائياً"
                    } else {
                      "فعّله لظهور الشريط حتى عند الاتصال من سجل الهاتف مباشرة"
                    },
                    fontSize = 11.5.sp,
                    color = if (hasCallScreeningRole) Color(0xFF16A34A) else Color(0xFF0369A1)
                  )
                }
              }

              if (!hasCallScreeningRole) {
                Button(
                  onClick = { requestCallerIdRole() },
                  colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0284C7),
                    contentColor = Color.White
                  ),
                  shape = RoundedCornerShape(8.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                  modifier = Modifier
                    .heightIn(min = 38.dp)
                    .testTag("btn_caller_id_role")
                ) {
                  Text("تفعيل", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }

        if (!hasPhoneStatePermission) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = "اكتشاف مكالمات شريحة السيم كارد (SIM)",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "اسمح بصلاحية حالة الهاتف لاكتشاف المكالمات الواردة والصادرة على السيم كارد فوراً",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              OutlinedButton(
                onClick = { phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                  .heightIn(min = 36.dp)
                  .testTag("btn_caller_phone_permission")
              ) {
                Text("سماح", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        // Accessibility integration for WhatsApp incoming/outgoing calls, SIM Card calls, Saved Contacts & OEM Dialers
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (hasAccessibilityPermission) {
            Color(0xFF16A34A).copy(alpha = 0.10f)
          } else {
            Color(0xFF0284C7).copy(alpha = 0.08f)
          },
          border = BorderStroke(
            1.dp,
            if (hasAccessibilityPermission) Color(0xFF16A34A).copy(alpha = 0.45f)
            else Color(0xFF0284C7).copy(alpha = 0.40f)
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
              Icon(
                imageVector = if (hasAccessibilityPermission) Icons.Filled.CheckCircle else Icons.Outlined.Phone,
                contentDescription = null,
                tint = if (hasAccessibilityPermission) Color(0xFF16A34A) else Color(0xFF0284C7),
                modifier = Modifier.size(20.dp)
              )
              Column {
                Text(
                  text = "مكالمات واتساب والسيم كارد الواردة والصادرة",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = if (hasAccessibilityPermission) {
                    "مفعّل • يظهر العنوان تلقائياً في مكالمات السيم كارد الواردة وواتساب وتيليجرام حتى للأرقام المحفوظة"
                  } else {
                    "فعّل مساعد قراءة الشاشة ليعمل الشريط مع مكالمات واتساب الواردة والسيم كارد والأسماء المحفوظة"
                  },
                  fontSize = 11.sp,
                  color = if (hasAccessibilityPermission) Color(0xFF16A34A) else Color(0xFF0369A1)
                )
              }
            }

            if (!hasAccessibilityPermission) {
              Button(
                onClick = { openAccessibilitySettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF0284C7),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_caller_accessibility_permission")
              ) {
                Text("تفعيل", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        Button(
          onClick = { handleToggleCallerAddressBar(!isCallerAddressBarEnabled) },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isCallerAddressBarEnabled) Color(0xFFDC2626) else Color(0xFF0284C7),
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("btn_toggle_caller_address_bar")
        ) {
          Icon(
            imageVector = Icons.Filled.Phone,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (isCallerAddressBarEnabled) {
              "إيقاف شريط عنوان المتصل العائم"
            } else {
              "تفعيل شريط عنوان المتصل العائم"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }

        if (isCallerAddressBarEnabled && hasOverlayPermission) {
          OutlinedButton(
            onClick = {
              val firstCustomer = registeredCustomers.firstOrNull { it.writtenAddress.isNotBlank() }
              if (firstCustomer != null) {
                val suspicious = suspiciousCustomers.firstOrNull { it.phone == firstCustomer.phone }
                CallAddressOverlayService.showForLookup(
                  context = context,
                  result = CallerAddressLookupResult(
                    phone = firstCustomer.originalPhone.ifBlank { firstCustomer.phone },
                    normalizedPhone = firstCustomer.phone,
                    customer = firstCustomer,
                    suspicious = suspicious
                  ),
                  isPreview = true
                )
              } else {
                Toast.makeText(
                  context,
                  "سجّل زبوناً واحداً على الأقل في قاعدة البيانات لمعاينة الشريط برقم حقيقي",
                  Toast.LENGTH_LONG
                ).show()
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 42.dp)
              .testTag("btn_preview_caller_address_bar")
          ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("معاينة شكل الشريط العائم الآن", fontSize = 13.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }

    // ==========================================
    // 2.7. Background Persistence & Battery Keep-Alive Card
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(
        width = if (isBackgroundKeepAliveEnabled) 1.5.dp else 1.dp,
        color = if (isBackgroundKeepAliveEnabled) Color(0xFF059669) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_background_keep_alive")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF059669).copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Filled.CloudSync,
                contentDescription = "استمرار العمل بالخلفية",
                tint = Color(0xFF059669),
                modifier = Modifier.size(22.dp)
              )
            }

            Column {
              Text(
                text = "استمرار العمل بالخلفية حتى عند الإقفال",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = if (isKeepAliveServiceRunning || isBackgroundKeepAliveEnabled) {
                  "نشط • تستمر قراءة المكالمات والأدوات العائمة حتى بعد إغلاق التطبيق أو قفل الشاشة"
                } else {
                  "فعّله لضمان استمرار عمل التطبيق بالخلفية وعدم توقفه بعد مسح التطبيقات المفتوحة"
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = if (isBackgroundKeepAliveEnabled) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Switch(
            checked = isBackgroundKeepAliveEnabled,
            onCheckedChange = { onToggleBackgroundKeepAlive(it) },
            modifier = Modifier.testTag("switch_background_keep_alive")
          )
        }

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (isBatteryUnrestricted) {
            Color(0xFF16A34A).copy(alpha = 0.10f)
          } else {
            Color(0xFFD97706).copy(alpha = 0.10f)
          },
          border = BorderStroke(
            1.dp,
            if (isBatteryUnrestricted) Color(0xFF16A34A).copy(alpha = 0.45f)
            else Color(0xFFD97706).copy(alpha = 0.45f)
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = if (isBatteryUnrestricted) "البطارية: بدون قيود ✓" else "تثبيت العمل بالخلفية (بدون قيود البطارية)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = if (isBatteryUnrestricted) {
                  "لن يغلق النظام خدمات التطبيق في الخلفية"
                } else {
                  "يمنع نظام الهاتف من إيقاف التطبيق عند قفل الشاشة"
                },
                fontSize = 11.sp,
                color = if (isBatteryUnrestricted) Color(0xFF16A34A) else Color(0xFFB45309)
              )
            }

            if (!isBatteryUnrestricted) {
              Button(
                onClick = { openBatteryUnrestrictedSettings() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFFD97706),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 38.dp)
                  .testTag("btn_battery_unrestricted")
              ) {
                Text("إلغاء القيود", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }

    // ==========================================
    // 3. Suspicious Customers Watchlist (الزبائن المشبوهون بسرقة الطلبات)
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.2.dp, Color(0xFFDC2626).copy(alpha = 0.45f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_suspicious_customers")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(Color(0xFFDC2626).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Outlined.ReportProblem,
              contentDescription = "الزبائن المشبوهون",
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(21.dp)
            )
          }
          Column {
            Text(
              text = "الزبائن المشبوهون بسرقة الطلبات (${suspiciousCustomers.size})",
              fontSize = 15.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "أضف أرقام الزبائن المشبوهين ليتم تنبيهك باهتزاز الهاتف وملاحظة تحذيرية فور إدخال الرقم أو فحصه",
              fontSize = 12.sp,
              lineHeight = 17.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        OutlinedTextField(
          value = suspiciousPhoneInput,
          onValueChange = { suspiciousPhoneInput = it },
          label = { Text("رقم هاتف الزبون المشبوه") },
          placeholder = { Text("مثال: 07710266414") },
          leadingIcon = {
            Icon(Icons.Outlined.Phone, contentDescription = null, tint = Color(0xFFDC2626))
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
          singleLine = true,
          colors = darkSlateTextFieldColors(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_suspicious_phone")
        )

        OutlinedTextField(
          value = suspiciousNoteInput,
          onValueChange = { suspiciousNoteInput = it },
          label = { Text("سبب التحذير / ملاحظة (اختياري)") },
          placeholder = { Text("مثال: استلم الطلب ولم يدفع / زبون مشبوه") },
          singleLine = true,
          colors = darkSlateTextFieldColors(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_suspicious_note")
        )

        Button(
          onClick = {
            val cleanPhone = suspiciousPhoneInput.trim()
            val norm = CustomerRecord.normalizePhoneNumber(cleanPhone)
            if (norm.length < 7) {
              Toast.makeText(context, "يرجى إدخال رقم هاتف صحيح (7 أرقام على الأقل)", Toast.LENGTH_SHORT).show()
              return@Button
            }
            onAddSuspiciousCustomer(cleanPhone, suspiciousNoteInput.trim())
            suspiciousPhoneInput = ""
            suspiciousNoteInput = ""
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFDC2626),
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("btn_add_suspicious_phone")
        ) {
          Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("إضافة رقم للقائمة المشبوهة", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        if (suspiciousCustomers.isNotEmpty()) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("suspicious_customers_list")
          ) {
            suspiciousCustomers.forEach { item ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFFEF2F2),
                border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("suspicious_item_${item.phone}")
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                  ) {
                    Text(
                      text = item.originalPhone.ifBlank { item.phone },
                      fontSize = 14.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF991B1B)
                    )
                    Text(
                      text = item.note,
                      fontSize = 12.sp,
                      color = Color(0xFFB91C1C)
                    )
                  }
                  IconButton(
                    onClick = { onRemoveSuspiciousCustomer(item.phone) },
                    modifier = Modifier
                      .size(48.dp)
                      .testTag("btn_remove_suspicious_${item.phone}")
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Delete,
                      contentDescription = "إزالة الرقم من القائمة المشبوهة",
                      tint = Color(0xFFDC2626),
                      modifier = Modifier.size(20.dp)
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // ==========================================
    // 4. Register Customer Phone & Address Card
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_register_customer")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(Color(0xFF059669).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Filled.PersonAdd,
              contentDescription = "تسجيل زبون",
              tint = Color(0xFF059669),
              modifier = Modifier.size(20.dp)
            )
          }
          Column {
            Text(
              text = "تسجيل رقم وعنوان زبون",
              fontSize = 15.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "احفظ الرقم والعنوان في قاعدة البيانات لتتعرف عليه العدسة العائمة تلقائياً",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        OutlinedTextField(
          value = regPhone,
          onValueChange = { input ->
            regPhone = input
            val norm = CustomerRecord.normalizePhoneNumber(input)
            if (norm.length >= 7) {
              coroutineScope.launch {
                existingCustomerHint = onLookupCustomerByPhone(norm)
              }
            } else {
              existingCustomerHint = null
            }
          },
          label = { Text("رقم هاتف الزبون") },
          placeholder = { Text("مثال: 07710266414") },
          leadingIcon = {
            Icon(Icons.Outlined.Phone, contentDescription = null)
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
          singleLine = true,
          colors = darkSlateTextFieldColors(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_register_customer_phone")
        )

        matchedSuspiciousForRegister?.let { sus ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFFEF2F2),
            border = BorderStroke(1.5.dp, Color(0xFFDC2626)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("warning_suspicious_in_register")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "تحذير زبون مشبوه",
                tint = Color(0xFFDC2626),
                modifier = Modifier.size(20.dp)
              )
              Column {
                Text(
                  text = "تحذير: هذا الزبون مشبوه بسرقة الطلبات!",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF991B1B)
                )
                if (sus.note.isNotBlank()) {
                  Text(
                    text = "الملاحظة: ${sus.note}",
                    fontSize = 11.5.sp,
                    color = Color(0xFFB91C1C)
                  )
                }
              }
            }
          }
        }

        existingCustomerHint?.let { match ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "الرقم مسجل مسبقاً بعنوان: ${match.writtenAddress} (الحفظ سيحدّث العنوان)",
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
          }
        }

        OutlinedTextField(
          value = regAddress,
          onValueChange = { regAddress = it },
          label = { Text("عنوان الزبون") },
          placeholder = { Text("مثال: بغداد - المنصور - شارع 14 رمضان") },
          leadingIcon = {
            Icon(Icons.Outlined.LocationOn, contentDescription = null)
          },
          colors = darkSlateTextFieldColors(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_register_customer_address")
        )

        OutlinedTextField(
          value = regArea,
          onValueChange = { regArea = it },
          label = { Text("تفاصيل إضافية / نقطة دالة (اختياري)") },
          placeholder = { Text("مثال: قرب تقاطع الرواد، دار 12") },
          colors = darkSlateTextFieldColors(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_register_customer_area")
        )

        Button(
          onClick = {
            val p = regPhone.trim()
            val a = regAddress.trim()
            val ar = regArea.trim()
            val norm = CustomerRecord.normalizePhoneNumber(p)
            if (norm.length < 7) {
              Toast.makeText(context, "يرجى إدخال رقم هاتف صحيح", Toast.LENGTH_SHORT).show()
              return@Button
            }
            if (a.isBlank()) {
              Toast.makeText(context, "يرجى إدخال عنوان الزبون", Toast.LENGTH_SHORT).show()
              return@Button
            }
            onRegisterCustomer(p, a, ar)
            regPhone = ""
            regAddress = ""
            regArea = ""
            existingCustomerHint = null
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF059669),
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("btn_register_customer")
        ) {
          Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("حفظ رقم وعنوان الزبون", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
      }
    }

    // ==========================================
    // 4. Registered Customers Database Summary
    // ==========================================
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("card_registered_customers_list")
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Outlined.PeopleOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp)
            )
            Text(
              text = "الزبائن المسجلون (${registeredCustomers.size})",
              fontSize = 14.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          FilledTonalButton(
            onClick = onSyncFirestore,
            enabled = !isFirestoreSyncing,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp)
          ) {
            Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (isFirestoreSyncing) "جاري المزامنة..." else "مزامنة",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        if (registeredCustomers.isEmpty()) {
          Text(
            text = "لا يوجد زبائن مسجلون بعد. أضف رقم وعنوان الزبون بالأعلى أو من خلال الطلبات.",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          registeredCustomers.take(8).forEach { customer ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = null,
                    tint = Color(0xFF059669),
                    modifier = Modifier.size(14.dp)
                  )
                  Text(
                    text = customer.originalPhone.ifBlank { customer.phone },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                  )
                  Text(
                    text = buildString {
                      append(customer.writtenAddress)
                      if (customer.areaSubtitle.isNotBlank()) {
                        append(" — ")
                        append(customer.areaSubtitle)
                      }
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        }
      }
    }

    // ==========================================
    // 5. App Updates & Release Checker (تحديث التطبيق - أسفل قائمة الإعدادات)
    // ==========================================
    AppUpdateSettingsCard(
      updateSourceUrl = updateSourceUrl,
      lastUpdateCheckTime = lastUpdateCheckTime,
      appUpdateState = appUpdateState,
      onSaveUpdateSourceUrl = onSaveUpdateSourceUrl,
      onCheckForAppUpdates = onCheckForAppUpdates
    )
  }
}
