package ai.alagent.features.skills

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SkillUi(val id: String, val name: String, val version: String, val description: String, val enabled: Boolean)

@Composable
fun SkillsScreen(
    skills: List<SkillUi> = emptyList(),
    onImportZip: () -> Unit = {},
    onEnabledChange: (String, Boolean) -> Unit = { _,_ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Skills", style = MaterialTheme.typography.headlineMedium)
        Text("Data-only SKILL.md packages. Executables, symlinks and unsafe archives are rejected.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onImportZip) { Text("Import skill ZIP") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(skills, key = { it.id }) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${skill.name} ${skill.version}", style = MaterialTheme.typography.titleMedium)
                            Text(skill.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(skill.enabled, { onEnabledChange(skill.id, it) })
                    }
                }
            }
        }
    }
}
