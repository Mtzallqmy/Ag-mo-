package ai.alagent.features.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModelUi(
    val id: String,
    val name: String,
    val provider: String,
    val local: Boolean,
    val ready: Boolean,
    val selected: Boolean = false,
    val status: String? = null,
    val progress: Int? = null
)

@Composable
fun ModelsScreen(
    models: List<ModelUi> = emptyList(),
    onImportLocal: () -> Unit = {},
    onDownloadLocal: () -> Unit = {},
    onSelect: (String) -> Unit = {},
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Models", style = MaterialTheme.typography.headlineMedium)
        Text("Capabilities come from stored metadata, never model-name guessing.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportLocal) { Text("Import local") }
            OutlinedButton(onClick = onDownloadLocal) { Text("Download HTTPS") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(models, key = { it.id }) { model ->
                Card(Modifier.fillMaxWidth().clickable(enabled = model.ready) { onSelect(model.id) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model.selected, onClick = { if (model.ready) onSelect(model.id) }, enabled = model.ready)
                            Column(Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.titleMedium)
                                Text("${model.provider} • ${if (model.local) "local" else "cloud"} • ${model.status ?: if (model.ready) "ready" else "not ready"}")
                                model.progress?.let { Text("$it%", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (model.status in setOf("QUEUED", "DOWNLOADING")) OutlinedButton(onClick = { onPause(model.id) }) { Text("Pause") }
                        if (model.status in setOf("PAUSED", "FAILED")) OutlinedButton(onClick = { onResume(model.id) }) { Text(if (model.status == "FAILED") "Retry" else "Resume") }
                    }
                }
            }
        }
    }
}
