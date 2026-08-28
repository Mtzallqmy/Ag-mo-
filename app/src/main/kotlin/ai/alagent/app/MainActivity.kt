package ai.alagent.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.alagent.core.model.ApprovalChoice
import ai.alagent.core.model.ApprovalRequest
import ai.alagent.core.model.RiskLevel
import ai.alagent.features.agents.AgentsScreen
import ai.alagent.features.automations.AutomationsScreen
import ai.alagent.features.chat.ChatScreen
import ai.alagent.features.debug.DebugScreen
import ai.alagent.features.home.HomeScreen
import ai.alagent.features.memory.MemoryScreen
import ai.alagent.features.models.ModelsScreen
import ai.alagent.features.providers.ProvidersScreen
import ai.alagent.features.settings.SettingsScreen
import ai.alagent.features.skills.SkillsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AlAgentRoot(viewModel) } }
    }
}

@Composable
private fun AlAgentRoot(viewModel: MainViewModel) {
    val nav = rememberNavController()
    val input by viewModel.input.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val chatBusy by viewModel.chatBusy.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val models by viewModel.models.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val run by viewModel.agentRunUi.collectAsState()
    val automations by viewModel.automations.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val audit by viewModel.audit.collectAsState()
    val approval by viewModel.pendingApproval.collectAsState()
    val uiError by viewModel.uiError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showModelImport by remember { mutableStateOf(false) }
    var importContext by remember { mutableStateOf("8192") }
    var importAgentCapable by remember { mutableStateOf(true) }
    var showModelDownload by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    var downloadName by remember { mutableStateOf("") }
    var downloadChecksum by remember { mutableStateOf("") }
    var downloadContext by remember { mutableStateOf("8192") }
    var downloadAgentCapable by remember { mutableStateOf(true) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val contextWindow = importContext.toIntOrNull()?.coerceIn(1024, 1_000_000) ?: 8192
            viewModel.importLocalModel(it, contextWindow, importAgentCapable)
        }
    }
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSkill)
    }

    LaunchedEffect(uiError) {
        uiError?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    approval?.let { request -> ApprovalDialog(request, viewModel::resolveApproval) }
    if (showModelImport) {
        AlertDialog(
            onDismissRequest = { showModelImport = false },
            title = { Text("Import local model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose a LiteRT-LM compatible model. Mark tool-calling only when the model is known to follow structured tool instructions.")
                    OutlinedTextField(importContext, { importContext = it.filter(Char::isDigit) }, label = { Text("Context window") })
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Agent/tool capable")
                        Switch(importAgentCapable, { importAgentCapable = it })
                    }
                }
            },
            confirmButton = { Button(onClick = { showModelImport = false; modelPicker.launch(arrayOf("*/*")) }) { Text("Choose file") } },
            dismissButton = { OutlinedButton(onClick = { showModelImport = false }) { Text("Cancel") } }
        )
    }

    if (showModelDownload) {
        AlertDialog(
            onDismissRequest = { showModelDownload = false },
            title = { Text("Download local model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(downloadUrl, { downloadUrl = it }, label = { Text("HTTPS URL") })
                    OutlinedTextField(downloadName, { downloadName = it }, label = { Text("Display name") })
                    OutlinedTextField(downloadChecksum, { downloadChecksum = it.filter { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }, label = { Text("SHA-256 (recommended)") })
                    OutlinedTextField(downloadContext, { downloadContext = it.filter(Char::isDigit) }, label = { Text("Context window") })
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Agent/tool capable")
                        Switch(downloadAgentCapable, { downloadAgentCapable = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val contextWindow = downloadContext.toIntOrNull()?.coerceIn(1024, 1_000_000) ?: 8192
                    viewModel.downloadLocalModel(downloadUrl, downloadName, downloadChecksum.takeIf(String::isNotBlank), contextWindow, downloadAgentCapable)
                    showModelDownload = false
                }, enabled = downloadUrl.startsWith("https://")) { Text("Download") }
            },
            dismissButton = { OutlinedButton(onClick = { showModelDownload = false }) { Text("Cancel") } }
        )
    }

    val bottomRoutes = listOf("home" to "Home", "chat" to "Chat", "agents" to "Runs", "models" to "Models", "settings" to "Settings")
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                bottomRoutes.forEach { (route, label) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = { nav.navigate(route) { launchSingleTop = true; restoreState = true } },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    accessibilityConnected = viewModel.accessibilityConnected(),
                    notificationConnected = viewModel.notificationConnected(),
                    modelCount = models.size,
                    providerCount = providers.count { it.enabled },
                    onAccessibilitySetup = { nav.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onNotificationSetup = { nav.context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onProviders = { nav.navigate("providers") },
                    onSkills = { nav.navigate("skills") },
                    onMemory = { nav.navigate("memory") },
                    onAutomations = { nav.navigate("automations") },
                    onLogs = { nav.navigate("debug") }
                )
            }
            composable("chat") {
                ChatScreen(messages, streaming, input, chatBusy, viewModel::setInput, viewModel::sendChat, viewModel::runAgent)
            }
            composable("agents") { AgentsScreen(run, viewModel::pauseAgent, viewModel::resumeAgent, viewModel::stopAgent) }
            composable("models") {
                ModelsScreen(
                    models,
                    onImportLocal = { showModelImport = true },
                    onDownloadLocal = { showModelDownload = true },
                    onSelect = viewModel::selectModel,
                    onPause = viewModel::pauseModelDownload,
                    onResume = viewModel::resumeModelDownload
                )
            }
            composable("providers") { ProvidersScreen(providers, viewModel::configureProvider) }
            composable("skills") { SkillsScreen(skills, onImportZip = { skillPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, onEnabledChange = viewModel::setSkillEnabled) }
            composable("memory") { MemoryScreen(memories, viewModel::addMemory, viewModel::deleteMemory) }
            composable("automations") { AutomationsScreen(automations, viewModel::createOneTimeAutomation, viewModel::createRecurringAutomation, viewModel::disableAutomation) }
            composable("settings") {
                SettingsScreen(settings.privacyMode, settings.preferLocal, settings.localApiEnabled, viewModel::setPrivacyMode, viewModel::setPreferLocal, viewModel::setLocalApiEnabled)
            }
            composable("debug") { DebugScreen(audit) }
        }
    }
}

@Composable
private fun ApprovalDialog(request: ApprovalRequest, resolve: (ApprovalChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = { resolve(ApprovalChoice.DENY) },
        title = { Text("Approval required — ${request.riskLevel}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tool: ${request.toolId}")
                Text("Operation: ${request.operation}")
                request.targetApplication?.let { Text("Target: $it") }
                if (request.potentialSensitiveData.isNotEmpty()) Text("Sensitive data: ${request.potentialSensitiveData.joinToString()}")
                Text(request.reason)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.riskLevel <= RiskLevel.MEDIUM) {
                    OutlinedButton(onClick = { resolve(ApprovalChoice.ALWAYS_ALLOW_TYPE) }) { Text("Always allow type") }
                }
                Button(onClick = { resolve(ApprovalChoice.APPROVE_ONCE) }) { Text("Approve once") }
            }
        },
        dismissButton = { OutlinedButton(onClick = { resolve(ApprovalChoice.DENY) }) { Text("Deny") } }
    )
}
