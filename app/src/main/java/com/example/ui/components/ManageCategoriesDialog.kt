package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.DeliveryCategory

@Composable
fun ManageCategoriesDialog(
  categories: List<DeliveryCategory>,
  onAddCategory: (name: String, colorHex: String) -> Unit,
  onRenameCategory: (categoryId: String, newName: String) -> Unit,
  onDeleteCategory: (categoryId: String) -> Unit,
  onMoveUp: (categoryId: String) -> Unit,
  onMoveDown: (categoryId: String) -> Unit,
  onResetDefaults: () -> Unit,
  onDismiss: () -> Unit
) {
  var newCategoryName by remember { mutableStateOf("") }
  var selectedColorHex by remember { mutableStateOf(DeliveryCategory.PRESET_COLORS.first()) }
  var editingCategoryId by remember { mutableStateOf<String?>(null) }
  var editingCategoryName by remember { mutableStateOf("") }
  var categoryToDelete by remember { mutableStateOf<DeliveryCategory?>(null) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = Color.White,
      shadowElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth(0.95f)
        .fillMaxHeight(0.85f)
        .testTag("manage_categories_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(20.dp)
      ) {
        // Header
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
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFFEFF6FF)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = Color(0xFF0052CC),
                modifier = Modifier.size(20.dp)
              )
            }
            Column {
              Text(
                text = "إدارة وتخصيص القوائم",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
              )
              Text(
                text = "إضافة قوائم، تغيير أسمائها، ترتيبها أو حذفها",
                fontSize = 11.5.sp,
                color = Color(0xFF64748B)
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("btn_close_manage_categories")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "إغلاق",
              tint = Color(0xFF64748B)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Divider(color = Color(0xFFF1F5F9))
        Spacer(modifier = Modifier.height(14.dp))

        // Section: Add New Category
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFFF8FAFC),
          border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Text(
              text = "إضافة قائمة تصنيف جديدة",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1E293B)
            )

            OutlinedTextField(
              value = newCategoryName,
              onValueChange = { newCategoryName = it },
              placeholder = { Text("اسم القائمة الجديدة (مثال: مستعجل، منطقة الشمال...)", fontSize = 12.sp, color = Color(0xFF94A3B8)) },
              singleLine = true,
              textStyle = TextStyle(
                color = Color(0xFF1E293B),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
              ),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFF1E293B),
                unfocusedTextColor = Color(0xFF1E293B),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = Color(0xFF0052CC),
                unfocusedBorderColor = Color(0xFFCBD5E1),
                cursorColor = Color(0xFF1E293B)
              ),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("input_new_category_name")
            )

            // Color Selector
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "لون القائمة:",
                fontSize = 12.sp,
                color = Color(0xFF475569)
              )
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                DeliveryCategory.PRESET_COLORS.forEach { colorHex ->
                  val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(Color.Gray)
                  val isSelected = selectedColorHex.equals(colorHex, ignoreCase = true)
                  Box(
                    modifier = Modifier
                      .size(24.dp)
                      .clip(CircleShape)
                      .background(color)
                      .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) Color(0xFF0F172A) else Color.White,
                        shape = CircleShape
                      )
                      .clickable { selectedColorHex = colorHex },
                    contentAlignment = Alignment.Center
                  ) {
                    if (isSelected) {
                      Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                      )
                    }
                  }
                }
              }
            }

            Button(
              onClick = {
                if (newCategoryName.isNotBlank()) {
                  onAddCategory(newCategoryName.trim(), selectedColorHex)
                  newCategoryName = ""
                }
              },
              enabled = newCategoryName.isNotBlank(),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0052CC),
                contentColor = Color.White
              ),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .testTag("btn_add_category_submit")
            ) {
              Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("إضافة القائمة", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Existing Categories List Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "القوائم الحالية (${categories.size}):",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF334155)
          )
          TextButton(
            onClick = onResetDefaults,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF64748B))
            Spacer(modifier = Modifier.width(4.dp))
            Text("الافتراضية", fontSize = 11.5.sp, color = Color(0xFF64748B))
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Categories List
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          itemsIndexed(categories, key = { _, cat -> cat.id }) { index, category ->
            val isEditing = editingCategoryId == category.id

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color(0xFFFAFAFA),
              border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("category_row_${category.id}")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                // Color Dot & Name
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Box(
                    modifier = Modifier
                      .size(14.dp)
                      .clip(CircleShape)
                      .background(category.primaryColor)
                  )

                  if (isEditing) {
                    OutlinedTextField(
                      value = editingCategoryName,
                      onValueChange = { editingCategoryName = it },
                      singleLine = true,
                      textStyle = TextStyle(
                        color = Color(0xFF1E293B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                      ),
                      colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF1E293B),
                        unfocusedTextColor = Color(0xFF1E293B),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color(0xFF0052CC),
                        unfocusedBorderColor = Color(0xFFCBD5E1),
                        cursorColor = Color(0xFF1E293B)
                      ),
                      shape = RoundedCornerShape(8.dp),
                      modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("input_rename_category_${category.id}")
                    )
                  } else {
                    Text(
                      text = category.name,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF1E293B)
                    )
                  }
                }

                // Actions: Edit/Save, Move Up, Move Down, Delete
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  if (isEditing) {
                    IconButton(
                      onClick = {
                        if (editingCategoryName.isNotBlank()) {
                          onRenameCategory(category.id, editingCategoryName.trim())
                        }
                        editingCategoryId = null
                      },
                      modifier = Modifier
                        .size(34.dp)
                        .testTag("btn_save_rename_${category.id}")
                    ) {
                      Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "حفظ الاسم",
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(18.dp)
                      )
                    }
                  } else {
                    IconButton(
                      onClick = {
                        editingCategoryId = category.id
                        editingCategoryName = category.name
                      },
                      modifier = Modifier
                        .size(34.dp)
                        .testTag("btn_edit_category_${category.id}")
                    ) {
                      Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل الاسم",
                        tint = Color(0xFF0052CC),
                        modifier = Modifier.size(16.dp)
                      )
                    }
                  }

                  // Move Up
                  IconButton(
                    onClick = { onMoveUp(category.id) },
                    enabled = index > 0,
                    modifier = Modifier
                      .size(34.dp)
                      .testTag("btn_move_up_${category.id}")
                  ) {
                    Icon(
                      imageVector = Icons.Default.KeyboardArrowUp,
                      contentDescription = "تقديم للأعلى",
                      tint = if (index > 0) Color(0xFF475569) else Color(0xFFCBD5E1),
                      modifier = Modifier.size(18.dp)
                    )
                  }

                  // Move Down
                  IconButton(
                    onClick = { onMoveDown(category.id) },
                    enabled = index < categories.size - 1,
                    modifier = Modifier
                      .size(34.dp)
                      .testTag("btn_move_down_${category.id}")
                  ) {
                    Icon(
                      imageVector = Icons.Default.KeyboardArrowDown,
                      contentDescription = "تأخير للأسفل",
                      tint = if (index < categories.size - 1) Color(0xFF475569) else Color(0xFFCBD5E1),
                      modifier = Modifier.size(18.dp)
                    )
                  }

                  // Delete
                  IconButton(
                    onClick = { categoryToDelete = category },
                    modifier = Modifier
                      .size(34.dp)
                      .testTag("btn_delete_category_${category.id}")
                  ) {
                    Icon(
                      imageVector = Icons.Default.Delete,
                      contentDescription = "حذف القائمة",
                      tint = Color(0xFFDC2626),
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Done / Close button
        Button(
          onClick = onDismiss,
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF0F172A),
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .testTag("btn_done_manage_categories")
        ) {
          Text("تم وحفظ التعديلات", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }

  // Delete Confirmation Dialog
  categoryToDelete?.let { cat ->
    AlertDialog(
      onDismissRequest = { categoryToDelete = null },
      title = {
        Text("حذف قائمة (${cat.name})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
      },
      text = {
        Text(
          "هل أنت متأكد من حذف هذه القائمة؟ لن يتم حذف الطلبات الموجودة فيها وإنما ستظل في قائمة (الكل).",
          fontSize = 13.sp,
          color = Color(0xFF475569)
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteCategory(cat.id)
            categoryToDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          Text("نعم، حذف القائمة", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { categoryToDelete = null }) {
          Text("إلغاء", color = Color(0xFF64748B))
        }
      },
      containerColor = Color.White,
      shape = RoundedCornerShape(16.dp)
    )
  }
}
