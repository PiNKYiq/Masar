package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QrScannerModal(
  onDismiss: () -> Unit,
  onScanResult: (String) -> Unit
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val keyboardController = LocalSoftwareKeyboardController.current

  // State to track if camera permission is granted
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
      ) == PackageManager.PERMISSION_GRANTED
    )
  }

  // Camera permission launcher
  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    hasCameraPermission = isGranted
    if (!isGranted) {
      Toast.makeText(context, "يلزم إذن الكاميرا لمسح الباركود ضوئياً", Toast.LENGTH_SHORT).show()
    }
  }

  // Auto request permission if not granted on launch
  LaunchedEffect(Unit) {
    if (!hasCameraPermission) {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  // Flashlight / Torch state
  var isTorchOn by remember { mutableStateOf(false) }
  var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
  var hasDetectedBarcode by remember { mutableStateOf(false) }
  var manualCodeInput by remember { mutableStateOf("") }

  // Photo picker to scan barcode from existing image/screenshot
  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()
        if (bitmap != null) {
          val inputImage = InputImage.fromBitmap(bitmap, 0)
          val scanner = BarcodeScanning.getClient()
          scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
              val code = barcodes.firstOrNull()?.rawValue ?: barcodes.firstOrNull()?.displayValue
              if (!code.isNullOrBlank()) {
                Toast.makeText(context, "تم مسح الرمز من الصورة: $code", Toast.LENGTH_SHORT).show()
                onScanResult(code)
              } else {
                Toast.makeText(context, "لم يتم العثور على باركود في الصورة المحددة", Toast.LENGTH_SHORT).show()
              }
            }
            .addOnFailureListener {
              Toast.makeText(context, "تعذر قراءة الباركود من الصورة", Toast.LENGTH_SHORT).show()
            }
        }
      } catch (e: Exception) {
        Toast.makeText(context, "خطأ في قراءة ملف الصورة", Toast.LENGTH_SHORT).show()
      }
    }
  }

  // Laser line animation
  val infiniteTransition = rememberInfiniteTransition(label = "scanner")
  val laserOffset by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 180f,
    animationSpec = infiniteRepeatable(
      animation = tween(1300, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "laser"
  )


  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .padding(vertical = 16.dp)
        .testTag("qr_scanner_dialog"),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // ==========================================
        // 1. Top Bar: Title & Close Button
        // ==========================================
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFFEFF6FF)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = Color(0xFF0052CC),
                modifier = Modifier.size(20.dp)
              )
            }
            Column {
              Text(
                text = "كاميرا مسح الباركود و QR",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
              )
              Text(
                text = "مسح تلقائي وإدراج مباشر في البحث",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
              )
            }
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("btn_close_scanner")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "إغلاق",
              tint = Color(0xFF64748B)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ==========================================
        // 2. Camera Viewfinder with Live Scanner
        // ==========================================
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0F172A))
            .border(2.dp, Color(0xFF0052CC), RoundedCornerShape(18.dp)),
          contentAlignment = Alignment.Center
        ) {
          if (hasCameraPermission) {
            // Live CameraX Preview & ML Kit Analyzer
            AndroidView(
              factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()

                cameraProviderFuture.addListener({
                  val cameraProvider = cameraProviderFuture.get()
                  val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                  }

                  val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build()
                  val scanner = BarcodeScanning.getClient(options)

                  val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                  imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !hasDetectedBarcode) {
                      val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                      )
                      scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                          if (!hasDetectedBarcode) {
                            val scanned = barcodes.firstOrNull()?.rawValue
                              ?: barcodes.firstOrNull()?.displayValue
                            if (!scanned.isNullOrBlank()) {
                              hasDetectedBarcode = true
                              previewView.post {
                                Toast.makeText(
                                  ctx,
                                  "تم قراءة الباركود: $scanned",
                                  Toast.LENGTH_SHORT
                                ).show()
                                onScanResult(scanned)
                              }
                            }
                          }
                        }
                        .addOnCompleteListener {
                          imageProxy.close()
                        }
                    } else {
                      imageProxy.close()
                    }
                  }

                  val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                  try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                      lifecycleOwner,
                      cameraSelector,
                      preview,
                      imageAnalysis
                    )
                    cameraControl = camera.cameraControl
                  } catch (e: Exception) {
                    e.printStackTrace()
                  }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
              },
              modifier = Modifier
                .fillMaxSize()
                .testTag("camera_preview_view")
            )

            // Scanning Overlay Crosshairs and Laser
            Box(
              modifier = Modifier
                .size(190.dp)
                .border(2.dp, Color(0xFF60A5FA).copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              // Moving laser line
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(2.5.dp)
                  .offset(y = (laserOffset - 90).dp)
                  .background(Color(0xFF38BDF8))
              )
            }

            // Torch Button (Top End inside camera view)
            IconButton(
              onClick = {
                val nextState = !isTorchOn
                isTorchOn = nextState
                cameraControl?.enableTorch(nextState)
              },
              modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .testTag("btn_toggle_torch")
            ) {
              Icon(
                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "الفلاش",
                tint = if (isTorchOn) Color(0xFFFBBF24) else Color.White,
                modifier = Modifier.size(18.dp)
              )
            }

            // Pick Image from Gallery Button (Top Start inside camera view)
            IconButton(
              onClick = {
                photoPickerLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
              },
              modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .testTag("btn_scan_from_image")
            ) {
              Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "مسح من صورة",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
            }
          } else {
            // Permission request fallback card
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
              modifier = Modifier.padding(16.dp)
            ) {
              Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(44.dp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "يلزم إذن الكاميرا لمسح الباركود تلقائياً",
                fontSize = 12.5.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
              )
              Spacer(modifier = Modifier.height(10.dp))
              Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("btn_grant_camera_permission")
              ) {
                Text("منح إذن الكاميرا", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "وجّه عدسة الكاميرا نحو رمز QR أو باركود الطلب للمسح الفوري:",
          fontSize = 11.5.sp,
          color = Color(0xFF64748B)
        )

        Spacer(modifier = Modifier.height(8.dp))


        Spacer(modifier = Modifier.height(12.dp))

        // ==========================================
        // 4. Manual Barcode Entry Box & Confirm
        // ==========================================
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
          contentAlignment = Alignment.CenterStart
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Outlined.Search,
              contentDescription = null,
              tint = Color(0xFF94A3B8),
              modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box(modifier = Modifier.weight(1f)) {
              if (manualCodeInput.isEmpty()) {
                Text(
                  text = "أو أدخل رقم الباركود يدوياً...",
                  fontSize = 12.sp,
                  color = Color(0xFF94A3B8)
                )
              }
              BasicTextField(
                value = manualCodeInput,
                onValueChange = { manualCodeInput = it },
                textStyle = TextStyle(
                  fontSize = 12.5.sp,
                  color = Color(0xFF1E293B)
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF1E293B)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                  onSearch = {
                    if (manualCodeInput.isNotBlank()) {
                      keyboardController?.hide()
                      onScanResult(manualCodeInput.trim())
                    }
                  }
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("manual_barcode_input")
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action button to confirm or scan
        Button(
          onClick = {
            if (manualCodeInput.isNotBlank()) {
              val code = manualCodeInput.trim()
              Toast.makeText(context, "تم إدخال الرمز: $code", Toast.LENGTH_SHORT).show()
              onScanResult(code)
            } else {
              Toast.makeText(context, "وجّه الكاميرا نحو الباركود أو أدخل الرقم يدوياً", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .testTag("btn_confirm_scan")
        ) {
          Icon(
            imageVector = Icons.Default.QrCodeScanner,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(17.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (manualCodeInput.isNotBlank()) "إدراج في شريط البحث: $manualCodeInput" else "بحث برقم الباركود",
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp
          )
        }
      }
    }
  }
}
