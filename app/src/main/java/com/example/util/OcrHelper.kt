package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

data class OcrResult(
  val orderNumber: String?,
  val phone1: String?,
  val phone2: String?,
  val price: String? = null,
  val governorate: String? = null, // Customer address is strictly manual entry only!
  val region: String? = null,
  val rawText: String,
  val isPriceFromGreen: Boolean = false,
  val isPriceFromCurrency: Boolean = false,
  val isPriceFromLabelAndCurrency: Boolean = false,
  val isPriceFromTargetRegion: Boolean = false,
  val detectedCurrencySymbol: String? = null,
  val detectedPriceLabel: String? = null
)

data class CurrencyPriceMatch(
  val cleanPrice: String,
  val symbol: String,
  val score: Int,
  val lineIndex: Int,
  val matchedLabel: String? = null
)

object OcrHelper {

  private const val TAG = "OcrHelper"

  // Currency symbols & codes (ordered longest-first inside alternation)
  private val CURRENCY_TOKEN_REGEX_PATTERN = listOf(
    "دينار\\s*عراقي",
    "دينار\\s*أردني",
    "دينار\\s*كويتي",
    "ريال\\s*سعودي",
    "درهم\\s*إماراتي",
    "دنانير",
    "دينار",
    "ريال",
    "درهم",
    "جنيه",
    "ليرة",
    "د\\.\\s*ع\\.?",
    "ع\\.\\s*د\\.?",
    "د\\s*/\\s*ع",
    "د\\s+ع",
    "ر\\.\\s*س\\.?",
    "د\\.\\s*إ\\.?",
    "د\\.\\s*أ\\.?",
    "د\\.\\s*ك\\.?",
    "ر\\.\\s*ق\\.?",
    "د\\.\\s*ب\\.?",
    "ر\\.\\s*ع\\.?",
    "ج\\.\\s*م\\.?",
    "ل\\.\\s*س\\.?",
    "ل\\.\\s*ل\\.?",
    "I\\.Q\\.D\\.?",
    "(?<![A-Za-z])(?:IQD|USD|EUR|GBP|SAR|AED|JOD|KWD|QAR|BHD|OMR|EGP|SYP|LBP|TRY|TL)(?![A-Za-z])",
    "US\\$",
    "\\$",
    "€",
    "£",
    "₺",
    "﷼",
    "¥",
    "₹",
    "₽",
    "฿"
  ).joinToString("|")

  private val CURRENCY_CONTAINS_REGEX = Regex("(?i)(?:$CURRENCY_TOKEN_REGEX_PATTERN)")
  private val NUMBER_AFTER_CURRENCY_REGEX = Regex(
    "(?i)($CURRENCY_TOKEN_REGEX_PATTERN)\\s*(?:[:=/(،]\\s*|[-−–—]\\s+)?((?:[-−–—]\\s*)?[0-9]+(?:[.,\\s][0-9]{3})*(?:[.,][0-9]{1,2})?(?:\\s*[-−–—](?!\\d))?)"
  )
  private val CURRENCY_AFTER_NUMBER_REGEX = Regex(
    "(?i)(?<![0-9.,])((?:(?<=^|[:=\\s(،\\n])[-−–—]\\s*|[-−–—](?=[0-9]))?[0-9]+(?:[.,\\s][0-9]{3})*(?:[.,][0-9]{1,2})?(?:\\s*[-−–—](?!\\d))?)\\s*(?:[:/(،]?\\s*)($CURRENCY_TOKEN_REGEX_PATTERN)"
  )

  /**
   * Lower-left screen/image region where the total order price ("السعر الكلي (مع التوصيل)") always appears.
   * Covers x: 0%..64% (left column, excluding right-column quantity) and y: 42%..99%.
   */
  fun getLowerLeftPriceRegionRect(width: Int, height: Int): Rect {
    if (width <= 2 || height <= 2) {
      return Rect(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
    val left = 0
    val top = (height * 0.42f).toInt().coerceIn(0, height - 2)
    val right = (width * 0.64f).toInt().coerceIn(2, width)
    val bottom = (height * 0.99f).toInt().coerceIn(top + 2, height)
    return Rect(left, top, right, bottom)
  }

  /**
   * Crops and upscales the lower-left price region so ML Kit recognizes digits like "90,000", "0", and "-15,000" cleanly.
   */
  fun createUpscaledLowerLeftCrop(bitmap: Bitmap): Bitmap? {
    if (bitmap.isRecycled || bitmap.width < 10 || bitmap.height < 10) return null
    return try {
      val rect = getLowerLeftPriceRegionRect(bitmap.width, bitmap.height)
      val cropW = rect.width().coerceAtLeast(1)
      val cropH = rect.height().coerceAtLeast(1)
      val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, cropW, cropH)
      val scaleFactor = if (cropW < 900) 2.5f else 2.0f
      val targetW = (cropW * scaleFactor).toInt().coerceAtMost(1800).coerceAtLeast(cropW)
      val targetH = (cropH * scaleFactor).toInt().coerceAtMost(2600).coerceAtLeast(cropH)
      if (targetW > cropW || targetH > cropH) {
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (scaled != cropped) {
          cropped.recycle()
        }
        scaled
      } else {
        cropped
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to crop lower-left region", e)
      null
    }
  }

  /**
   * Crops and upscales the top/upper region where order numbers and customer phones typically appear.
   */
  fun createUpscaledUpperHeaderCrop(bitmap: Bitmap): Bitmap? {
    if (bitmap.isRecycled || bitmap.width < 10 || bitmap.height < 10) return null
    return try {
      val cropW = bitmap.width
      val cropH = (bitmap.height * 0.56f).toInt().coerceIn(2, bitmap.height)
      val cropped = Bitmap.createBitmap(bitmap, 0, 0, cropW, cropH)
      val targetW = (cropW * 1.6f).toInt().coerceAtMost(1920).coerceAtLeast(cropW)
      val targetH = (cropH * 1.6f).toInt().coerceAtMost(2100).coerceAtLeast(cropH)
      if (targetW > cropW || targetH > cropH) {
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (scaled != cropped) {
          cropped.recycle()
        }
        scaled
      } else {
        cropped
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to crop upper header region", e)
      null
    }
  }

  /**
   * Produces a high-contrast grayscale (and auto-inverted for dark-mode screenshots) bitmap
   * so thin minus signs ("-"), commas, and digits stand out clearly for ML Kit Text Recognition.
   */
  fun createHighContrastOcrBitmap(bitmap: Bitmap): Bitmap? {
    if (bitmap.isRecycled || bitmap.width < 10 || bitmap.height < 10) return null
    return try {
      val sampleStepX = maxOf(1, bitmap.width / 24)
      val sampleStepY = maxOf(1, bitmap.height / 24)
      var totalLum = 0L
      var samples = 0
      var y = 0
      while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
          val p = bitmap.getPixel(x, y)
          val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
          totalLum += lum
          samples++
          x += sampleStepX
        }
        y += sampleStepY
      }
      val avgLum = if (samples > 0) totalLum / samples else 128L
      val isDarkMode = avgLum < 115L

      val w = bitmap.width
      val h = bitmap.height
      val contrast = if (isDarkMode) 1.55f else 1.35f
      val slope = if (isDarkMode) -contrast else contrast
      val offset = if (isDarkMode) 255f + (128f * (contrast - 1f)) else 128f * (1f - contrast)
      val lut = IntArray(256) { lum ->
        (slope * lum + offset).toInt().coerceIn(0, 255)
      }

      val pixels = IntArray(w * h)
      bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
      for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val lum = ((r * 213 + g * 715 + b * 72) / 1000).coerceIn(0, 255)
        val v = lut[lum]
        pixels[i] = (p and 0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
      }
      val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      out.setPixels(pixels, 0, w, 0, 0, w, h)
      out
    } catch (e: Exception) {
      Log.w(TAG, "Failed to create high-contrast OCR bitmap", e)
      null
    }
  }

  suspend fun extractFromImage(context: Context, uri: Uri): OcrResult = withContext(Dispatchers.Default) {
    var decodedBitmap: Bitmap? = null
    var contrastBitmap: Bitmap? = null
    var roiCropBitmap: Bitmap? = null
    var roiContrastBitmap: Bitmap? = null
    var headerContrastBitmap: Bitmap? = null

    fun recycleBitmaps() {
      listOf(
        roiContrastBitmap,
        headerContrastBitmap,
        roiCropBitmap,
        contrastBitmap,
        decodedBitmap
      ).forEach { bmp ->
        if (bmp != null && !bmp.isRecycled) {
          bmp.recycle()
        }
      }
    }

    try {
      decodedBitmap = decodeSoftwareBitmapFromUri(context, uri)
      val inputImage: InputImage? = if (decodedBitmap != null) {
        InputImage.fromBitmap(decodedBitmap, 0)
      } else {
        runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
      }

      if (inputImage == null) {
        return@withContext fallbackOcrData(null, null, null)
      }

      val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

      suspend fun runRecognizerSafe(bmp: Bitmap?): Text? {
        if (bmp == null || bmp.isRecycled) return null
        return runCatching {
          Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
        }.getOrNull()
      }

      val fullVision = runCatching { Tasks.await(recognizer.process(inputImage)) }.getOrNull()
      contrastBitmap = decodedBitmap?.let { createHighContrastOcrBitmap(it) }
      val contrastVision = runRecognizerSafe(contrastBitmap)

      roiCropBitmap = decodedBitmap?.let { createUpscaledLowerLeftCrop(it) }
      val roiVision = runRecognizerSafe(roiCropBitmap)

      roiContrastBitmap = roiCropBitmap?.let { createHighContrastOcrBitmap(it) }
      val roiContrastVision = runRecognizerSafe(roiContrastBitmap)

      headerContrastBitmap = decodedBitmap?.let { createUpscaledUpperHeaderCrop(it) }?.let { rawHeader ->
        val enhanced = createHighContrastOcrBitmap(rawHeader)
        if (enhanced != null && enhanced != rawHeader) {
          rawHeader.recycle()
          enhanced
        } else {
          rawHeader
        }
      }
      val headerVision = runRecognizerSafe(headerContrastBitmap)

      val extraScreenChunks = listOfNotNull(
        contrastVision?.let { buildSpatialRowsText(it).ifBlank { it.text } },
        contrastVision?.text,
        headerVision?.let { buildSpatialRowsText(it).ifBlank { it.text } },
        headerVision?.text
      ).filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }

      val extraRoiChunks = listOfNotNull(
        roiContrastVision?.let { buildSpatialRowsText(it).ifBlank { it.text } },
        roiContrastVision?.text
      ).filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }

      val primaryVision = fullVision ?: contrastVision ?: headerVision
      when {
        primaryVision != null -> {
          analyzeVisionTextWithRegion(
            visionText = primaryVision,
            bitmap = decodedBitmap,
            roiVisionText = roiVision ?: roiContrastVision,
            roiBitmap = roiCropBitmap,
            extraScreenText = extraScreenChunks,
            extraRoiText = extraRoiChunks
          )
        }
        roiVision != null || roiContrastVision != null -> {
          val activeRoi = roiVision ?: roiContrastVision!!
          val combinedRoiText = listOfNotNull(
            activeRoi.text.takeIf { it.isNotBlank() },
            extraRoiChunks
          ).joinToString("\n")
          val fallback = parseOcrText(combinedRoiText)
          val regionPrice = extractPriceFromLowerLeftCandidates(
            visionText = null,
            bitmap = null,
            roiVisionText = activeRoi,
            roiBitmap = roiCropBitmap,
            extraRoiText = extraRoiChunks,
            excludedPhoneDigits = emptySet(),
            excludedOrderDigits = fallback.orderNumber?.replace(Regex("[^0-9]"), "")
          )
          if (regionPrice != null) {
            fallback.copy(price = regionPrice, isPriceFromTargetRegion = true)
          } else {
            fallback
          }
        }
        else -> fallbackOcrData(null, null, null)
      }
    } catch (e: Exception) {
      Log.e(TAG, "extractFromImage failed", e)
      fallbackOcrData(null, null, null)
    } finally {
      recycleBitmaps()
    }
  }

  private fun decodeSoftwareBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
      val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, boundsOpts)
      }

      // Preserve full native screenshot resolution (up to 3840px) so thin minus signs, commas, and small digits remain crisp
      var sampleSize = 1
      val maxDim = 3840
      while (boundsOpts.outWidth / sampleSize > maxDim || boundsOpts.outHeight / sampleSize > maxDim) {
        sampleSize *= 2
      }

      val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOpts)
      } ?: return null

      val rotationDegrees = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          val exif = ExifInterface(stream)
          when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
          }
        } ?: 0f
      } catch (_: Exception) {
        0f
      }

      if (rotationDegrees != 0f) {
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated != rawBitmap) {
          rawBitmap.recycle()
        }
        rotated
      } else {
        rawBitmap
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode software bitmap from uri", e)
      null
    }
  }

  /**
   * Analyzes ML Kit recognized text together with the captured bitmap.
   * Combines:
   * 1. Green-colored text detection (with currency-symbol scoring boost).
   * 2. Currency-symbol recognition (including spatially adjacent boxes on the same visual row).
   * 3. Keyword-based fallback (المجموع، السعر، المطلوب، ...).
   */
  fun analyzeVisionTextWithBitmap(
    visionText: Text,
    bitmap: Bitmap?,
    extraScreenText: String? = null
  ): OcrResult {
    return analyzeVisionTextWithRegion(
      visionText = visionText,
      bitmap = bitmap,
      roiVisionText = null,
      roiBitmap = null,
      extraScreenText = extraScreenText,
      extraRoiText = null
    )
  }

  /**
   * Analyzes ML Kit recognized text together with the full bitmap AND the dedicated
   * lower-left price region crop/accessibility nodes ("الجزء السفلي الأيسر").
   */
  fun analyzeVisionTextWithRegion(
    visionText: Text,
    bitmap: Bitmap?,
    roiVisionText: Text? = null,
    roiBitmap: Bitmap? = null,
    extraScreenText: String? = null,
    extraRoiText: String? = null
  ): OcrResult {
    val rawText = visionText.text
    val visualRowsText = buildSpatialRowsText(visionText)
    val roiRawText = roiVisionText?.text.orEmpty()
    val roiVisualRowsText = roiVisionText?.let { buildSpatialRowsText(it) }.orEmpty()

    val combinedForParsing = listOfNotNull(
      extraScreenText?.takeIf { it.isNotBlank() },
      visualRowsText.takeIf { it.isNotBlank() },
      rawText.takeIf { it.isNotBlank() },
      extraRoiText?.takeIf { it.isNotBlank() },
      roiVisualRowsText.takeIf { it.isNotBlank() },
      roiRawText.takeIf { it.isNotBlank() }
    ).joinToString("\n")

    val combinedRoiForParsing = listOfNotNull(
      extraRoiText?.takeIf { it.isNotBlank() },
      roiVisualRowsText.takeIf { it.isNotBlank() },
      roiRawText.takeIf { it.isNotBlank() }
    ).joinToString("\n")

    val baseResult = parseOcrText(combinedForParsing).copy(rawText = normalizeArabicNumerals(combinedForParsing))

    val excludedPhoneDigits = mutableSetOf<String>()
    baseResult.phone1?.let { excludedPhoneDigits.add(it.replace(Regex("[^0-9]"), "")) }
    baseResult.phone2?.let { excludedPhoneDigits.add(it.replace(Regex("[^0-9]"), "")) }
    extractAllPhoneNumbers(combinedForParsing).forEach { phone ->
      excludedPhoneDigits.add(phone.replace(Regex("[^0-9]"), ""))
    }
    val excludedOrderDigits = baseResult.orderNumber?.replace(Regex("[^0-9]"), "")

    val roiCurrencyMatch = if (combinedRoiForParsing.isNotBlank()) {
      extractPriceByCurrencySymbol(
        rawText = combinedRoiForParsing,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
    } else null

    val currencyMatch = roiCurrencyMatch ?: extractPriceByCurrencySymbol(
      rawText = combinedForParsing,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )

    // Extract green candidates from dedicated lower-left ROI crop first
    val roiElementCandidates = mutableListOf<Pair<String, Rect>>()
    val roiLineCandidates = mutableListOf<Pair<String, Rect>>()
    if (roiVisionText != null) {
      for (block in roiVisionText.textBlocks) {
        for (line in block.lines) {
          line.boundingBox?.let { roiLineCandidates.add(line.text to it) }
          for (element in line.elements) {
            element.boundingBox?.let { roiElementCandidates.add(element.text to it) }
          }
        }
      }
    }

    val roiGreenPrice = if (roiBitmap != null && !roiBitmap.isRecycled && roiVisionText != null) {
      val roiLineGreen = extractGreenColoredPriceFromBitmap(
        bitmap = roiBitmap,
        candidates = roiLineCandidates,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
      val roiElemGreen = extractGreenColoredPriceFromBitmap(
        bitmap = roiBitmap,
        candidates = roiElementCandidates,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
      if (roiLineGreen != null && roiCurrencyMatch != null && roiLineGreen == roiCurrencyMatch.cleanPrice) {
        roiLineGreen
      } else {
        roiElemGreen ?: roiLineGreen
      }
    } else null

    val roiRedPrice = if (roiBitmap != null && !roiBitmap.isRecycled && roiVisionText != null) {
      val roiLineRed = extractRedColoredPriceFromBitmap(
        bitmap = roiBitmap,
        candidates = roiLineCandidates,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
      val roiElemRed = extractRedColoredPriceFromBitmap(
        bitmap = roiBitmap,
        candidates = roiElementCandidates,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
      roiElemRed ?: roiLineRed
    } else null

    // Collect full-image candidates (and detect which ones sit inside lower-left region)
    val elementCandidates = mutableListOf<Pair<String, Rect>>()
    val lineCandidates = mutableListOf<Pair<String, Rect>>()
    for (block in visionText.textBlocks) {
      for (line in block.lines) {
        line.boundingBox?.let { rect -> lineCandidates.add(line.text to rect) }
        for (element in line.elements) {
          element.boundingBox?.let { rect -> elementCandidates.add(element.text to rect) }
        }
      }
    }

    val fullLowerLeftGreenPrice = if (bitmap != null && !bitmap.isRecycled) {
      val roiBounds = getLowerLeftPriceRegionRect(bitmap.width, bitmap.height)
      val lowerLeftLines = lineCandidates.filter { (_, r) ->
        r.centerX() <= roiBounds.right + (bitmap.width * 0.03f) && r.centerY() >= roiBounds.top
      }
      val lowerLeftElems = elementCandidates.filter { (_, r) ->
        r.centerX() <= roiBounds.right + (bitmap.width * 0.03f) && r.centerY() >= roiBounds.top
      }
      extractGreenColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = lowerLeftElems,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      ) ?: extractGreenColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = lowerLeftLines,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
    } else null

    val fullLowerLeftRedPrice = if (bitmap != null && !bitmap.isRecycled) {
      val roiBounds = getLowerLeftPriceRegionRect(bitmap.width, bitmap.height)
      val lowerLeftLines = lineCandidates.filter { (_, r) ->
        r.centerX() <= roiBounds.right + (bitmap.width * 0.03f) && r.centerY() >= roiBounds.top
      }
      val lowerLeftElems = elementCandidates.filter { (_, r) ->
        r.centerX() <= roiBounds.right + (bitmap.width * 0.03f) && r.centerY() >= roiBounds.top
      }
      extractRedColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = lowerLeftElems,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      ) ?: extractRedColoredPriceFromBitmap(
        bitmap = bitmap,
        candidates = lowerLeftLines,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      )
    } else null

    val targetRegionGreenPrice = roiGreenPrice ?: fullLowerLeftGreenPrice
    val targetRegionRedPrice = roiRedPrice ?: fullLowerLeftRedPrice
    val targetRegionColorPrice = targetRegionRedPrice ?: targetRegionGreenPrice

    // Spatial extraction inside the lower-left target region (handles non-green or non-Latin-currency Arabic screens)
    val targetRegionNumericPrice = extractPriceFromLowerLeftCandidates(
      visionText = visionText,
      bitmap = bitmap,
      roiVisionText = roiVisionText,
      roiBitmap = roiBitmap,
      extraRoiText = extraRoiText,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )

    // 1. Prioritize lower-left region whenever it yields an explicit label+currency, green/red price, currency price, or formatted/valid price
    if (roiCurrencyMatch != null && roiCurrencyMatch.matchedLabel != null) {
      val resolvedWithRed = if (
        targetRegionRedPrice != null &&
        roiCurrencyMatch.cleanPrice != "0" &&
        !roiCurrencyMatch.cleanPrice.startsWith("-") &&
        targetRegionRedPrice.removePrefix("-") == roiCurrencyMatch.cleanPrice
      ) {
        "-${roiCurrencyMatch.cleanPrice}"
      } else {
        roiCurrencyMatch.cleanPrice
      }
      val matchesGreen = targetRegionGreenPrice == resolvedWithRed
      return baseResult.copy(
        price = resolvedWithRed,
        isPriceFromGreen = matchesGreen,
        isPriceFromCurrency = true,
        isPriceFromLabelAndCurrency = true,
        isPriceFromTargetRegion = true,
        detectedCurrencySymbol = roiCurrencyMatch.symbol,
        detectedPriceLabel = roiCurrencyMatch.matchedLabel
      )
    }

    if (!targetRegionColorPrice.isNullOrBlank()) {
      val isFromGreen = targetRegionRedPrice == null && targetRegionGreenPrice != null
      val matchesCurr = currencyMatch != null &&
        currencyMatch.cleanPrice.removePrefix("-") == targetRegionColorPrice.removePrefix("-")
      return baseResult.copy(
        price = targetRegionColorPrice,
        isPriceFromGreen = isFromGreen,
        isPriceFromCurrency = matchesCurr,
        isPriceFromLabelAndCurrency = matchesCurr && currencyMatch?.matchedLabel != null,
        isPriceFromTargetRegion = true,
        detectedCurrencySymbol = if (matchesCurr) currencyMatch?.symbol else null,
        detectedPriceLabel = if (matchesCurr) currencyMatch?.matchedLabel else null
      )
    }

    if (roiCurrencyMatch != null) {
      return baseResult.copy(
        price = roiCurrencyMatch.cleanPrice,
        isPriceFromGreen = false,
        isPriceFromCurrency = true,
        isPriceFromLabelAndCurrency = roiCurrencyMatch.matchedLabel != null,
        isPriceFromTargetRegion = true,
        detectedCurrencySymbol = roiCurrencyMatch.symbol,
        detectedPriceLabel = roiCurrencyMatch.matchedLabel
      )
    }

    if (!targetRegionNumericPrice.isNullOrBlank()) {
      val matchesCurr = currencyMatch != null &&
        currencyMatch.cleanPrice.removePrefix("-") == targetRegionNumericPrice.removePrefix("-")
      return baseResult.copy(
        price = targetRegionNumericPrice,
        isPriceFromGreen = false,
        isPriceFromCurrency = matchesCurr,
        isPriceFromLabelAndCurrency = matchesCurr && currencyMatch?.matchedLabel != null,
        isPriceFromTargetRegion = true,
        detectedCurrencySymbol = if (matchesCurr) currencyMatch?.symbol else null,
        detectedPriceLabel = if (matchesCurr) currencyMatch?.matchedLabel else null
      )
    }

    if (bitmap == null || bitmap.isRecycled) {
      return if (currencyMatch != null) {
        baseResult.copy(
          price = currencyMatch.cleanPrice,
          isPriceFromGreen = false,
          isPriceFromCurrency = true,
          isPriceFromLabelAndCurrency = currencyMatch.matchedLabel != null,
          detectedCurrencySymbol = currencyMatch.symbol,
          detectedPriceLabel = currencyMatch.matchedLabel
        )
      } else {
        baseResult
      }
    }

    // Full-bitmap green & red color check
    val greenLinePrice = extractGreenColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = lineCandidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )

    val greenElementPrice = extractGreenColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = elementCandidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )

    val greenPrice = if (greenLinePrice != null && currencyMatch != null && greenLinePrice == currencyMatch.cleanPrice) {
      greenLinePrice
    } else {
      greenElementPrice ?: greenLinePrice
    }

    val redLinePrice = extractRedColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = lineCandidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )
    val redElementPrice = extractRedColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = elementCandidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits
    )
    val redPrice = redElementPrice ?: redLinePrice

    val greenDigitsLen = greenPrice?.replace(Regex("[^0-9]"), "")?.length ?: 0
    val greenIsZero = greenPrice == "0"
    val currencyDigitsLen = currencyMatch?.cleanPrice?.replace(Regex("[^0-9]"), "")?.length ?: 0
    val hasExplicitLabeledCurrency = currencyMatch?.matchedLabel != null

    return when {
      hasExplicitLabeledCurrency && (greenPrice.isNullOrBlank() || greenPrice == currencyMatch!!.cleanPrice) -> {
        val resolvedPrice = if (
          redPrice != null &&
          currencyMatch!!.cleanPrice != "0" &&
          !currencyMatch.cleanPrice.startsWith("-") &&
          redPrice.removePrefix("-") == currencyMatch.cleanPrice
        ) {
          "-${currencyMatch.cleanPrice}"
        } else {
          currencyMatch!!.cleanPrice
        }
        baseResult.copy(
          price = resolvedPrice,
          isPriceFromGreen = greenPrice == resolvedPrice,
          isPriceFromCurrency = true,
          isPriceFromLabelAndCurrency = true,
          detectedCurrencySymbol = currencyMatch.symbol,
          detectedPriceLabel = currencyMatch.matchedLabel
        )
      }
      hasExplicitLabeledCurrency && greenPrice != null && greenPrice != currencyMatch!!.cleanPrice -> {
        val greenMatchesAnyCurrency = lineCandidates.any { (txt, _) ->
          val cleaned = extractCleanNumericCandidate(txt, excludedPhoneDigits, excludedOrderDigits)
          cleaned == greenPrice && CURRENCY_CONTAINS_REGEX.containsMatchIn(normalizeArabicNumerals(txt))
        }
        if (greenMatchesAnyCurrency) {
          baseResult.copy(
            price = greenPrice,
            isPriceFromGreen = true,
            isPriceFromCurrency = true,
            isPriceFromLabelAndCurrency = false,
            detectedCurrencySymbol = null,
            detectedPriceLabel = null
          )
        } else {
          baseResult.copy(
            price = currencyMatch.cleanPrice,
            isPriceFromGreen = false,
            isPriceFromCurrency = true,
            isPriceFromLabelAndCurrency = true,
            detectedCurrencySymbol = currencyMatch.symbol,
            detectedPriceLabel = currencyMatch.matchedLabel
          )
        }
      }
      !redPrice.isNullOrBlank() && (greenPrice.isNullOrBlank() || redPrice.startsWith("-")) -> {
        val matchesCurrency = currencyMatch != null && currencyMatch.cleanPrice.removePrefix("-") == redPrice.removePrefix("-")
        baseResult.copy(
          price = redPrice,
          isPriceFromGreen = false,
          isPriceFromCurrency = matchesCurrency,
          isPriceFromLabelAndCurrency = matchesCurrency && currencyMatch?.matchedLabel != null,
          detectedCurrencySymbol = if (matchesCurrency) currencyMatch?.symbol else null,
          detectedPriceLabel = if (matchesCurrency) currencyMatch?.matchedLabel else null
        )
      }
      !greenPrice.isNullOrBlank() && (greenDigitsLen >= 3 || greenIsZero || currencyMatch == null || currencyDigitsLen < 3) -> {
        val matchesCurrency = currencyMatch != null && currencyMatch.cleanPrice == greenPrice
        baseResult.copy(
          price = greenPrice,
          isPriceFromGreen = true,
          isPriceFromCurrency = matchesCurrency,
          isPriceFromLabelAndCurrency = matchesCurrency && currencyMatch?.matchedLabel != null,
          detectedCurrencySymbol = if (matchesCurrency) currencyMatch?.symbol else null,
          detectedPriceLabel = if (matchesCurrency) currencyMatch?.matchedLabel else null
        )
      }
      currencyMatch != null -> {
        baseResult.copy(
          price = currencyMatch.cleanPrice,
          isPriceFromGreen = false,
          isPriceFromCurrency = true,
          isPriceFromLabelAndCurrency = currencyMatch.matchedLabel != null,
          detectedCurrencySymbol = currencyMatch.symbol,
          detectedPriceLabel = currencyMatch.matchedLabel
        )
      }
      else -> {
        baseResult.copy(isPriceFromGreen = false)
      }
    }
  }

  /**
   * Scans candidates strictly inside the lower-left marked area of the screen/screenshot
   * (where "السعر الكلي (مع التوصيل)" sits) and extracts the clean numeric price even when
   * Arabic currency symbols aren't recognized by Latin OCR.
   */
  fun extractPriceFromLowerLeftCandidates(
    visionText: Text?,
    bitmap: Bitmap?,
    roiVisionText: Text? = null,
    roiBitmap: Bitmap? = null,
    extraRoiText: String? = null,
    excludedPhoneDigits: Set<String> = emptySet(),
    excludedOrderDigits: String? = null
  ): String? {
    data class CandidateEntry(
      val cleanPrice: String,
      val score: Int,
      val yRatio: Float
    )

    val candidates = mutableListOf<CandidateEntry>()
    val labelRegex = Regex("(?i)(?:السعر\\s*الكلي|السعر|المبلغ|المجموع|الإجمالي|الاجمالي|المطلوب|total|price|amount)")
    val feeOrQtyRegex = Regex("(?i)(?:الكمية|كمية|التوصيل|خصم|حسم|quantity|qty|discount)")

    fun evaluateLineOrBox(
      raw: String,
      yRatio: Float,
      boxBitmap: Bitmap?,
      boxRect: Rect?,
      fromRoiCrop: Boolean,
      hasAdjacentLabelAbove: Boolean = false
    ) {
      val cleaned = extractCleanNumericCandidate(
        raw = raw,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      ) ?: return

      val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
      val hasSeparator = cleaned.contains(",") || cleaned.contains(".")
      val hasCurrency = CURRENCY_CONTAINS_REGEX.containsMatchIn(normalizeArabicNumerals(raw))
      val isGreenBox = if (boxBitmap != null && boxRect != null && !boxBitmap.isRecycled) {
        extractGreenColoredPriceFromBitmap(
          bitmap = boxBitmap,
          candidates = listOf(raw to boxRect),
          excludedPhoneDigits = excludedPhoneDigits,
          excludedOrderDigits = excludedOrderDigits
        ) != null
      } else false
      val isRedBox = if (boxBitmap != null && boxRect != null && !boxBitmap.isRecycled) {
        extractRedColoredPriceFromBitmap(
          bitmap = boxBitmap,
          candidates = listOf(raw to boxRect),
          excludedPhoneDigits = excludedPhoneDigits,
          excludedOrderDigits = excludedOrderDigits
        ) != null
      } else false

      val finalCleaned = if (isRedBox && cleaned != "0" && !cleaned.startsWith("-")) {
        "-$cleaned"
      } else {
        cleaned
      }
      val isZeroPrice = finalCleaned == "0" || digitsOnly.all { it == '0' }
      val hasNegativeSign = finalCleaned.startsWith("-")

      // Skip bare 1-2 digit counters (like quantity "1"), EXCEPT "0" or red/negative or currency/separator-tagged prices
      if (digitsOnly.length < 3 && !isZeroPrice && !hasNegativeSign && !hasCurrency && !hasSeparator && !isRedBox) return

      // Skip bare 4-digit years (1990..2099) without comma, currency, green/red color, or explicit label
      val numericInt = digitsOnly.toIntOrNull()
      val hasLabel = hasAdjacentLabelAbove || labelRegex.containsMatchIn(normalizeArabicNumerals(raw))
      if (!hasSeparator && !hasCurrency && !isGreenBox && !isRedBox && !hasLabel && !hasNegativeSign && numericInt != null && numericInt in 1990..2099) {
        return
      }

      var score = 100
      if (fromRoiCrop) score += 25
      if (hasLabel) score += 180
      if (hasCurrency) score += 150
      if (isGreenBox) score += 130
      if (isRedBox) score += 140
      if (hasNegativeSign) score += 95
      if (isZeroPrice) score += 65
      if (hasSeparator) score += 90
      if (digitsOnly.length in 4..7) score += 60 else if (digitsOnly.length == 3) score += 30
      if (digitsOnly.endsWith("000") || digitsOnly.endsWith("500") || digitsOnly.endsWith("00")) score += 25
      if (feeOrQtyRegex.containsMatchIn(normalizeArabicNumerals(raw)) && !hasLabel) score -= 120
      score += (yRatio.coerceIn(0f, 1f) * 55f).toInt()

      candidates.add(CandidateEntry(cleanPrice = finalCleaned, score = score, yRatio = yRatio))
    }

    fun isNearLabelAbove(boxRect: Rect?, allLines: List<Pair<String, Rect>>, refHeight: Float): Boolean {
      if (boxRect == null) return false
      val maxVertGap = (refHeight * 0.14f).coerceAtLeast(90f)
      return allLines.any { (txt, lRect) ->
        if (!labelRegex.containsMatchIn(normalizeArabicNumerals(txt))) return@any false
        val vertDist = boxRect.top - lRect.bottom
        val horizOverlap = kotlin.math.min(boxRect.right, lRect.right) >= kotlin.math.max(boxRect.left, lRect.left) - 120
        vertDist in -20..maxVertGap.toInt() && horizOverlap
      }
    }

    // 1. Evaluate upscaled lower-left ROI OCR lines & elements
    if (roiVisionText != null) {
      val roiH = roiBitmap?.height?.takeIf { it > 0 }?.toFloat() ?: 1000f
      val roiAllLines = roiVisionText.textBlocks.flatMap { b ->
        b.lines.mapNotNull { l -> l.boundingBox?.let { l.text to it } }
      }
      for (block in roiVisionText.textBlocks) {
        for (line in block.lines) {
          val rect = line.boundingBox
          val yRatio = if (rect != null) (rect.centerY() / roiH).coerceIn(0f, 1f) else 0.5f
          val nearLabel = isNearLabelAbove(rect, roiAllLines, roiH)
          evaluateLineOrBox(line.text, yRatio, roiBitmap, rect, fromRoiCrop = true, hasAdjacentLabelAbove = nearLabel)
          for (elem in line.elements) {
            val eRect = elem.boundingBox
            val eYRatio = if (eRect != null) (eRect.centerY() / roiH).coerceIn(0f, 1f) else yRatio
            evaluateLineOrBox(elem.text, eYRatio, roiBitmap, eRect, fromRoiCrop = true, hasAdjacentLabelAbove = nearLabel)
          }
        }
      }
    }

    // 2. Evaluate full-image boxes located inside the lower-left zone
    if (visionText != null && bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
      val roiRect = getLowerLeftPriceRegionRect(bitmap.width, bitmap.height)
      val roiHeightF = (bitmap.height - roiRect.top).coerceAtLeast(1).toFloat()
      val fullAllLines = visionText.textBlocks.flatMap { b ->
        b.lines.mapNotNull { l -> l.boundingBox?.let { l.text to it } }
      }
      for (block in visionText.textBlocks) {
        for (line in block.lines) {
          val rect = line.boundingBox ?: continue
          if (rect.centerX() <= roiRect.right + (bitmap.width * 0.03f) && rect.centerY() >= roiRect.top) {
            val yRatio = ((rect.centerY() - roiRect.top) / roiHeightF).coerceIn(0f, 1f)
            val nearLabel = isNearLabelAbove(rect, fullAllLines, roiHeightF)
            evaluateLineOrBox(line.text, yRatio, bitmap, rect, fromRoiCrop = false, hasAdjacentLabelAbove = nearLabel)
            for (elem in line.elements) {
              val eRect = elem.boundingBox ?: continue
              if (eRect.centerX() <= roiRect.right + (bitmap.width * 0.03f) && eRect.centerY() >= roiRect.top) {
                val eYRatio = ((eRect.centerY() - roiRect.top) / roiHeightF).coerceIn(0f, 1f)
                evaluateLineOrBox(elem.text, eYRatio, bitmap, eRect, fromRoiCrop = false, hasAdjacentLabelAbove = nearLabel)
              }
            }
          }
        }
      }
    }

    // 3. Evaluate Accessibility nodes & contrast ROI lines from the lower-left region
    if (!extraRoiText.isNullOrBlank()) {
      val roiLines = extraRoiText.split("\n", "\r").filter { it.isNotBlank() }
      roiLines.forEachIndexed { idx, line ->
        val yRatio = if (roiLines.size <= 1) 0.8f else idx.toFloat() / (roiLines.size - 1).coerceAtLeast(1)
        val prevIsLabel = idx > 0 && labelRegex.containsMatchIn(normalizeArabicNumerals(roiLines[idx - 1]))
        evaluateLineOrBox(line, yRatio, null, null, fromRoiCrop = true, hasAdjacentLabelAbove = prevIsLabel)
      }
    }

    return candidates.maxWithOrNull(
      compareBy<CandidateEntry> { it.score }
        .thenBy { it.yRatio }
        .thenBy { it.cleanPrice.replace(Regex("[^0-9]"), "").length }
    )?.cleanPrice
  }

  /**
   * Reconstructs horizontal visual rows from ML Kit lines so side-by-side elements
   * like "السعر الكلي", "-", "25,000", and "IQD" on the same row are joined cleanly.
   */
  private fun buildSpatialRowsText(visionText: Text): String {
    val boxes = mutableListOf<Pair<String, Rect>>()
    for (block in visionText.textBlocks) {
      for (line in block.lines) {
        val rect = line.boundingBox
        val txt = line.text.trim()
        if (rect != null && txt.isNotEmpty()) {
          boxes.add(txt to rect)
        }
      }
    }
    if (boxes.size <= 1) return ""

    val sortedByY = boxes.sortedBy { it.second.centerY() }
    val rows = mutableListOf<MutableList<Pair<String, Rect>>>()
    for (item in sortedByY) {
      val cy = item.second.centerY()
      val height = item.second.height().coerceAtLeast(16)
      val existingRow = rows.lastOrNull()?.let { row ->
        val rowCy = row.map { it.second.centerY() }.average()
        val rowH = row.map { it.second.height().coerceAtLeast(16) }.average()
        val tolerance = maxOf(14.0, maxOf(height.toDouble(), rowH) * 0.70)
        if (kotlin.math.abs(cy - rowCy) <= tolerance) row else null
      }
      if (existingRow != null) {
        existingRow.add(item)
      } else {
        rows.add(mutableListOf(item))
      }
    }

    return rows.joinToString("\n") { row ->
      row.sortedBy { it.second.left }
        .joinToString(" ") { it.first }
        // Tighten isolated minus tokens like "- 15,000" or "15,000 -" produced by split ML Kit boxes
        .replace(Regex("(^|\\s)([-−–—])\\s+(?=[0-9٠-٩۰-۹])"), "$1$2")
        .replace(Regex("([0-9٠-٩۰-۹][0-9٠-٩۰-۹.,]*)\\s+([-−–—])(?=\\s|$)"), "-$1")
    }
  }

  /**
   * Checks whether a pixel color falls within the Green Color Range using HSV & RGB channel checks.
   * Covers vivid green text, dark emerald receipts, and soft green highlight badges.
   */
  fun isGreenColor(pixel: Int): Boolean {
    val alpha = Color.alpha(pixel)
    if (alpha < 80) return false

    val r = Color.red(pixel)
    val g = Color.green(pixel)
    val b = Color.blue(pixel)

    if (g < 45) return false
    if (g < r + 10 || g < b + 8) return false

    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hue = hsv[0]
    val saturation = hsv[1]
    val value = hsv[2]

    return hue in 68f..172f && saturation >= 0.12f && value >= 0.16f
  }

  /**
   * Checks whether a pixel color falls within the Red Color Range using HSV & RGB channel checks.
   * Covers negative / refund prices rendered in red (amount given back to the customer).
   */
  fun isRedColor(pixel: Int): Boolean {
    val alpha = Color.alpha(pixel)
    if (alpha < 75) return false

    val r = Color.red(pixel)
    val g = Color.green(pixel)
    val b = Color.blue(pixel)

    if (r < 85) return false
    if (r < g + 28 || r < b + 28) return false

    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hue = hsv[0]
    val saturation = hsv[1]
    val value = hsv[2]

    return (hue <= 24f || hue >= 332f) && saturation >= 0.20f && value >= 0.22f
  }

  /**
   * Inspects pixel colors inside each text candidate bounding box on the captured Bitmap.
   * Exclusively extracts the clean numeric price if any numeric candidate matches the Green Color Range.
   */
  fun extractGreenColoredPriceFromBitmap(
    bitmap: Bitmap,
    candidates: List<Pair<String, Rect>>,
    excludedPhoneDigits: Set<String> = emptySet(),
    excludedOrderDigits: String? = null
  ): String? {
    return extractColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = candidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits,
      forceNegative = false,
      colorPredicate = ::isGreenColor
    )
  }

  /**
   * Extracts negative/refund prices rendered in red color (e.g. "-15,000" or red "15,000").
   * Guarantees a leading "-" sign for non-zero red price values.
   */
  fun extractRedColoredPriceFromBitmap(
    bitmap: Bitmap,
    candidates: List<Pair<String, Rect>>,
    excludedPhoneDigits: Set<String> = emptySet(),
    excludedOrderDigits: String? = null
  ): String? {
    return extractColoredPriceFromBitmap(
      bitmap = bitmap,
      candidates = candidates,
      excludedPhoneDigits = excludedPhoneDigits,
      excludedOrderDigits = excludedOrderDigits,
      forceNegative = true,
      colorPredicate = ::isRedColor
    )
  }

  private fun extractColoredPriceFromBitmap(
    bitmap: Bitmap,
    candidates: List<Pair<String, Rect>>,
    excludedPhoneDigits: Set<String>,
    excludedOrderDigits: String?,
    forceNegative: Boolean,
    colorPredicate: (Int) -> Boolean
  ): String? {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

    var bestPrice: String? = null
    var bestScore = -1f

    for ((rawText, box) in candidates) {
      val numericCandidate = extractCleanNumericCandidate(
        raw = rawText,
        excludedPhoneDigits = excludedPhoneDigits,
        excludedOrderDigits = excludedOrderDigits
      ) ?: continue

      val left = box.left.coerceIn(0, bitmap.width - 1)
      val top = box.top.coerceIn(0, bitmap.height - 1)
      val right = box.right.coerceIn(left + 1, bitmap.width)
      val bottom = box.bottom.coerceIn(top + 1, bitmap.height)

      val boxWidth = right - left
      val boxHeight = bottom - top
      if (boxWidth <= 0 || boxHeight <= 0) continue

      val stepX = maxOf(1, boxWidth / 80)
      val stepY = maxOf(1, boxHeight / 40)

      var sampledCount = 0
      var matchedColorCount = 0

      var y = top
      while (y < bottom) {
        var x = left
        while (x < right) {
          sampledCount++
          if (colorPredicate(bitmap.getPixel(x, y))) {
            matchedColorCount++
          }
          x += stepX
        }
        y += stepY
      }

      if (sampledCount > 0 && matchedColorCount >= 3) {
        val colorRatio = matchedColorCount.toFloat() / sampledCount.toFloat()
        if (colorRatio >= 0.025f) {
          val resolvedCandidate = if (forceNegative && numericCandidate != "0" && !numericCandidate.startsWith("-")) {
            "-$numericCandidate"
          } else {
            numericCandidate
          }
          val digitCount = resolvedCandidate.replace(Regex("[^0-9]"), "").length
          val isZero = resolvedCandidate == "0"
          val lengthBonus = if (digitCount >= 3 || isZero) 0.15f else 0f
          val currencyBonus = if (CURRENCY_CONTAINS_REGEX.containsMatchIn(normalizeArabicNumerals(rawText))) 0.50f else 0f
          val minusBonus = if (resolvedCandidate.startsWith("-")) 0.25f else 0f
          val score = colorRatio + lengthBonus + currencyBonus + minusBonus
          if (score > bestScore) {
            bestScore = score
            bestPrice = resolvedCandidate
          }
        }
      }
    }

    return bestPrice
  }

  /**
   * Extracts a clean numeric price from a text snippet, rejecting phone numbers, dates/times, and order numbers,
   * while supporting zero ("0") and negative ("-") refund values.
   */
  private fun extractCleanNumericCandidate(
    raw: String,
    excludedPhoneDigits: Set<String>,
    excludedOrderDigits: String?
  ): String? {
    val normalized = normalizeArabicNumerals(raw)
      .replace('−', '-')
      .replace('–', '-')
      .replace('—', '-')
      .trim()
    if (normalized.isBlank()) return null

    // Ignore clear date/time formats like 2026/11/12, 12-11-2026, 08:30
    if (Regex("^\\d{1,4}[/\\-]\\d{1,2}[/\\-]\\d{1,4}$").matches(normalized) ||
      Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(normalized)
    ) {
      return null
    }

    // Match numbers including optional leading/trailing minus such as -25,000 / 25,000- / 0 / 25,000
    val matches = Regex("((?:(?<=^|[:=\\s(،\\n])-\\s*|-(?=[0-9]))?[0-9][0-9.,]*(?:\\s*-(?!\\d))?)").findAll(normalized)
      .map { it.groupValues[1].trim(',', '.', ' ') }
      .filter { candidate ->
        val digitsOnly = candidate.replace(Regex("[^0-9]"), "")
        if (digitsOnly.isEmpty()) return@filter false
        if (digitsOnly.length > 9) return@filter false // Too long for IQD/price, likely phone or barcode
        if (!excludedOrderDigits.isNullOrBlank() && digitsOnly == excludedOrderDigits) return@filter false
        if (excludedPhoneDigits.any { it.isNotEmpty() && (it == digitsOnly || it.endsWith(digitsOnly)) }) return@filter false
        if ((digitsOnly.startsWith("07") || digitsOnly.startsWith("7")) && digitsOnly.length >= 10) return@filter false
        true
      }
      .toList()

    if (matches.isEmpty()) return null
    val chosen = matches.maxByOrNull { it.replace(Regex("[^0-9]"), "").length } ?: return null
    val cleaned = cleanPriceNumber(chosen)
    return cleaned.takeIf { it.any { ch -> ch.isDigit() } }
  }

  /**
   * Recognizes the order price exclusively by matching numbers adjacent to currency symbols
   * (e.g., IQD, د.ع, دينار, $, USD, €, £, SAR, ر.س, AED, درهم, ﷼, etc.) either before or after the number.
   */
  fun extractPriceByCurrencySymbol(
    rawText: String,
    excludedPhoneDigits: Set<String> = emptySet(),
    excludedOrderDigits: String? = null
  ): CurrencyPriceMatch? {
    if (rawText.isBlank()) return null
    val normalized = normalizeArabicNumerals(rawText)
    val lines = normalized.split("\n", "\r")

    val explicitLabelRegex = Regex(
      "(?i)(السعر\\s*الكلي|السعر\\s*الإجمالي|السعر\\s*الاجمالي|سعر\\s*الطلب|المبلغ\\s*الكلي|المبلغ\\s*المطلوب|المبلغ\\s*الإجمالي|المبلغ\\s*الاجمالي|مبلغ\\s*الطلب|السعر|المبلغ|المجموع|الإجمالي|الاجمالي|المطلوب|total\\s*price|total\\s*amount|grand\\s*total|amount\\s*due|total|price|amount)"
    )
    val totalHintRegex = Regex("(?i)(?:السعر\\s*الكلي|السعر\\s*الإجمالي|السعر\\s*الاجمالي|المبلغ\\s*الكلي|المبلغ\\s*الإجمالي|المبلغ\\s*الاجمالي|المجموع|الإجمالي|الاجمالي|المطلوب|الكلي|الصافي|total\\s*price|total\\s*amount|grand\\s*total|amount\\s*due|payable|total|net)")
    val priceHintRegex = Regex("(?i)(?:سعر\\s*الطلب|السعر|مبلغ\\s*الطلب|المبلغ|الحساب|ثمن|قيمة|price|amount|cost)")
    val feeOrDiscountRegex = Regex("(?i)(?:التوصيل|توصيل|أجور|اجور|كلفة\\s*النقل|delivery|shipping|fee|خصم|حسم|discount|ضريبة|tax)")

    val candidates = mutableListOf<CurrencyPriceMatch>()

    lines.forEachIndexed { lineIdx, line ->
      if (line.isBlank()) return@forEachIndexed

      val rawMatches = mutableListOf<Pair<String, String>>()
      CURRENCY_AFTER_NUMBER_REGEX.findAll(line).forEach { m ->
        rawMatches.add(m.groupValues[1] to m.groupValues[2].trim())
      }
      NUMBER_AFTER_CURRENCY_REGEX.findAll(line).forEach { m ->
        rawMatches.add(m.groupValues[2] to m.groupValues[1].trim())
      }

      if (rawMatches.isEmpty()) return@forEachIndexed

      val prevLine = lines.getOrNull(lineIdx - 1).orEmpty()
      val prevHasNoNumber = !Regex("\\d").containsMatchIn(prevLine)

      val sameLineLabel = explicitLabelRegex.find(line)?.value
      val prevLineLabel = if (sameLineLabel == null && prevHasNoNumber) explicitLabelRegex.find(prevLine)?.value else null
      val matchedLabel = sameLineLabel ?: prevLineLabel

      val isTotal = totalHintRegex.containsMatchIn(line) || (prevHasNoNumber && totalHintRegex.containsMatchIn(prevLine))
      val isPriceLabel = priceHintRegex.containsMatchIn(line) || (prevHasNoNumber && priceHintRegex.containsMatchIn(prevLine))
      val isFeeOrDiscount = feeOrDiscountRegex.containsMatchIn(line)

      for ((rawNum, rawSymbol) in rawMatches) {
        val cleaned = cleanPriceNumber(rawNum)
        val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
        if (digitsOnly.isEmpty() || digitsOnly.length > 9) continue
        if (!excludedOrderDigits.isNullOrBlank() && digitsOnly == excludedOrderDigits) continue
        if (excludedPhoneDigits.any { it.isNotEmpty() && (it == digitsOnly || it.endsWith(digitsOnly)) }) continue
        if ((digitsOnly.startsWith("07") || digitsOnly.startsWith("7")) && digitsOnly.length >= 10) continue

        var score = 100
        if (isTotal) score += 80
        if (isPriceLabel) score += 50
        if (sameLineLabel != null) score += 30
        if (isFeeOrDiscount && !isTotal) score -= 55
        if (digitsOnly.length >= 3) score += 15

        candidates.add(
          CurrencyPriceMatch(
            cleanPrice = cleaned,
            symbol = rawSymbol,
            score = score,
            lineIndex = lineIdx,
            matchedLabel = matchedLabel
          )
        )
      }
    }

    if (candidates.isEmpty()) return null
    return candidates.maxWithOrNull(
      compareBy<CurrencyPriceMatch> { it.score }
        .thenBy { it.lineIndex }
        .thenBy { it.cleanPrice.replace(Regex("[^0-9]"), "").length }
    )
  }

  fun normalizeArabicNumerals(input: String): String {
    val arabicNumerals = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
    val persianNumerals = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    val sb = StringBuilder()
    for (c in input) {
      val arabicIdx = arabicNumerals.indexOf(c)
      if (arabicIdx != -1) {
        sb.append(arabicIdx)
        continue
      }
      val persianIdx = persianNumerals.indexOf(c)
      if (persianIdx != -1) {
        sb.append(persianIdx)
        continue
      }
      when (c) {
        '٬' -> sb.append(',')
        '٫' -> sb.append('.')
        else -> sb.append(c)
      }
    }
    return sb.toString()
  }

  /**
   * Repairs common ML Kit character-lookalike confusions (e.g. "38,OOO" -> "38,000",
   * "O7875160858" -> "07875160858", "17l348608" -> "171348608") ONLY inside tokens
   * that are predominantly numeric, without corrupting normal letters or currency codes.
   */
  fun repairNumericLookalikes(input: String): String {
    if (input.isBlank()) return input
    val tokenRegex = Regex("[A-Za-z0-9.,+/|\\-]{4,}")
    return tokenRegex.replace(input) { match ->
      val token = match.value
      val lookalikeCount = token.count { it == 'O' || it == 'o' || it == 'I' || it == 'l' || it == '|' || it == 'S' || it == 'B' }
      val digitCount = token.count { it.isDigit() }
      val alphaCount = token.count { it.isLetter() }
      val upper = token.uppercase()
      // Never touch known currency/ID prefixes
      if (upper in setOf("IQD", "USD", "SAR", "AED", "JOD", "KWD", "ORDER")) {
        return@replace token
      }
      val shouldRepair =
        (digitCount >= 1 && alphaCount > 0 && alphaCount == lookalikeCount) ||
          (digitCount >= 3 && (digitCount + lookalikeCount) > alphaCount && digitCount >= alphaCount)
      if (!shouldRepair) return@replace token

      token.map { ch ->
        when (ch) {
          'O', 'o', 'D' -> '0'
          'I', 'l', '|' -> '1'
          'S' -> '5'
          'B' -> '8'
          else -> ch
        }
      }.joinToString("")
    }
  }

  fun parseOcrText(rawText: String): OcrResult {
    if (rawText.isBlank()) {
      return fallbackOcrData(null, null, null)
    }

    // Convert Arabic/Eastern numbers to Western digits and repair lookalike numeric OCR confusions
    val text = repairNumericLookalikes(normalizeArabicNumerals(rawText))

    // 1. Phone number extraction
    val rawPhones = mutableListOf<String>()
    val lines = text.split("\n", "\r")

    // Check line by line for labelled phones first
    var labeledPhone1: String? = null
    var labeledPhone2: String? = null

    for (line in lines) {
      val lower = line.lowercase()
      if (lower.contains("هاتف 1") || lower.contains("هاتف1") || lower.contains("phone 1") || lower.contains("phone1") || lower.contains("الاول") || lower.contains("الأول")) {
        extractSinglePhone(line)?.let { if (labeledPhone1 == null) labeledPhone1 = it }
      } else if (lower.contains("هاتف 2") || lower.contains("هاتف2") || lower.contains("phone 2") || lower.contains("phone2") || lower.contains("الثاني")) {
        extractSinglePhone(line)?.let { if (labeledPhone2 == null) labeledPhone2 = it }
      }
    }

    // Pattern for Iraqi numbers: 07XX XXX XXXX (11 digits), +964 7XX... (12-13 digits), or 7XX XXX XXXX (10 digits)
    val phonePattern = Pattern.compile("(?<!\\d)(?:(?:\\+?964|00964|964)[\\s\\-]?)?(?:0)?(7[3-9]\\d[\\s\\-]?\\d{3}[\\s\\-]?\\d{4})(?!\\d)|(?<!\\d)(07[3-9]\\d{8})(?!\\d)|(?<!\\d)(7[3-9]\\d{8})(?!\\d)|(?<!\\d)(?:(?:\\+?966|00966)[\\s\\-]?)?(05\\d{8})(?!\\d)")
    val phoneMatcher = phonePattern.matcher(text)

    while (phoneMatcher.find()) {
      val match = phoneMatcher.group()
      val formatted = formatPhoneNumber(match)
      if (formatted.isNotBlank() && !rawPhones.contains(formatted)) {
        rawPhones.add(formatted)
      }
    }

    // Assign Phone 1 and Phone 2 strictly without fake mock fallbacks!
    val finalPhone1 = labeledPhone1 ?: rawPhones.getOrNull(0)
    val finalPhone2 = labeledPhone2 ?: rawPhones.firstOrNull { it != finalPhone1 }

    // Set of digits in phone numbers so order number NEVER accidentally takes a phone number!
    val phoneDigitSets = mutableSetOf<String>()
    finalPhone1?.let { phoneDigitSets.add(it.replace(Regex("[^0-9]"), "")) }
    finalPhone2?.let { phoneDigitSets.add(it.replace(Regex("[^0-9]"), "")) }

    // 2A. Look for explicitly labeled order number first
    var orderNumber: String? = null
    val orderRegex = Regex("(?i)(?:order|ord|طلب|رقم\\s*الطلب|كود|تتبع|بوليصة)[:\\s#]*([A-Za-z0-9\\-]{5,15})")
    for (line in lines) {
      val match = orderRegex.find(line)
      if (match != null) {
        val candidate = match.groupValues[1].trim()
        val candidateDigits = candidate.replace(Regex("[^0-9]"), "")
        if (candidateDigits.isNotEmpty() && !phoneDigitSets.contains(candidateDigits) && !candidateDigits.startsWith("07") && !candidateDigits.startsWith("7")) {
          orderNumber = candidate
          break
        }
      }
    }

    // 2B. Detect currency-symbol price before unlabeled order-number fallback so e.g. "150000 IQD" isn't stolen as an order number
    val currencyMatch = extractPriceByCurrencySymbol(
      rawText = text,
      excludedPhoneDigits = phoneDigitSets,
      excludedOrderDigits = orderNumber?.replace(Regex("[^0-9]"), "")
    )
    val currencyPriceDigits = currencyMatch?.cleanPrice?.replace(Regex("[^0-9]"), "")

    // 2C. If no labeled order, look for 6-10 digit numbers that are NOT phones and NOT the currency price
    if (orderNumber == null) {
      val digitSequences = Regex("(?<!\\d)(\\d{6,10})(?!\\d)").findAll(text)
        .map { it.groupValues[1] }
        .filter { candidate ->
          !phoneDigitSets.contains(candidate) &&
          candidate != currencyPriceDigits &&
          !candidate.startsWith("07") &&
          !candidate.startsWith("7") &&
          !candidate.startsWith("05") &&
          !candidate.startsWith("5") &&
          !candidate.startsWith("964")
        }
        .toList()

      if (digitSequences.isNotEmpty()) {
        orderNumber = digitSequences.first()
      }
    }

    // 3. Final Price resolution: Currency Symbol first, then Keyword fallback (clean numeric amount without currency symbol)
    val fallbackKeywordPrice = if (currencyMatch == null) {
      extractFallbackPriceFromText(
        normalizedText = text,
        lines = lines,
        orderNumber = orderNumber,
        phoneDigitSets = phoneDigitSets
      )
    } else null

    val finalPrice = currencyMatch?.cleanPrice ?: fallbackKeywordPrice

    return OcrResult(
      orderNumber = orderNumber,
      phone1 = finalPhone1,
      phone2 = finalPhone2,
      price = finalPrice,
      governorate = null, // Customer address is strictly manual entry only as requested!
      region = null,
      rawText = text,
      isPriceFromGreen = false,
      isPriceFromCurrency = currencyMatch != null,
      isPriceFromLabelAndCurrency = currencyMatch?.matchedLabel != null,
      detectedCurrencySymbol = currencyMatch?.symbol,
      detectedPriceLabel = currencyMatch?.matchedLabel
    )
  }

  private fun extractFallbackPriceFromText(
    normalizedText: String,
    lines: List<String>,
    orderNumber: String?,
    phoneDigitSets: Set<String>
  ): String? {
    val orderDigits = orderNumber?.replace(Regex("[^0-9]"), "")

    // Numbers directly following price keywords: المجموع، السعر، السعر الكلي، المطلوب، الإجمالي، المبلغ، دينار، total، price، amount
    // Preserves minus sign (-15,000 or 15,000-) and zero (0)
    val priceLabelRegex = Regex(
      "(?i)(?:سعر\\s*الطلب|السعر\\s*الكلي|السعر|المبلغ\\s*المطلوب|المبلغ|المطلوب|الإجمالي|الاجمالي|المجموع|الواصل|واصل|المتبقي|الحساب|دينار|د\\.ع|total|price|amount)[:\\s=]*(-\\s*[0-9][0-9.,]*|[0-9][0-9.,]*(?:\\s*-)?)(?:\\s*(?:د\\.ع|د\\.\\s*ع|دينار|دنانير|الف|ألف|IQD))?"
    )
    for (line in lines) {
      val normLine = line.replace('−', '-').replace('–', '-').replace('—', '-')
      val match = priceLabelRegex.find(normLine)
      if (match != null) {
        val cleaned = cleanPriceNumber(match.groupValues[1])
        val numDigits = cleaned.replace(Regex("[^0-9]"), "")
        if (numDigits.isNotEmpty() && numDigits != orderDigits && !phoneDigitSets.contains(numDigits)) {
          return cleaned
        }
      }
    }

    // Numbers followed by amount word ألف / الف (e.g., "30 الف" or "-30 الف")
    val normFull = normalizedText.replace('−', '-').replace('–', '-').replace('—', '-')
    val alfRegex = Regex("(-?\\s*[0-9][0-9.,]{0,11})\\s*(?:ألف|الف)")
    val alfMatch = alfRegex.find(normFull)
    if (alfMatch != null) {
      val cleaned = cleanPriceNumber(alfMatch.groupValues[1])
      val numDigits = cleaned.replace(Regex("[^0-9]"), "")
      if (numDigits.isNotEmpty() && numDigits != orderDigits && !phoneDigitSets.contains(numDigits)) {
        return cleaned
      }
    }

    return null
  }

  private fun extractSinglePhone(line: String): String? {
    // Strip labels like "هاتف 1", "هاتف 2", "الهاتف 1", "phone 1", etc. so digit '1' or '2' is never prepended!
    val stripped = line
      .replace(Regex("(?i)(?:رقم\\s*الهاتف|الهاتف|هاتف|phone|mobile|جوال|موبايل)\\s*[12١٢]"), " ")
      .replace(Regex("(?i)(?:الأول|الاول|الثاني)"), " ")

    val pattern = Pattern.compile("(?<!\\d)(?:(?:\\+?964|00964|964)[\\s\\-]?)?(?:0)?(7[3-9]\\d[\\s\\-]?\\d{3}[\\s\\-]?\\d{4})(?!\\d)|(?<!\\d)(07[3-9]\\d{8})(?!\\d)|(?<!\\d)(7[3-9]\\d{8})(?!\\d)|(?<!\\d)(?:(?:\\+?966|00966)[\\s\\-]?)?(05\\d{8})(?!\\d)|(?<!\\d)(\\d{10,11})(?!\\d)")
    val matcher = pattern.matcher(stripped)
    if (matcher.find()) {
      val raw = matcher.group()
      return formatPhoneNumber(raw)
    }

    val digitsOnly = stripped.replace(Regex("[^0-9]"), "")
    if (digitsOnly.length in 9..14 && (digitsOnly.startsWith("07") || digitsOnly.startsWith("7") || digitsOnly.startsWith("964") || digitsOnly.startsWith("05"))) {
      return formatPhoneNumber(digitsOnly)
    }
    return null
  }

  fun formatPhoneNumber(raw: String): String {
    val digits = raw.replace(Regex("[^0-9]"), "")
    return when {
      // 964 + 7XX + 8 digits = 12 digits
      digits.startsWith("964") && digits.length >= 12 -> {
        val local = digits.substring(3)
        if (local.length >= 10) {
          "+964 ${local.substring(0, 3)} ${local.substring(3, 6)} ${local.substring(6)}"
        } else {
          "+964 $local"
        }
      }
      // 07XX + 8 digits = 11 digits
      digits.startsWith("07") && digits.length == 11 -> {
        "+964 ${digits.substring(1, 4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
      }
      // 7XX + 8 digits = 10 digits
      digits.startsWith("7") && digits.length == 10 -> {
        "+964 ${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
      }
      // Saudi: 05X + 7 digits = 10 digits
      digits.startsWith("05") && digits.length == 10 -> {
        "+966 ${digits.substring(1, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
      }
      digits.length in 10..11 -> {
        if (digits.startsWith("0")) "+964 ${digits.substring(1)}" else "+964 $digits"
      }
      digits.length >= 9 -> "+$digits"
      else -> digits
    }
  }

  /**
   * Cleans currency symbols, words (دينار، ألف، د.ع، IQD), and all non-numeric letters,
   * leaving the clean numeric price value (e.g. "25,000", "0", or "-15,000").
   */
  fun cleanPriceNumber(raw: String): String {
    val normalized = normalizeArabicNumerals(raw)
      .replace('−', '-')
      .replace('–', '-')
      .replace('—', '-')
    val withoutCurrency = CURRENCY_CONTAINS_REGEX.replace(normalized, "")
      .replace("ألف", "")
      .replace("الف", "")
      .trim()

    val isNegative = Regex("(?:^|[:=\\s(،])\\s*-\\s*[0-9]").containsMatchIn(withoutCurrency) ||
      Regex("^[0-9][0-9.,]*\\s*-\\s*$").containsMatchIn(withoutCurrency)

    val numericPart = withoutCurrency
      .replace(Regex("[^0-9.,]"), "")
      .trim(',', '.', ' ')
    if (numericPart.isEmpty()) return ""

    val digitsOnly = numericPart.replace(Regex("[^0-9]"), "")
    if (digitsOnly.isEmpty()) return ""
    if (digitsOnly.all { it == '0' }) return "0"

    return if (isNegative) "-$numericPart" else numericPart
  }

  fun extractAllPhoneNumbers(rawText: String): List<String> {
    if (rawText.isBlank()) return emptyList()
    val text = normalizeArabicNumerals(rawText)
    val phones = mutableListOf<String>()
    val seenNormalized = mutableSetOf<String>()

    val phonePattern = Pattern.compile("(?<!\\d)(?:(?:\\+?964|00964|964)[\\s\\-]?)?(?:0)?(7[3-9]\\d[\\s\\-]?\\d{3}[\\s\\-]?\\d{4})(?!\\d)|(?<!\\d)(07[3-9]\\d{8})(?!\\d)|(?<!\\d)(7[3-9]\\d{8})(?!\\d)|(?<!\\d)(?:(?:\\+?966|00966)[\\s\\-]?)?(05\\d{8})(?!\\d)")
    val matcher = phonePattern.matcher(text)

    while (matcher.find()) {
      val match = matcher.group()
      val formatted = formatPhoneNumber(match)
      val norm = com.example.model.CustomerRecord.normalizePhoneNumber(formatted)
      if (formatted.isNotBlank() && norm.length >= 9 && seenNormalized.add(norm)) {
        phones.add(formatted)
      }
    }
    return phones
  }

  private fun fallbackOcrData(order: String?, p1: String?, p2: String?): OcrResult {
    return OcrResult(
      orderNumber = order,
      phone1 = p1,
      phone2 = p2,
      price = null,
      governorate = null,
      region = null,
      rawText = "",
      isPriceFromGreen = false
    )
  }
}
