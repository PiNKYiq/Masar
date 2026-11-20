package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.model.NoteIconType

@Entity(tableName = "delivery_stops")
data class DeliveryStopEntity(
  @PrimaryKey val id: String,
  val orderNumber: String,
  val orderCodeNumber: String = "",
  val price: String = "",
  val dateTime: String,
  val title: String,
  val subtitle: String,
  val note: String,
  val noteIconType: String,
  val status: String,
  val categoryId: String = "",
  val categoryName: String = "",
  val categoryColorHex: String? = null,
  val governorate: String,
  val region: String,
  val phone1: String,
  val phone2: String,
  val recipientName: String,
  val recipientPhone: String,
  val locationCoordinates: String?,
  val isLocationSaved: Boolean,
  val locationStatus: String,
  val issueNotes: String,
  val capturedIssuePhotoUri: String?,
  val capturedContentsPhotoUri: String?,
  val capturedExternalPhotoUri: String?,
  val isAutoSaveEnabled: Boolean,
  val hasExternalPhoto: Boolean,
  val hasContentsPhoto: Boolean,
  val screenshotUriString: String?,
  val isOcrProcessed: Boolean,
  val isNewOrder: Boolean,
  val isDeleted: Boolean,
  val deletedAt: String?,
  val createdAt: Long = System.currentTimeMillis()
)

fun DeliveryStopEntity.toDomainModel(): DeliveryStop {
  val resolvedCategoryId = categoryId.ifBlank { status }
  val parsedStatus = runCatching { DeliveryStatus.valueOf(status) }.getOrNull()
    ?: runCatching { DeliveryStatus.valueOf(resolvedCategoryId) }.getOrDefault(DeliveryStatus.IN_TRANSIT)
  val resolvedCategoryName = categoryName.ifBlank {
    runCatching { DeliveryStatus.valueOf(resolvedCategoryId).labelAr }.getOrDefault(parsedStatus.labelAr)
  }
  return DeliveryStop(
    id = id,
    orderNumber = orderNumber,
    orderCodeNumber = orderCodeNumber,
    price = price,
    dateTime = dateTime,
    title = title,
    subtitle = subtitle,
    note = note,
    noteIconType = runCatching { NoteIconType.valueOf(noteIconType) }.getOrDefault(NoteIconType.CHECK),
    status = parsedStatus,
    categoryId = resolvedCategoryId,
    categoryName = resolvedCategoryName,
    categoryColorHex = categoryColorHex,
    governorate = governorate,
    region = region,
    phone1 = phone1,
    phone2 = phone2,
    recipientName = recipientName,
    recipientPhone = recipientPhone,
    locationCoordinates = locationCoordinates,
    isLocationSaved = isLocationSaved,
    locationStatus = locationStatus,
    issueNotes = issueNotes,
    capturedIssuePhotoUri = capturedIssuePhotoUri,
    capturedContentsPhotoUri = capturedContentsPhotoUri,
    capturedExternalPhotoUri = capturedExternalPhotoUri,
    isAutoSaveEnabled = isAutoSaveEnabled,
    hasExternalPhoto = hasExternalPhoto,
    hasContentsPhoto = hasContentsPhoto,
    screenshotUriString = screenshotUriString,
    isOcrProcessed = isOcrProcessed,
    isNewOrder = isNewOrder,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    createdAt = createdAt
  )
}

fun DeliveryStop.toEntity(): DeliveryStopEntity {
  val resolvedCategoryId = categoryId.ifBlank { status.name }
  val resolvedCategoryName = categoryName.ifBlank { status.labelAr }
  return DeliveryStopEntity(
    id = id,
    orderNumber = orderNumber,
    orderCodeNumber = orderCodeNumber,
    price = price,
    dateTime = dateTime,
    title = title,
    subtitle = subtitle,
    note = note,
    noteIconType = noteIconType.name,
    status = status.name,
    categoryId = resolvedCategoryId,
    categoryName = resolvedCategoryName,
    categoryColorHex = categoryColorHex,
    governorate = governorate,
    region = region,
    phone1 = phone1,
    phone2 = phone2,
    recipientName = recipientName,
    recipientPhone = recipientPhone,
    locationCoordinates = locationCoordinates,
    isLocationSaved = isLocationSaved,
    locationStatus = locationStatus,
    issueNotes = issueNotes,
    capturedIssuePhotoUri = capturedIssuePhotoUri,
    capturedContentsPhotoUri = capturedContentsPhotoUri,
    capturedExternalPhotoUri = capturedExternalPhotoUri,
    isAutoSaveEnabled = isAutoSaveEnabled,
    hasExternalPhoto = hasExternalPhoto,
    hasContentsPhoto = hasContentsPhoto,
    screenshotUriString = screenshotUriString,
    isOcrProcessed = isOcrProcessed,
    isNewOrder = isNewOrder,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    createdAt = createdAt
  )
}
