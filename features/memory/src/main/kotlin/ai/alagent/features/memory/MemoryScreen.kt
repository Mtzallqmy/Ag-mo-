package ai.alagent.features.memory

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class MemoryUi(val id: String, val kind: String, val text: String)

@Composable
fun MemoryScreen(
    memories: List<MemoryUi> = emptyList(),
    onAdd: (String, String) -> Unit = { _,_ -> },
    onDelete: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var kind by remember { mutableStateOf("fact") }
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Memory", style = MaterialTheme.typography.headlineMedium)
        Text("Long-term memory is explicit and user-editable.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(kind, { kind = it }, label = { Text("Kind") }, modifier = Modifier.weight(0.35f))
            OutlinedTextField(text, { text = it }, label = { Text("Memory") }, modifier = Modifier.weight(0.65f))
        }
        Button(onClick = { onAdd(kind, text); text = "" }, enabled = text.isNotBlank()) { Text("Add memory") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) { Text(item.kind, style = MaterialTheme.typography.labelMedium); Text(item.text) }
                        OutlinedButton(onClick = { onDelete(item.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
