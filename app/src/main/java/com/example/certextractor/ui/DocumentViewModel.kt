package com.example.certextractor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.certextractor.data.local.AiSettings
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.data.repository.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ExtractionMode {
    FIELDS,
    FREE_TEXT
}

data class DocumentUiState(
    val fields: List<ExtractionField> = listOf(
        ExtractionField(
            name = "اسم الطالب",
            description = "الاسم الكامل للطالب"
        ),
        ExtractionField(
            name = "رقم القيد",
            description = "رقم القيد أو التسجيل"
        ),
        ExtractionField(
            name = "المدرسة",
            description = "اسم المدرسة"
        ),
        ExtractionField(
            name = "المعدل",
            description = "المعدل أو النسبة"
        ),
        ExtractionField(
            name = "التاريخ",
            description = "تاريخ الإصدار أو السنة الدراسية"
        )
    ),
    val results: List<ExtractionResult> = emptyList(),
    val selectedUris: List<Uri> = emptyList(),
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val extractionMode: ExtractionMode = ExtractionMode.FIELDS,
    val freeTextPrompt: String = "",
    val showAddFieldDialog: Boolean = false,
    val editingField: ExtractionField? = null,
    val showSettings: Boolean = false
)

class DocumentViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)

    private val _uiState = MutableStateFlow(
        DocumentUiState()
    )

    val uiState: StateFlow<DocumentUiState> =
        _uiState.asStateFlow()

    fun getAiSettings(): AiSettings {
        return repository.settings
    }

    fun setDocuments(uris: List<Uri>) {
        _uiState.update {
            it.copy(
                selectedUris = uris
            )
        }
    }

    fun clearAll() {
        _uiState.update {
            it.copy(
                results = emptyList(),
                selectedUris = emptyList(),
                progress = 0,
                total = 0,
                message = ""
            )
        }
    }

    fun setExtractionMode(mode: ExtractionMode) {
        _uiState.update {
            it.copy(
                extractionMode = mode
            )
        }
    }

    fun setFreeTextPrompt(prompt: String) {
        _uiState.update {
            it.copy(
                freeTextPrompt = prompt
            )
        }
    }

    fun showSettings() {
        _uiState.update {
            it.copy(
                showSettings = true
            )
        }
    }

    fun hideSettings() {
        _uiState.update {
            it.copy(
                showSettings = false
            )
        }
    }

    fun addField(
        name: String,
        description: String
    ) {
        if (name.isBlank()) {
            return
        }

        _uiState.update { state ->
            state.copy(
                fields = state.fields + ExtractionField(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    description = description.trim()
                ),
                showAddFieldDialog = false
            )
        }
    }

    fun removeField(id: String) {
        _uiState.update { state ->
            state.copy(
                fields = state.fields.filter {
                    it.id != id
                }
            )
        }
    }

    fun toggleField(id: String) {
        _uiState.update { state ->
            state.copy(
                fields = state.fields.map { field ->
                    if (field.id == id) {
                        field.copy(
                            enabled = !field.enabled
                        )
                    } else {
                        field
                    }
                }
            )
        }
    }

    fun updateField(
        id: String,
        name: String,
        description: String
    ) {
        _uiState.update { state ->
            state.copy(
                fields = state.fields.map { field ->
                    if (field.id == id) {
                        field.copy(
                            name = name.trim(),
                            description = description.trim()
                        )
                    } else {
                        field
                    }
                },
                editingField = null
            )
        }
    }

    fun showAddFieldDialog() {
        _uiState.update {
            it.copy(
                showAddFieldDialog = true
            )
        }
    }

    fun startEditingField(
        field: ExtractionField
    ) {
        _uiState.update {
            it.copy(
                editingField = field
            )
        }
    }

    fun dismissDialog() {
        _uiState.update {
            it.copy(
                showAddFieldDialog = false,
                editingField = null
            )
        }
    }

    fun processDocuments() {

        val state = _uiState.value

        if (!repository.settings.isConfigured()) {
            _uiState.update {
                it.copy(
                    showSettings = true,
                    message = "Configure API key first"
                )
            }
            return
        }

        if (state.selectedUris.isEmpty()) {
            _uiState.update {
                it.copy(
                    message = "Select files first"
                )
            }
            return
        }

        if (
            state.extractionMode == ExtractionMode.FIELDS &&
            state.fields.none { field ->
                field.enabled
            }
        ) {
            _uiState.update {
                it.copy(
                    message = "Enable at least one field"
                )
            }
            return
        }

        if (
            state.extractionMode == ExtractionMode.FREE_TEXT &&
            state.freeTextPrompt.isBlank()
        ) {
            _uiState.update {
                it.copy(
                    message = "Write extraction prompt"
                )
            }
            return
        }

        viewModelScope.launch {

            val totalFiles =
                state.selectedUris.size

            _uiState.update {
                it.copy(
                    isProcessing = true,
                    results = emptyList(),
                    progress = 0,
                    total = totalFiles,
                    message = "Processing..."
                )
            }

            try {

                repository.processBatch(
                    uris = state.selectedUris,
                    fields = state.fields,
                    freeTextPrompt = state.freeTextPrompt,
                    isFreeTextMode =
                        state.extractionMode ==
                            ExtractionMode.FREE_TEXT,

                    onProgress = {
                            current,
                            total,
                            result ->

                        _uiState.update { currentState ->

                            currentState.copy(
                                progress = current,
                                results =
                                    currentState.results + result,
                                message =
                                    "Processed $current of $total"
                            )
                        }
                    }
                )

                val finalResults =
                    _uiState.value.results

                val successCount =
                    finalResults.count {
                        it.status == "success"
                    }

                val errorCount =
                    finalResults.count {
                        it.status == "error"
                    }

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        message =
                            "Done! " +
                                "$successCount/$totalFiles succeeded" +
                                if (errorCount > 0) {
                                    " - $errorCount failed"
                                } else {
                                    ""
                                }
                    )
                }

            } catch (e: Exception) {

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        message =
                            "Processing failed: " +
                                (
                                    e.message
                                        ?: "Unknown error"
                                )
                    )
                }
            }
        }
    }
}
