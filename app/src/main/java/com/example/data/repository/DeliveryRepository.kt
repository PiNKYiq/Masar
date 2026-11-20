package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.CustomerRecordDao
import com.example.data.local.DeliveryStopDao
import com.example.data.local.toDomain
import com.example.data.local.toDomainModel
import com.example.data.local.toEntity
import com.example.data.remote.FirestoreCustomerRepository
import com.example.model.CustomerRecord
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStop
import com.example.model.SuspiciousCustomer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DeliveryRepository(
  private val dao: DeliveryStopDao,
  private val customerDao: CustomerRecordDao? = null,
  private val context: Context? = null,
  private val firestoreCustomerRepo: FirestoreCustomerRepository? = context?.let { FirestoreCustomerRepository(it) }
) {
  private sealed interface PersistOp {
    data class SaveStop(val stop: DeliveryStop, val deferred: CompletableDeferred<Unit>? = null) : PersistOp
    data class SaveAllStops(val stops: List<DeliveryStop>, val deferred: CompletableDeferred<Unit>? = null) : PersistOp
    data class DeleteStop(val id: String, val deferred: CompletableDeferred<Unit>? = null) : PersistOp
    data class EmptyDeleted(val deferred: CompletableDeferred<Unit>? = null) : PersistOp
    data class ClearAll(val deferred: CompletableDeferred<Unit>? = null) : PersistOp
    data class Barrier(val deferred: CompletableDeferred<Unit>) : PersistOp
  }

  private val stopsDbMutex = Mutex()
  private val filesLock = Any()
  private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val persistChannel = Channel<PersistOp>(Channel.UNLIMITED)

  val allStopsFlow: Flow<List<DeliveryStop>> = dao.getAllStopsFlow().map { entities ->
    val categories = loadCategories()
    entities.map { hydrateCategory(it.toDomainModel(), categories) }
  }

  private fun hydrateCategory(stop: DeliveryStop, categories: List<com.example.model.DeliveryCategory>): DeliveryStop {
    val resolvedCatId = stop.categoryId.ifBlank { stop.status.name }
    val matchingCat = categories.firstOrNull { it.id == resolvedCatId } ?: return stop
    return stop.copy(
      categoryId = matchingCat.id,
      categoryName = matchingCat.name,
      categoryColorHex = matchingCat.primaryColorHex
    )
  }

  private val categoriesFile: java.io.File?
    get() = context?.let { java.io.File(it.filesDir, "custom_categories.json") }

  private val suspiciousFile: java.io.File?
    get() = context?.let { java.io.File(it.filesDir, "suspicious_customers.json") }

  private val _suspiciousCustomersFlow = MutableStateFlow<List<SuspiciousCustomer>>(emptyList())
  val suspiciousCustomersFlow: StateFlow<List<SuspiciousCustomer>> = _suspiciousCustomersFlow.asStateFlow()

  init {
    _suspiciousCustomersFlow.value = loadSuspiciousCustomers()
    startPersistenceWorker()
  }

  private fun startPersistenceWorker() {
    persistenceScope.launch {
      for (op in persistChannel) {
        when (op) {
          is PersistOp.SaveStop -> {
            try {
              stopsDbMutex.withLock {
                dao.insertOrUpdate(op.stop.toEntity())
              }
            } finally {
              op.deferred?.complete(Unit)
            }
            persistenceScope.launch {
              runCatching { recordCustomerLocallyAndAsyncCloud(op.stop) }
            }
          }
          is PersistOp.SaveAllStops -> {
            try {
              stopsDbMutex.withLock {
                dao.insertAll(op.stops.map { it.toEntity() })
              }
            } finally {
              op.deferred?.complete(Unit)
            }
          }
          is PersistOp.DeleteStop -> {
            try {
              stopsDbMutex.withLock {
                dao.deleteById(op.id)
              }
            } finally {
              op.deferred?.complete(Unit)
            }
          }
          is PersistOp.EmptyDeleted -> {
            try {
              stopsDbMutex.withLock {
                dao.emptyDeleted()
              }
            } finally {
              op.deferred?.complete(Unit)
            }
          }
          is PersistOp.ClearAll -> {
            try {
              stopsDbMutex.withLock {
                dao.clearAll()
              }
              customerDao?.clearAll()
            } finally {
              op.deferred?.complete(Unit)
            }
          }
          is PersistOp.Barrier -> {
            op.deferred.complete(Unit)
          }
        }
      }
    }
  }

  private suspend fun recordCustomerLocallyAndAsyncCloud(stop: DeliveryStop) {
    val p1 = stop.phone1.trim()
    val p2 = stop.phone2.trim()
    val targetPhone = if (p1.isNotBlank()) p1 else p2
    val address = stop.title.trim()

    if (targetPhone.isNotBlank() && address.isNotBlank()) {
      val normalized = CustomerRecord.normalizePhoneNumber(targetPhone)
      val coords = CustomerRecord.parseCoordinatesFromUrlOrText(stop.locationCoordinates)

      val customer = CustomerRecord(
        phone = normalized,
        originalPhone = targetPhone,
        writtenAddress = address,
        areaSubtitle = stop.subtitle,
        locationCoordinates = stop.locationCoordinates,
        latitude = coords?.first,
        longitude = coords?.second,
        lastUpdated = "الآن"
      )
      customerDao?.insertOrUpdate(customer.toEntity())

      // Detach Firestore cloud sync so offline network never delays subsequent Room saves
      persistenceScope.launch {
        runCatching {
          firestoreCustomerRepo?.saveCustomer(
            rawPhone = normalized,
            address = address,
            lat = coords?.first,
            lng = coords?.second
          )
        }
      }
    }
  }

  private fun writeTextAtomically(file: java.io.File, content: String) {
    file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    val tempFile = java.io.File(file.parentFile, "${file.name}.tmp")
    tempFile.writeText(content)
    if (!tempFile.renameTo(file)) {
      file.writeText(content)
      tempFile.delete()
    }
  }

  fun loadCategories(): List<DeliveryCategory> = synchronized(filesLock) {
    val file = categoriesFile ?: return DeliveryCategory.DEFAULT_CATEGORIES
    if (!file.exists()) return DeliveryCategory.DEFAULT_CATEGORIES
    return try {
      val text = file.readText()
      val array = org.json.JSONArray(text)
      val list = mutableListOf<DeliveryCategory>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          DeliveryCategory(
            id = obj.getString("id"),
            name = obj.getString("name"),
            primaryColorHex = obj.getString("primaryColorHex"),
            containerColorHex = obj.optString("containerColorHex", "#F8FAFC"),
            borderColorHex = obj.optString("borderColorHex", "#E2E8F0"),
            dotColorHex = obj.optString("dotColorHex", obj.getString("primaryColorHex")),
            isSystemDefault = obj.optBoolean("isSystemDefault", false),
            displayOrder = obj.optInt("displayOrder", i)
          )
        )
      }
      if (list.isEmpty()) DeliveryCategory.DEFAULT_CATEGORIES else list.sortedBy { it.displayOrder }
    } catch (_: Exception) {
      DeliveryCategory.DEFAULT_CATEGORIES
    }
  }

  fun saveCategories(categories: List<DeliveryCategory>) = synchronized(filesLock) {
    val file = categoriesFile ?: return
    try {
      val array = org.json.JSONArray()
      categories.forEachIndexed { index, cat ->
        val obj = org.json.JSONObject().apply {
          put("id", cat.id)
          put("name", cat.name)
          put("primaryColorHex", cat.primaryColorHex)
          put("containerColorHex", cat.containerColorHex)
          put("borderColorHex", cat.borderColorHex)
          put("dotColorHex", cat.dotColorHex)
          put("isSystemDefault", cat.isSystemDefault)
          put("displayOrder", index)
        }
        array.put(obj)
      }
      writeTextAtomically(file, array.toString())
    } catch (_: Exception) {}
  }

  fun enqueueSaveStop(stop: DeliveryStop) {
    persistChannel.trySend(PersistOp.SaveStop(stop))
  }

  fun enqueueSaveAllStops(stops: List<DeliveryStop>) {
    persistChannel.trySend(PersistOp.SaveAllStops(stops))
  }

  fun enqueueDeleteStop(id: String) {
    persistChannel.trySend(PersistOp.DeleteStop(id))
  }

  fun enqueueEmptyDeleted() {
    persistChannel.trySend(PersistOp.EmptyDeleted())
  }

  suspend fun flushPendingWrites() {
    val barrier = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.Barrier(barrier))
    barrier.await()
  }

  fun flushPendingWritesBlocking(timeoutMs: Long = 2500L) {
    val barrier = CompletableDeferred<Unit>()
    if (persistChannel.trySend(PersistOp.Barrier(barrier)).isSuccess) {
      kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
          barrier.await()
        }
      }
    }
  }

  suspend fun getAllStops(): List<DeliveryStop> = withContext(Dispatchers.IO) {
    flushPendingWrites()
    val categories = loadCategories()
    stopsDbMutex.withLock {
      dao.getAllStops().map { hydrateCategory(it.toDomainModel(), categories) }
    }
  }

  suspend fun saveStop(stop: DeliveryStop) {
    val deferred = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.SaveStop(stop, deferred))
    deferred.await()
  }

  suspend fun saveAllStops(stops: List<DeliveryStop>) {
    val deferred = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.SaveAllStops(stops, deferred))
    deferred.await()
  }

  suspend fun deleteStop(id: String) {
    val deferred = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.DeleteStop(id, deferred))
    deferred.await()
  }

  suspend fun emptyDeletedStops() {
    val deferred = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.EmptyDeleted(deferred))
    deferred.await()
  }

  suspend fun clearAll() {
    val deferred = CompletableDeferred<Unit>()
    persistChannel.send(PersistOp.ClearAll(deferred))
    deferred.await()
  }

  fun clearCategoriesForTest() = synchronized(filesLock) {
    categoriesFile?.delete()
    Unit
  }

  // ==========================================
  // Suspicious Customers Watchlist (القائمة السوداء)
  // ==========================================

  fun loadSuspiciousCustomers(): List<SuspiciousCustomer> = synchronized(filesLock) {
    val file = suspiciousFile ?: return _suspiciousCustomersFlow.value
    if (!file.exists()) return emptyList()
    return try {
      val array = org.json.JSONArray(file.readText())
      val list = mutableListOf<SuspiciousCustomer>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val rawPhone = obj.optString("phone", "")
        val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
        if (normalized.length >= 7) {
          list.add(
            SuspiciousCustomer(
              phone = normalized,
              originalPhone = obj.optString("originalPhone", rawPhone).ifBlank { normalized },
              note = obj.optString("note", SuspiciousCustomer.DEFAULT_NOTE).ifBlank { SuspiciousCustomer.DEFAULT_NOTE },
              addedAt = obj.optString("addedAt", "")
            )
          )
        }
      }
      list
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun persistSuspiciousCustomers(list: List<SuspiciousCustomer>) = synchronized(filesLock) {
    _suspiciousCustomersFlow.value = list
    val file = suspiciousFile ?: return
    try {
      val array = org.json.JSONArray()
      list.forEach { item ->
        val obj = org.json.JSONObject().apply {
          put("phone", item.phone)
          put("originalPhone", item.originalPhone)
          put("note", item.note)
          put("addedAt", item.addedAt)
        }
        array.put(obj)
      }
      writeTextAtomically(file, array.toString())
    } catch (_: Exception) {}
  }

  fun addSuspiciousCustomer(rawPhone: String, rawNote: String = ""): SuspiciousCustomer? {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.length < 7) return null
    val finalNote = rawNote.trim().ifBlank { SuspiciousCustomer.DEFAULT_NOTE }
    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
      .format(java.util.Date())
    val record = SuspiciousCustomer(
      phone = normalized,
      originalPhone = rawPhone.trim().ifBlank { normalized },
      note = finalNote,
      addedAt = timestamp
    )
    val updated = listOf(record) + loadSuspiciousCustomers().filter { it.phone != normalized }
    persistSuspiciousCustomers(updated)
    return record
  }

  fun removeSuspiciousCustomer(rawPhone: String) {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.isBlank()) return
    val updated = loadSuspiciousCustomers().filter { it.phone != normalized }
    persistSuspiciousCustomers(updated)
  }

  fun findSuspiciousByPhone(rawPhone: String): SuspiciousCustomer? {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.length < 7) return null
    return _suspiciousCustomersFlow.value.firstOrNull { it.phone == normalized }
      ?: loadSuspiciousCustomers().firstOrNull { it.phone == normalized }
  }

  fun findSuspiciousForPhones(vararg phones: String): SuspiciousCustomer? {
    for (p in phones) {
      val match = findSuspiciousByPhone(p)
      if (match != null) return match
    }
    return null
  }

  fun clearSuspiciousCustomersForTest() {
    suspiciousFile?.delete()
    _suspiciousCustomersFlow.value = emptyList()
  }

  // ==========================================
  // Cloud Firestore & Local Customer Storage
  // ==========================================

  /**
   * Auto-lookup customer by phone number with zero-lag local-first priority:
   * 1. Query local Room customer cache first (exact + normalized/trailing match).
   * 2. Query local delivery stops immediately so offline/slow data never delays call overlays.
   * 3. Fall back to Firebase Firestore with a short timeout and cache locally.
   */
  suspend fun findCustomerByPhone(rawPhone: String): CustomerRecord? = withContext(Dispatchers.IO) {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.length < 7) return@withContext null
    val suffixKey = CustomerRecord.trailingPhoneKey(normalized)

    // 1. Query local Room database first (fast, preserves areaSubtitle)
    val exactLocal = customerDao?.findByPhone(normalized)?.toDomain()
    if (exactLocal != null) {
      return@withContext exactLocal
    }

    // Check normalized/trailing match across local customer cache for legacy formatted entries
    val allLocal = customerDao?.getAll().orEmpty()
    val fuzzyLocal = allLocal.firstOrNull { entity ->
      val entityNorm = CustomerRecord.normalizePhoneNumber(entity.normalizedPhone)
      entityNorm == normalized ||
        (suffixKey.length >= 10 && CustomerRecord.trailingPhoneKey(entityNorm) == suffixKey)
    }?.toDomain()
    if (fuzzyLocal != null) {
      return@withContext fuzzyLocal
    }

    // 2. Check historical local delivery stops BEFORE network calls for instant call display
    val stops = dao.getAllStops()
    val matchedStop = stops.firstOrNull { stop ->
      if (stop.title.isBlank()) return@firstOrNull false
      val n1 = CustomerRecord.normalizePhoneNumber(stop.phone1)
      val n2 = CustomerRecord.normalizePhoneNumber(stop.phone2)
      n1 == normalized ||
        n2 == normalized ||
        (suffixKey.length >= 10 && (
          (n1.length >= 10 && CustomerRecord.trailingPhoneKey(n1) == suffixKey) ||
          (n2.length >= 10 && CustomerRecord.trailingPhoneKey(n2) == suffixKey)
        ))
    }

    if (matchedStop != null) {
      val coords = CustomerRecord.parseCoordinatesFromUrlOrText(matchedStop.locationCoordinates)
      val customer = CustomerRecord(
        phone = normalized,
        originalPhone = rawPhone,
        writtenAddress = matchedStop.title,
        areaSubtitle = matchedStop.subtitle,
        locationCoordinates = matchedStop.locationCoordinates,
        latitude = coords?.first,
        longitude = coords?.second,
        lastUpdated = matchedStop.dateTime
      )
      customerDao?.insertOrUpdate(customer.toEntity())
      CoroutineScope(Dispatchers.IO).launch {
        firestoreCustomerRepo?.saveCustomer(
          rawPhone = normalized,
          address = customer.writtenAddress,
          lat = customer.latitude,
          lng = customer.longitude
        )
      }
      return@withContext customer
    }

    // 3. Query Firebase Firestore with short timeout so slow network never blocks calls
    val firestoreMatch = kotlinx.coroutines.withTimeoutOrNull(2500L) {
      runCatching { firestoreCustomerRepo?.findCustomerByPhone(normalized) }.getOrNull()
    }
    if (firestoreMatch != null) {
      customerDao?.insertOrUpdate(firestoreMatch.toEntity())
      return@withContext firestoreMatch
    }

    null
  }

  suspend fun saveCustomer(customer: CustomerRecord) = withContext(Dispatchers.IO) {
    customerDao?.insertOrUpdate(customer.toEntity())
    CoroutineScope(Dispatchers.IO).launch {
      firestoreCustomerRepo?.saveCustomer(
        rawPhone = customer.phone,
        address = customer.writtenAddress,
        lat = customer.latitude,
        lng = customer.longitude
      )
    }
    Unit
  }

  suspend fun getAllCustomers(): List<CustomerRecord> = withContext(Dispatchers.IO) {
    customerDao?.getAll()?.map { it.toDomain() } ?: emptyList()
  }

  suspend fun getCustomersCount(): Int = withContext(Dispatchers.IO) {
    customerDao?.getCount() ?: 0
  }

  /**
   * Syncs all customers bidirectionally with Cloud Firestore:
   * - Uploads all local customer records to Firestore.
   * - Pulls any existing customers from Firestore into local Room.
   */
  suspend fun syncWithFirestore(): Result<Int> = withContext(Dispatchers.IO) {
    try {
      val localRecords = customerDao?.getAll()?.map { it.toDomain() } ?: emptyList()
      // Upload local to Firestore
      if (localRecords.isNotEmpty()) {
        firestoreCustomerRepo?.syncBatchToFirestore(localRecords)
      }

      // Fetch all from Firestore and store locally
      val remoteRecords = firestoreCustomerRepo?.fetchAllCustomers() ?: emptyList()
      if (remoteRecords.isNotEmpty()) {
        customerDao?.insertAll(remoteRecords.map { it.toEntity() })
      }

      val total = customerDao?.getCount() ?: remoteRecords.size
      Result.success(total)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Starts automatic realtime synchronization with Firestore.
   * Any change made in Firestore is immediately persisted to the local database.
   */
  fun startRealtimeSync(onCountChanged: ((Int) -> Unit)? = null): com.google.firebase.firestore.ListenerRegistration? {
    return firestoreCustomerRepo?.observeCustomers { remoteRecords ->
      CoroutineScope(Dispatchers.IO).launch {
        if (remoteRecords.isNotEmpty()) {
          customerDao?.insertAll(remoteRecords.map { it.toEntity() })
        }
        val count = customerDao?.getCount() ?: remoteRecords.size
        onCountChanged?.invoke(count)
      }
    }
  }

  companion object {
    @Volatile
    private var INSTANCE: DeliveryRepository? = null

    fun initialize(context: Context): DeliveryRepository {
      return INSTANCE ?: synchronized(this) {
        val db = AppDatabase.getInstance(context)
        val repo = DeliveryRepository(
          dao = db.deliveryStopDao(),
          customerDao = db.customerRecordDao(),
          context = context.applicationContext
        )
        INSTANCE = repo
        repo
      }
    }

    fun getInstance(context: Context? = null): DeliveryRepository? {
      if (INSTANCE != null) return INSTANCE
      if (context != null) return initialize(context)
      return null
    }
  }
}
