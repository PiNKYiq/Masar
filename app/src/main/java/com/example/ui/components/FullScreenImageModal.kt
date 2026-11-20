package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Full screen image viewer dialog with zoom and pan gestures,
 * displaying images in their maximum resolution and aspect ratio.
 */
@Composable
fun FullScreenImageModal(
  imageModel: Any?,
  title: String = "معاينة الصورة بالحجم الكامل",
  onDismiss: () -> Unit
) {
  if (imageModel == null) return

  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true
    )
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF090D16))
        .testTag("fullscreen_image_viewer")
    ) {
      // Zoomable and Pannable Full Resolution Image
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
              val newScale = (scale * zoom).coerceIn(1f, 5f)
              scale = newScale
              if (newScale > 1f) {
                offsetX += pan.x
                offsetY += pan.y
              } else {
                offsetX = 0f
                offsetY = 0f
              }
            }
          },
        contentAlignment = Alignment.Center
      ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val imageRequest = remember(imageModel) {
          coil.request.ImageRequest.Builder(context)
            .data(imageModel)
            .size(2048, 2048)
            .crossfade(true)
            .build()
        }
        AsyncImage(
          model = imageRequest,
          contentDescription = title,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offsetX,
              translationY = offsetY
            )
        )
      }

      // Discrete floating close button at the top corner without a blocking bar
      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .statusBarsPadding()
          .padding(16.dp)
          .size(38.dp)
          .background(Color.Black.copy(alpha = 0.55f), CircleShape)
          .testTag("btn_close_fullscreen_image")
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "إغلاق المعاينة",
          tint = Color.White,
          modifier = Modifier.size(20.dp)
        )
      }

      // Floating Reset Zoom button if user has zoomed in
      if (scale > 1f) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF0052CC).copy(alpha = 0.85f),
          modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(16.dp)
            .clickable {
              scale = 1f
              offsetX = 0f
              offsetY = 0f
            }
        ) {
          Text(
            text = "إعادة ضبط الحجم",
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
          )
        }
      }

      // Bottom Helper Pill
      Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 24.dp)
          .navigationBarsPadding()
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = Icons.Default.ZoomIn,
            contentDescription = null,
            tint = Color(0xFF60A5FA),
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = "يمكنك التكبير والسحب بإصبعين لفحص التفاصيل",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }
  }
}
