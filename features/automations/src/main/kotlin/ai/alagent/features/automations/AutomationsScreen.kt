package ai.alagent.features.automations

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

data class AutomationUi(val id: String, val name: String, val schedule: String, val goal: String, val status: String, val nextRun: Long?)

@Composable
fun AutomationsScreen(
    automations: List<AutomationUi> = emptyList(),
    onCreateOnce: (name: String, goal: String, delayMinutes: Long) -> Unit = { _,_,_ -> },
    onCreateRecurring: (name: String, goal: String, intervalMinutes: Long) -> Unit = { _,_,_ -> },
    onDisable: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Automations", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("Minutes") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { minutes.toLongOrNull()?.let { onCreateOnce(name, goal, it) } }, enabled = goal.isNotBlank()) { Text("Run once after") }
            OutlinedButton(onClick = { minutes.toLongOrNull()?.let { onCreateRecurring(name, goal, it.coerceAtLeast(15)) } }, enabled = goal.isNotBlank()) { Text("Recurring") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(automations, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.schedule)
                        Text(item.goal, style = MaterialTheme.typography.bodySmall)
                        Text("Status: ${item.status}")
                        if (item.status == "ENABLED") OutlinedButton(onClick = { onDisable(item.id) }) { Text("Disable") }
                    }
                }
            }
        }
    }
}
