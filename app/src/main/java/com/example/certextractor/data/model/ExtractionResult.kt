package com.example.certextractor.data.model

data class ExtractionResult(
    val fileName: String = "",
    val values: Map<String, String> = emptyMap(),
    val status: String = "pending",
    val errorMessage: String = ""
)
