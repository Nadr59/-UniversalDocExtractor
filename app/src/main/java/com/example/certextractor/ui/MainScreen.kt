package com.example.certextractor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.certextractor.utils.CsvExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DocumentViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (uiState.showSettings) {
        SettingsScreen(
            settings = viewModel.getAiSettings(),
            onBack = { viewModel.hideSettings() }
        )
        return
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
            }
            viewModel.setDocuments(uris)
        }
    }

    val csvSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            CsvExporter.writeCsvToUri(
                context = context,
                uri = it,
                results = uiState.results,
                fields = uiState.fields
            )
        }
    }

    val settings = viewModel.getAiSettings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Document Extractor",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.showSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            if (!settings.isConfigured()) {
                item {
                    SetupPromptCard(onSetup = { viewModel.showSettings() })
                }
            } else {
                item {
                    ActiveProviderCard(
                        provider = settings.provider,
                        model = settings.getActiveModel()
                    )
                }
            }

            item {
                FileSelectionCard(
                    selectedCount = uiState.selectedUris.size,
                    onSelectFiles = { filePicker.launch(arrayOf("image/*")) },
                    onClear = { viewModel.clearAll() }
                )
            }

            if (uiState.selectedUris.isNotEmpty()) {
                item {
                    ImagePreviewRow(uris = uiState.selectedUris)
                }
            }

            item {
                ExtractionModeToggle(
                    mode = uiState.extractionMode,
                    onModeChange = { viewModel.setExtractionMode(it) }
                )
            }

            if (uiState.extractionMode == ExtractionMode.FIELDS) {
                item {
                    FieldsSection(
                        fields = uiState.fields,
                        onToggle = { viewModel.toggleField(it) },
                        onRemove = { viewModel.removeField(it) },
                        onEdit = { viewModel.startEditingField(it) },
                        onAdd = { viewModel.showAddFieldDialog() }
                    )
                }
            } else {
                item {
                    FreeTextSection(
                        prompt = uiState.freeTextPrompt,
                        onPromptChange = { viewModel.setFreeTextPrompt(it) }
                    )
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

                items(uiState.results) { result ->
                    ResultCard(result = result)
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (uiState.showAddFieldDialog || uiState.editingField != null) {
        FieldDialog(
            existingField = uiState.editingField,
            onDismiss = { viewModel.dismissDialog() },
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
