package ai.alagent.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    privacyMode: Boolean = false,
    preferLocal: Boolean = true,
    localApiEnabled: Boolean = false,
    onPrivacyModeChange: (Boolean) -> Unit = {},
    onPreferLocalChange: (Boolean) -> Unit = {},
    onLocalApiEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SettingSwitch("Privacy mode", "Routes only to local models.", privacyMode, onPrivacyModeChange)
        SettingSwitch("Prefer local", "Use on-device models first when capabilities allow.", preferLocal, onPreferLocalChange)
        SettingSwitch("Local API", "Bind an integration API to 127.0.0.1:8765 only.", localApiEnabled, onLocalApiEnabledChange)
        Text("Remote API exposure is not enabled by this switch. Non-loopback binding requires explicit authentication and secure transport.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
