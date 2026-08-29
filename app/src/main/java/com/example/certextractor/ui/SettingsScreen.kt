package com.example.certextractor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.certextractor.data.local.AiSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit
) {
    var provider by remember { mutableStateOf(settings.provider) }
    var groqKey by remember { mutableStateOf(settings.groqKey) }
    var groqModel by remember { mutableStateOf(settings.groqModel) }
    var openrouterKey by remember { mutableStateOf(settings.openrouterKey) }
    var openrouterModel by remember { mutableStateOf(settings.openrouterModel) }
    var openaiKey by remember { mutableStateOf(settings.openaiKey) }
    var openaiModel by remember { mutableStateOf(settings.openaiModel) }
    var geminiKey by remember { mutableStateOf(settings.geminiKey) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }
    var mistralKey by remember { mutableStateOf(settings.mistralKey) }
    var mistralModel by remember { mutableStateOf(settings.mistralModel) }
    var customUrl by remember { mutableStateOf(settings.customUrl) }
    var customKey by remember { mutableStateOf(settings.customKey) }
    var customModel by remember { mutableStateOf(settings.customModel) }
    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val providers = listOf(
        "gemini" to "Google Gemini (Recommended)",
        "openrouter" to "OpenRouter",
        "openai" to "OpenAI (ChatGPT)",
        "groq" to "Groq",
        "mistral" to "Mistral AI",
        "custom" to "Custom (OpenAI Compatible)"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Provider:", fontWeight = FontWeight.Bold)

            providers.forEach { (key, name) ->
                Card(
                    onClick = { provider = key; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (provider == key)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider == key,
                            onClick = { provider = key; saved = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            fontWeight = if (provider == key) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API Keys:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showKeys = !showKeys }) {
                    Icon(
                        if (showKeys) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showKeys) "Hide" else "Show")
                }
            }

            when (provider) {

                "gemini" -> {
                    InfoCard(
                        "Free tier available. Get key at aistudio.google.com/apikey\n" +
                        "1. Go to aistudio.google.com/apikey\n" +
                        "2. Create API key\n" +
                        "3. Restrict to Gemini API only when prompted"
                    )
                    KeyField(
                        label = "Gemini API Key",
                        value = geminiKey,
                        onValueChange = { geminiKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "AIza..."
                    )
                    VisionModelDropdown(
                        label = "Model",
                        models = listOf(
                            "gemini-2.5-flash" to "Gemini 2.5 Flash (Recommended)",
                            "gemini-2.5-flash-lite" to "Gemini 2.5 Flash-Lite (Fastest)",
                            "gemini-2.5-pro" to "Gemini 2.5 Pro (Best Quality)",
                            "gemini-3.6-flash" to "Gemini 3.6 Flash (Latest)",
                            "gemini-3.7-flash" to "Gemini 3.7 Flash (Newest)"
                        ),
                        selected = geminiModel,
                        onSelect = { geminiModel = it; saved = false }
                    )
                }

                "openrouter" -> {
                    InfoCard(
                        "Get key at openrouter.ai/keys\n" +
                        "Some models have free tier"
                    )
                    KeyField(
                        label = "OpenRouter API Key",
                        value = openrouterKey,
                        onValueChange = { openrouterKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "sk-or-..."
                    )
                    VisionModelDropdown(
                        label = "Model",
                        models = listOf(
                            "google/gemini-2.5-flash-preview:free" to "Gemini 2.5 Flash (Free)",
                            "google/gemini-2.0-flash-exp:free" to "Gemini 2.0 Flash (Free)",
                            "qwen/qwen-2.5-vl-72b-instruct:free" to "Qwen 2.5 VL 72B (Free)",
                            "mistralai/pixtral-12b" to "Pixtral 12B"
                        ),
                        selected = openrouterModel,
                        onSelect = { openrouterModel = it; saved = false }
                    )
                    OutlinedTextField(
                        value = openrouterModel,
                        onValueChange = { openrouterModel = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Or type model name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                "groq" -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Note: Most Groq vision models have been decommissioned.",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Text-only models may not work for image extraction. Consider Gemini or OpenRouter instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    KeyField(
                        label = "Groq API Key",
                        value = groqKey,
                        onValueChange = { groqKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "gsk_..."
                    )
                    OutlinedTextField(
                        value = groqModel,
                        onValueChange = { groqModel = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model Name") },
                        placeholder = { Text("e.g. llama-3.3-70b-versatile") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                "openai" -> {
                    KeyField(
                        label = "OpenAI API Key",
                        value = openaiKey,
                        onValueChange = { openaiKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "sk-..."
                    )
                    VisionModelDropdown(
                        label = "Vision Model",
                        models = listOf(
                            "gpt-4o-mini" to "GPT-4o Mini",
                            "gpt-4o" to "GPT-4o",
                            "gpt-4-turbo" to "GPT-4 Turbo"
                        ),
                        selected = openaiModel,
                        onSelect = { openaiModel = it; saved = false }
                    )
                }

                "mistral" -> {
                    KeyField(
                        label = "Mistral API Key",
                        value = mistralKey,
                        onValueChange = { mistralKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "key..."
                    )
                    VisionModelDropdown(
                        label = "Vision Model",
                        models = listOf(
                            "pixtral-12b-2409" to "Pixtral 12B",
                            "mistral-large-latest" to "Mistral Large"
                        ),
                        selected = mistralModel,
                        onSelect = { mistralModel = it; saved = false }
                    )
                }

                "custom" -> {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Server URL") },
                        placeholder = { Text("https://api.example.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    KeyField(
                        label = "API Key",
                        value = customKey,
                        onValueChange = { customKey = it; saved = false },
                        showKey = showKeys,
                        placeholder = "key..."
                    )
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model Name") },
                        placeholder = { Text("gpt-4o-mini") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    settings.provider = provider
                    settings.groqKey = groqKey
                    settings.groqModel = groqModel
                    settings.openrouterKey = openrouterKey
                    settings.openrouterModel = openrouterModel
                    settings.openaiKey = openaiKey
                    settings.openaiModel = openaiModel
                    settings.geminiKey = geminiKey
                    settings.geminiModel = geminiModel
                    settings.mistralKey = mistralKey
                    settings.mistralModel = mistralModel
                    settings.customUrl = customUrl
                    settings.customKey = customKey
                    settings.customModel = customModel
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Settings", fontWeight = FontWeight.Bold)
            }

            if (saved) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Settings saved!", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionModelDropdown(
    label: String,
    models: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = models.find { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(12.dp),
            label = { Text(label) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); expanded = false }
                )
            }
        }
    }
}
