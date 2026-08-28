package com.example.certextractor.data.model

data class GroqResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: GroqMessage
)

data class GroqMessage(
    val content: String
)
