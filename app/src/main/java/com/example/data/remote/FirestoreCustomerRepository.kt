package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.R
import com.example.model.CustomerRecord
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud Firestore repository for customer records.
 *
 * Rules & Schema specifications:
 * - Document Collection: "customers"
 * - Document ID (Primary Key): Phone number normalized (e.g. "07710266414")
 * - Document Fields (strictly no customer name):
 *     1. phone: String
 *     2. address: String
 *     3. lat: Double?
 *     4. lng: Double?
 *     5. date: String
 * - Offline Persistence: Local disk cache enabled via PersistentCacheSettings
 */
class FirestoreCustomerRepository(private val context: Context) {

  private val firestore: FirebaseFirestore by lazy {
    val databaseId = try {
      context.getString(R.string.firestore_database_id)
    } catch (_: Exception) {
      ""
    }

    val db = if (databaseId.isNotBlank() && databaseId != "(default)") {
      try {
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), databaseId)
      } catch (e: Exception) {
        Log.w(TAG, "Named database instance fallback to default: ${e.message}")
        FirebaseFirestore.getInstance()
      }
    } else {
      FirebaseFirestore.getInstance()
    }

    try {
      val settings = FirebaseFirestoreSettings.Builder()
        .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
        .build()
      db.firestoreSettings = settings
    } catch (e: Exception) {
      Log.d(TAG, "Persistence settings note: ${e.message}")
    }

    db
  }

  private val customersCollection get() = firestore.collection(COLLECTION_CUSTOMERS)

  /**
   * Immediately look up a customer in Firestore by normalized phone number.
   * If found, returns the record containing phone, address, and coordinates.
   */
  suspend fun findCustomerByPhone(rawPhone: String): CustomerRecord? = withContext(Dispatchers.IO) {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.length < 7) return@withContext null

    try {
      val doc = customersCollection.document(normalized).get().await()
      if (doc.exists()) {
        val phone = doc.getString("phone") ?: normalized
        val address = doc.getString("address") ?: ""
        val lat = doc.getDouble("lat")
        val lng = doc.getDouble("lng")
        val date = doc.getString("date") ?: ""

        val coordsStr = if (lat != null && lng != null) "$lat,$lng" else null
        val mapUrl = CustomerRecord.buildGoogleMapsUrl(lat, lng)

        return@withContext CustomerRecord(
          phone = normalized,
          originalPhone = phone,
          writtenAddress = address,
          areaSubtitle = "",
          locationCoordinates = coordsStr,
          latitude = lat,
          longitude = lng,
          mapUrl = mapUrl,
          lastUpdated = date.ifBlank { "Firestore سحابي" }
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Firestore query for $normalized failed: ${e.message}")
    }
    null
  }

  /**
   * Saves or updates customer record in Firestore with document ID = phone.
   * Fields strictly: phone, address, lat, lng, date.
   */
  suspend fun saveCustomer(
    rawPhone: String,
    address: String,
    lat: Double?,
    lng: Double?,
    customDate: String? = null
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val normalized = CustomerRecord.normalizePhoneNumber(rawPhone)
    if (normalized.length < 7 || address.isBlank()) {
      return@withContext Result.failure(IllegalArgumentException("رقم الهاتف أو العنوان غير صالح"))
    }

    try {
      val formattedDate = customDate ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
      val data = hashMapOf<String, Any?>(
        "phone" to normalized,
        "address" to address.trim(),
        "lat" to lat,
        "lng" to lng,
        "date" to formattedDate
      )

      customersCollection.document(normalized).set(data, SetOptions.merge()).await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed saving customer to Firestore: ${e.message}")
      Result.failure(e)
    }
  }

  /**
   * Fetches all registered customer documents from Firestore.
   */
  suspend fun fetchAllCustomers(): List<CustomerRecord> = withContext(Dispatchers.IO) {
    try {
      val snapshot = customersCollection.get().await()
      val list = mutableListOf<CustomerRecord>()
      for (doc in snapshot.documents) {
        val phone = doc.getString("phone") ?: doc.id
        val address = doc.getString("address") ?: ""
        val lat = doc.getDouble("lat")
        val lng = doc.getDouble("lng")
        val date = doc.getString("date") ?: ""
        if (phone.isNotBlank() && address.isNotBlank()) {
          val coordsStr = if (lat != null && lng != null) "$lat,$lng" else null
          list.add(
            CustomerRecord(
              phone = phone,
              originalPhone = phone,
              writtenAddress = address,
              areaSubtitle = "",
              locationCoordinates = coordsStr,
              latitude = lat,
              longitude = lng,
              mapUrl = CustomerRecord.buildGoogleMapsUrl(lat, lng),
              lastUpdated = date.ifBlank { "Firestore سحابي" }
            )
          )
        }
      }
      list
    } catch (e: Exception) {
      Log.w(TAG, "Error fetching all customers: ${e.message}")
      emptyList()
    }
  }

  /**
   * Syncs a batch of customer records to Firestore.
   */
  suspend fun syncBatchToFirestore(records: List<CustomerRecord>): Result<Int> = withContext(Dispatchers.IO) {
    try {
      var savedCount = 0
      val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
      val batch = firestore.batch()

      for (rec in records) {
        val norm = CustomerRecord.normalizePhoneNumber(rec.phone)
        if (norm.length >= 7 && rec.writtenAddress.isNotBlank()) {
          val docRef = customersCollection.document(norm)
          val data = hashMapOf<String, Any?>(
            "phone" to norm,
            "address" to rec.writtenAddress.trim(),
            "lat" to rec.latitude,
            "lng" to rec.longitude,
            "date" to formattedDate
          )
          batch.set(docRef, data, SetOptions.merge())
          savedCount++
        }
      }

      if (savedCount > 0) {
        batch.commit().await()
      }
      Result.success(savedCount)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Listens to Firestore customer collection changes in real time,
   * automatically receiving updates whenever documents are added or updated in the cloud.
   */
  fun observeCustomers(onUpdate: (List<CustomerRecord>) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
    return try {
      customersCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Snapshot listener error: ${error.message}")
          return@addSnapshotListener
        }
        if (snapshot != null) {
          val list = mutableListOf<CustomerRecord>()
          for (doc in snapshot.documents) {
            val phone = doc.getString("phone") ?: doc.id
            val address = doc.getString("address") ?: ""
            val lat = doc.getDouble("lat")
            val lng = doc.getDouble("lng")
            val date = doc.getString("date") ?: ""
            if (phone.isNotBlank() && address.isNotBlank()) {
              val coordsStr = if (lat != null && lng != null) "$lat,$lng" else null
              list.add(
                CustomerRecord(
                  phone = phone,
                  originalPhone = phone,
                  writtenAddress = address,
                  areaSubtitle = "",
                  locationCoordinates = coordsStr,
                  latitude = lat,
                  longitude = lng,
                  mapUrl = CustomerRecord.buildGoogleMapsUrl(lat, lng),
                  lastUpdated = date.ifBlank { "Firestore سحابي" }
                )
              )
            }
          }
          onUpdate(list)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to attach snapshot listener: ${e.message}")
      null
    }
  }

  companion object {
    private const val TAG = "FirestoreCustomerRepo"
    const val COLLECTION_CUSTOMERS = "customers"
  }
}
