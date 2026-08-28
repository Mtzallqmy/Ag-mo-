package ai.alagent.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ChatMessageUi(val id: String, val role: String, val text: String)

@Composable
fun ChatScreen(
    messages: List<ChatMessageUi> = emptyList(),
    streamingText: String = "",
    input: String = "",
    busy: Boolean = false,
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onRunAgent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chat", style = MaterialTheme.typography.headlineMedium)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            messages.forEach { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message.role.uppercase(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(message.text)
                    }
                }
            }
            if (streamingText.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) { Text(streamingText, Modifier.padding(14.dp)) }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
            label = { Text("Message or goal") },
            enabled = !busy
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSend, enabled = input.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "Working…" else "Send")
            }
            OutlinedButton(onClick = onRunAgent, enabled = input.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                Text("Run agent")
            }
        }
    }
}
