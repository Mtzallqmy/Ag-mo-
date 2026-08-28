package ai.alagent.features.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AgentRunUi(
    val runId: String? = null,
    val goal: String? = null,
    val lifecycle: String = "IDLE",
    val eventLines: List<String> = emptyList()
)

@Composable
fun AgentsScreen(
    run: AgentRunUi = AgentRunUi(),
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agent Runs", style = MaterialTheme.typography.headlineMedium)
        Text("Status: ${run.lifecycle}", color = MaterialTheme.colorScheme.primary)
        run.goal?.let { Text("Goal: $it") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPause, enabled = run.lifecycle == "RUNNING") { Text("Pause") }
            OutlinedButton(onClick = onResume, enabled = run.lifecycle == "PAUSED") { Text("Resume") }
            Button(onClick = onStop, enabled = run.lifecycle in setOf("RUNNING", "PAUSED")) { Text("Stop") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            run.eventLines.takeLast(150).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
