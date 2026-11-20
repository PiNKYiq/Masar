package com.example.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.model.CustomerRecord
import com.example.model.DeliveryStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TrashBackupHelper {

  private val TELEGRAM_PACKAGES = listOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram",
    "org.telegram.plus"
  )

  fun sanitizeOrderFileName(stop: DeliveryStop, usedNames: MutableSet<String>): String {
    val rawName = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
      .ifBlank { stop.id }
    val sanitized = rawName
      .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
      .trim('.', ' ')
      .ifBlank { "order_${stop.id}" }

    var candidate = sanitized
    var suffix = 2
    while (!usedNames.add(candidate)) {
      candidate = "${sanitized}_$suffix"
      suffix++
    }
    return "$candidate.zip"
  }

  fun buildOrderInfoText(stop: DeliveryStop): String {
    val orderNum = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim().ifBlank { "غير محدد" }
    val cleanPrice = OcrHelper.cleanPriceNumber(stop.price).ifBlank { "غير محدد" }
    val coords = CustomerRecord.parseCoordinatesFromUrlOrText(stop.locationCoordinates)
    val mapsUrl = if (coords != null) CustomerRecord.buildGoogleMapsUrl(coords.first, coords.second) else ""
    val statusName = stop.categoryName.ifBlank { stop.status.labelAr }

    return buildString {
      appendLine("===============================")
      appendLine("نسخة احتياطية للطلب رقم: $orderNum")
      appendLine("===============================")
      appendLine("رقم الطلب: $orderNum")
      appendLine("الحالة / القائمة: $statusName")
      appendLine("السعر: $cleanPrice")
      appendLine("التاريخ والوقت: ${stop.dateTime.ifBlank { "غير محدد" }}")
      appendLine("العنوان: ${stop.title.ifBlank { "غير محدد" }}")
      if (stop.subtitle.isNotBlank()) appendLine("تفاصيل العنوان: ${stop.subtitle}")
      if (stop.governorate.isNotBlank()) appendLine("المحافظة: ${stop.governorate}")
      if (stop.region.isNotBlank()) appendLine("المنطقة: ${stop.region}")
      appendLine("رقم الهاتف 1: ${stop.phone1.ifBlank { "غير مسجل" }}")
      if (stop.phone2.isNotBlank()) appendLine("رقم الهاتف 2: ${stop.phone2}")
      if (!stop.locationCoordinates.isNullOrBlank()) {
        appendLine("الإحداثيات: ${stop.locationCoordinates}")
      }
      if (mapsUrl.isNotBlank()) {
        appendLine("رابط الموقع على الخريطة: $mapsUrl")
      }
      if (stop.note.isNotBlank()) appendLine("ملاحظة الحالة: ${stop.note}")
      if (stop.issueNotes.isNotBlank()) appendLine("ملاحظات إضافية: ${stop.issueNotes}")
    }
  }

  fun buildOrderJson(stop: DeliveryStop): String {
    return JSONObject().apply {
      put("id", stop.id)
      put("orderNumber", stop.orderNumber)
      put("orderCodeNumber", stop.orderCodeNumber)
      put("dateTime", stop.dateTime)
      put("title", stop.title)
      put("subtitle", stop.subtitle)
      put("governorate", stop.governorate)
      put("region", stop.region)
      put("phone1", stop.phone1)
      put("phone2", stop.phone2)
      put("price", OcrHelper.cleanPriceNumber(stop.price))
      put("status", stop.status.name)
      put("statusLabelAr", stop.status.labelAr)
      put("categoryId", stop.categoryId)
      put("categoryName", stop.categoryName)
      put("note", stop.note)
      put("issueNotes", stop.issueNotes)
      put("locationCoordinates", stop.locationCoordinates)
      put("locationStatus", stop.locationStatus)
    }.toString(2)
  }

  fun createOrderBackupZip(
    context: Context,
    stop: DeliveryStop,
    outputDir: File,
    usedNames: MutableSet<String>
  ): File {
    if (!outputDir.exists()) outputDir.mkdirs()
    val fileName = sanitizeOrderFileName(stop, usedNames)
    val zipFile = File(outputDir, fileName)

    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      // 1. Order Arabic readable text info
      zos.putNextEntry(ZipEntry("order_info.txt"))
      zos.write(buildOrderInfoText(stop).toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // 2. Order structured JSON data
      zos.putNextEntry(ZipEntry("order_data.json"))
      zos.write(buildOrderJson(stop).toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // 3. All attached images for this order
      val imageSources = listOf(
        "screenshot" to stop.screenshotUriString,
        "contents_photo" to stop.capturedContentsPhotoUri,
        "external_photo" to stop.capturedExternalPhotoUri,
        "issue_photo" to stop.capturedIssuePhotoUri
      )

      imageSources.forEach { (baseEntryName, uriString) ->
        if (!uriString.isNullOrBlank()) {
          addImageToZip(context, zos, baseEntryName, uriString)
        }
      }
    }

    return zipFile
  }

  private fun addImageToZip(
    context: Context,
    zos: ZipOutputStream,
    baseEntryName: String,
    uriString: String
  ) {
    val inputStream = openUriInputStream(context, uriString) ?: return
    val ext = guessExtension(context, uriString)
    val entryName = "${baseEntryName}.$ext"
    try {
      inputStream.use { input ->
        zos.putNextEntry(ZipEntry(entryName))
        input.copyTo(zos, bufferSize = 8192)
        zos.closeEntry()
      }
    } catch (_: Exception) {
      // Skip unreadable or missing image without failing the rest of the backup
    }
  }

  private fun openUriInputStream(context: Context, uriString: String): InputStream? {
    return try {
      val parsed = Uri.parse(uriString)
      when (parsed.scheme) {
        null, "" -> {
          val file = File(uriString)
          if (file.exists()) FileInputStream(file) else null
        }
        "file" -> {
          val path = parsed.path ?: return null
          val file = File(path)
          if (file.exists()) FileInputStream(file) else null
        }
        else -> context.contentResolver.openInputStream(parsed)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun guessExtension(context: Context, uriString: String): String {
    return try {
      val uri = Uri.parse(uriString)
      val mime = if (uri.scheme == "content") context.contentResolver.getType(uri) else null
      when {
        mime?.contains("png", ignoreCase = true) == true -> "png"
        mime?.contains("webp", ignoreCase = true) == true -> "webp"
        uriString.endsWith(".png", ignoreCase = true) -> "png"
        uriString.endsWith(".webp", ignoreCase = true) -> "webp"
        else -> "jpg"
      }
    } catch (_: Exception) {
      "jpg"
    }
  }

  suspend fun createDeletedOrdersBackupZips(
    context: Context,
    deletedStops: List<DeliveryStop>
  ): List<File> = withContext(Dispatchers.IO) {
    val backupsDir = File(context.cacheDir, "backups")
    if (backupsDir.exists()) {
      backupsDir.listFiles()?.forEach { it.delete() }
    } else {
      backupsDir.mkdirs()
    }

    val usedNames = mutableSetOf<String>()
    deletedStops.map { stop ->
      createOrderBackupZip(context, stop, backupsDir, usedNames)
    }
  }

  fun findInstalledTelegramPackage(context: Context): String? {
    val pm = context.packageManager
    for (pkg in TELEGRAM_PACKAGES) {
      val installed = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION")
          pm.getPackageInfo(pkg, 0)
        }
        true
      } catch (_: Exception) {
        false
      }
      if (installed) return pkg
    }
    return null
  }

  fun buildTelegramShareIntent(
    context: Context,
    zipFiles: List<File>,
    fileProviderAuthority: String = "${context.packageName}.fileprovider"
  ): Intent? {
    if (zipFiles.isEmpty()) return null

    val uris = ArrayList<Uri>(zipFiles.size)
    zipFiles.forEach { file ->
      val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
      uris.add(uri)
    }

    val baseIntent = if (uris.size == 1) {
      Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uris[0])
      }
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/zip"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
      }
    }

    baseIntent.apply {
      putExtra(Intent.EXTRA_SUBJECT, "نسخة احتياطية للطلبات المحذوفة")
      putExtra(
        Intent.EXTRA_TEXT,
        "نسخة احتياطية (${uris.size} طلب محذوف) — كل طلب بصيغة ZIP برقم الطلب مع المعلومات والصور"
      )
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      // Attach ClipData so Android grants URI read access for every item in ACTION_SEND_MULTIPLE
      val clip = ClipData.newRawUri("backup_zips", uris[0])
      for (i in 1 until uris.size) {
        clip.addItem(ClipData.Item(uris[i]))
      }
      clipData = clip
    }

    val telegramPkg = findInstalledTelegramPackage(context)
    return if (telegramPkg != null) {
      baseIntent.setPackage(telegramPkg)
      baseIntent
    } else {
      Intent.createChooser(baseIntent, "إرسال النسخة الاحتياطية إلى تليجرام")
    }
  }
}
