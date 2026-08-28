package com.example.certextractor.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.ui.theme.*

@Composable
fun FileSelectionCard(selectedCount: Int, onSelectFiles: () -> Unit, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedCount == 0) {
                Icon(Icons.Outlined.CloudUpload, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("اختر الصور أو الوثائق", style = MaterialTheme.typography.titleMedium)
                Text("JPG · PNG · WEBP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(40.dp), tint = SuccessGreen)
                Spacer(Modifier.height(8.dp))
                Text("$selectedCount ملف مختار", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelectFiles) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (selectedCount == 0) "اختيار الملفات" else "إضافة ملفات")
                }
                if (selectedCount > 0) {
                    OutlinedButton(onClick = onClear) { Text("مسح الكل") }
                }
            }
        }
    }
}

@Composable
fun ImagePreviewRow(uris: List<Uri>) {
    Column {
        Text("المعاينة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            uris.take(5).forEach { uri ->
                Card(Modifier.size(72.dp), shape = RoundedCornerShape(8.dp)) {
                    AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            if (uris.size > 5) {
                Card(Modifier.size(72.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("+${uris.size - 5}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ExtractionModeToggle(mode: ExtractionMode, onModeChange: (ExtractionMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ExtractionMode.FIELDS,
            onClick = { onModeChange(ExtractionMode.FIELDS) },
            label = { Text("حقول محددة") },
            leadingIcon = {
                if (mode == ExtractionMode.FIELDS) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                else Icon(Icons.Outlined.List, null, Modifier.size(18.dp))
            },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = mode == ExtractionMode.FREE_TEXT,
            onClick = { onModeChange(ExtractionMode.FREE_TEXT) },
            label = { Text("نص حر") },
            leadingIcon = {
                if (mode == ExtractionMode.FREE_TEXT) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                else Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun FieldsSection(
    fields: List<ExtractionField>,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (ExtractionField) -> Unit,
    onAdd: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("الحقول المطلوب استخراجها", style = MaterialTheme.typography.titleMedium)
            Text("فعّل أو عطّل الحقول حسب حاجتك", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            fields.forEachIndexed { index, field ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = field.enabled, onCheckedChange = { onToggle(field.id) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(field.name, style = MaterialTheme.typography.bodyLarge,
                            color = if (field.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (field.description.isNotBlank()) {
                            Text(field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onEdit(field) }) {
                        Icon(Icons.Default.Edit, "تعديل", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onRemove(field.id) }) {
                        Icon(Icons.Default.Delete, "حذف", Modifier.size(20.dp), tint = ErrorRed)
                    }
                }
                if (index < fields.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("إضافة حقل جديد")
            }
        }
    }
}

@Composable
fun FreeTextSection(prompt: String, onPromptChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("اكتب ما تريد استخراجه", style = MaterialTheme.typography.titleMedium)
            Text("يمكنك كتابة أي طلب باللغة العربية", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("مثال: استخرج أسماء الطلاب ودرجاتهم ورتبهم") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun ProcessButton(isProcessing: Boolean, fileCount: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isProcessing && fileCount > 0,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isProcessing) {
            CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
            Text("جاري المعالجة...", style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("استخراج البيانات ($fileCount ملف)", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun StatusCard(isProcessing: Boolean, progress: Int, total: Int, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isProcessing && total > 0) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surface,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = if (isProcessing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ResultsHeader(totalCount: Int, successCount: Int, onExport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("النتائج", style = MaterialTheme.typography.titleLarge)
            Text("$successCount من $totalCount بنجاح", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onExport) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(4.dp))
            Text("تصدير CSV")
        }
    }
}

@Composable
fun ResultCard(result: ExtractionResult) {
    val isSuccess = result.status == "success"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel, null,
                    tint = if (isSuccess) SuccessGreen else ErrorRed, modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(result.fileName, style = MaterialTheme.typography.titleMedium)
            }
            if (isSuccess && result.values.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                result.values.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("$key:", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 80.dp, max = 140.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium,
                            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (!isSuccess && result.errorMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(result.errorMessage, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
            }
        }
    }
}

@Composable
fun FieldDialog(
    existingField: ExtractionField?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf(existingField?.name ?: "") }
    var description by remember { mutableStateOf(existingField?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingField != null) "تعديل الحقل" else "إضافة حقل جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الحقل") },
                    placeholder = { Text("مثال: رقم الفاتورة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("وصف الحقل (اختياري)") },
                    placeholder = { Text("مثال: الرقم المرجعي للفواتير") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, description) }, enabled = name.isNotBlank()) {
                Text(if (existingField != null) "تحديث" else "إضافة")
            }
        },
          = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
