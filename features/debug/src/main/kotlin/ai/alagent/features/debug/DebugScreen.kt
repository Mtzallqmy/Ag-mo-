package ai.alagent.features.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AuditUi(val id: String, val time: Long, val level: String, val component: String, val event: String)

@Composable
fun DebugScreen(audit: List<AuditUi> = emptyList(), modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Logs", style = MaterialTheme.typography.headlineMedium)
        Text("Structured audit events. Secrets and passwords are intentionally excluded.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(audit, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("${row.level} • ${row.component}", style = MaterialTheme.typography.labelMedium); Text(row.event) } }
            }
        }
    }
}
