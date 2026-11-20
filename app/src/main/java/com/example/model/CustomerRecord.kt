package com.example.model

data class CustomerRecord(
  val phone: String, // Normalized phone (e.g. "07710266414")
  val originalPhone: String = phone,
  val writtenAddress: String, // Address title / street
  val areaSubtitle: String = "", // Area / neighborhood / apartment
  val locationCoordinates: String? = null, // e.g. "33.3152,44.3661"
  val latitude: Double? = null,
  val longitude: Double? = null,
  val mapUrl: String = "",
  val lastUpdated: String = ""
) {
  companion object {
    fun toAsciiDigits(raw: String): String {
      if (raw.isEmpty()) return raw
      val sb = StringBuilder(raw.length)
      for (ch in raw) {
        when (ch) {
          in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
          in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
          else -> sb.append(ch)
        }
      }
      return sb.toString()
    }

    /**
     * Normalizes phone number by converting Arabic/Persian numerals to ASCII,
     * removing all non-digits, and standardizing Iraqi & international prefixes.
     */
    fun normalizePhoneNumber(raw: String?): String {
      if (raw == null) return ""
      val digits = toAsciiDigits(raw).filter { it.isDigit() }
      return when {
        digits.startsWith("00964") -> {
          val rest = digits.substring(5)
          if (rest.startsWith("0")) rest else "0$rest"
        }
        digits.startsWith("964") -> {
          val rest = digits.substring(3)
          if (rest.startsWith("0")) rest else "0$rest"
        }
        digits.length == 10 && digits.startsWith("7") -> "0$digits"
        else -> digits
      }
    }

    /**
     * Returns the last 10 digits for suffix matching when numbers carry different country prefixes.
     */
    fun trailingPhoneKey(raw: String?): String {
      val norm = normalizePhoneNumber(raw)
      return if (norm.length >= 10) norm.takeLast(10) else norm
    }

    /**
     * Extracts latitude and longitude from a Google Maps URL or raw coordinates string.
     */
    fun parseCoordinatesFromUrlOrText(input: String?): Pair<Double, Double>? {
      if (input.isNullOrBlank()) return null
      val text = input.trim()

      // Format 1: "...q=-?33.3152,44.3661..." or "...query=-?33.3152,44.3661..." or "...@33.3152,44.3661..."
      val queryRegex = Regex("""(?:q=|query=|@)(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
      queryRegex.find(text)?.let { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lng != null) return Pair(lat, lng)
      }

      // Format 2: "33.3152, 44.3661" or raw coordinates anywhere in text
      val rawRegex = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
      rawRegex.find(text)?.let { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lng != null) return Pair(lat, lng)
      }

      return null
    }

    fun buildGoogleMapsUrl(latitude: Double?, longitude: Double?): String {
      return if (latitude != null && longitude != null) {
        "https://maps.google.com/?q=$latitude,$longitude"
      } else {
        ""
      }
    }
  }
}
