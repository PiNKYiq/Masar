package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Global Dark Slate text input color (#1E293B)
val DarkSlateColor = Color(0xFF1E293B)

val CustomTextSelectionColors = TextSelectionColors(
  handleColor = DarkSlateColor,
  backgroundColor = DarkSlateColor.copy(alpha = 0.25f)
)

val DarkSlateInputTextStyle = TextStyle(
  fontSize = 14.sp,
  fontWeight = FontWeight.Medium
)

@Composable
fun darkSlateTextFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = MaterialTheme.colorScheme.onSurface,
  unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
  cursorColor = MaterialTheme.colorScheme.primary,
  focusedBorderColor = MaterialTheme.colorScheme.primary,
  unfocusedBorderColor = MaterialTheme.colorScheme.outline,
  focusedLabelColor = MaterialTheme.colorScheme.primary,
  unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
  focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
  unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
)

private val LightColorScheme = lightColorScheme(
  primary = BrandBlue,
  onPrimary = Color.White,
  primaryContainer = BrandBlueLight,
  onPrimaryContainer = BrandBlueDark,
  secondary = Slate700,
  onSecondary = Color.White,
  secondaryContainer = Slate100,
  onSecondaryContainer = Slate900,
  tertiary = NoteSuccessGreen,
  onTertiary = Color.White,
  background = Color(0xFFF8FAFC),
  surface = Color.White,
  onBackground = DarkSlateColor,
  onSurface = DarkSlateColor,
  surfaceVariant = Slate100,
  onSurfaceVariant = DarkSlateColor,
  outline = Slate200,
  outlineVariant = Slate300,
)

private val DarkColorScheme = darkColorScheme(
  primary = Color(0xFF90CAF9),
  onPrimary = Color(0xFF0D47A1),
  primaryContainer = Color(0xFF1565C0),
  onPrimaryContainer = Color(0xFFE3F2FD),
  secondary = Slate300,
  onSecondary = Slate900,
  secondaryContainer = Slate800,
  onSecondaryContainer = Slate100,
  tertiary = Color(0xFF6EE7B7),
  background = Color(0xFF0F172A),
  surface = Color(0xFF1E293B),
  onBackground = Color(0xFFF8FAFC),
  onSurface = Color(0xFFF8FAFC),
  surfaceVariant = Color(0xFF334155),
  onSurfaceVariant = Color(0xFFCBD5E1),
  outline = Color(0xFF475569),
  outlineVariant = Color(0xFF334155),
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Keep branded blue consistent
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  CompositionLocalProvider(
    LocalTextSelectionColors provides CustomTextSelectionColors
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}
