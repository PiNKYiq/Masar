package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.CustomerRecord
import com.example.model.DeliveryStatus
import com.example.model.DeliveryStop
import com.example.model.NoteIconType
import com.example.model.SuspiciousCustomer
import com.example.ui.theme.DarkSlateInputTextStyle
import com.example.ui.theme.darkSlateTextFieldColors
import com.example.util.VibrationHelper
import kotlinx.coroutines.launch

@Composable
fun AddNewAddressDialog(
  onDismiss: () -> Unit,
  onSave: (DeliveryStop) -> Unit,
  onFindCustomerByPhone: (suspend (String) -> CustomerRecord?)? = null,
  suspiciousCustomers: List<SuspiciousCustomer> = emptyList()
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var title by remember { mutableStateOf("") }
  var subtitle by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }
  var phoneNumber by remember { mutableStateOf("") }
  var selectedStatus by remember { mutableStateOf(DeliveryStatus.IN_TRANSIT) }
  var orderNumber by remember { mutableStateOf("ORD-${(9000..9999).random()}#") }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var matchedCustomerNotice by remember { mutableStateOf<String?>(null) }
  var lastVibratedSuspiciousPhone by remember { mutableStateOf<String?>(null) }

  val matchedSuspicious = remember(phoneNumber, suspiciousCustomers) {
    val norm = CustomerRecord.normalizePhoneNumber(phoneNumber)
    if (norm.length >= 7) suspiciousCustomers.firstOrNull { it.phone == norm } else null
  }
  LaunchedEffect(matchedSuspicious?.phone) {
    val p = matchedSuspicious?.phone
    if (p != null && p != lastVibratedSuspiciousPhone) {
      VibrationHelper.vibrateSuspiciousAlert(context)
      lastVibratedSuspiciousPhone = p
    } else if (p == null) {
      lastVibratedSuspiciousPhone = null
    }
  }

  fun searchPhoneAndAutoFill(phoneInput: String) {
    if (onFindCustomerByPhone == null) return
    val clean = CustomerRecord.normalizePhoneNumber(phoneInput)
    if (clean.length >= 7) {
      coroutineScope.launch {
        val found = onFindCustomerByPhone(clean)
        if (found != null) {
          if (title.isBlank()) {
            title = found.writtenAddress
          }
          if (subtitle.isBlank() && found.areaSubtitle.isNotBlank()) {
            subtitle = found.areaSubtitle
          }
          matchedCustomerNotice = "تم سحب العنوان (${found.writtenAddress}) تلقائياً من المزامنة السابقة!"
        } else {
          matchedCustomerNotice = null
        }
      }
    } else {
      matchedCustomerNotice = null
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 16.dp)
        .testTag("add_address_dialog"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "إضافة عنوان توصيل جديد",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E293B)
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "إغلاق")
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = orderNumber,
          onValueChange = { orderNumber = it },
          label = { Text("رقم الطلب") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = darkSlateTextFieldColors(),
          textStyle = DarkSlateInputTextStyle
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = phoneNumber,
          onValueChange = {
            phoneNumber = it
            searchPhoneAndAutoFill(it)
          },
          label = { Text("رقم هاتف الزبون (للسحب التلقائي)") },
          placeholder = { Text("مثال: 07710266414") },
          modifier = Modifier.fillMaxWidth().testTag("input_customer_phone_quick"),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = darkSlateTextFieldColors(),
          textStyle = DarkSlateInputTextStyle
        )

        matchedSuspicious?.let { sus ->
          Spacer(modifier = Modifier.height(6.dp))
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFFEF2F2),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDC2626)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("banner_suspicious_dialog_warning")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(Icons.Default.Warning, contentDescription = "زبون مشبوه", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
              Text(
                text = "تحذير: زبون مشبوه بسرقة الطلبات! (${sus.note})",
                fontSize = 11.5.sp,
                color = Color(0xFF991B1B),
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        matchedCustomerNotice?.let { notice ->
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "🌟 $notice",
            fontSize = 11.5.sp,
            color = Color(0xFF15803D),
            fontWeight = FontWeight.Bold
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = title,
          onValueChange = {
            title = it
            errorMessage = null
          },
          label = { Text("الحي والشارع الرئيسي") },
          placeholder = { Text("مثال: حي الصحافة - شارع العليا") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = darkSlateTextFieldColors(),
          textStyle = DarkSlateInputTextStyle
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = subtitle,
          onValueChange = { subtitle = it },
          label = { Text("تفاصيل العنوان والمبنى") },
          placeholder = { Text("مثال: عمارة النخيل 4، شقة 12") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = darkSlateTextFieldColors(),
          textStyle = DarkSlateInputTextStyle
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text("الملاحظة السفلية") },
          placeholder = { Text("مثال: الاتصال قبل الوصول بـ 10 دقائق") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 2,
          shape = RoundedCornerShape(10.dp),
          colors = darkSlateTextFieldColors(),
          textStyle = DarkSlateInputTextStyle
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "حالة الطلب:",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF1E293B)
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          DeliveryStatus.entries.forEach { st ->
            val isSelected = st == selectedStatus
            FilterChip(
              selected = isSelected,
              onClick = { selectedStatus = st },
              label = {
                Text(
                  text = st.badgeText,
                  fontSize = 10.5.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = st.primaryColor,
                selectedLabelColor = Color.White
              )
            )
          }
        }

        if (errorMessage != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorMessage ?: "",
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp
          )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(onClick = onDismiss) {
            Text("إلغاء", color = Color(0xFF64748B))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (title.isBlank()) {
                errorMessage = "يرجى كتابة اسم الحي والشارع"
                return@Button
              }
              val newStop = DeliveryStop(
                id = System.currentTimeMillis().toString(),
                orderNumber = orderNumber.ifBlank { "ORD-9999#" },
                dateTime = "اليوم، الآن",
                title = title.trim(),
                subtitle = subtitle.ifBlank { "الرياض" }.trim(),
                note = note.ifBlank { "لا توجد ملاحظات إضافية" }.trim(),
                noteIconType = when (selectedStatus) {
                  DeliveryStatus.DELIVERED -> NoteIconType.CHECK
                  DeliveryStatus.DELAYED -> NoteIconType.HISTORY
                  DeliveryStatus.RETURNED -> NoteIconType.INFO
                  DeliveryStatus.LINE_ISSUES -> NoteIconType.ROUTE
                  DeliveryStatus.IN_TRANSIT -> NoteIconType.NAVIGATION
                },
                status = selectedStatus,
                phone1 = phoneNumber.trim(),
                recipientPhone = phoneNumber.ifBlank { "+964 770 000 0000" }
              )
              onSave(newStop)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0052CC)),
            shape = RoundedCornerShape(10.dp)
          ) {
            Text("إضافة العنوان")
          }
        }
      }
    }
  }
}
