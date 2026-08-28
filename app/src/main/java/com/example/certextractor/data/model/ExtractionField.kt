package com.example.certextractor.data.model

import java.util.UUID

data class ExtractionField(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true
)
