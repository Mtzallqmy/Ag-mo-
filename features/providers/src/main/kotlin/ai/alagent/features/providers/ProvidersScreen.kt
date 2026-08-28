package ai.alagent.features.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class ProviderUi(val id: String, val name: String, val enabled: Boolean, val hasSecret: Boolean, val baseUrl: String? = null)

@Composable
fun ProvidersScreen(
    providers: List<ProviderUi> = emptyList(),
    onConfigure: (providerId: String, enabled: Boolean, apiKey: String?, baseUrl: String?, modelId: String?) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Providers", style = MaterialTheme.typography.headlineMedium)
        providers.forEach { provider ->
            ProviderCard(provider, onConfigure)
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderUi,
    onConfigure: (String, Boolean, String?, String?, String?) -> Unit
) {
    var enabled by remember(provider.id, provider.enabled) { mutableStateOf(provider.enabled) }
    var key by remember(provider.id) { mutableStateOf("") }
    var baseUrl by remember(provider.id, provider.baseUrl) { mutableStateOf(provider.baseUrl.orEmpty()) }
    var modelId by remember(provider.id) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            if (provider.id != "local") {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (provider.hasSecret) "API key (stored; enter to replace)" else "API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (provider.id in setOf("custom", "ollama")) {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = modelId, onValueChange = { modelId = it }, label = { Text("Model ID to add") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = {
                onConfigure(provider.id, enabled, key.takeIf(String::isNotBlank), baseUrl.takeIf(String::isNotBlank), modelId.takeIf(String::isNotBlank))
                key = ""
                modelId = ""
            }) { Text("Save") }
        }
    }
}
