package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.DeliveryApplication
import com.example.data.local.SettingsPreferences
import com.example.data.repository.DeliveryRepository
import com.example.model.CustomerRecord
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.model.NoteIconType
import com.example.model.SuspiciousCustomer
import com.example.util.AppUpdateCheckState
import com.example.util.AppUpdateChecker
import com.example.util.OcrResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

enum class SortOrder {
  DEFAULT,
  ALPHABETICAL
}

private val arabicCollator = Collator.getInstance(Locale("ar")).apply {
  strength = Collator.PRIMARY
  decomposition = Collator.CANONICAL_DECOMPOSITION
}

fun sortStopsByArabicAddress(list: List<DeliveryStop>): List<DeliveryStop> {
  return list.sortedWith { a, b ->
    val tA = if (a.title.startsWith("حي ")) a.title.substring(3).trim() else a.title.trim()
    val tB = if (b.title.startsWith("حي ")) b.title.substring(3).trim() else b.title.trim()
    val comp = arabicCollator.compare(tA, tB)
    if (comp != 0) comp else arabicCollator.compare(a.subtitle, b.subtitle)
  }
}

data class DeliveryUiState(
  val stops: List<DeliveryStop> = emptyList(),
  val categories: List<DeliveryCategory> = DeliveryCategory.DEFAULT_CATEGORIES,
  val selectedCategoryId: String? = "IN_TRANSIT", // Defaults to "قيد التوصيل" (In Transit) upon app launch
  val searchQuery: String = "",
  val sortOrder: SortOrder = SortOrder.ALPHABETICAL,
  val selectedStop: DeliveryStop? = null,
  val isDetailSheetOpen: Boolean = false,
  val isAddDialogOpen: Boolean = false,
  val isQrScannerOpen: Boolean = false,
  val isShowingDeleted: Boolean = false, // When true, displays "الطلبات المحذوفة"
  val isManageCategoriesDialogOpen: Boolean = false,
  val isFirestoreSyncing: Boolean = false,
  val syncedCustomersCount: Int = 0,
  val lastFirestoreSyncTime: String? = null,
  val matchedCustomerForCurrentOrder: CustomerRecord? = null,
  val isDarkMode: Boolean = false,
  val isCallerAddressBarEnabled: Boolean = false,
  val isBackgroundKeepAliveEnabled: Boolean = true,
  val registeredCustomers: List<CustomerRecord> = emptyList(),
  val suspiciousCustomers: List<SuspiciousCustomer> = emptyList(),
  val prefillCustomerPhone: String = "",
  val isSelectionMode: Boolean = false,
  val selectedStopIds: Set<String> = emptySet(),
  val isAppRefreshing: Boolean = false,
  val refreshGeneration: Int = 0,
  val updateSourceUrl: String = "",
  val lastUpdateCheckTime: String = "",
  val appUpdateState: AppUpdateCheckState = AppUpdateCheckState.Idle,
  val userMessage: String? = null
) {
  val selectedCount: Int get() = selectedStopIds.size
  val areAllFilteredSelected: Boolean
    get() = filteredStops.isNotEmpty() && filteredStops.all { selectedStopIds.contains(it.id) }
  val activeStops: List<DeliveryStop> get() = stops.filter { !it.isDeleted }
  val deletedStops: List<DeliveryStop> get() = stops.filter { it.isDeleted }
  val activeCount: Int get() = activeStops.size
  val deletedCount: Int get() = deletedStops.size

  fun countForCategoryId(catId: String): Int {
    return stops.count { stop ->
      !stop.isDeleted && (stop.categoryId == catId || (stop.categoryId.isBlank() && stop.status.name == catId))
    }
  }

  val selectedCategory: DeliveryCategory?
    get() = categories.firstOrNull { it.id == selectedCategoryId }

  val filteredStops: List<DeliveryStop>
    get() {
      val source = if (isShowingDeleted) deletedStops else activeStops
      val query = searchQuery.trim()
      val digitsQuery = query.filter { it.isDigit() }
      val base = source.filter { stop ->
        val matchesCategory = isShowingDeleted || query.isNotBlank() || selectedCategoryId == null ||
            stop.categoryId == selectedCategoryId ||
            (stop.categoryId.isBlank() && stop.status.name == selectedCategoryId)

        val matchesQuery = if (query.isBlank()) {
          true
        } else {
          fun norm(s: String) = s.lowercase()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")

          val normQ = norm(query)

          // 1. Search by Customer Address (العنوان)
          val matchesAddress = norm(stop.title).contains(normQ) ||
              norm(stop.subtitle).contains(normQ) ||
              norm(stop.governorate).contains(normQ) ||
              norm(stop.region).contains(normQ)

          // 2. Search by Order Number (رقم الطلب)
          val matchesOrderNumber = stop.orderCodeNumber.contains(query, ignoreCase = true) ||
              stop.orderNumber.contains(query, ignoreCase = true) ||
              (digitsQuery.length >= 2 && (
                  stop.orderCodeNumber.filter { it.isDigit() }.contains(digitsQuery) ||
                  stop.orderNumber.filter { it.isDigit() }.contains(digitsQuery)
              ))

          // 3. Search by Customer Phone Number (رقم الزبون)
          val matchesPhone = stop.phone1.contains(query, ignoreCase = true) ||
              stop.phone2.contains(query, ignoreCase = true) ||
              (digitsQuery.length >= 3 && (
                  stop.phone1.filter { it.isDigit() }.contains(digitsQuery) ||
                  stop.phone2.filter { it.isDigit() }.contains(digitsQuery)
              ))

          matchesAddress || matchesOrderNumber || matchesPhone
        }

        matchesCategory && matchesQuery
      }
      return sortStopsByArabicAddress(base)
    }

  fun countForStatus(status: DeliveryStatus): Int = countForCategoryId(status.name)
  val selectedFilter: DeliveryStatus?
    get() = runCatching { selectedCategoryId?.let { DeliveryStatus.valueOf(it) } }.getOrNull()
}

class DeliveryViewModel @JvmOverloads constructor(
  application: Application? = null
) : ViewModel() {

  private val appContext: Context? =
    application ?: runCatching { DeliveryApplication.instance }.getOrNull()

  private val repository: DeliveryRepository? =
    DeliveryRepository.getInstance(appContext)

  private val settingsPreferences: SettingsPreferences =
    SettingsPreferences(appContext)

  private val _uiState = MutableStateFlow(DeliveryUiState())
  val uiState: StateFlow<DeliveryUiState> = _uiState.asStateFlow()

  private val deletedInSessionIds = mutableSetOf<String>()
  private val stateWriteLock = Any()
  private var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null
  private var autoRefreshJob: Job? = null

  init {
    observeThemePreference()
    observeCallerAddressBarPreference()
    observeBackgroundKeepAlivePreference()
    loadUpdatePreferences()
    loadSavedCategories()
    loadSuspiciousCustomers()
    loadAndObserveSavedStops()
    loadRegisteredCustomers()
    startRealtimeFirestoreSync()
  }

  private fun observeThemePreference() {
    viewModelScope.launch {
      settingsPreferences.isDarkModeFlow.collect { enabled ->
        _uiState.update { it.copy(isDarkMode = enabled) }
      }
    }
  }

  private fun observeCallerAddressBarPreference() {
    val initialSync = settingsPreferences.isCallerAddressBarEnabledSync()
    if (initialSync) {
      _uiState.update { it.copy(isCallerAddressBarEnabled = true) }
    }
    viewModelScope.launch {
      settingsPreferences.isCallerAddressBarEnabledFlow.collect { enabled ->
        _uiState.update { it.copy(isCallerAddressBarEnabled = enabled) }
      }
    }
  }

  private fun observeBackgroundKeepAlivePreference() {
    val initialSync = settingsPreferences.isBackgroundKeepAliveEnabledSync()
    _uiState.update { it.copy(isBackgroundKeepAliveEnabled = initialSync) }
    viewModelScope.launch {
      settingsPreferences.isBackgroundKeepAliveEnabledFlow.collect { enabled ->
        _uiState.update { it.copy(isBackgroundKeepAliveEnabled = enabled) }
      }
    }
  }

  fun setBackgroundKeepAliveEnabled(enabled: Boolean) {
    settingsPreferences.setBackgroundKeepAliveEnabledSync(enabled)
    _uiState.update {
      it.copy(
        isBackgroundKeepAliveEnabled = enabled,
        userMessage = if (enabled) {
          "تم تفعيل استمرار العمل بالخلفية حتى عند إقفال التطبيق أو قفل الشاشة"
        } else {
          "تم إيقاف خدمة البقاء النشط في الخلفية"
        }
      )
    }
    viewModelScope.launch {
      settingsPreferences.setBackgroundKeepAliveEnabled(enabled)
    }
    appContext?.let { ctx ->
      com.example.service.BackgroundKeepAliveService.syncWithPreferences(ctx)
    }
  }

  private fun loadUpdatePreferences() {
    val savedUrl = settingsPreferences.getUpdateSourceUrlSync()
    val savedTime = settingsPreferences.getLastUpdateCheckTimeSync()
    if (savedUrl.isNotBlank() || savedTime.isNotBlank()) {
      _uiState.update {
        it.copy(
          updateSourceUrl = savedUrl,
          lastUpdateCheckTime = savedTime
        )
      }
    }
  }

  fun saveUpdateSourceUrl(rawUrl: String) {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
      settingsPreferences.setUpdateSourceUrlSync("")
      _uiState.update {
        it.copy(
          updateSourceUrl = "",
          appUpdateState = AppUpdateCheckState.Idle,
          userMessage = "تم مسح رابط مصدر التحديثات"
        )
      }
      return
    }
    val normalized = AppUpdateChecker.normalizeUpdateEndpoint(trimmed)
    if (normalized == null) {
      _uiState.update {
        it.copy(
          appUpdateState = AppUpdateCheckState.Error(
            "الرابط غير صالح. استخدم رابط HTTPS صحيح أو رابط مستودع GitHub مثل: https://github.com/user/repo"
          ),
          userMessage = "يرجى إدخال رابط HTTPS أو رابط GitHub صحيح"
        )
      }
      return
    }
    settingsPreferences.setUpdateSourceUrlSync(trimmed)
    _uiState.update {
      it.copy(
        updateSourceUrl = trimmed,
        appUpdateState = AppUpdateCheckState.Idle,
        userMessage = "تم حفظ رابط مصدر التحديثات بنجاح!"
      )
    }
  }

  fun checkForAppUpdates() {
    val sourceUrl = _uiState.value.updateSourceUrl.ifBlank {
      settingsPreferences.getUpdateSourceUrlSync()
    }
    if (sourceUrl.isBlank()) {
      _uiState.update {
        it.copy(
          appUpdateState = AppUpdateCheckState.NeedsSourceUrl(
            "أضف رابط مستودع GitHub أو رابط ملف التحديثات (HTTPS JSON) أولاً للتحقق من وجود إصدار أحدث."
          )
        )
      }
      return
    }

    _uiState.update { it.copy(appUpdateState = AppUpdateCheckState.Checking) }
    viewModelScope.launch {
      val result = AppUpdateChecker.checkForUpdate(
        rawSourceUrl = sourceUrl,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentVersionName = BuildConfig.VERSION_NAME
      )
      val checkedAt = when (result) {
        is AppUpdateCheckState.UpToDate -> result.checkedAt
        is AppUpdateCheckState.UpdateAvailable -> result.checkedAt
        else -> _uiState.value.lastUpdateCheckTime
      }
      if (checkedAt.isNotBlank()) {
        settingsPreferences.setLastUpdateCheckTimeSync(checkedAt)
      }
      val snackbarMsg = when (result) {
        is AppUpdateCheckState.UpdateAvailable ->
          "يتوفر إصدار جديد (${result.release.versionName})! يمكنك فتح صفحة التحديث الآن."
        is AppUpdateCheckState.UpToDate ->
          "التطبيق محدّث بالفعل إلى آخر إصدار (${result.currentVersionName})"
        else -> null
      }
      _uiState.update { state ->
        state.copy(
          appUpdateState = result,
          lastUpdateCheckTime = checkedAt,
          userMessage = snackbarMsg ?: state.userMessage
        )
      }
    }
  }

  fun toggleDarkMode(enabled: Boolean) {
    _uiState.update {
      it.copy(
        isDarkMode = enabled,
        userMessage = if (enabled) "تم تفعيل الوضع الداكن" else "تم تفعيل الوضع الفاتح"
      )
    }
    viewModelScope.launch {
      settingsPreferences.setDarkMode(enabled)
    }
  }

  fun setCallerAddressBarEnabled(enabled: Boolean) {
    _uiState.update {
      it.copy(
        isCallerAddressBarEnabled = enabled,
        userMessage = if (enabled) {
          "تم تفعيل الشريط العائم لعنوان الزبون أثناء الاتصال"
        } else {
          "تم إيقاف الشريط العائم لعنوان الزبون أثناء الاتصال"
        }
      )
    }
    viewModelScope.launch {
      settingsPreferences.setCallerAddressBarEnabled(enabled)
    }
    if (enabled) {
      appContext?.let { com.example.service.BackgroundKeepAliveService.syncWithPreferences(it) }
    } else {
      appContext?.let { com.example.service.CallAddressOverlayService.dismiss(it) }
    }
  }

  fun loadRegisteredCustomers() {
    val repo = repository ?: return
    viewModelScope.launch {
      val list = repo.getAllCustomers()
      _uiState.update {
        it.copy(
          registeredCustomers = list,
          syncedCustomersCount = list.size.coerceAtLeast(it.syncedCustomersCount)
        )
      }
    }
  }

  fun registerCustomer(phone: String, address: String, area: String = "") {
    val cleanPhone = phone.trim()
    val cleanAddress = address.trim()
    val cleanArea = area.trim()
    val normalized = CustomerRecord.normalizePhoneNumber(cleanPhone)

    if (normalized.length < 7) {
      _uiState.update { it.copy(userMessage = "يرجى إدخال رقم هاتف صحيح") }
      return
    }
    if (cleanAddress.isBlank()) {
      _uiState.update { it.copy(userMessage = "يرجى إدخال عنوان الزبون") }
      return
    }

    viewModelScope.launch {
      val existing = repository?.findCustomerByPhone(normalized)
      val customer = CustomerRecord(
        phone = normalized,
        originalPhone = cleanPhone,
        writtenAddress = cleanAddress,
        areaSubtitle = cleanArea,
        locationCoordinates = existing?.locationCoordinates,
        latitude = existing?.latitude,
        longitude = existing?.longitude,
        mapUrl = existing?.mapUrl ?: "",
        lastUpdated = "الآن"
      )
      repository?.saveCustomer(customer)
      val updatedList = repository?.getAllCustomers() ?: listOf(customer)
      _uiState.update {
        it.copy(
          registeredCustomers = updatedList,
          syncedCustomersCount = updatedList.size,
          prefillCustomerPhone = "",
          userMessage = "تم تسجيل رقم وعنوان الزبون في قاعدة البيانات بنجاح!"
        )
      }
    }
  }

  fun setPrefillCustomerPhone(phone: String) {
    _uiState.update { it.copy(prefillCustomerPhone = phone) }
  }

  fun clearPrefillCustomerPhone() {
    _uiState.update { it.copy(prefillCustomerPhone = "") }
  }

  // ==========================================
  // Suspicious Customers Watchlist (الزبائن المشبوهون)
  // ==========================================

  private fun loadSuspiciousCustomers() {
    val repo = repository ?: return
    _uiState.update { it.copy(suspiciousCustomers = repo.loadSuspiciousCustomers()) }
    viewModelScope.launch {
      repo.suspiciousCustomersFlow.collect { list ->
        _uiState.update { it.copy(suspiciousCustomers = list) }
      }
    }
  }

  fun addSuspiciousCustomer(phone: String, note: String = "") {
    val normalized = CustomerRecord.normalizePhoneNumber(phone)
    if (normalized.length < 7) {
      _uiState.update { it.copy(userMessage = "يرجى إدخال رقم هاتف صحيح (7 أرقام على الأقل)") }
      return
    }
    val finalNote = note.trim().ifBlank { SuspiciousCustomer.DEFAULT_NOTE }
    val saved = repository?.addSuspiciousCustomer(phone, finalNote) ?: run {
      val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(java.util.Date())
      SuspiciousCustomer(
        phone = normalized,
        originalPhone = phone.trim().ifBlank { normalized },
        note = finalNote,
        addedAt = timestamp
      )
    }
    _uiState.update { state ->
      val updated = listOf(saved) + state.suspiciousCustomers.filter { it.phone != saved.phone }
      state.copy(
        suspiciousCustomers = updated,
        userMessage = "تمت إضافة الرقم (${saved.originalPhone}) إلى قائمة الزبائن المشبوهين"
      )
    }
  }

  fun removeSuspiciousCustomer(phone: String) {
    val normalized = CustomerRecord.normalizePhoneNumber(phone)
    if (normalized.isBlank()) return
    repository?.removeSuspiciousCustomer(normalized)
    _uiState.update { state ->
      state.copy(
        suspiciousCustomers = state.suspiciousCustomers.filter { it.phone != normalized },
        userMessage = "تمت إزالة الرقم من قائمة الزبائن المشبوهين"
      )
    }
  }

  fun findSuspiciousCustomer(vararg phones: String): SuspiciousCustomer? {
    val current = _uiState.value.suspiciousCustomers
    for (p in phones) {
      val normalized = CustomerRecord.normalizePhoneNumber(p)
      if (normalized.length >= 7) {
        current.firstOrNull { it.phone == normalized }?.let { return it }
        repository?.findSuspiciousByPhone(normalized)?.let { return it }
      }
    }
    return null
  }

  private fun startRealtimeFirestoreSync() {
    val repo = repository ?: return
    firestoreListener?.remove()
    firestoreListener = repo.startRealtimeSync { count ->
      viewModelScope.launch {
        val list = repo.getAllCustomers()
        _uiState.update {
          it.copy(
            syncedCustomersCount = count,
            registeredCustomers = list
          )
        }
      }
    }
    syncFirestore(silent = true)
  }

  override fun onCleared() {
    super.onCleared()
    persistAndFlushBlocking(1500L)
    firestoreListener?.remove()
  }

  fun syncFirestore(silent: Boolean = false) {
    val repo = repository ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isFirestoreSyncing = true) }
      val result = repo.syncWithFirestore()
      val nowStr = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
      if (result.isSuccess) {
        val count = result.getOrDefault(0)
        val refreshed = repo.getAllCustomers()
        _uiState.update {
          it.copy(
            isFirestoreSyncing = false,
            registeredCustomers = refreshed,
            syncedCustomersCount = count,
            lastFirestoreSyncTime = nowStr,
            userMessage = if (!silent) "تمت المزامنة بنجاح مع Firebase Firestore السحابية ($count زبون)!" else null
          )
        }
      } else {
        val err = result.exceptionOrNull()?.message ?: "خطأ في الاتصال"
        _uiState.update {
          it.copy(
            isFirestoreSyncing = false,
            userMessage = if (!silent) "تنبيه Firestore: يعمل التطبيق بوضع عدم الاتصال (Offline) - $err" else null
          )
        }
      }
    }
  }

  suspend fun findCustomerByPhone(phone: String): CustomerRecord? {
    return repository?.findCustomerByPhone(phone)
  }

  fun checkCustomerForPhone(phone: String) {
    val clean = CustomerRecord.normalizePhoneNumber(phone)
    if (clean.length < 7) {
      _uiState.update { it.copy(matchedCustomerForCurrentOrder = null) }
      return
    }
    viewModelScope.launch {
      val match = repository?.findCustomerByPhone(phone)
      _uiState.update { it.copy(matchedCustomerForCurrentOrder = match) }
    }
  }

  fun clearMatchedCustomer() {
    _uiState.update { it.copy(matchedCustomerForCurrentOrder = null) }
  }

  private fun loadAndObserveSavedStops() {
    val repo = repository ?: return
    viewModelScope.launch {
      loadSavedStopsInternal(repo)
      repo.allStopsFlow.collect { dbStops ->
        if (dbStops.isNotEmpty()) {
          mergeDbStopsIntoState(dbStops)
        }
      }
    }
  }

  fun loadSavedStops(preferDb: Boolean = false) {
    val repo = repository ?: return
    viewModelScope.launch {
      loadSavedStopsInternal(repo, preferDb = preferDb)
    }
  }

  private suspend fun loadSavedStopsInternal(repo: DeliveryRepository, preferDb: Boolean = false) {
    val saved = repo.getAllStops().filter { it.id !in deletedInSessionIds }
    if (saved.isNotEmpty()) {
      val currentCategories = _uiState.value.categories
      val migratedIds = mutableSetOf<String>()
      val hydrated = saved.map { stop ->
        val withCat = hydrateStopCategory(stop, currentCategories)
        val migrated = migrateStopPhotosToPersistentDir(withCat)
        if (migrated != withCat) migratedIds.add(migrated.id)
        migrated
      }
      val (recoveredStops, recoveredSelected) = applyPendingPhotoCaptureRecovery(hydrated)
      if (recoveredSelected != null) migratedIds.add(recoveredSelected.id)
      mergeDbStopsIntoState(
        dbStops = recoveredStops,
        preferDbValues = preferDb,
        preferDbStopIds = migratedIds
      )
      if (recoveredSelected != null) {
        _uiState.update { state ->
          if (state.selectedStop == null && !state.isDetailSheetOpen) {
            state.copy(
              selectedStop = recoveredSelected,
              isDetailSheetOpen = true
            )
          } else {
            state
          }
        }
      }
      if (migratedIds.isNotEmpty()) {
        repo.enqueueSaveAllStops(recoveredStops)
      }
    }
  }

  private fun applyPendingPhotoCaptureRecovery(stops: List<DeliveryStop>): Pair<List<DeliveryStop>, DeliveryStop?> {
    val pending = settingsPreferences.getPendingPhotoCapture() ?: return stops to null
    val file = pending.outputFilePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
    val hasValidCapturedFile = file != null && file.exists() && file.length() > 0L
    if (!hasValidCapturedFile) {
      if (file != null && file.exists() && file.length() == 0L) {
        runCatching { file.delete() }
      }
      settingsPreferences.clearPendingPhotoCapture()
      return stops to null
    }

    var recoveredStop: DeliveryStop? = null
    val updated = stops.map { stop ->
      if (stop.id == pending.stopId) {
        val patched = when (pending.targetName) {
          "CONTENTS" -> stop.copy(
            capturedContentsPhotoUri = pending.outputUriString,
            hasContentsPhoto = true
          )
          "EXTERNAL" -> stop.copy(
            capturedExternalPhotoUri = pending.outputUriString,
            hasExternalPhoto = true
          )
          "ISSUE" -> stop.copy(capturedIssuePhotoUri = pending.outputUriString)
          "SCREENSHOT" -> stop.copy(screenshotUriString = pending.outputUriString)
          else -> stop
        }
        recoveredStop = patched
        patched
      } else {
        stop
      }
    }
    if (recoveredStop != null) {
      settingsPreferences.clearPendingPhotoCapture()
    }
    return updated to recoveredStop
  }

  fun preparePendingPhotoCapture(
    stopId: String,
    targetName: String,
    outputUriString: String,
    outputFilePath: String
  ) {
    synchronized(stateWriteLock) {
      var targetToPersist: DeliveryStop? = null
      _uiState.update { state ->
        val currentSelected = state.selectedStop?.takeIf { it.id == stopId }
        val existingInList = state.stops.firstOrNull { it.id == stopId }
        val sourceStop = currentSelected ?: existingInList
        if (sourceStop != null) {
          val cleanOrder = sourceStop.orderCodeNumber.ifBlank { sourceStop.orderNumber }.trim()
          val resolvedCatId = sourceStop.categoryId.ifBlank { sourceStop.status.name }
          val matchedCat = state.categories.firstOrNull { it.id == resolvedCatId }
          val resolvedStatus = runCatching { DeliveryStatus.valueOf(resolvedCatId) }.getOrNull() ?: sourceStop.status
          val normalizedStop = sourceStop.copy(
            isNewOrder = false,
            orderNumber = cleanOrder,
            orderCodeNumber = cleanOrder,
            status = resolvedStatus,
            categoryId = resolvedCatId,
            categoryName = matchedCat?.name ?: sourceStop.categoryName.ifBlank { resolvedStatus.labelAr },
            categoryColorHex = matchedCat?.primaryColorHex ?: sourceStop.categoryColorHex
          )
          targetToPersist = normalizedStop
          val updatedStops = if (existingInList != null) {
            state.stops.map { if (it.id == stopId) normalizedStop else it }
          } else {
            listOf(normalizedStop) + state.stops
          }
          state.copy(
            stops = updatedStops,
            selectedStop = if (state.selectedStop?.id == stopId) normalizedStop else state.selectedStop
          )
        } else {
          state
        }
      }
      targetToPersist?.let { repository?.enqueueSaveStop(it) }
    }
    settingsPreferences.savePendingPhotoCapture(
      stopId = stopId,
      targetName = targetName,
      outputUriString = outputUriString,
      outputFilePath = outputFilePath
    )
    repository?.flushPendingWritesBlocking(1000L)
  }

  fun savePendingGalleryPick(stopId: String, targetName: String) {
    persistInProgressEdits()
    settingsPreferences.savePendingGalleryPick(stopId, targetName)
    repository?.flushPendingWritesBlocking(1000L)
  }

  fun consumePendingGalleryPick(): Pair<String, String>? {
    val pending = settingsPreferences.getPendingGalleryPick()
    settingsPreferences.clearPendingGalleryPick()
    return pending
  }

  fun applyPickedPhotoFromExternalPicker(stopId: String?, targetName: String, uriString: String) {
    val repo = repository ?: return
    val fallbackPending = consumePendingGalleryPick()
    val resolvedStopId = stopId?.takeIf { it.isNotBlank() } ?: fallbackPending?.first ?: return
    val resolvedTarget = targetName.ifBlank { fallbackPending?.second ?: "" }.ifBlank { return }

    viewModelScope.launch {
      // Ensure DB stops are loaded in case the Activity was recreated right after picking
      if (_uiState.value.stops.none { it.id == resolvedStopId } &&
        _uiState.value.selectedStop?.id != resolvedStopId
      ) {
        loadSavedStopsInternal(repo, preferDb = false)
      }
      when (resolvedTarget) {
        "CONTENTS" -> updateCapturedContentsPhoto(resolvedStopId, uriString)
        "EXTERNAL" -> updateCapturedExternalPhoto(resolvedStopId, uriString)
        "ISSUE" -> updateCapturedIssuePhoto(resolvedStopId, uriString)
        "SCREENSHOT" -> updateScreenshotForStop(resolvedStopId, uriString)
      }
      repo.flushPendingWrites()
    }
  }

  fun clearPendingPhotoCapture(deleteEmptyFile: Boolean = false) {
    if (deleteEmptyFile) {
      val path = settingsPreferences.getPendingPhotoCapture()?.outputFilePath
      if (!path.isNullOrBlank()) {
        runCatching {
          val file = java.io.File(path)
          if (file.exists() && file.length() == 0L) {
            file.delete()
          }
        }
      }
    }
    settingsPreferences.clearPendingPhotoCapture()
  }

  private fun mergeDbStopsIntoState(
    dbStops: List<DeliveryStop>,
    preferDbValues: Boolean = false,
    preferDbStopIds: Set<String> = emptySet()
  ) {
    val validDbStops = dbStops.filter { it.id !in deletedInSessionIds }
    if (validDbStops.isEmpty()) return
    val currentCategories = _uiState.value.categories
    val hydrated = validDbStops.map { hydrateStopCategory(it, currentCategories) }
    _uiState.update { state ->
      val dbById = hydrated.associateBy { it.id }
      val existingById = state.stops.associateBy { it.id }
      // Preserve any newly created in-memory stops not yet returned by the DB snapshot
      val inMemoryOnly = state.stops.filter { it.id !in dbById }
      val mergedDb = hydrated.map { dbStop ->
        val inMem = existingById[dbStop.id]
        val shouldPreferDb = preferDbValues || preferDbStopIds.contains(dbStop.id)
        if (inMem != null && !shouldPreferDb) inMem else dbStop
      }
      // Always merge DB orders into state.stops even if isDetailSheetOpen == true,
      // while leaving state.selectedStop untouched so in-progress edits are preserved.
      state.copy(stops = inMemoryOnly + mergedDb)
    }
  }

  fun loadSavedCategories() {
    val repo = repository ?: return
    val loaded = repo.loadCategories()
    _uiState.update { it.copy(categories = loaded) }
  }

  suspend fun loadSavedStopsSuspending(): List<DeliveryStop> {
    val repo = repository ?: return emptyList()
    val saved = repo.getAllStops().filter { it.id !in deletedInSessionIds }
    val currentCategories = _uiState.value.categories
    val hydrated = saved.map { hydrateStopCategory(it, currentCategories) }
    val (recoveredStops, recoveredSelected) = applyPendingPhotoCaptureRecovery(hydrated)
    if (recoveredStops.isNotEmpty()) {
      mergeDbStopsIntoState(recoveredStops, preferDbValues = recoveredSelected != null)
    }
    if (recoveredSelected != null) {
      _uiState.update { state ->
        if (state.selectedStop == null && !state.isDetailSheetOpen) {
          state.copy(
            selectedStop = recoveredSelected,
            isDetailSheetOpen = true
          )
        } else {
          state
        }
      }
      repo.enqueueSaveStop(recoveredSelected)
    }
    return recoveredStops
  }

  private fun hydrateStopCategory(stop: DeliveryStop, categories: List<DeliveryCategory>): DeliveryStop {
    val resolvedCatId = stop.categoryId.ifBlank { stop.status.name }
    val matchingCat = categories.firstOrNull { it.id == resolvedCatId }
    val matchingStatus = runCatching { DeliveryStatus.valueOf(resolvedCatId) }.getOrNull() ?: stop.status
    return stop.copy(
      categoryId = resolvedCatId,
      categoryName = matchingCat?.name ?: stop.categoryName.ifBlank { matchingStatus.labelAr },
      categoryColorHex = matchingCat?.primaryColorHex ?: stop.categoryColorHex,
      status = matchingStatus
    )
  }

  private fun migrateStopPhotosToPersistentDir(stop: DeliveryStop): DeliveryStop {
    val ctx = appContext ?: return stop
    val s1 = migrateSingleUriIfCache(ctx, stop.screenshotUriString)
    val s2 = migrateSingleUriIfCache(ctx, stop.capturedIssuePhotoUri)
    val s3 = migrateSingleUriIfCache(ctx, stop.capturedContentsPhotoUri)
    val s4 = migrateSingleUriIfCache(ctx, stop.capturedExternalPhotoUri)
    return if (s1 != stop.screenshotUriString ||
      s2 != stop.capturedIssuePhotoUri ||
      s3 != stop.capturedContentsPhotoUri ||
      s4 != stop.capturedExternalPhotoUri
    ) {
      stop.copy(
        screenshotUriString = s1,
        capturedIssuePhotoUri = s2,
        capturedContentsPhotoUri = s3,
        capturedExternalPhotoUri = s4
      )
    } else {
      stop
    }
  }

  private fun migrateSingleUriIfCache(ctx: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) return uriString
    return try {
      val uri = android.net.Uri.parse(uriString)
      val path = uri.path ?: return uriString
      // Check if it's pointing to cache_images, cache_root, or cacheDir/images
      val cacheImagesDir = java.io.File(ctx.cacheDir, "images")
      val sourceFile = when {
        uri.scheme == "file" -> java.io.File(path)
        path.contains("/cache_images/") -> java.io.File(cacheImagesDir, path.substringAfter("/cache_images/"))
        path.contains("/cache_root/") -> java.io.File(ctx.cacheDir, path.substringAfter("/cache_root/"))
        path.contains("/images/") && uri.toString().contains("fileprovider") -> {
          val candidate = java.io.File(cacheImagesDir, path.substringAfterLast("/"))
          if (candidate.exists()) candidate else return uriString
        }
        else -> return uriString
      }
      if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() == 0L) return uriString
      val persistentDir = java.io.File(ctx.filesDir, "order_images")
      if (!persistentDir.exists()) persistentDir.mkdirs()
      val destFile = java.io.File(persistentDir, sourceFile.name)
      if (!destFile.exists() || destFile.length() == 0L) {
        sourceFile.copyTo(destFile, overwrite = true)
      }
      androidx.core.content.FileProvider.getUriForFile(
        ctx,
        "${ctx.packageName}.fileprovider",
        destFile
      ).toString()
    } catch (_: Exception) {
      uriString
    }
  }

  private fun hasMeaningfulOrderData(stop: DeliveryStop): Boolean {
    return stop.orderCodeNumber.isNotBlank() ||
      stop.orderNumber.isNotBlank() ||
      stop.title.isNotBlank() ||
      stop.subtitle.isNotBlank() ||
      stop.phone1.isNotBlank() ||
      stop.phone2.isNotBlank() ||
      stop.issueNotes.isNotBlank() ||
      stop.price.isNotBlank() ||
      stop.screenshotUriString != null ||
      stop.capturedIssuePhotoUri != null ||
      stop.capturedContentsPhotoUri != null ||
      stop.capturedExternalPhotoUri != null ||
      stop.isLocationSaved ||
      stop.categoryId != DeliveryStatus.IN_TRANSIT.name ||
      stop.status != DeliveryStatus.IN_TRANSIT
  }

  suspend fun saveStopSuspending(stop: DeliveryStop) {
    saveOrConfirmStop(stop)
    repository?.flushPendingWrites()
  }

  fun persistAndFlushBlocking(timeoutMs: Long = 2500L) {
    persistInProgressEdits()
    repository?.flushPendingWritesBlocking(timeoutMs)
  }

  /**
   * Immediately saves & flushes all latest order, address, category, and customer changes,
   * then refreshes the in-memory UI lists from persisted storage.
   */
  fun refreshAndSaveApp(manual: Boolean = true) {
    autoRefreshJob?.cancel()
    _uiState.update {
      it.copy(
        isAppRefreshing = true,
        refreshGeneration = it.refreshGeneration + 1
      )
    }
    autoRefreshJob = viewModelScope.launch {
      performRefreshAndSave(manual = manual)
    }
  }

  private fun scheduleAutoRefreshAndSave(debounceMs: Long = 0L) {
    autoRefreshJob?.cancel()
    _uiState.update {
      it.copy(
        isAppRefreshing = true,
        refreshGeneration = it.refreshGeneration + 1
      )
    }
    autoRefreshJob = viewModelScope.launch {
      if (debounceMs > 0L) {
        delay(debounceMs)
      }
      performRefreshAndSave(manual = false)
    }
  }

  private suspend fun performRefreshAndSave(manual: Boolean) {
    try {
      persistInProgressEdits()
      val repo = repository
      if (repo != null) {
        val (snapshotStops, snapshotCategories) = synchronized(stateWriteLock) {
          _uiState.value.stops to _uiState.value.categories
        }
        repo.saveCategories(snapshotCategories)
        if (manual && snapshotStops.isNotEmpty()) {
          repo.enqueueSaveAllStops(snapshotStops)
        }
        repo.flushPendingWrites()

        val loadedCategories = repo.loadCategories()
        val loadedCustomers = repo.getAllCustomers()
        val loadedSuspicious = repo.loadSuspiciousCustomers()
        _uiState.update { state ->
          state.copy(
            categories = loadedCategories,
            registeredCustomers = loadedCustomers,
            syncedCustomersCount = loadedCustomers.size.coerceAtLeast(state.syncedCustomersCount),
            suspiciousCustomers = loadedSuspicious
          )
        }
        loadSavedStopsInternal(repo, preferDb = false)
        if (manual) {
          viewModelScope.launch {
            runCatching { repo.syncWithFirestore() }
          }
        }
      }
    } finally {
      _uiState.update { state ->
        state.copy(
          isAppRefreshing = false,
          userMessage = if (manual) "تم تحديث التطبيق وحفظ آخر التغييرات بنجاح" else state.userMessage
        )
      }
    }
  }

  private fun persistStop(stopId: String) {
    val target = synchronized(stateWriteLock) {
      val state = _uiState.value
      state.stops.firstOrNull { it.id == stopId }
        ?: state.selectedStop?.takeIf { it.id == stopId && hasMeaningfulOrderData(it) }
    } ?: return
    repository?.enqueueSaveStop(target)
  }

  fun setCategoryFilter(categoryId: String?) {
    _uiState.update {
      it.copy(
        selectedCategoryId = categoryId,
        isShowingDeleted = false,
        isSelectionMode = false,
        selectedStopIds = emptySet()
      )
    }
  }

  fun setFilter(status: DeliveryStatus?) {
    setCategoryFilter(status?.name)
  }

  fun setShowingDeleted(show: Boolean) {
    _uiState.update {
      it.copy(
        isShowingDeleted = show,
        selectedCategoryId = if (show) null else it.selectedCategoryId,
        isSelectionMode = false,
        selectedStopIds = emptySet()
      )
    }
  }

  fun enterSelectionMode(initialStopId: String? = null) {
    _uiState.update { state ->
      val updatedIds = if (initialStopId != null) state.selectedStopIds + initialStopId else state.selectedStopIds
      state.copy(
        isSelectionMode = true,
        selectedStopIds = updatedIds
      )
    }
  }

  fun exitSelectionMode() {
    _uiState.update {
      it.copy(
        isSelectionMode = false,
        selectedStopIds = emptySet()
      )
    }
  }

  fun toggleStopSelection(stopId: String) {
    _uiState.update { state ->
      val updatedIds = if (state.selectedStopIds.contains(stopId)) {
        state.selectedStopIds - stopId
      } else {
        state.selectedStopIds + stopId
      }
      state.copy(
        isSelectionMode = true,
        selectedStopIds = updatedIds
      )
    }
  }

  fun selectAllFilteredStops() {
    _uiState.update { state ->
      val allIds = state.filteredStops.map { it.id }.toSet()
      state.copy(
        isSelectionMode = true,
        selectedStopIds = allIds
      )
    }
  }

  fun deselectAllStops() {
    _uiState.update { state ->
      state.copy(selectedStopIds = emptySet())
    }
  }

  fun toggleSelectAllFilteredStops() {
    _uiState.update { state ->
      val visibleIds = state.filteredStops.map { it.id }.toSet()
      val allSelected = visibleIds.isNotEmpty() && visibleIds.all { state.selectedStopIds.contains(it) }
      state.copy(
        isSelectionMode = true,
        selectedStopIds = if (allSelected) emptySet() else visibleIds
      )
    }
  }

  fun bulkDeleteSelectedStops() {
    synchronized(stateWriteLock) {
      val ids = _uiState.value.selectedStopIds
      if (ids.isEmpty()) return
      var updatedList: List<DeliveryStop> = emptyList()
      _uiState.update { state ->
        updatedList = state.stops.map {
          if (ids.contains(it.id)) it.copy(isDeleted = true, deletedAt = "تم الحذف") else it
        }
        state.copy(
          stops = updatedList,
          isSelectionMode = false,
          selectedStopIds = emptySet(),
          userMessage = "تم نقل ${ids.size} طلب إلى سلة المحذوفات"
        )
      }
      repository?.enqueueSaveAllStops(updatedList)
    }
    scheduleAutoRefreshAndSave()
  }

  fun bulkRestoreSelectedStops() {
    synchronized(stateWriteLock) {
      val ids = _uiState.value.selectedStopIds
      if (ids.isEmpty()) return
      var updatedList: List<DeliveryStop> = emptyList()
      _uiState.update { state ->
        updatedList = state.stops.map {
          if (ids.contains(it.id)) it.copy(isDeleted = false, deletedAt = null) else it
        }
        state.copy(
          stops = updatedList,
          isSelectionMode = false,
          selectedStopIds = emptySet(),
          userMessage = "تمت استعادة ${ids.size} طلب بنجاح!"
        )
      }
      repository?.enqueueSaveAllStops(updatedList)
    }
    scheduleAutoRefreshAndSave()
  }

  fun bulkPermanentlyDeleteSelectedStops() {
    synchronized(stateWriteLock) {
      val ids = _uiState.value.selectedStopIds
      if (ids.isEmpty()) return
      deletedInSessionIds.addAll(ids)
      _uiState.update { state ->
        state.copy(
          stops = state.stops.filter { !ids.contains(it.id) },
          isSelectionMode = false,
          selectedStopIds = emptySet(),
          userMessage = "تم الحذف النهائي لـ ${ids.size} طلب"
        )
      }
      ids.forEach { repository?.enqueueDeleteStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  fun bulkUpdateCategoryForSelectedStops(category: DeliveryCategory) {
    synchronized(stateWriteLock) {
      val ids = _uiState.value.selectedStopIds
      if (ids.isEmpty()) return
      val matchingStatus = runCatching { DeliveryStatus.valueOf(category.id) }.getOrNull()
      val defaultNote = when (matchingStatus) {
        DeliveryStatus.DELIVERED -> "تم التسليم بنجاح في يد المستلم"
        DeliveryStatus.DELAYED -> "طلب مؤجل: طلب العميل إعادة التوصيل لاحقاً"
        DeliveryStatus.RETURNED -> "طلب راجع للشركة: ألغى العميل أو تعذر التسليم"
        DeliveryStatus.LINE_ISSUES -> "مشاكل خط السير أو تعذر الوصول للعنوان"
        DeliveryStatus.IN_TRANSIT, null -> "طلب (${category.name}) إلى عنوان الزبون"
      }
      val iconType = when (matchingStatus) {
        DeliveryStatus.DELIVERED -> NoteIconType.CHECK
        DeliveryStatus.DELAYED -> NoteIconType.HISTORY
        DeliveryStatus.RETURNED -> NoteIconType.INFO
        DeliveryStatus.LINE_ISSUES -> NoteIconType.ROUTE
        DeliveryStatus.IN_TRANSIT, null -> NoteIconType.NAVIGATION
      }
      var updatedList: List<DeliveryStop> = emptyList()
      _uiState.update { state ->
        updatedList = state.stops.map {
          if (ids.contains(it.id)) {
            it.copy(
              categoryId = category.id,
              categoryName = category.name,
              categoryColorHex = category.primaryColorHex,
              status = matchingStatus ?: it.status,
              note = defaultNote,
              noteIconType = iconType
            )
          } else it
        }
        state.copy(
          stops = updatedList,
          isSelectionMode = false,
          selectedStopIds = emptySet(),
          userMessage = "تم نقل ${ids.size} طلب إلى قائمة: ${category.name}"
        )
      }
      repository?.enqueueSaveAllStops(updatedList)
    }
    scheduleAutoRefreshAndSave()
  }

  fun openManageCategoriesDialog() {
    _uiState.update { it.copy(isManageCategoriesDialogOpen = true) }
  }

  fun closeManageCategoriesDialog() {
    _uiState.update { it.copy(isManageCategoriesDialogOpen = false) }
  }

  fun addCategory(name: String, colorHex: String) {
    if (name.isBlank()) return
    val newCat = DeliveryCategory.createCustom(
      name = name.trim(),
      primaryColorHex = colorHex,
      displayOrder = _uiState.value.categories.size
    )
    val updated = _uiState.value.categories + newCat
    _uiState.update { it.copy(categories = updated, userMessage = "تمت إضافة قائمة (${newCat.name}) بنجاح!") }
    repository?.saveCategories(updated)
    scheduleAutoRefreshAndSave()
  }

  fun renameCategory(categoryId: String, newName: String) {
    if (newName.isBlank()) return
    synchronized(stateWriteLock) {
      val cleanName = newName.trim()
      val updatedCategories = _uiState.value.categories.map {
        if (it.id == categoryId) it.copy(name = cleanName) else it
      }
      val updatedStops = _uiState.value.stops.map {
        if (it.categoryId == categoryId) it.copy(categoryName = cleanName) else it
      }
      _uiState.update { state ->
        val updatedSelected = if (state.selectedStop?.categoryId == categoryId) {
          state.selectedStop.copy(categoryName = cleanName)
        } else state.selectedStop
        state.copy(
          categories = updatedCategories,
          stops = updatedStops,
          selectedStop = updatedSelected,
          userMessage = "تم تعديل اسم القائمة إلى: $cleanName"
        )
      }
      repository?.saveCategories(updatedCategories)
      repository?.enqueueSaveAllStops(updatedStops)
    }
    scheduleAutoRefreshAndSave()
  }

  fun deleteCategory(categoryId: String) {
    val target = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
    val updatedCategories = _uiState.value.categories.filter { it.id != categoryId }
    val newSelectedCat = if (_uiState.value.selectedCategoryId == categoryId) null else _uiState.value.selectedCategoryId
    _uiState.update {
      it.copy(
        categories = updatedCategories,
        selectedCategoryId = newSelectedCat,
        userMessage = "تم حذف قائمة (${target.name})"
      )
    }
    repository?.saveCategories(updatedCategories)
    scheduleAutoRefreshAndSave()
  }

  fun moveCategoryUp(categoryId: String) {
    val current = _uiState.value.categories.toMutableList()
    val index = current.indexOfFirst { it.id == categoryId }
    if (index > 0) {
      val item = current.removeAt(index)
      current.add(index - 1, item)
      val reordered = current.mapIndexed { idx, cat -> cat.copy(displayOrder = idx) }
      _uiState.update { it.copy(categories = reordered) }
      repository?.saveCategories(reordered)
      scheduleAutoRefreshAndSave()
    }
  }

  fun moveCategoryDown(categoryId: String) {
    val current = _uiState.value.categories.toMutableList()
    val index = current.indexOfFirst { it.id == categoryId }
    if (index >= 0 && index < current.size - 1) {
      val item = current.removeAt(index)
      current.add(index + 1, item)
      val reordered = current.mapIndexed { idx, cat -> cat.copy(displayOrder = idx) }
      _uiState.update { it.copy(categories = reordered) }
      repository?.saveCategories(reordered)
      scheduleAutoRefreshAndSave()
    }
  }

  fun resetCategoriesToDefault() {
    val defaults = DeliveryCategory.DEFAULT_CATEGORIES
    _uiState.update { it.copy(categories = defaults, selectedCategoryId = "IN_TRANSIT", userMessage = "تمت استعادة القوائم الافتراضية") }
    repository?.saveCategories(defaults)
    scheduleAutoRefreshAndSave()
  }

  fun updateStopCategory(stopId: String, category: DeliveryCategory) {
    synchronized(stateWriteLock) {
      val matchingStatus = runCatching { DeliveryStatus.valueOf(category.id) }.getOrNull()
      val defaultNote = when (matchingStatus) {
        DeliveryStatus.DELIVERED -> "تم التسليم بنجاح في يد المستلم"
        DeliveryStatus.DELAYED -> "طلب مؤجل: طلب العميل إعادة التوصيل لاحقاً"
        DeliveryStatus.RETURNED -> "طلب راجع للشركة: ألغى العميل أو تعذر التسليم"
        DeliveryStatus.LINE_ISSUES -> "مشاكل خط السير أو تعذر الوصول للعنوان"
        DeliveryStatus.IN_TRANSIT, null -> "طلب (${category.name}) إلى عنوان الزبون"
      }
      val iconType = when (matchingStatus) {
        DeliveryStatus.DELIVERED -> NoteIconType.CHECK
        DeliveryStatus.DELAYED -> NoteIconType.HISTORY
        DeliveryStatus.RETURNED -> NoteIconType.INFO
        DeliveryStatus.LINE_ISSUES -> NoteIconType.ROUTE
        DeliveryStatus.IN_TRANSIT, null -> NoteIconType.NAVIGATION
      }
      var persistedTarget: DeliveryStop? = null
      _uiState.update { state ->
        val updatedSelected = if (state.selectedStop?.id == stopId) {
          state.selectedStop.copy(
            categoryId = category.id,
            categoryName = category.name,
            categoryColorHex = category.primaryColorHex,
            status = matchingStatus ?: state.selectedStop.status,
            note = defaultNote,
            noteIconType = iconType
          )
        } else state.selectedStop

        val existsInStops = state.stops.any { it.id == stopId }
        val updated = if (existsInStops) {
          state.stops.map {
            if (it.id == stopId) {
              val copy = it.copy(
                categoryId = category.id,
                categoryName = category.name,
                categoryColorHex = category.primaryColorHex,
                status = matchingStatus ?: it.status,
                note = defaultNote,
                noteIconType = iconType
              )
              persistedTarget = copy
              copy
            } else it
          }
        } else if (updatedSelected != null && hasMeaningfulOrderData(updatedSelected)) {
          val promoted = updatedSelected.copy(isNewOrder = false)
          persistedTarget = promoted
          listOf(promoted) + state.stops
        } else {
          state.stops
        }
        state.copy(
          stops = updated,
          selectedStop = updatedSelected?.let { if (persistedTarget != null) it.copy(isNewOrder = false) else it },
          userMessage = "تم تصنيف الطلب كـ: ${category.name}"
        )
      }
      persistedTarget?.let { repository?.enqueueSaveStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  fun deleteStop(stopId: String) {
    synchronized(stateWriteLock) {
      var persistedTarget: DeliveryStop? = null
      _uiState.update { state ->
        val updated = state.stops.map {
          if (it.id == stopId) {
            val copy = it.copy(isDeleted = true, deletedAt = "تم الحذف")
            persistedTarget = copy
            copy
          } else it
        }
        state.copy(
          stops = updated,
          isDetailSheetOpen = if (state.selectedStop?.id == stopId) false else state.isDetailSheetOpen,
          selectedStop = if (state.selectedStop?.id == stopId) null else state.selectedStop,
          userMessage = "تم نقل الطلب إلى سلة المحذوفات"
        )
      }
      persistedTarget?.let { repository?.enqueueSaveStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  fun restoreStop(stopId: String) {
    synchronized(stateWriteLock) {
      var persistedTarget: DeliveryStop? = null
      _uiState.update { state ->
        val updated = state.stops.map {
          if (it.id == stopId) {
            val copy = it.copy(isDeleted = false, deletedAt = null)
            persistedTarget = copy
            copy
          } else it
        }
        state.copy(
          stops = updated,
          userMessage = "تمت استعادة الطلب بنجاح إلى القائمة النشطة!"
        )
      }
      persistedTarget?.let { repository?.enqueueSaveStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  fun permanentlyDeleteStop(stopId: String) {
    synchronized(stateWriteLock) {
      deletedInSessionIds.add(stopId)
      _uiState.update { state ->
        val updated = state.stops.filter { it.id != stopId }
        state.copy(
          stops = updated,
          isDetailSheetOpen = if (state.selectedStop?.id == stopId) false else state.isDetailSheetOpen,
          selectedStop = if (state.selectedStop?.id == stopId) null else state.selectedStop,
          userMessage = "تم حذف الطلب نهائياً"
        )
      }
      repository?.enqueueDeleteStop(stopId)
    }
    scheduleAutoRefreshAndSave()
  }

  fun emptyDeletedStops() {
    synchronized(stateWriteLock) {
      val deletedIds = _uiState.value.stops.filter { it.isDeleted }.map { it.id }
      deletedInSessionIds.addAll(deletedIds)
      _uiState.update { state ->
        state.copy(
          stops = state.stops.filter { !it.isDeleted },
          userMessage = "تم إفراغ سلة الطلبات المحذوفة بنجاح"
        )
      }
      repository?.enqueueEmptyDeleted()
    }
    scheduleAutoRefreshAndSave()
  }

  fun setSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun toggleSortOrder() {
    _uiState.update {
      val next = if (it.sortOrder == SortOrder.DEFAULT) SortOrder.ALPHABETICAL else SortOrder.DEFAULT
      it.copy(
        sortOrder = next,
        userMessage = if (next == SortOrder.ALPHABETICAL) "تم الترتيب أبجدياً (أ-ي)" else "تم الترتيب الافتراضي"
      )
    }
  }

  fun openDetailSheet(stop: DeliveryStop) {
    _uiState.update {
      it.copy(
        selectedStop = stop,
        isDetailSheetOpen = true
      )
    }
  }

  fun persistInProgressEdits() {
    synchronized(stateWriteLock) {
      val state = _uiState.value
      val current = state.selectedStop ?: return
      val existsInStops = state.stops.any { it.id == current.id }
      if (!existsInStops && !hasMeaningfulOrderData(current)) return

      val cleanOrder = current.orderCodeNumber.ifBlank { current.orderNumber }.trim()
      val resolvedCatId = current.categoryId.ifBlank { current.status.name }
      val matchedCat = state.categories.firstOrNull { it.id == resolvedCatId }
      val resolvedStatus = runCatching { DeliveryStatus.valueOf(resolvedCatId) }.getOrNull() ?: current.status
      val finalStop = current.copy(
        isNewOrder = false,
        orderNumber = cleanOrder,
        orderCodeNumber = cleanOrder,
        status = resolvedStatus,
        categoryId = resolvedCatId,
        categoryName = matchedCat?.name ?: current.categoryName.ifBlank { resolvedStatus.labelAr },
        categoryColorHex = matchedCat?.primaryColorHex ?: current.categoryColorHex
      )
      _uiState.update { s ->
        val updatedStops = if (s.stops.any { it.id == finalStop.id }) {
          s.stops.map { if (it.id == finalStop.id) finalStop else it }
        } else {
          listOf(finalStop) + s.stops
        }
        s.copy(
          stops = updatedStops,
          selectedStop = if (s.selectedStop?.id == finalStop.id) finalStop else s.selectedStop
        )
      }
      repository?.enqueueSaveStop(finalStop)
    }
  }

  fun closeDetailSheet() {
    synchronized(stateWriteLock) {
      val state = _uiState.value
      val current = state.selectedStop
      if (current != null && (state.stops.any { it.id == current.id } || hasMeaningfulOrderData(current))) {
        val cleanOrder = current.orderCodeNumber.ifBlank { current.orderNumber }.trim()
        val resolvedCatId = current.categoryId.ifBlank { current.status.name }
        val matchedCat = state.categories.firstOrNull { it.id == resolvedCatId }
        val resolvedStatus = runCatching { DeliveryStatus.valueOf(resolvedCatId) }.getOrNull() ?: current.status
        val finalStop = current.copy(
          isNewOrder = false,
          orderNumber = cleanOrder,
          orderCodeNumber = cleanOrder,
          status = resolvedStatus,
          categoryId = resolvedCatId,
          categoryName = matchedCat?.name ?: current.categoryName.ifBlank { resolvedStatus.labelAr },
          categoryColorHex = matchedCat?.primaryColorHex ?: current.categoryColorHex
        )
        _uiState.update { s ->
          val updatedStops = if (s.stops.any { it.id == finalStop.id }) {
            s.stops.map { if (it.id == finalStop.id) finalStop else it }
          } else {
            listOf(finalStop) + s.stops
          }
          s.copy(
            stops = updatedStops,
            isDetailSheetOpen = false,
            selectedStop = null
          )
        }
        repository?.enqueueSaveStop(finalStop)
        scheduleAutoRefreshAndSave()
      } else {
        _uiState.update {
          it.copy(
            isDetailSheetOpen = false,
            selectedStop = null
          )
        }
      }
    }
  }

  fun openNewOrderScreen() {
    val newStop = DeliveryStop(
      id = "ord_new_${System.currentTimeMillis()}",
      orderNumber = "", // Empty by default - only entered order number is displayed
      orderCodeNumber = "", // Empty by default
      dateTime = "اليوم، الآن",
      title = "", // Customer address empty by default, manual entry only
      subtitle = "", // Details empty by default, manual entry only
      note = "طلب جديد قيد التوصيل",
      noteIconType = NoteIconType.NAVIGATION,
      status = DeliveryStatus.IN_TRANSIT,
      governorate = "",
      region = "",
      phone1 = "", // Empty by default
      phone2 = "", // Empty by default
      issueNotes = "",
      screenshotUriString = null,
      isOcrProcessed = false,
      isNewOrder = true
    )
    _uiState.update {
      it.copy(
        selectedStop = newStop,
        isDetailSheetOpen = true,
        userMessage = "طلب جديد: أدخل رقم الطلب وعنوان الزبون يدوياً أو عبر OCR"
      )
    }
  }

  fun openNewOrderWithPrefill(price: String, phone: String = "") {
    val cleanPrice = com.example.util.OcrHelper.cleanPriceNumber(price)
    val cleanPhone = phone.trim()
    val newStop = DeliveryStop(
      id = "ord_new_${System.currentTimeMillis()}",
      orderNumber = "",
      orderCodeNumber = "",
      price = cleanPrice,
      dateTime = "اليوم، الآن",
      title = "",
      subtitle = "",
      note = "طلب جديد قيد التوصيل",
      noteIconType = NoteIconType.NAVIGATION,
      status = DeliveryStatus.IN_TRANSIT,
      governorate = "",
      region = "",
      phone1 = cleanPhone,
      phone2 = "",
      issueNotes = "",
      screenshotUriString = null,
      isOcrProcessed = false,
      isNewOrder = true
    )
    _uiState.update {
      it.copy(
        selectedStop = newStop,
        isDetailSheetOpen = true,
        userMessage = if (cleanPrice.isNotBlank()) "تم سحب السعر ($cleanPrice) إلى حقل السعر بنجاح!" else null
      )
    }
  }


  fun saveOrConfirmStop(stop: DeliveryStop) {
    synchronized(stateWriteLock) {
      deletedInSessionIds.remove(stop.id)
      val stateSnapshot = _uiState.value
      val existingAuth = stateSnapshot.selectedStop?.takeIf { it.id == stop.id }
        ?: stateSnapshot.stops.firstOrNull { it.id == stop.id }
      val cleanOrder = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
      val currentCategories = stateSnapshot.categories
      val resolvedCatId = stop.categoryId.ifBlank { stop.status.name }
      val matchedCat = currentCategories.firstOrNull { it.id == resolvedCatId }
      val resolvedStatus = runCatching { DeliveryStatus.valueOf(resolvedCatId) }.getOrNull() ?: stop.status

      val preservedExternalPhoto = stop.capturedExternalPhotoUri ?: existingAuth?.capturedExternalPhotoUri
      val preservedContentsPhoto = stop.capturedContentsPhotoUri ?: existingAuth?.capturedContentsPhotoUri
      val preservedIssuePhoto = stop.capturedIssuePhotoUri ?: existingAuth?.capturedIssuePhotoUri
      val preservedScreenshot = stop.screenshotUriString ?: existingAuth?.screenshotUriString

      val finalStop = stop.copy(
        isNewOrder = false,
        orderNumber = cleanOrder,
        orderCodeNumber = cleanOrder,
        status = resolvedStatus,
        categoryId = resolvedCatId,
        categoryName = matchedCat?.name ?: stop.categoryName.ifBlank { resolvedStatus.labelAr },
        categoryColorHex = matchedCat?.primaryColorHex ?: stop.categoryColorHex,
        capturedExternalPhotoUri = preservedExternalPhoto,
        hasExternalPhoto = preservedExternalPhoto != null || stop.hasExternalPhoto,
        capturedContentsPhotoUri = preservedContentsPhoto,
        hasContentsPhoto = preservedContentsPhoto != null || stop.hasContentsPhoto,
        capturedIssuePhotoUri = preservedIssuePhoto,
        screenshotUriString = preservedScreenshot
      )
      _uiState.update { state ->
        val isNew = stop.isNewOrder || state.stops.none { it.id == stop.id }
        val updatedList = if (isNew) {
          listOf(finalStop) + state.stops.filter { it.id != finalStop.id }
        } else {
          state.stops.map { if (it.id == finalStop.id) finalStop else it }
        }
        state.copy(
          stops = updatedList,
          isShowingDeleted = if (isNew && !finalStop.isDeleted) false else state.isShowingDeleted,
          searchQuery = if (isNew) "" else state.searchQuery,
          isDetailSheetOpen = false,
          selectedStop = null,
          userMessage = if (isNew) "تمت إضافة الطلب وتصنيفه كـ (${finalStop.categoryName}) بنجاح!" else "تم حفظ وتحديث الطلب بنجاح!"
        )
      }
      repository?.enqueueSaveStop(finalStop)
    }
    scheduleAutoRefreshAndSave()
  }

  fun openAddDialog() {
    openNewOrderScreen()
  }

  fun closeAddDialog() {
    _uiState.update { it.copy(isAddDialogOpen = false) }
  }

  fun openQrScanner() {
    _uiState.update { it.copy(isQrScannerOpen = true) }
  }

  fun closeQrScanner() {
    _uiState.update { it.copy(isQrScannerOpen = false) }
  }

  fun addNewStop(newStop: DeliveryStop) {
    saveOrConfirmStop(newStop)
  }

  fun updateStopStatus(stopId: String, newStatus: DeliveryStatus) {
    synchronized(stateWriteLock) {
      val defaultNote = when (newStatus) {
        DeliveryStatus.DELIVERED -> "تم التسليم بنجاح في يد المستلم"
        DeliveryStatus.DELAYED -> "طلب مؤجل: طلب العميل إعادة التوصيل لاحقاً"
        DeliveryStatus.RETURNED -> "طلب راجع للشركة: ألغى العميل أو تعذر التسليم"
        DeliveryStatus.LINE_ISSUES -> "مشاكل خط السير أو تعذر الوصول للعنوان"
        DeliveryStatus.IN_TRANSIT -> "طلب قيد التوصيل إلى عنوان الزبون"
      }
      val iconType = when (newStatus) {
        DeliveryStatus.DELIVERED -> NoteIconType.CHECK
        DeliveryStatus.DELAYED -> NoteIconType.HISTORY
        DeliveryStatus.RETURNED -> NoteIconType.INFO
        DeliveryStatus.LINE_ISSUES -> NoteIconType.ROUTE
        DeliveryStatus.IN_TRANSIT -> NoteIconType.NAVIGATION
      }
      val matchingCat = _uiState.value.categories.firstOrNull { it.id == newStatus.name }
      val newCategoryName = matchingCat?.name ?: newStatus.labelAr
      val newCategoryColor = matchingCat?.primaryColorHex
      var persistedTarget: DeliveryStop? = null

      _uiState.update { state ->
        val updatedSelected = if (state.selectedStop?.id == stopId) {
          state.selectedStop.copy(
            status = newStatus,
            categoryId = newStatus.name,
            categoryName = newCategoryName,
            categoryColorHex = newCategoryColor,
            note = defaultNote,
            noteIconType = iconType
          )
        } else state.selectedStop

        val existsInStops = state.stops.any { it.id == stopId }
        val updated = if (existsInStops) {
          state.stops.map {
            if (it.id == stopId) {
              val copy = it.copy(
                status = newStatus,
                categoryId = newStatus.name,
                categoryName = newCategoryName,
                categoryColorHex = newCategoryColor,
                note = defaultNote,
                noteIconType = iconType
              )
              persistedTarget = copy
              copy
            } else it
          }
        } else if (updatedSelected != null && hasMeaningfulOrderData(updatedSelected)) {
          val promoted = updatedSelected.copy(isNewOrder = false)
          persistedTarget = promoted
          listOf(promoted) + state.stops
        } else {
          state.stops
        }
        state.copy(
          stops = updated,
          selectedStop = updatedSelected?.let { if (persistedTarget != null) it.copy(isNewOrder = false) else it },
          userMessage = "تم تصنيف الطلب كـ: ${newStatus.labelAr}"
        )
      }
      persistedTarget?.let { repository?.enqueueSaveStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  private fun mutateAndPersistStop(
    stopId: String,
    successMessage: String? = null,
    autoRefreshDebounceMs: Long = 0L,
    transform: (DeliveryStop) -> DeliveryStop
  ) {
    var shouldTriggerRefresh = false
    synchronized(stateWriteLock) {
      var persistedTarget: DeliveryStop? = null
      _uiState.update { state ->
        val transformedSelected = if (state.selectedStop?.id == stopId) {
          transform(state.selectedStop)
        } else state.selectedStop

        val existsInStops = state.stops.any { it.id == stopId }
        val updatedStops = if (existsInStops) {
          state.stops.map {
            if (it.id == stopId) {
              val copy = transform(it)
              persistedTarget = copy
              copy
            } else it
          }
        } else if (transformedSelected != null && hasMeaningfulOrderData(transformedSelected)) {
          val promoted = transformedSelected.copy(isNewOrder = false)
          persistedTarget = promoted
          listOf(promoted) + state.stops
        } else {
          state.stops
        }

        val finalSelected = transformedSelected?.let {
          if (persistedTarget != null && it.isNewOrder) it.copy(isNewOrder = false) else it
        }
        state.copy(
          stops = updatedStops,
          selectedStop = finalSelected,
          userMessage = successMessage ?: state.userMessage
        )
      }
      persistedTarget?.let {
        repository?.enqueueSaveStop(it)
        shouldTriggerRefresh = true
      }
    }
    if (shouldTriggerRefresh) {
      scheduleAutoRefreshAndSave(debounceMs = autoRefreshDebounceMs)
    }
  }

  fun updateOrderFields(
    stopId: String,
    orderCode: String,
    p1: String,
    p2: String,
    notes: String,
    price: String? = null
  ) {
    val cleanOrder = orderCode.trim()
    val cleanPrice = price?.let { com.example.util.OcrHelper.cleanPriceNumber(it) }
    mutateAndPersistStop(stopId, autoRefreshDebounceMs = 220L) { stop ->
      stop.copy(
        orderNumber = cleanOrder,
        orderCodeNumber = cleanOrder,
        phone1 = p1.trim(),
        phone2 = p2.trim(),
        issueNotes = notes.trim(),
        price = cleanPrice ?: stop.price
      )
    }
  }

  fun updateLocationCoordinates(stopId: String, lat: Double, lng: Double) {
    val formatted = String.format("%.4f, %.4f", lat, lng)
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = "تم حفظ موقعك الحقيقي ($formatted) كموقع للزبون بنجاح!"
    ) { stop ->
      stop.copy(
        locationCoordinates = formatted,
        isLocationSaved = true,
        locationStatus = "الموقع محفوظ: $formatted"
      )
    }
  }

  fun updateCapturedIssuePhoto(stopId: String, uriString: String?) {
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = if (uriString != null) "تم إرفاق صورة المشكلة بنجاح" else "تمت إزالة صورة المشكلة"
    ) { stop ->
      stop.copy(capturedIssuePhotoUri = uriString)
    }
  }

  fun handleSharedScreenshot(uriString: String) {
    synchronized(stateWriteLock) {
      var persistedTarget: DeliveryStop? = null
      _uiState.update { state ->
        val targetStop = if (state.isDetailSheetOpen) state.selectedStop else null

        if (targetStop != null) {
          val updatedStop = targetStop.copy(
            screenshotUriString = uriString,
            isOcrProcessed = false
          )
          val existsInStops = state.stops.any { it.id == updatedStop.id }
          val finalStop = if (existsInStops) updatedStop else updatedStop.copy(isNewOrder = false)
          val updatedStops = if (existsInStops) {
            state.stops.map { if (it.id == finalStop.id) finalStop else it }
          } else {
            listOf(finalStop) + state.stops
          }
          persistedTarget = finalStop
          state.copy(
            stops = updatedStops,
            selectedStop = finalStop,
            isDetailSheetOpen = true,
            userMessage = "تم إرفاق لقطة الشاشة وجاري استخراج البيانات تلقائياً"
          )
        } else {
          val newStop = DeliveryStop(
            id = "ord_new_${System.currentTimeMillis()}",
            orderNumber = "",
            orderCodeNumber = "",
            dateTime = "اليوم، الآن",
            title = "",
            subtitle = "",
            note = "طلب جديد من سكرين شوت مشترك",
            noteIconType = NoteIconType.NAVIGATION,
            status = DeliveryStatus.IN_TRANSIT,
            governorate = "",
            region = "",
            phone1 = "",
            phone2 = "",
            issueNotes = "",
            screenshotUriString = uriString,
            isOcrProcessed = false,
            isNewOrder = true
          )
          persistedTarget = newStop
          state.copy(
            stops = listOf(newStop) + state.stops,
            selectedStop = newStop,
            isDetailSheetOpen = true,
            userMessage = "تم إنشاء طلب جديد بلقطة الشاشة وجاري استخراج البيانات تلقائياً"
          )
        }
      }
      persistedTarget?.let { repository?.enqueueSaveStop(it) }
    }
    scheduleAutoRefreshAndSave()
  }

  fun updateScreenshotForStop(stopId: String, uriString: String?) {
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = if (uriString != null) "تم إرفاق لقطة الشاشة بنجاح" else "تم حذف لقطة الشاشة"
    ) { stop ->
      stop.copy(
        screenshotUriString = uriString,
        isOcrProcessed = false
      )
    }
  }

  fun updateCapturedContentsPhoto(stopId: String, uriString: String?) {
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = if (uriString != null) "تم حفظ صورة محتويات الطلب بنجاح" else "تمت إزالة صورة محتويات الطلب"
    ) { stop ->
      stop.copy(
        capturedContentsPhotoUri = uriString,
        hasContentsPhoto = uriString != null
      )
    }
  }

  fun updateCapturedExternalPhoto(stopId: String, uriString: String?) {
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = if (uriString != null) "تم حفظ صورة شكل الطلب الخارجي بنجاح" else "تمت إزالة صورة شكل الطلب الخارجي"
    ) { stop ->
      stop.copy(
        capturedExternalPhotoUri = uriString,
        hasExternalPhoto = uriString != null
      )
    }
  }

  fun updateCustomerAddress(stopId: String, address: String, area: String) {
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = "تم تحديث عنوان الزبون يدوياً بنجاح"
    ) { stop ->
      stop.copy(
        title = address,
        subtitle = area,
        governorate = address,
        region = area
      )
    }
  }

  fun applyOcrResult(stopId: String, ocrResult: OcrResult) {
    val cleanOrder = ocrResult.orderNumber?.trim()
    val cleanPrice = ocrResult.price?.let { com.example.util.OcrHelper.cleanPriceNumber(it) }?.takeIf { it.isNotBlank() }
    mutateAndPersistStop(
      stopId = stopId,
      successMessage = "تم استخراج ونقل أرقام الهواتف ورقم وسعر الطلب عبر OCR بنجاح!"
    ) { stop ->
      val finalOrder = cleanOrder ?: stop.orderCodeNumber
      stop.copy(
        orderNumber = finalOrder,
        orderCodeNumber = finalOrder,
        phone1 = ocrResult.phone1 ?: stop.phone1,
        phone2 = ocrResult.phone2 ?: stop.phone2,
        price = cleanPrice ?: stop.price,
        isOcrProcessed = true
      )
    }
  }

  fun clearUserMessage() {
    _uiState.update { it.copy(userMessage = null) }
  }
}
