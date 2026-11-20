package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.CustomerRecord

@Entity(tableName = "customers_database")
data class CustomerRecordEntity(
  @PrimaryKey val normalizedPhone: String,
  val originalPhone: String,
  val writtenAddress: String,
  val areaSubtitle: String,
  val locationCoordinates: String?,
  val latitude: Double?,
  val longitude: Double?,
  val mapUrl: String,
  val lastUpdated: String,
  val updatedAtTimestamp: Long = System.currentTimeMillis()
)

fun CustomerRecordEntity.toDomain(): CustomerRecord = CustomerRecord(
  phone = normalizedPhone,
  originalPhone = originalPhone,
  writtenAddress = writtenAddress,
  areaSubtitle = areaSubtitle,
  locationCoordinates = locationCoordinates,
  latitude = latitude,
  longitude = longitude,
  mapUrl = mapUrl,
  lastUpdated = lastUpdated
)

fun CustomerRecord.toEntity(): CustomerRecordEntity = CustomerRecordEntity(
  normalizedPhone = CustomerRecord.normalizePhoneNumber(phone),
  originalPhone = originalPhone,
  writtenAddress = writtenAddress,
  areaSubtitle = areaSubtitle,
  locationCoordinates = locationCoordinates ?: if (latitude != null && longitude != null) "$latitude,$longitude" else null,
  latitude = latitude,
  longitude = longitude,
  mapUrl = mapUrl.ifBlank { CustomerRecord.buildGoogleMapsUrl(latitude, longitude) },
  lastUpdated = lastUpdated.ifBlank { "الآن" }
)
