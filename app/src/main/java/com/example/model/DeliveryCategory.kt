package com.example.model

import androidx.compose.ui.graphics.Color

data class DeliveryCategory(
  val id: String,
  val name: String,
  val primaryColorHex: String,
  val containerColorHex: String = "#F8FAFC",
  val borderColorHex: String = "#E2E8F0",
  val dotColorHex: String = primaryColorHex,
  val isSystemDefault: Boolean = false,
  val displayOrder: Int = 0
) {
  val primaryColor: Color
    get() = runCatching { Color(android.graphics.Color.parseColor(primaryColorHex)) }.getOrDefault(Color(0xFF0284C7))

  val containerColor: Color
    get() = runCatching { Color(android.graphics.Color.parseColor(containerColorHex)) }.getOrDefault(Color(0xFFF0F9FF))

  val borderColor: Color
    get() = runCatching { Color(android.graphics.Color.parseColor(borderColorHex)) }.getOrDefault(Color(0xFFBAE6FD))

  val dotColor: Color
    get() = runCatching { Color(android.graphics.Color.parseColor(dotColorHex)) }.getOrDefault(primaryColor)

  companion object {
    val DEFAULT_CATEGORIES = listOf(
      DeliveryCategory(
        id = "IN_TRANSIT",
        name = "قيد التوصيل",
        primaryColorHex = "#0284C7",
        containerColorHex = "#F0F9FF",
        borderColorHex = "#BAE6FD",
        dotColorHex = "#0284C7",
        isSystemDefault = true,
        displayOrder = 0
      ),
      DeliveryCategory(
        id = "DELIVERED",
        name = "تم التسليم",
        primaryColorHex = "#16A34A",
        containerColorHex = "#F0FDF4",
        borderColorHex = "#BBF7D0",
        dotColorHex = "#16A34A",
        isSystemDefault = true,
        displayOrder = 1
      ),
      DeliveryCategory(
        id = "DELAYED",
        name = "مؤجل",
        primaryColorHex = "#D97706",
        containerColorHex = "#FFFBEB",
        borderColorHex = "#FDE68A",
        dotColorHex = "#D97706",
        isSystemDefault = true,
        displayOrder = 2
      ),
      DeliveryCategory(
        id = "LINE_ISSUES",
        name = "مشاكل خط",
        primaryColorHex = "#9333EA",
        containerColorHex = "#FAF5FF",
        borderColorHex = "#E9D5FF",
        dotColorHex = "#9333EA",
        isSystemDefault = true,
        displayOrder = 3
      ),
      DeliveryCategory(
        id = "RETURNED",
        name = "راجع للشركة",
        primaryColorHex = "#E11D48",
        containerColorHex = "#FFF1F2",
        borderColorHex = "#FECDD3",
        dotColorHex = "#E11D48",
        isSystemDefault = true,
        displayOrder = 4
      )
    )

    val PRESET_COLORS = listOf(
      "#0284C7", // Sky Blue
      "#16A34A", // Emerald Green
      "#D97706", // Amber
      "#9333EA", // Purple
      "#E11D48", // Rose Red
      "#0D9488", // Teal
      "#4F46E5", // Indigo
      "#EA580C", // Orange
      "#DB2777"  // Pink
    )

    fun createCustom(name: String, primaryColorHex: String, displayOrder: Int): DeliveryCategory {
      val id = "cat_${System.currentTimeMillis()}_${(100..999).random()}"
      return DeliveryCategory(
        id = id,
        name = name,
        primaryColorHex = primaryColorHex,
        containerColorHex = "#F8FAFC",
        borderColorHex = primaryColorHex,
        dotColorHex = primaryColorHex,
        isSystemDefault = false,
        displayOrder = displayOrder
      )
    }
  }
}
