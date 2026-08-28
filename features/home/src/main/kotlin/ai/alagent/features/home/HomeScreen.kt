package ai.alagent.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    accessibilityConnected: Boolean = false,
    notificationConnected: Boolean = false,
    modelCount: Int = 0,
    providerCount: Int = 0,
    onAccessibilitySetup: () -> Unit = {},
    onNotificationSetup: () -> Unit = {},
    onProviders: () -> Unit = {},
    onSkills: () -> Unit = {},
    onMemory: () -> Unit = {},
    onAutomations: () -> Unit = {},
    onLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AL Agent", style = MaterialTheme.typography.headlineLarge)
        Text("Local-first agent control center", color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusCard("Accessibility", accessibilityConnected, onAccessibilitySetup)
        StatusCard("Notification access", notificationConnected, onNotificationSetup)
        Card(Modifier.fillMaxWidth()) {
            Text("$modelCount models • $providerCount providers", Modifier.padding(16.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onProviders, modifier = Modifier.weight(1f)) { Text("Providers") }
            OutlinedButton(onClick = onSkills, modifier = Modifier.weight(1f)) { Text("Skills") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMemory, modifier = Modifier.weight(1f)) { Text("Memory") }
            OutlinedButton(onClick = onAutomations, modifier = Modifier.weight(1f)) { Text("Automations") }
        }
        Button(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Logs & diagnostics") }
    }
}

@Composable
private fun StatusCard(label: String, ready: Boolean, onSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(label, style = MaterialTheme.typography.titleMedium); Text(if (ready) "Ready" else "Needs setup") }
            if (!ready) OutlinedButton(onClick = onSetup) { Text("Set up") }
        }
    }
}
