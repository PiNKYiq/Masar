package com.example.util

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
  val versionName: String,
  val versionCode: Int,
  val releaseNotes: String,
  val downloadUrl: String,
  val publishedAt: String = ""
)

sealed class AppUpdateCheckState {
  data object Idle : AppUpdateCheckState()
  data object Checking : AppUpdateCheckState()
  data class NeedsSourceUrl(val message: String) : AppUpdateCheckState()
  data class UpToDate(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val checkedAt: String
  ) : AppUpdateCheckState()

  data class UpdateAvailable(
    val currentVersionName: String,
    val release: AppReleaseInfo,
    val checkedAt: String
  ) : AppUpdateCheckState()

  data class Error(val message: String) : AppUpdateCheckState()
}

/**
 * Google Play-compliant app update checker.
 * Fetches lightweight JSON release metadata over HTTPS (custom JSON manifest or GitHub Releases API)
 * and opens the verified release/store URL via Intent.ACTION_VIEW without downloading or installing APKs internally.
 */
object AppUpdateChecker {

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()
  }

  private val GITHUB_REPO_URL_REGEX = Regex(
    """^https://(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?$""",
    RegexOption.IGNORE_CASE
  )
  private val GITHUB_SHORTHAND_REGEX = Regex(
    """^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$"""
  )
  private val BUILD_CODE_IN_TAG_REGEX = Regex("""(?:\+|build[._-]?|b)(\d+)$""", RegexOption.IGNORE_CASE)

  /**
   * Normalizes user-entered update source into a secure HTTPS endpoint.
   * Supports:
   * - Full GitHub repo link: https://github.com/owner/repo -> GitHub Releases API latest endpoint
   * - Shorthand GitHub repo: owner/repo -> GitHub Releases API latest endpoint
   * - Direct HTTPS JSON manifest URL: https://example.com/update.json
   */
  fun normalizeUpdateEndpoint(rawInput: String): String? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    GITHUB_REPO_URL_REGEX.matchEntire(trimmed)?.let { match ->
      val owner = match.groupValues[1]
      val repo = match.groupValues[2]
      return "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    GITHUB_SHORTHAND_REGEX.matchEntire(trimmed)?.let { match ->
      val owner = match.groupValues[1]
      val repo = match.groupValues[2]
      return "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    if (trimmed.startsWith("https://", ignoreCase = true)) {
      val host = runCatching { Uri.parse(trimmed).host }.getOrNull()
      if (!host.isNullOrBlank()) {
        return trimmed
      }
    }

    return null
  }

  /**
   * Parses either a custom update manifest JSON or a GitHub Releases API JSON response.
   */
  fun parseReleaseJson(jsonBody: String): AppReleaseInfo? {
    if (jsonBody.isBlank()) return null
    return try {
      val json = JSONObject(jsonBody)

      // 1. Custom manifest JSON format: { "versionCode": 2, "versionName": "1.1", "releaseNotes": "...", "downloadUrl": "https://..." }
      val hasManifestFields = json.has("versionCode") || json.has("version_code") ||
        json.has("versionName") || json.has("version_name")
      if (hasManifestFields) {
        val vCode = when {
          json.has("versionCode") -> json.optInt("versionCode", 0)
          json.has("version_code") -> json.optInt("version_code", 0)
          else -> 0
        }
        val vName = sequenceOf(
          json.optString("versionName", ""),
          json.optString("version_name", ""),
          json.optString("version", "")
        ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
          .ifBlank { if (vCode > 0) vCode.toString() else "" }

        if (vName.isBlank() && vCode <= 0) return null

        val notes = sequenceOf(
          json.optString("releaseNotes", ""),
          json.optString("release_notes", ""),
          json.optString("changelog", ""),
          json.optString("notes", "")
        ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        val rawUrl = sequenceOf(
          json.optString("downloadUrl", ""),
          json.optString("download_url", ""),
          json.optString("storeUrl", ""),
          json.optString("store_url", ""),
          json.optString("url", "")
        ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        val safeUrl = sanitizeExternalUrl(rawUrl)
        val published = json.optString("publishedAt", json.optString("published_at", "")).trim()
        return AppReleaseInfo(
          versionName = stripLeadingV(vName),
          versionCode = vCode,
          releaseNotes = notes,
          downloadUrl = safeUrl,
          publishedAt = published
        )
      }

      // 2. GitHub Releases API format: { "tag_name": "v1.2", "name": "...", "body": "...", "html_url": "https://github.com/..." }
      val tagName = json.optString("tag_name", "").trim()
      if (tagName.isNotBlank()) {
        val cleanedVersion = stripLeadingV(tagName)
        val buildCodeMatch = BUILD_CODE_IN_TAG_REGEX.find(cleanedVersion)
        val parsedCode = buildCodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val cleanVersionName = cleanedVersion.replace(BUILD_CODE_IN_TAG_REGEX, "").trim()
        val notes = json.optString("body", "").trim()
          .ifBlank { json.optString("name", "").trim() }
        val htmlUrl = sanitizeExternalUrl(json.optString("html_url", "").trim())
        val published = json.optString("published_at", "").trim()

        return AppReleaseInfo(
          versionName = cleanVersionName.ifBlank { cleanedVersion },
          versionCode = parsedCode,
          releaseNotes = notes,
          downloadUrl = htmlUrl,
          publishedAt = published
        )
      }

      null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Returns true if the remote release is strictly newer than the currently installed version.
   */
  fun isNewerVersion(
    currentVersionCode: Int,
    currentVersionName: String,
    release: AppReleaseInfo
  ): Boolean {
    if (release.versionCode > 0 && currentVersionCode > 0) {
      if (release.versionCode > currentVersionCode) return true
      if (release.versionCode < currentVersionCode) return false
    }
    return compareVersionNames(release.versionName, currentVersionName) > 0
  }

  fun compareVersionNames(remoteVersion: String, localVersion: String): Int {
    val remoteParts = extractNumericVersionParts(remoteVersion)
    val localParts = extractNumericVersionParts(localVersion)
    val maxLen = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until maxLen) {
      val r = remoteParts.getOrElse(i) { 0 }
      val l = localParts.getOrElse(i) { 0 }
      if (r != l) return r.compareTo(l)
    }
    return 0
  }

  private fun extractNumericVersionParts(version: String): List<Int> {
    val cleaned = stripLeadingV(version)
      .substringBefore("-")
      .substringBefore("+")
    if (cleaned.isBlank()) return emptyList()
    return cleaned.split(".")
      .mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
  }

  private fun stripLeadingV(raw: String): String {
    val t = raw.trim()
    return if ((t.startsWith("v", ignoreCase = true)) && t.length > 1 && t[1].isDigit()) {
      t.substring(1)
    } else {
      t
    }
  }

  fun sanitizeExternalUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase(Locale.US)
    return if (lower.startsWith("https://") || lower.startsWith("market://")) trimmed else ""
  }

  fun buildSafeExternalUpdateIntent(url: String): Intent? {
    val safeUrl = sanitizeExternalUrl(url)
    if (safeUrl.isBlank()) return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  suspend fun checkForUpdate(
    rawSourceUrl: String,
    currentVersionCode: Int,
    currentVersionName: String
  ): AppUpdateCheckState = withContext(Dispatchers.IO) {
    val endpoint = normalizeUpdateEndpoint(rawSourceUrl)
      ?: return@withContext AppUpdateCheckState.NeedsSourceUrl(
        "أضف رابط مستودع GitHub أو رابط ملف التحديثات (HTTPS JSON) أولاً للتحقق من وجود إصدار أحدث."
      )

    val checkedAt = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US).format(Date())

    try {
      val request = Request.Builder()
        .url(endpoint)
        .header("Accept", "application/vnd.github+json, application/json")
        .header("User-Agent", "MasarDeliveryApp/$currentVersionName")
        .get()
        .build()

      httpClient.newCall(request).execute().use { response ->
        if (response.code == 404) {
          return@withContext AppUpdateCheckState.Error(
            "لم يتم العثور على إصدار منشور في هذا الرابط بعد (404). تأكد من نشر إصدار (Release) أو صحة الرابط."
          )
        }
        if (!response.isSuccessful) {
          return@withContext AppUpdateCheckState.Error(
            "تعذر الاتصال بخادم التحديثات حالياً (رمز الاستجابة: ${response.code})."
          )
        }
        val body = response.body?.string().orEmpty()
        val release = parseReleaseJson(body)
          ?: return@withContext AppUpdateCheckState.Error(
            "تعذر قراءة بيانات الإصدار من الرابط. تأكد أنه رابط GitHub Releases أو ملف JSON صحيح."
          )

        if (isNewerVersion(currentVersionCode, currentVersionName, release)) {
          AppUpdateCheckState.UpdateAvailable(
            currentVersionName = currentVersionName,
            release = release,
            checkedAt = checkedAt
          )
        } else {
          AppUpdateCheckState.UpToDate(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            checkedAt = checkedAt
          )
        }
      }
    } catch (_: IOException) {
      AppUpdateCheckState.Error(
        "تعذر التحقق من التحديثات. تأكد من اتصال الإنترنت وحاول مرة أخرى."
      )
    } catch (e: Exception) {
      AppUpdateCheckState.Error(
        "حدث خطأ غير متوقع أثناء فحص التحديثات: ${e.message ?: "خطأ غير معروف"}"
      )
    }
  }
}
