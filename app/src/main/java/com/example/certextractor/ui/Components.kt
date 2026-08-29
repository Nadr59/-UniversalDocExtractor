package com.example.certextractor.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.ui.theme.ErrorRed
import com.example.certextractor.ui.theme.SuccessGreen

@Composable
fun FileSelectionCard(
    selectedCount: Int,
    onSelectFiles: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedCount == 0) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Select images or documents", style = MaterialTheme.typography.titleMedium)
                Text(
                    "JPG - PNG - WEBP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = SuccessGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("$selectedCount files selected", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelectFiles) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (selectedCount == 0) "Select Files" else "Add More")
                }
                if (selectedCount > 0) {
                    OutlinedButton(onClick = onClear) {
                        Text("Clear All")
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreviewRow(uris: List<Uri>) {
    Column {
        Text(
            "Preview",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            uris.take(5).forEach { uri ->
                Card(modifier = Modifier.size(72.dp), shape = RoundedCornerShape(8.dp)) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            if (uris.size > 5) {
                Card(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "+" + (uris.size - 5).toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExtractionModeToggle(
    mode: ExtractionMode,
    onModeChange: (ExtractionMode) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ExtractionMode.FIELDS,
            onClick = { onModeChange(ExtractionMode.FIELDS) },
            label = { Text("Defined Fields") },
            leadingIcon = {
                if (mode == ExtractionMode.FIELDS) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.List, null, Modifier.size(18.dp))
                }
            },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = mode == ExtractionMode.FREE_TEXT,
            onClick = { onModeChange(ExtractionMode.FREE_TEXT) },
            label = { Text("Free Text") },
            leadingIcon = {
                if (mode == ExtractionMode.FREE_TEXT) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                }
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Extraction Fields", style = MaterialTheme.typography.titleMedium)
            Text(
                "Toggle fields on/off as needed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            fields.forEachIndexed { index, field ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = field.enabled,
                        onCheckedChange = { onToggle(field.id) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            field.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (field.enabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (field.description.isNotBlank()) {
                            Text(
                                field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { onEdit(field) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemove(field.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(20.dp),
                            tint = ErrorRed
                        )
                    }
                }
                if (index < fields.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add New Field")
            }
        }
    }
}

@Composable
fun FreeTextSection(
    prompt: String,
    onPromptChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Describe what to extract", style = MaterialTheme.typography.titleMedium)
            Text(
                "Write any extraction request",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Extract student names and grades") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun ProcessButton(
    isProcessing: Boolean,
    fileCount: Int,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isProcessing && fileCount > 0,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text("Processing...", style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Extract Data (" + fileCount.toString() + " files)",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun StatusCard(
    isProcessing: Boolean,
    progress: Int,
    total: Int,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isProcessing && total > 0) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surface,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isProcessing)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ResultsHeader(
    totalCount: Int,
    successCount: Int,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Results", style = MaterialTheme.typography.titleLarge)
            Text(
                successCount.toString() + " of " + totalCount.toString() + " succeeded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(onClick = onExport) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Export CSV")
        }
    }
}

@Composable
fun ResultCard(result: ExtractionResult) {
    val isSuccess: Boolean = result.status == "success"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (isSuccess) SuccessGreen else ErrorRed,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(result.fileName, style = MaterialTheme.typography.titleMedium)
            }

            if (isSuccess && result.values.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                result.values.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "$key:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 80.dp, max = 140.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = value.ifBlank { "---" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value.isBlank())
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (!isSuccess && result.errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    result.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed
                )
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
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank()
            ) {
                Text(text = if (existingField != null) "Update" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
        title = {
            Text(
                text = if (existingField != null) "Edit Field" else "Add New Field"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Field Name") },
                    placeholder = { Text("Invoice Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("Reference number on invoice") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
