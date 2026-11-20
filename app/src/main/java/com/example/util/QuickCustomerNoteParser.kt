package com.example.util

import com.example.model.CustomerRecord

data class ParsedCustomerEntry(
  val phone: String,
  val normalizedPhone: String,
  val address: String = "",
  val area: String = "",
  val sourceLine: String = ""
)

data class ScreenQuickParseResult(
  val entries: List<ParsedCustomerEntry>,
  val candidateLines: List<String>
)

/**
 * Extracts customer phone numbers from screen text or clipboard.
 * Strictly recognizes ONLY:
 *  - 11-digit phone numbers (e.g., 07875160858), OR
 *  - Phone numbers starting with a country code (e.g., +964..., 00964..., 9647XXXXXXXXX).
 * Customer address is always left empty for manual user input.
 */
object QuickCustomerNoteParser {

  // Matches potential phone spans (with optional + prefix and spaces/dashes/dots between groups)
  private val PHONE_CANDIDATE_REGEX = Regex(
    "(?<![\\d+])(?:\\+?\\d[\\d\\s.\\-]{8,18}\\d)(?!\\d)"
  )

  private val PRICE_SUFFIX_REGEX = Regex("(?i)^\\s*(?:د\\.?\\s*ع|ع\\.?\\s*د|دينار|iqd|usd|\\$|ريال|درهم)")
  private val TIMESTAMP_OR_BATTERY_REGEX = Regex("^\\d{1,2}:\\d{2}(?:\\s*[APap][Mm])?$|^\\d{1,3}%$")
  private val DATE_ONLY_REGEX = Regex("^\\d{2,4}[/-]\\d{1,2}[/-]\\d{2,4}$")

  fun normalizeDigits(input: String): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length)
    for (ch in input) {
      when (ch) {
        in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
        in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  fun parseFromScreenText(rawScreenText: String): ScreenQuickParseResult {
    if (rawScreenText.isBlank()) {
      return ScreenQuickParseResult(entries = emptyList(), candidateLines = emptyList())
    }

    val rawLines = normalizeDigits(rawScreenText)
      .split('\n')
      .map { it.trim() }
      .filter { line ->
        line.isNotBlank() &&
          line.length >= 2 &&
          !TIMESTAMP_OR_BATTERY_REGEX.matches(line) &&
          !DATE_ONLY_REGEX.matches(line)
      }

    if (rawLines.isEmpty()) {
      return ScreenQuickParseResult(entries = emptyList(), candidateLines = emptyList())
    }

    val seenPhones = mutableSetOf<String>()
    val entries = mutableListOf<ParsedCustomerEntry>()

    for (line in rawLines) {
      for (match in PHONE_CANDIDATE_REGEX.findAll(line)) {
        val raw = match.value.trim()
        if (DATE_ONLY_REGEX.matches(raw)) continue

        val after = line.substring(match.range.last + 1)
        if (PRICE_SUFFIX_REGEX.containsMatchIn(after)) continue

        val normPhone = extractStrictPhone(raw) ?: continue
        if (!seenPhones.add(normPhone)) continue

        entries.add(
          ParsedCustomerEntry(
            phone = normPhone,
            normalizedPhone = normPhone,
            address = "",
            area = "",
            sourceLine = line
          )
        )
      }
    }

    return ScreenQuickParseResult(
      entries = entries,
      candidateLines = emptyList()
    )
  }

  /**
   * Accepts ONLY:
   *  1. Phone numbers starting with a country code:
   *     - '+' followed by 10..15 digits (e.g. +964 771 555 6666, +966 50 123 4567)
   *     - '00' followed by country code (total 11..16 digits, e.g. 009647712223333)
   *     - '964' followed by 10 digits (e.g. 9647712223333) or '9640' + 10 digits (e.g. 96407712223333)
   *  2. Phone numbers consisting of exactly 11 digits (e.g. 07875160858, 07710266414)
   */
  private fun extractStrictPhone(rawMatch: String): String? {
    val trimmed = rawMatch.trim()
    val digitsOnly = trimmed.filter { it.isDigit() }
    if (digitsOnly.isEmpty()) return null

    val startsWithPlusCountryCode = trimmed.startsWith("+") && digitsOnly.length in 10..15
    val startsWithDoubleZeroCountryCode = digitsOnly.startsWith("00") && digitsOnly.length in 11..16
    val startsWith964CountryCode = digitsOnly.startsWith("964") &&
      (digitsOnly.length == 13 || (digitsOnly.startsWith("9640") && digitsOnly.length == 14))
    val isExactElevenDigits = !trimmed.startsWith("+") && !digitsOnly.startsWith("00") && digitsOnly.length == 11

    if (!startsWithPlusCountryCode &&
      !startsWithDoubleZeroCountryCode &&
      !startsWith964CountryCode &&
      !isExactElevenDigits
    ) {
      return null
    }

    val normalized = CustomerRecord.normalizePhoneNumber(digitsOnly)
    return normalized.takeIf { it.length >= 10 }
  }
}
