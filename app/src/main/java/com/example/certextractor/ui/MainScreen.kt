package com.example.certextractor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.certextractor.utils.ExcelExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DocumentViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (uiState.showSettings) {
        SettingsScreen(
            settings = viewModel.getAiSettings(),
            onBack = { viewModel.hideSettings() }
        )
        return
    }

    /*
     * اختيار الصور.
     *
     * إذا لم تكن هناك صور:
     *     تصبح الصور المختارة هي القائمة الجديدة.
     *
     * إذا كانت هناك صور:
     *     تتم إضافة الصور الجديدة إليها.
     *
     * كما يتم منع تكرار نفس Uri.
     */
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->

        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }

        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // بعض مزودي الملفات لا يسمحون بالصلاحية الدائمة.
            }
        }

        val currentUris = uiState.selectedUris

        val mergedUris = buildList {
            addAll(currentUris)

            uris.forEach { newUri ->
                if (!contains(newUri)) {
                    add(newUri)
                }
            }
        }

        viewModel.setDocuments(mergedUris)
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

    val excelSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.ms-excel"
        )
    ) { uri: Uri? ->

        uri?.let {
            ExcelExporter.writeExcelToUri(
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
                        text = "Document Extractor",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.showSettings()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "Settings"
                        )
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

            contentPadding = PaddingValues(
                vertical = 16.dp
            )
        ) {

            /*
             * إعداد مزود الذكاء الاصطناعي
             */
            if (!settings.isConfigured()) {

                item {
                    SetupPromptCard(
                        onSetup = {
                            viewModel.showSettings()
                        }
                    )
                }

            } else {

                item {
                    ActiveProviderCard(
                        provider = settings.provider,
                        model = settings.getActiveModel()
                    )
                }
            }

            /*
             * اختيار الصور
             */
            item {

                FileSelectionCard(
                    selectedCount = uiState.selectedUris.size,

                    onSelectFiles = {
                        /*
                         * نفس زر الاختيار يستخدم أيضًا كزر
                         * "إضافة المزيد" عندما تكون هناك صور.
                         *
                         * النتيجة الجديدة ستُضاف إلى القائمة
                         * الحالية في callback أعلاه.
                         */
                        filePicker.launch(
                            arrayOf("image/*")
                        )
                    },

                    onClear = {
                        viewModel.clearAll()
                    }
                )
            }

            /*
             * معاينة الصور المختارة
             */
            if (uiState.selectedUris.isNotEmpty()) {

                item {

                    ImagePreviewRow(
                        uris = uiState.selectedUris
                    )
                }
            }

            /*
             * وضع الاستخراج
             */
            item {

                ExtractionModeToggle(
                    mode = uiState.extractionMode,

                    onModeChange = {
                        viewModel.setExtractionMode(it)
                    }
                )
            }

            /*
             * الحقول الديناميكية
             */
            if (uiState.extractionMode == ExtractionMode.FIELDS) {

                item {

                    FieldsSection(
                        fields = uiState.fields,

                        onToggle = {
                            viewModel.toggleField(it)
                        },

                        onRemove = {
                            viewModel.removeField(it)
                        },

                        onEdit = {
                            viewModel.startEditingField(it)
                        },

                        onAdd = {
                            viewModel.showAddFieldDialog()
                        }
                    )
                }

            } else {

                item {

                    FreeTextSection(
                        prompt = uiState.freeTextPrompt,

                        onPromptChange = {
                            viewModel.setFreeTextPrompt(it)
                        }
                    )
                }
            }

            /*
             * زر بدء الاستخراج
             */
            item {

                ProcessButton(
                    isProcessing = uiState.isProcessing,

                    fileCount = uiState.selectedUris.size,

                    onClick = {
                        viewModel.processDocuments()
                    }
                )
            }

            /*
             * ==========================================
             * شريط تقدم المعالجة
             * ==========================================
             *
             * يتم تحديثه مباشرة من:
             *
             * uiState.progress
             * uiState.total
             *
             * لذلك سيظهر التقدم أثناء وصول النتائج.
             */
            if (
                uiState.isProcessing ||
                uiState.message.isNotEmpty()
            ) {

                item {

                    ProcessingProgressCard(
                        isProcessing = uiState.isProcessing,
                        progress = uiState.progress,
                        total = uiState.total,
                        message = uiState.message,
                        resultsCount = uiState.results.size
                    )
                }
            }

            /*
             * النتائج
             */
            if (uiState.results.isNotEmpty()) {

                item {

                    ResultsHeader(
                        totalCount = uiState.results.size,

                        successCount = uiState.results.count {
                            it.status == "success"
                        },

                        onExportCsv = {

                            val ts = SimpleDateFormat(
                                "yyyyMMdd_HHmmss",
                                Locale.getDefault()
                            ).format(Date())

                            csvSaver.launch(
                                "extracted_$ts.csv"
                            )
                        },

                        onExportExcel = {

                            val ts = SimpleDateFormat(
                                "yyyyMMdd_HHmmss",
                                Locale.getDefault()
                            ).format(Date())

                            excelSaver.launch(
                                "extracted_$ts.xls"
                            )
                        }
                    )
                }

                items(
                    items = uiState.results
                ) { result ->

                    ResultCard(
                        result = result
                    )
                }
            }

            item {
                Spacer(
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }

    /*
     * حوار إضافة/تعديل الحقول
     */
    if (
        uiState.showAddFieldDialog ||
        uiState.editingField != null
    ) {

        FieldDialog(
            existingField = uiState.editingField,

            onDismiss = {
                viewModel.dismissDialog()
            },

            onConfirm = { name, description ->

                val editing =
                    uiState.editingField

                if (editing != null) {

                    viewModel.updateField(
                        editing.id,
                        name,
                        description
                    )

                } else {

                    viewModel.addField(
                        name,
                        description
                    )
                }
            }
        )
    }
}


/**
 * بطاقة تقدم مستقلة.
 *
 * لا تعتمد على StatusCard القديمة،
 * حتى نضمن أن شريط التقدم ظاهر فعليًا.
 */
@Composable
private fun ProcessingProgressCard(
    isProcessing: Boolean,
    progress: Int,
    total: Int,
    message: String,
    resultsCount: Int
) {

    val safeTotal =
        if (total > 0) total else 1

    val safeProgress =
        progress.coerceIn(
            0,
            safeTotal
        )

    val progressValue =
        safeProgress.toFloat() / safeTotal.toFloat()

    Card(
        modifier = Modifier
            .fillMaxWidth(),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            if (isProcessing) {

                Text(
                    text = "جاري معالجة الصور",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "$safeProgress / $total",
                    style = MaterialTheme.typography.headlineSmall
                )

                LinearProgressIndicator(
                    progress = {
                        progressValue
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                )

                Text(
                    text = "${(progressValue * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (resultsCount > 0) {

                    Text(
                        text =
                            "تم استخراج بيانات $resultsCount صورة حتى الآن",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (message.isNotBlank()) {

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            } else {

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (resultsCount > 0) {

                    Text(
                        text =
                            "إجمالي النتائج: $resultsCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
