package com.example.model

data class SuspiciousCustomer(
  val phone: String, // Normalized via CustomerRecord.normalizePhoneNumber
  val originalPhone: String = phone,
  val note: String = DEFAULT_NOTE,
  val addedAt: String = ""
) {
  companion object {
    const val DEFAULT_NOTE = "زبون مشبوه بسرقة الطلبات"
  }
}
