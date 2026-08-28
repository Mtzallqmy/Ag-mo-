package ai.alagent.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.alagent.ai.provider.api.AiStreamEvent
import ai.alagent.agent.runtime.AgentEvent
import ai.alagent.agent.runtime.AgentExecutionConfig
import ai.alagent.app.runtime.ApprovalCoordinator
import ai.alagent.app.runtime.ChatCoordinator
import ai.alagent.app.runtime.AutomationScheduler
import ai.alagent.app.runtime.MemoryAdminService
import ai.alagent.app.runtime.SkillAdminService
import ai.alagent.app.runtime.ModelDownloadManager
import ai.alagent.app.runtime.LocalApiController
import ai.alagent.app.runtime.ModelAdminService
import ai.alagent.app.runtime.ProviderAdminService
import ai.alagent.app.settings.AppSettings
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.model.ApprovalChoice
import ai.alagent.features.agents.AgentRunUi
import ai.alagent.features.automations.AutomationUi
import ai.alagent.features.memory.MemoryUi
import ai.alagent.features.skills.SkillUi
import ai.alagent.features.debug.AuditUi
import ai.alagent.features.chat.ChatMessageUi
import ai.alagent.features.models.ModelUi
import ai.alagent.features.providers.ProviderUi
import ai.alagent.service.agent.AgentRunCoordinator
import ai.alagent.service.agent.AgentService
import ai.alagent.service.agent.RunLifecycle
import ai.alagent.tools.accessibility.AlAgentAccessibilityService
import ai.alagent.tools.notifications.AlAgentNotificationListenerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlAgentDatabase,
    private val chat: ChatCoordinator,
    private val agentRuns: AgentRunCoordinator,
    private val approvals: ApprovalCoordinator,
    private val settingsStore: AppSettingsStore,
    private val providerAdmin: ProviderAdminService,
    private val modelAdmin: ModelAdminService,
    private val downloads: ModelDownloadManager,
    private val automationsAdmin: AutomationScheduler,
    private val memoryAdmin: MemoryAdminService,
    private val skillAdmin: SkillAdminService,
    private val localApi: LocalApiController
) : ViewModel() {
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()
    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()
    private val _agentEvents = MutableStateFlow<List<String>>(emptyList())

    val pendingApproval = approvals.pending
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val runStatus = agentRuns.status

    val providers: StateFlow<List<ProviderUi>> = providerAdmin.providers().map { rows ->
        rows.map { row ->
            val config = database.providers().config(row.id)
            ProviderUi(row.id, row.displayName, row.enabled, providerAdmin.hasSecret(row.id), config?.baseUrl)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val models: StateFlow<List<ModelUi>> = combine(modelAdmin.models(), settingsStore.settings, database.models().observeDownloads()) { rows, appSettings, downloads ->
        val byModel = downloads.associateBy { it.modelId }
        rows.map { row ->
            val local = row.providerId == "local"
            val download = byModel[row.id]
            val ready = if (!local) true else download?.let { it.status == "COMPLETED" && File(it.destinationPath).isFile } == true
            val progress = download?.totalBytes?.takeIf { it > 0 }?.let { ((download.bytesDownloaded * 100 / it).coerceIn(0, 100)).toInt() }
            ModelUi(row.id, row.displayName, row.providerId ?: "unknown", local, ready, selected = row.id == appSettings.preferredModelId, status = download?.status, progress = progress)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val agentRunUi: StateFlow<AgentRunUi> = combine(agentRuns.status, _agentEvents) { status, lines ->
        AgentRunUi(status.runId, status.goal, status.lifecycle.name, lines)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRunUi())

    val automations: StateFlow<List<AutomationUi>> = automationsAdmin.schedules().map { rows ->
        rows.map { AutomationUi(it.id, it.name, it.schedule, it.goal, it.status, it.nextRun) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memories: StateFlow<List<MemoryUi>> = memoryAdmin.memories().map { rows ->
        rows.map { MemoryUi(it.id, it.kind, it.text) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val skills: StateFlow<List<SkillUi>> = skillAdmin.skills().map { rows ->
        rows.map { SkillUi(it.skillId, it.name, it.version, it.description, it.enabled) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val audit: StateFlow<List<AuditUi>> = database.audit().observeRecent(200).map { rows ->
        rows.map { AuditUi(it.id, it.timestamp, it.level, it.component, it.eventType) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { refreshMessages() }
        viewModelScope.launch {
            agentRuns.events.collect { event ->
                val line = when (event) {
                    is AgentEvent.SessionStarted -> "Session ${event.sessionId.value.take(8)} started"
                    is AgentEvent.TaskStarted -> "Goal: ${event.goal}"
                    is AgentEvent.TurnStarted -> "Turn ${event.number}"
                    is AgentEvent.PlanUpdated -> "Plan: ${event.summary}"
                    is AgentEvent.ToolStarted -> "Tool: ${event.toolId}"
                    is AgentEvent.ObservationCaptured -> "Observe: ${event.summary.take(180)}"
                    is AgentEvent.VerificationCompleted -> "Verify: ${event.result.status}${event.result.reason?.let { ": $it" }.orEmpty()}"
                    is AgentEvent.ApprovalRequired -> "Approval required: ${event.request.operation}"
                    is AgentEvent.Retry -> "Retry ${event.count}: ${event.reason}"
                    is AgentEvent.Completed -> "Completed: ${event.message}"
                    is AgentEvent.Failed -> "Failed: ${event.reason}"
                }
                _agentEvents.value = (_agentEvents.value + line).takeLast(200)
            }
        }
    }

    fun setInput(value: String) { _input.value = value }
    fun clearError() { _uiError.value = null }

    fun sendChat() {
        val text = _input.value.trim()
        if (text.isEmpty() || _chatBusy.value) return
        _input.value = ""
        _chatBusy.value = true
        _streaming.value = ""
        _messages.value = _messages.value + ChatMessageUi(UUID.randomUUID().toString(), "user", text)
        viewModelScope.launch {
            runCatching {
                chat.send(text).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> _streaming.value += event.text
                        else -> Unit
                    }
                }
            }.onFailure { _uiError.value = it.message ?: "Chat failed" }
            _streaming.value = ""
            _chatBusy.value = false
            refreshMessages()
        }
    }

    fun runAgent() {
        val goal = _input.value.trim()
        if (goal.isEmpty() || agentRuns.status.value.lifecycle in setOf(RunLifecycle.RUNNING, RunLifecycle.PAUSED)) return
        _input.value = ""
        _agentEvents.value = emptyList()
        viewModelScope.launch {
            runCatching {
                val current = settingsStore.current()
                AgentService.start(context)
                agentRuns.start(
                    goal,
                    AgentExecutionConfig(
                        privacyMode = current.privacyMode,
                        preferredModelId = current.preferredModelId
                    )
                )
            }.onFailure { _uiError.value = it.message ?: "Unable to start agent" }
        }
    }

    fun pauseAgent() = agentRuns.pause()
    fun resumeAgent() = agentRuns.resume()
    fun stopAgent() = agentRuns.stop()
    fun resolveApproval(choice: ApprovalChoice) = approvals.resolve(choice)

    fun configureProvider(id: String, enabled: Boolean, apiKey: String?, baseUrl: String?, modelId: String?) {
        viewModelScope.launch {
            runCatching { providerAdmin.configure(id, enabled, apiKey, baseUrl, modelId) }
                .onFailure { _uiError.value = it.message ?: "Provider configuration failed" }
        }
    }

    fun importLocalModel(uri: Uri, contextWindow: Int, agentCapable: Boolean) {
        viewModelScope.launch {
            runCatching {
                modelAdmin.importLocalModel(uri, displayName(uri), contextWindow, agentCapable)
            }.onSuccess { id -> settingsStore.setPreferredModel(id) }
             .onFailure { _uiError.value = it.message ?: "Model import failed" }
        }
    }

    fun selectModel(id: String?) { viewModelScope.launch { settingsStore.setPreferredModel(id) } }
    fun setPrivacyMode(value: Boolean) { viewModelScope.launch { settingsStore.setPrivacyMode(value) } }
    fun setPreferLocal(value: Boolean) { viewModelScope.launch { settingsStore.setPreferLocal(value) } }
    fun setLocalApiEnabled(value: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (value) localApi.startLoopback() else localApi.stop()
                settingsStore.setLocalApiEnabled(value)
            }.onFailure { _uiError.value = it.message ?: "Unable to change Local API state" }
        }
    }


    fun pauseModelDownload(modelId: String) { viewModelScope.launch { downloads.pause(modelId) } }
    fun resumeModelDownload(modelId: String) { downloads.resume(modelId) }

    fun downloadLocalModel(url: String, name: String, checksum: String?, contextWindow: Int, agentCapable: Boolean) {
        viewModelScope.launch {
            runCatching { downloads.enqueue(url, name, contextWindow, checksum, agentCapable) }
                .onFailure { _uiError.value = it.message ?: "Unable to queue model download" }
        }
    }

    fun createOneTimeAutomation(name: String, goal: String, delayMinutes: Long) {
        viewModelScope.launch {
            runCatching { automationsAdmin.scheduleOnce(name, goal, System.currentTimeMillis() + delayMinutes.coerceAtLeast(0) * 60_000L, emptySet(), settingsStore.current().preferredModelId) }
                .onFailure { _uiError.value = it.message ?: "Unable to schedule automation" }
        }
    }

    fun createRecurringAutomation(name: String, goal: String, intervalMinutes: Long) {
        viewModelScope.launch {
            runCatching { automationsAdmin.scheduleRecurring(name, goal, intervalMinutes, emptySet(), settingsStore.current().preferredModelId) }
                .onFailure { _uiError.value = it.message ?: "Unable to schedule automation" }
        }
    }

    fun disableAutomation(id: String) { viewModelScope.launch { automationsAdmin.disable(id) } }
    fun addMemory(kind: String, text: String) { viewModelScope.launch { runCatching { memoryAdmin.add(kind, text) }.onFailure { _uiError.value = it.message } } }
    fun deleteMemory(id: String) { viewModelScope.launch { memoryAdmin.delete(id) } }
    fun importSkill(uri: Uri) { viewModelScope.launch { runCatching { skillAdmin.importZip(uri) }.onFailure { _uiError.value = it.message ?: "Skill import failed" } } }
    fun setSkillEnabled(id: String, enabled: Boolean) { viewModelScope.launch { skillAdmin.setEnabled(id, enabled) } }

    fun accessibilityConnected(): Boolean = AlAgentAccessibilityService.isConnected
    fun notificationConnected(): Boolean = AlAgentNotificationListenerService.isConnected

    private suspend fun refreshMessages() {
        _messages.value = chat.recentMessages().map { ChatMessageUi(it.id, it.role, it.content) }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "Local model"
    }
}
