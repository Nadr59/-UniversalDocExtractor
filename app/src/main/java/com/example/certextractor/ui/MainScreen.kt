package com.example.certextractor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.ui.theme.*
import com.example.certextractor.utils.CsvExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DocumentViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            viewModel.setDocuments(uris)
        }
    }

    val csvSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            CsvExporter.writeCsvToUri(context = context, uri = it, results = uiState.results, fields = uiState.fields)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مستخرج البيانات الذكي", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                FileSelectionCard(
                    selectedCount = uiState.selectedUris.size,
                    onSelectFiles = { filePicker.launch(arrayOf("image/*")) },
                    onClear = { viewModel.clearAll() }
                )
            }

            if (uiState.selectedUris.isNotEmpty()) {
                item { ImagePreviewRow(uris = uiState.selectedUris) }
            }

            item {
                ExtractionModeToggle(
                    mode = uiState.extractionMode,
                    onModeChange = { viewModel.setExtractionMode(it) }
                )
            }

            when (uiState.extractionMode) {
                ExtractionMode.FIELDS -> {
                    item {
                        FieldsSection(
                            fields = uiState.fields,
                            onToggle = { viewModel.toggleField(it) },
                            onRemove = { viewModel.removeField(it) },
                            onEdit = { viewModel.startEditingField(it) },
                            onAdd = { viewModel.showAddFieldDialog() }
                        )
                    }
                }
                ExtractionMode.FREE_TEXT -> {
                    item {
                        FreeTextSection(
                            prompt = uiState.freeTextPrompt,
                            onPromptChange = { viewModel.setFreeTextPrompt(it) }
                        )
                    }
                }
            }

            item {
                ProcessButton(
                    isProcessing = uiState.isProcessing,
                    fileCount = uiState.selectedUris.size,
                    onClick = { viewModel.processDocuments() }
                )
            }

            if (uiState.isProcessing || uiState.message.isNotEmpty()) {
                item {
                    StatusCard(
                        isProcessing = uiState.isProcessing,
                        progress = uiState.progress,
                        total = uiState.total,
                        message = uiState.message
                    )
                }
            }

            if (uiState.results.isNotEmpty()) {
                item {
                    ResultsHeader(
                        totalCount = uiState.results.size,
                        successCount = uiState.results.count { it.status == "success" },
                        onExport = {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            csvSaver.launch("extracted_$ts.csv")
                        }
                    )
                }

                items(items = uiState.results, key = { it.fileName }) { result ->
                    ResultCard(result = result)
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (uiState.showAddFieldDialog || uiState.editingField != null) {
        FieldDialog(
            existingField = uiState.editingField,
            onDismiss = {  () },
            onConfirm = { name, description ->
                val editing = uiState.editingField
                if (editing != null) {
                    viewModel.updateField(editing.id, name, description)
                } else {
                    viewModel.addField(name, description)
                }
            }
        )
    }
}
