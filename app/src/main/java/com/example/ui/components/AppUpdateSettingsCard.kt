package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.darkSlateTextFieldColors
import com.example.util.AppUpdateCheckState
import com.example.util.AppUpdateChecker

@Composable
fun AppUpdateSettingsCard(
  updateSourceUrl: String,
  lastUpdateCheckTime: String,
  appUpdateState: AppUpdateCheckState,
  onSaveUpdateSourceUrl: (String) -> Unit,
  onCheckForAppUpdates: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var isSourceConfigExpanded by remember(updateSourceUrl) {
    mutableStateOf(updateSourceUrl.isBlank())
  }
  var sourceUrlInput by remember(updateSourceUrl) {
    mutableStateOf(updateSourceUrl)
  }

  LaunchedEffect(appUpdateState) {
    if (appUpdateState is AppUpdateCheckState.NeedsSourceUrl) {
      isSourceConfigExpanded = true
    }
  }

  val isUpdateAvailable = appUpdateState is AppUpdateCheckState.UpdateAvailable
  val isChecking = appUpdateState is AppUpdateCheckState.Checking

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(
      width = if (isUpdateAvailable) 1.5.dp else 1.dp,
      color = if (isUpdateAvailable) {
        Color(0xFF0052CC)
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
      }
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = modifier
      .fillMaxWidth()
      .testTag("card_app_updates_settings")
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Header row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(Color(0xFF0052CC).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Filled.SystemUpdate,
              contentDescription = stringResource(id = R.string.app_updates_title),
              tint = Color(0xFF0052CC),
              modifier = Modifier.size(22.dp)
            )
          }

          Column {
            Text(
              text = stringResource(id = R.string.app_updates_title),
              fontSize = 15.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "الإصدار الحالي: ${BuildConfig.VERSION_NAME} (بناء ${BuildConfig.VERSION_CODE})",
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("text_current_app_version")
            )
            if (lastUpdateCheckTime.isNotBlank()) {
              Text(
                text = "آخر فحص: $lastUpdateCheckTime",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
              )
            }
          }
        }

        // Toggle source URL configuration button
        IconButton(
          onClick = { isSourceConfigExpanded = !isSourceConfigExpanded },
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("btn_toggle_update_source_config")
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(id = R.string.app_updates_source_config_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
          )
        }
      }

      // Status Feedback Area
      when (appUpdateState) {
        is AppUpdateCheckState.Idle -> {
          Text(
            text = stringResource(id = R.string.app_updates_subtitle),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        is AppUpdateCheckState.Checking -> {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("status_update_checking")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
              )
              Text(
                text = "جاري التحقق من وجود تحديثات جديدة...",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }

        is AppUpdateCheckState.UpToDate -> {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF16A34A).copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color(0xFF16A34A).copy(alpha = 0.40f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("status_update_up_to_date")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF16A34A),
                modifier = Modifier.size(20.dp)
              )
              Column {
                Text(
                  text = "التطبيق محدّث إلى آخر إصدار (${appUpdateState.currentVersionName})",
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF15803D)
                )
                Text(
                  text = "لا توجد إصدارات أحدث متاحة حالياً.",
                  fontSize = 11.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }

        is AppUpdateCheckState.UpdateAvailable -> {
          val release = appUpdateState.release
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFEFF6FF),
            border = BorderStroke(1.5.dp, Color(0xFF0052CC).copy(alpha = 0.55f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("status_update_available")
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Filled.NewReleases,
                  contentDescription = null,
                  tint = Color(0xFF0052CC),
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = "يتوفر تحديث جديد: الإصدار ${release.versionName}",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF0F172A)
                )
              }

              if (release.releaseNotes.isNotBlank()) {
                Text(
                  text = release.releaseNotes,
                  fontSize = 12.sp,
                  lineHeight = 18.sp,
                  color = Color(0xFF334155)
                )
              }

              if (release.downloadUrl.isNotBlank()) {
                Button(
                  onClick = {
                    val intent = AppUpdateChecker.buildSafeExternalUpdateIntent(release.downloadUrl)
                    if (intent != null) {
                      try {
                        context.startActivity(intent)
                      } catch (_: Exception) {
                        Toast.makeText(
                          context,
                          "تعذر فتح رابط التحديث على هذا الجهاز",
                          Toast.LENGTH_SHORT
                        ).show()
                      }
                    }
                  },
                  colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF059669),
                    contentColor = Color.White
                  ),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("btn_open_update_release")
                ) {
                  Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "فتح صفحة التحديث وتحميل الإصدار الجديد",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }
        }

        is AppUpdateCheckState.NeedsSourceUrl -> {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFD97706).copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color(0xFFD97706).copy(alpha = 0.45f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("status_update_needs_source")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = Color(0xFFB45309),
                modifier = Modifier.size(20.dp)
              )
              Text(
                text = appUpdateState.message,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFF92400E)
              )
            }
          }
        }

        is AppUpdateCheckState.Error -> {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFEF2F2),
            border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("status_update_error")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFDC2626),
                modifier = Modifier.size(20.dp)
              )
              Text(
                text = appUpdateState.message,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFF991B1B)
              )
            }
          }
        }
      }

      // Primary "Check for Updates" button
      Button(
        onClick = onCheckForAppUpdates,
        enabled = !isChecking,
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF0052CC),
          contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("btn_check_app_updates")
      ) {
        if (isChecking) {
          CircularProgressIndicator(
            color = Color.White,
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
          )
        } else {
          Icon(
            imageVector = Icons.Filled.SystemUpdate,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (isChecking) "جاري الفحص..." else stringResource(id = R.string.btn_check_app_updates),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
      }

      // Collapsible Update Source Configuration
      AnimatedVisibility(visible = isSourceConfigExpanded) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Text(
              text = "رابط مصدر التحديثات (GitHub Releases أو رابط JSON)",
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "بعد إجراء أي تعديل أو إصلاح هنا وبناء إصدار جديد (APK/AAB)، انشره في GitHub Releases أو عبر رابط JSON ثم احفظ الرابط هنا ليصل الإشعار بالتحديث داخل التطبيق مباشرة مع الحفاظ على بياناتك.",
              fontSize = 11.5.sp,
              lineHeight = 16.5.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
              value = sourceUrlInput,
              onValueChange = { sourceUrlInput = it },
              label = { Text("رابط GitHub أو ملف update.json") },
              placeholder = { Text("مثال: https://github.com/user/repo") },
              leadingIcon = {
                Icon(Icons.Outlined.Link, contentDescription = null)
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              singleLine = true,
              colors = darkSlateTextFieldColors(),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("input_update_source_url")
            )

            OutlinedButton(
              onClick = { onSaveUpdateSourceUrl(sourceUrlInput) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("btn_save_update_source_url")
            ) {
              Text(
                text = "حفظ رابط مصدر التحديثات",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }
    }
  }
}
