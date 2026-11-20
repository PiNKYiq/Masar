package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Compact refresh & save button featuring two interlocking semicircular arrows.
 * Spins smoothly whenever an order/category change auto-refreshes or when tapped manually.
 */
@Composable
fun RefreshSyncButton(
  isRefreshing: Boolean,
  refreshGeneration: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  testTagName: String = "btn_refresh_app",
  buttonDiameter: Dp = 32.dp,
  iconSize: Dp = 18.dp
) {
  val rotation = remember { Animatable(0f) }

  LaunchedEffect(refreshGeneration) {
    if (refreshGeneration > 0) {
      val target = rotation.value + 360f
      rotation.animateTo(
        targetValue = target,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
      )
      rotation.snapTo(rotation.value % 360f)
    }
  }

  LaunchedEffect(isRefreshing) {
    while (isRefreshing) {
      val next = rotation.value + 360f
      rotation.animateTo(
        targetValue = next,
        animationSpec = tween(durationMillis = 650, easing = LinearEasing)
      )
      rotation.snapTo(rotation.value % 360f)
    }
  }

  val containerColor by animateColorAsState(
    targetValue = if (isRefreshing) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    },
    animationSpec = tween(durationMillis = 200),
    label = "refreshContainerColor"
  )

  val arrowColor by animateColorAsState(
    targetValue = if (isRefreshing) {
      Color.White
    } else {
      MaterialTheme.colorScheme.primary
    },
    animationSpec = tween(durationMillis = 200),
    label = "refreshArrowColor"
  )

  val borderColor = if (isRefreshing) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
  }

  val descriptionText = stringResource(id = R.string.refresh_and_save_button_desc)

  Box(
    modifier = modifier
      .minimumInteractiveComponentSize()
      .clip(CircleShape)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { contentDescription = descriptionText }
      .testTag(testTagName),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = CircleShape,
      color = containerColor,
      border = BorderStroke(1.dp, borderColor),
      shadowElevation = if (isRefreshing) 2.dp else 0.dp,
      modifier = Modifier.size(buttonDiameter)
    ) {
      Box(contentAlignment = Alignment.Center) {
        DualSemicircleArrowsIcon(
          tint = arrowColor,
          rotationDegrees = rotation.value,
          modifier = Modifier.size(iconSize)
        )
      }
    }
  }
}

/**
 * Draws two symmetric semicircular curved arrows wrapping around each other in a circle.
 */
@Composable
private fun DualSemicircleArrowsIcon(
  tint: Color,
  rotationDegrees: Float,
  modifier: Modifier = Modifier
) {
  Canvas(modifier = modifier) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(size.width, size.height) * 0.37f
    val strokeWidth = (min(size.width, size.height) * 0.115f).coerceAtLeast(1.8f)
    val headLen = radius * 0.44f

    val arcTopLeft = Offset(cx - radius, cy - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val strokeStyle = Stroke(
      width = strokeWidth,
      cap = StrokeCap.Round,
      join = StrokeJoin.Round
    )

    rotate(degrees = rotationDegrees, pivot = Offset(cx, cy)) {
      // Semicircle arrow 1 (top half, sweeping clockwise from ~198° to ~342°)
      drawArc(
        color = tint,
        startAngle = 198f,
        sweepAngle = 144f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = strokeStyle
      )
      drawArrowHead(
        tint = tint,
        cx = cx,
        cy = cy,
        radius = radius,
        endAngleDeg = 342f,
        headLen = headLen,
        strokeStyle = strokeStyle
      )

      // Semicircle arrow 2 (bottom half, sweeping clockwise from ~18° to ~162°)
      drawArc(
        color = tint,
        startAngle = 18f,
        sweepAngle = 144f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = strokeStyle
      )
      drawArrowHead(
        tint = tint,
        cx = cx,
        cy = cy,
        radius = radius,
        endAngleDeg = 162f,
        headLen = headLen,
        strokeStyle = strokeStyle
      )
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
  tint: Color,
  cx: Float,
  cy: Float,
  radius: Float,
  endAngleDeg: Float,
  headLen: Float,
  strokeStyle: Stroke
) {
  val endRad = Math.toRadians(endAngleDeg.toDouble())
  val tipX = cx + (radius * cos(endRad)).toFloat()
  val tipY = cy + (radius * sin(endRad)).toFloat()

  // Backward direction opposite the clockwise tangent (endAngleDeg + 90 + 180 = endAngleDeg + 270)
  val backAngleDeg = endAngleDeg + 270f
  val wing1Rad = Math.toRadians((backAngleDeg - 38f).toDouble())
  val wing2Rad = Math.toRadians((backAngleDeg + 38f).toDouble())

  val w1X = tipX + (headLen * cos(wing1Rad)).toFloat()
  val w1Y = tipY + (headLen * sin(wing1Rad)).toFloat()
  val w2X = tipX + (headLen * cos(wing2Rad)).toFloat()
  val w2Y = tipY + (headLen * sin(wing2Rad)).toFloat()

  val path = Path().apply {
    moveTo(w1X, w1Y)
    lineTo(tipX, tipY)
    lineTo(w2X, w2Y)
  }
  drawPath(path = path, color = tint, style = strokeStyle)
}
