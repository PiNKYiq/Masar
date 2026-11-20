package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeliveryStatus
import com.example.model.DeliveryCategory
import com.example.model.DeliveryStop
import com.example.model.NoteIconType
import com.example.model.SuspiciousCustomer
import androidx.compose.material.icons.filled.Warning

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeliveryLocationCard(
  stop: DeliveryStop,
  onClick: () -> Unit,
  onDelete: (() -> Unit)? = null,
  onRestore: (() -> Unit)? = null,
  onPermanentDelete: (() -> Unit)? = null,
  onStatusChange: ((DeliveryStatus) -> Unit)? = null,
  categories: List<DeliveryCategory> = emptyList(),
  onCategoryChange: ((DeliveryCategory) -> Unit)? = null,
  isSelectionMode: Boolean = false,
  isSelected: Boolean = false,
  onToggleSelection: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
  matchedSuspicious: SuspiciousCustomer? = null,
  modifier: Modifier = Modifier
) {
  val status = stop.status
  val activeCategory = categories.firstOrNull { it.id == stop.categoryId }
  val badgeLabel = if (stop.categoryName.isNotBlank()) stop.categoryName else (activeCategory?.name ?: status.badgeText)
  val effectivePrimaryColor = activeCategory?.primaryColor ?: status.primaryColor
  val effectiveBorderColor = activeCategory?.borderColor ?: status.borderColor
  val effectiveContainerColor = activeCategory?.containerColor ?: status.containerColor

  var showDeleteDialog by remember { mutableStateOf(false) }
  var showPermanentDeleteDialog by remember { mutableStateOf(false) }
  var showStatusChangeDialog by remember { mutableStateOf(false) }

  val context = LocalContext.current
  val enteredOrderNumber = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
  val customerPhone = stop.phone1.ifBlank { stop.phone2 }.trim()

  val cardContainerColor = if (isSelected) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
  } else {
    MaterialTheme.colorScheme.surface
  }
  val cardBorderWidth = if (isSelected) 2.dp else 1.dp
  val cardBorderColor = if (isSelected) {
    MaterialTheme.colorScheme.primary
  } else {
    effectiveBorderColor.copy(alpha = 0.85f)
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .testTag("delivery_card_${stop.id}")
      .combinedClickable(
        onClick = {
          if (isSelectionMode) {
            onToggleSelection?.invoke()
          } else {
            onClick()
          }
        },
        onLongClick = {
          onLongClick?.invoke()
        }
      ),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = cardContainerColor
    ),
    border = CardDefaults.outlinedCardBorder().copy(
      width = cardBorderWidth,
      brush = androidx.compose.ui.graphics.SolidColor(cardBorderColor)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // 1. Top Row: Status / Category Filter Badge + Delete Button on the left | Order Number on the right
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Status / Category Filter Badge (فلتر تصفية الطلبات - قيد التوصيل) + Delete Button (زر الحذف)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (stop.isDeleted) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = Color(0xFFFEE2E2),
              border = BorderStroke(1.dp, Color(0xFFFCA5A5))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(13.dp))
                Text(
                  text = "طلب محذوف",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFDC2626),
                  maxLines = 1,
                  softWrap = false
                )
              }
            }
          } else {
            // Filter / Category Badge (فلتر تصفية الطلبات - قيد التوصيل)
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = effectiveBorderColor.copy(alpha = 0.25f),
              border = BorderStroke(1.dp, effectiveBorderColor.copy(alpha = 0.7f)),
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(
                  if (onStatusChange != null || onCategoryChange != null) Modifier.clickable { showStatusChangeDialog = true } else Modifier
                )
                .testTag("status_badge_${stop.id}")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
              ) {
                Icon(
                  imageVector = when (status) {
                    DeliveryStatus.DELIVERED -> Icons.Outlined.CheckCircle
                    DeliveryStatus.DELAYED -> Icons.Outlined.Inventory2
                    DeliveryStatus.RETURNED -> Icons.Outlined.Inventory
                    DeliveryStatus.LINE_ISSUES -> Icons.Outlined.AltRoute
                    DeliveryStatus.IN_TRANSIT -> Icons.Outlined.LocalShipping
                  },
                  contentDescription = null,
                  tint = effectivePrimaryColor,
                  modifier = Modifier.size(13.dp)
                )
                Text(
                  text = badgeLabel,
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = effectivePrimaryColor,
                  maxLines = 1,
                  softWrap = false
                )
                if (onStatusChange != null || onCategoryChange != null) {
                  Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = "تغيير تصنيف الحالة",
                    tint = effectivePrimaryColor,
                    modifier = Modifier.size(14.dp)
                  )
                }
              }
            }

            // Quick Delete Button (زر الحذف)
            if (onDelete != null) {
              Surface(
                shape = CircleShape,
                color = Color(0xFFFEE2E2),
                modifier = Modifier
                  .size(28.dp)
                  .clip(CircleShape)
                  .clickable { showDeleteDialog = true }
                  .testTag("btn_delete_card_${stop.id}")
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "حذف الطلب",
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(14.dp)
                  )
                }
              }
            }
          }
        }

        // Right Side (in RTL): Selection indicator (when active) + Order Number Badge
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (isSelectionMode) {
            Box(
              modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                  width = 1.5.dp,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                  shape = CircleShape
                )
                .clickable { onToggleSelection?.invoke() }
                .testTag("checkbox_card_${stop.id}"),
              contentAlignment = Alignment.Center
            ) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "محدد",
                  tint = Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }

          // Order Number Badge (رقم الطلب) - Prominently on one full line
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFFEFF6FF),
            border = BorderStroke(1.2.dp, Color(0xFF2563EB)),
            modifier = Modifier.testTag("prominent_order_id_${stop.id}")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              Icon(
                imageVector = Icons.Outlined.ReceiptLong,
                contentDescription = "رقم الطلب",
                tint = Color(0xFF2563EB),
                modifier = Modifier.size(14.dp)
              )
              Text(
                text = if (enteredOrderNumber.isNotBlank()) enteredOrderNumber else "رقم غير محدد",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1D4ED8),
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }
      }

      if (matchedSuspicious != null) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFFFEF2F2),
          border = BorderStroke(1.dp, Color(0xFFDC2626)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("card_suspicious_badge_${stop.id}")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Filled.Warning,
              contentDescription = "زبون مشبوه",
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = "تحذير: زبون مشبوه — ${matchedSuspicious.note}",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF991B1B)
            )
          }
        }
      }

      // 2. Middle Row: Address (العنوان بخط كبير و واضح)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          imageVector = Icons.Filled.LocationOn,
          contentDescription = null,
          tint = Color(0xFFEA580C),
          modifier = Modifier.size(19.dp)
        )
        Text(
          text = if (stop.title.isNotBlank()) stop.title else "بدون عنوان",
          fontSize = 16.5.sp,
          fontWeight = FontWeight.ExtraBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          lineHeight = 22.sp,
          modifier = Modifier.weight(1f)
        )
      }

      // 3. Bottom Row: Customer Phone Number (رقم الزبون) & Order Price (سعر الطلب)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // Customer Phone Number & WhatsApp
        Row(
          modifier = Modifier.weight(1f).padding(end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (customerPhone.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
              modifier = Modifier.clickable {
                com.example.util.CallerAddressHelper.launchDialWithAddressOverlay(context, customerPhone)
              }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
              ) {
                Icon(
                  imageVector = Icons.Outlined.Phone,
                  contentDescription = "اتصال بالزبون",
                  tint = Color(0xFF059669),
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = customerPhone,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }

            // Direct WhatsApp quick action icon
            Surface(
              shape = CircleShape,
              color = Color(0xFFDCFCE7),
              border = BorderStroke(0.5.dp, Color(0xFF86EFAC)),
              modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable {
                  try {
                    val cleanNum = customerPhone.filter { it.isDigit() }
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanNum"))
                    context.startActivity(waIntent)
                  } catch (e: Exception) {
                    // Ignore
                  }
                }
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Outlined.Chat,
                  contentDescription = "مراسلة واتساب",
                  tint = Color(0xFF16A34A),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          } else {
            Text(
              text = "رقم هاتف غير مسجل",
              fontSize = 11.5.sp,
              color = Color(0xFF94A3B8),
              maxLines = 1,
              softWrap = false
            )
          }
        }

        // Order Price (سعر الطلب - أرقام فقط بدون رمز العملة)
        val displayPrice = com.example.util.OcrHelper.cleanPriceNumber(stop.price)
        if (displayPrice.isNotBlank()) {
          val isNegativePrice = displayPrice.startsWith("-")
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isNegativePrice) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
            border = BorderStroke(1.dp, if (isNegativePrice) Color(0xFFFCA5A5) else Color(0xFF86EFAC)),
            modifier = Modifier.testTag("prominent_price_${stop.id}")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = displayPrice,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isNegativePrice) Color(0xFFDC2626) else Color(0xFF166534),
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }
      }

      // Actions for Deleted Stops: Restore & Permanent Delete
      if (stop.isDeleted) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Button(
            onClick = { onRestore?.invoke() },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFFE0F2FE),
              contentColor = Color(0xFF0284C7)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
              .weight(1f)
              .height(34.dp)
              .testTag("btn_restore_${stop.id}")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("استعادة الطلب", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
            }
          }

          Button(
            onClick = { showPermanentDeleteDialog = true },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFFFEE2E2),
              contentColor = Color(0xFFDC2626)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
              .weight(1f)
              .height(34.dp)
              .testTag("btn_permanent_delete_${stop.id}")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("حذف نهائي", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
            }
          }
        }
      }
    }
  }

  // Delete to Trash Confirmation Dialog
  if (showDeleteDialog) {
    val orderIdText = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      icon = {
        Icon(
          imageVector = Icons.Outlined.Delete,
          contentDescription = null,
          tint = Color(0xFFDC2626),
          modifier = Modifier.size(30.dp)
        )
      },
      title = {
        Text(
          text = "تأكيد حذف الطلب",
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Text(
          text = "هل أنت متأكد من رغبتك في نقل الطلب ($orderIdText) إلى سلة المحذوفات؟",
          fontSize = 13.5.sp,
          color = Color(0xFF475569),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showDeleteDialog = false
            onDelete?.invoke()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.testTag("btn_confirm_delete_card_${stop.id}")
        ) {
          Text("نعم، حذف", fontWeight = FontWeight.Bold, color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showDeleteDialog = false },
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // Permanent Delete Confirmation Dialog
  if (showPermanentDeleteDialog) {
    val orderIdText = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
    AlertDialog(
      onDismissRequest = { showPermanentDeleteDialog = false },
      icon = {
        Icon(
          imageVector = Icons.Outlined.DeleteForever,
          contentDescription = null,
          tint = Color(0xFFDC2626),
          modifier = Modifier.size(32.dp)
        )
      },
      title = {
        Text(
          text = "تأكيد الحذف النهائي",
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
          color = Color(0xFFDC2626),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Text(
          text = "هل أنت متأكد من حذف الطلب ($orderIdText) نهائياً؟ لن تتمكن من استعادته لاحقاً.",
          fontSize = 13.5.sp,
          color = Color(0xFF475569),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showPermanentDeleteDialog = false
            onPermanentDelete?.invoke()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.testTag("btn_confirm_perm_delete_${stop.id}")
        ) {
          Text("حذف نهائي", fontWeight = FontWeight.Bold, color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showPermanentDeleteDialog = false },
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // Quick Status Change Dialog directly from Card
  if (showStatusChangeDialog && onStatusChange != null) {
    val orderIdText = stop.orderCodeNumber.ifBlank { stop.orderNumber }.trim()
    AlertDialog(
      onDismissRequest = { showStatusChangeDialog = false },
      title = {
        Text(
          text = "تصنيف حالة الطلب ($orderIdText)",
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp,
          color = Color(0xFF0F172A),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "اختر الحالة الجديدة للطلب لتحديث البطاقة فوراً والتصفية:",
            fontSize = 12.5.sp,
            color = Color(0xFF64748B),
            textAlign = TextAlign.Start
          )
          Spacer(modifier = Modifier.height(2.dp))
          if (categories.isNotEmpty()) {
            categories.forEach { cat ->
              val isCurrent = cat.id == stop.categoryId || (stop.categoryId.isBlank() && cat.id == stop.status.name)
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isCurrent) cat.containerColor else Color(0xFFF8FAFC),
                border = BorderStroke(
                  width = if (isCurrent) 1.5.dp else 1.dp,
                  color = if (isCurrent) cat.primaryColor else Color(0xFFE2E8F0)
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .clickable {
                    showStatusChangeDialog = false
                    if (onCategoryChange != null) {
                      onCategoryChange.invoke(cat)
                    } else {
                      val matchingStatus = runCatching { DeliveryStatus.valueOf(cat.id) }.getOrNull()
                      if (matchingStatus != null) {
                        onStatusChange?.invoke(matchingStatus)
                      }
                    }
                  }
                  .testTag("category_choice_${cat.id}")
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(cat.dotColor)
                    )
                    Text(
                      text = cat.name,
                      fontSize = 13.sp,
                      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                      color = if (isCurrent) cat.primaryColor else Color(0xFF1E293B)
                    )
                  }
                  if (isCurrent) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = "القائمة الحالية",
                      tint = cat.primaryColor,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }
          } else {
            DeliveryStatus.values().forEach { st ->
              val isCurrent = st == stop.status
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isCurrent) st.containerColor else Color(0xFFF8FAFC),
                border = BorderStroke(
                  width = if (isCurrent) 1.5.dp else 1.dp,
                  color = if (isCurrent) st.primaryColor else Color(0xFFE2E8F0)
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .clickable {
                    showStatusChangeDialog = false
                    onStatusChange?.invoke(st)
                  }
                  .testTag("status_choice_${st.name}")
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(st.dotColor)
                    )
                    Text(
                      text = st.labelAr,
                      fontSize = 13.sp,
                      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                      color = if (isCurrent) st.primaryColor else Color(0xFF1E293B)
                    )
                  }
                  if (isCurrent) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = "الحالة الحالية",
                      tint = st.primaryColor,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showStatusChangeDialog = false }) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(16.dp)
    )
  }
}
