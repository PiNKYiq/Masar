package com.example.model

import androidx.compose.ui.graphics.Color

enum class DeliveryStatus(
  val labelAr: String,
  val badgeText: String,
  val primaryColor: Color,
  val containerColor: Color,
  val borderColor: Color,
  val dotColor: Color
) {
  IN_TRANSIT(
    labelAr = "قيد التوصيل",
    badgeText = "قيد التوصيل",
    primaryColor = Color(0xFF0284C7),
    containerColor = Color(0xFFF0F9FF),
    borderColor = Color(0xFFBAE6FD),
    dotColor = Color(0xFF0284C7)
  ),
  DELIVERED(
    labelAr = "تم التسليم",
    badgeText = "تم التسليم",
    primaryColor = Color(0xFF16A34A),
    containerColor = Color(0xFFF0FDF4),
    borderColor = Color(0xFFBBF7D0),
    dotColor = Color(0xFF16A34A)
  ),
  DELAYED(
    labelAr = "مؤجل",
    badgeText = "مؤجل",
    primaryColor = Color(0xFFD97706),
    containerColor = Color(0xFFFFFBEB),
    borderColor = Color(0xFFFDE68A),
    dotColor = Color(0xFFD97706)
  ),
  RETURNED(
    labelAr = "راجع للشركة",
    badgeText = "راجع للشركة",
    primaryColor = Color(0xFFE11D48),
    containerColor = Color(0xFFFFF1F2),
    borderColor = Color(0xFFFECDD3),
    dotColor = Color(0xFFE11D48)
  ),
  LINE_ISSUES(
    labelAr = "مشاكل خط",
    badgeText = "مشاكل خط",
    primaryColor = Color(0xFF9333EA),
    containerColor = Color(0xFFFAF5FF),
    borderColor = Color(0xFFE9D5FF),
    dotColor = Color(0xFF9333EA)
  )
}

enum class NoteIconType {
  CHECK,
  HISTORY,
  INFO,
  ROUTE,
  NAVIGATION,
  CALL
}

data class DeliveryStop(
  val id: String,
  val orderNumber: String,                  // e.g. "ORD-9810#"
  val orderCodeNumber: String = "",         // empty by default, editable & filled via OCR/typing
  val price: String = "",                   // e.g. "25,000 د.ع", editable & extracted via OCR
  val dateTime: String,                     // e.g. "12 مايو، 11:30 ص"
  val title: String,                        // e.g. "حي الروضة - شارع حفصة بنت عمر"
  val subtitle: String,                     // e.g. "فيلا 14، بجانب جامع الفردوس، شرق الرياض"
  val note: String,                         // e.g. "تم التسليم بنجاح في يد المستلم"
  val noteIconType: NoteIconType = NoteIconType.CHECK,
  val status: DeliveryStatus = DeliveryStatus.IN_TRANSIT,
  val categoryId: String = status.name,
  val categoryName: String = status.labelAr,
  val categoryColorHex: String? = null,
  val governorate: String = "",
  val region: String = "",
  val phone1: String = "",                  // empty by default, editable & filled via OCR/typing
  val phone2: String = "",                  // empty by default, editable & filled via OCR/typing
  val recipientName: String = "",
  val recipientPhone: String = "",
  val locationCoordinates: String? = null,  // real GPS coordinates e.g. "30.5081, 47.7835"
  val isLocationSaved: Boolean = false,
  val locationStatus: String = "آخر موقع مستلم: لا يوجد موقع محفوظ",
  val issueNotes: String = "",              // empty by default, editable
  val capturedIssuePhotoUri: String? = null,// photo captured via Camera or picked
  val capturedContentsPhotoUri: String? = null,// camera photo for package contents
  val capturedExternalPhotoUri: String? = null,// camera photo for external package appearance
  val isAutoSaveEnabled: Boolean = true,
  val hasExternalPhoto: Boolean = true,
  val hasContentsPhoto: Boolean = true,
  val screenshotUriString: String? = null,  // screenshot image URI from sharing or picker
  val isOcrProcessed: Boolean = false,
  val isNewOrder: Boolean = false,
  val isDeleted: Boolean = false,           // true when moved to deleted orders (الطلبات المحذوفة)
  val deletedAt: String? = null,
  val createdAt: Long = System.currentTimeMillis()
)


