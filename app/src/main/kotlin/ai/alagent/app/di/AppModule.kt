package ai.alagent.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import ai.alagent.ai.inference.AndroidLiteRtLmBackend
import ai.alagent.ai.inference.LiteRtLmBackend
import ai.alagent.ai.inference.LiteRtLocalInferenceEngine
import ai.alagent.ai.inference.LocalInferenceEngine
import ai.alagent.ai.provider.anthropic.AnthropicProvider
import ai.alagent.ai.provider.api.AiProvider
import ai.alagent.ai.provider.api.AiProviderRegistry
import ai.alagent.ai.provider.api.CapabilityModelRouter
import ai.alagent.ai.provider.api.ConnectivityProvider
import ai.alagent.ai.provider.api.ModelRouter
import ai.alagent.ai.provider.google.GeminiProvider
import ai.alagent.ai.provider.local.LocalAiProvider
import ai.alagent.ai.provider.openai.OpenAiCompatibleProvider
import ai.alagent.agent.cognition.ContextAssembler
import ai.alagent.agent.cognition.PromptBuilder
import ai.alagent.agent.planning.DirectPlanner
import ai.alagent.agent.planning.Planner
import ai.alagent.agent.policy.AppTierClassifier
import ai.alagent.agent.policy.ApprovalManager
import ai.alagent.agent.policy.DefaultPolicyEngine
import ai.alagent.agent.policy.LoopDetectionPolicy
import ai.alagent.agent.policy.PermissionManager
import ai.alagent.agent.policy.PolicyEngine
import ai.alagent.agent.policy.SensitiveDataDetector
import ai.alagent.agent.policy.ToolAuditLogger
import ai.alagent.agent.policy.ToolEligibilityFilter
import ai.alagent.agent.runtime.AgentEventBus
import ai.alagent.agent.runtime.AgentExecutionGate
import ai.alagent.agent.runtime.AgentRunRecorder
import ai.alagent.agent.runtime.AgentRuntime
import ai.alagent.agent.runtime.CompletionCriterionProbe
import ai.alagent.agent.runtime.ExecutionPhaseRunner
import ai.alagent.agent.runtime.ModelCompletionCriterionProbe
import ai.alagent.agent.runtime.PerceptionProvider
import ai.alagent.agent.runtime.PlanningPhaseRunner
import ai.alagent.agent.runtime.RecoveryPhaseRunner
import ai.alagent.agent.runtime.RuntimeContextProvider
import ai.alagent.agent.runtime.TaskController
import ai.alagent.agent.runtime.TurnRecorder
import ai.alagent.agent.runtime.VerificationPhaseRunner
import ai.alagent.agent.runtime.VerificationRoutingContextProvider
import ai.alagent.app.runtime.AccessibilityPerceptionProvider
import ai.alagent.app.runtime.AndroidConnectivityProvider
import ai.alagent.app.runtime.AndroidPermissionManager
import ai.alagent.app.runtime.ProviderAdminService
import ai.alagent.app.runtime.ModelAdminService
import ai.alagent.app.runtime.AppRuntimeStateUpdater
import ai.alagent.app.runtime.ApprovalCoordinator
import ai.alagent.app.runtime.DatabaseAgentRunRecorder
import ai.alagent.app.runtime.DatabaseRuntimeContextProvider
import ai.alagent.app.runtime.DatabaseToolAuditLogger
import ai.alagent.app.runtime.DatabaseTurnRecorder
import ai.alagent.app.runtime.DefaultAppTierClassifier
import ai.alagent.app.runtime.DefaultSensitiveDataDetector
import ai.alagent.app.runtime.DefaultVerificationRoutingContextProvider
import ai.alagent.app.runtime.RoomModelSource
import ai.alagent.app.runtime.WorkspaceCompletionProbe
import ai.alagent.app.settings.AppSettingsStore
import ai.alagent.core.database.AlAgentDatabase
import ai.alagent.core.database.AlAgentMigrations
import ai.alagent.core.files.AtomicFileStore
import ai.alagent.core.security.AndroidKeystoreSecretStore
import ai.alagent.core.security.SecretStore
import ai.alagent.skills.runtime.FileSkillRegistry
import ai.alagent.skills.runtime.SkillInstaller
import ai.alagent.skills.runtime.SkillManager
import ai.alagent.skills.runtime.SkillPackageLoader
import ai.alagent.skills.runtime.SkillRuntime
import ai.alagent.skills.runtime.SkillSecurityScanner
import ai.alagent.skills.runtime.SkillValidator
import ai.alagent.tools.accessibility.AccessibilityToolFactory
import ai.alagent.tools.android.AndroidInfoToolFactory
import ai.alagent.tools.api.ToolPreconditionValidator
import ai.alagent.tools.api.ToolRegistry
import ai.alagent.tools.clipboard.ClipboardToolFactory
import ai.alagent.tools.files.FileToolFactory
import ai.alagent.tools.intents.ForegroundAppProbe
import ai.alagent.tools.intents.IntentToolFactory
import ai.alagent.tools.notifications.NotificationToolFactory
import ai.alagent.tools.web.WebToolFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun httpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AlAgentDatabase =
        Room.databaseBuilder(context, AlAgentDatabase::class.java, "al-agent.db")
            .addMigrations(*AlAgentMigrations.ALL)
            .build()

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context) = AppSettingsStore(context)

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = AndroidKeystoreSecretStore(context)

    @Provides @Singleton
    fun workspace(@ApplicationContext context: Context): AtomicFileStore =
        AtomicFileStore(File(context.filesDir, "agent-workspace"))

    @Provides @Singleton
    fun approvalCoordinator(): ApprovalCoordinator = ApprovalCoordinator()

    @Provides
    fun approvalManager(coordinator: ApprovalCoordinator): ApprovalManager = coordinator

    @Provides @Singleton
    fun connectivity(@ApplicationContext context: Context): ConnectivityProvider = AndroidConnectivityProvider(context)

    @Provides @Singleton
    fun permissionManager(@ApplicationContext context: Context): PermissionManager = AndroidPermissionManager(context)

    @Provides @Singleton
    fun classifier(): AppTierClassifier = DefaultAppTierClassifier()

    @Provides @Singleton
    fun sensitiveDataDetector(): SensitiveDataDetector = DefaultSensitiveDataDetector()

    @Provides @Singleton
    fun auditLogger(database: AlAgentDatabase, @ApplicationScope scope: CoroutineScope): ToolAuditLogger =
        DatabaseToolAuditLogger(database, scope)

    @Provides @Singleton
    fun policyEngine(
        permissions: PermissionManager,
        classifier: AppTierClassifier,
        detector: SensitiveDataDetector,
        audit: ToolAuditLogger
    ): PolicyEngine = DefaultPolicyEngine(permissions, classifier, detector, audit)

    @Provides @Singleton
    fun tools(
        @ApplicationContext context: Context,
        workspace: AtomicFileStore,
        http: HttpClient
    ): ToolRegistry {
        val foreground = ForegroundAppProbe {
            runCatching { ai.alagent.tools.accessibility.AlAgentAccessibilityService.snapshotProvider.capture().packageName }.getOrNull()
        }
        return ToolRegistry(
            buildList {
                addAll(AccessibilityToolFactory.create())
                addAll(AndroidInfoToolFactory.create(context))
                addAll(IntentToolFactory.create(context, foreground))
                addAll(FileToolFactory.create(workspace))
                addAll(ClipboardToolFactory.create(context))
                addAll(NotificationToolFactory.create())
                addAll(WebToolFactory.create(http, workspace))
            }
        )
    }


    @Provides @Singleton
    fun skillManager(@ApplicationContext context: Context, json: Json, tools: ToolRegistry): SkillManager {
        val root = File(context.filesDir, "skills")
        val loader = SkillPackageLoader(json)
        val validator = SkillValidator(tools.descriptors().map { it.id }.toSet())
        val scanner = SkillSecurityScanner()
        val registry = FileSkillRegistry(root, loader)
        return SkillManager(registry, SkillInstaller(root, loader, validator, scanner), SkillRuntime(registry))
    }

    @Provides @Singleton
    fun modelSource(database: AlAgentDatabase, json: Json) = RoomModelSource(database, json)

    @Provides @Singleton
    fun providerAdmin(database: AlAgentDatabase, secrets: SecretStore, json: Json) = ProviderAdminService(database, secrets, json)

    @Provides @Singleton
    fun modelAdmin(@ApplicationContext context: Context, database: AlAgentDatabase, json: Json) = ModelAdminService(context, database, json)

    @Provides @Singleton
    fun liteRtBackend(@ApplicationContext context: Context): LiteRtLmBackend = AndroidLiteRtLmBackend(context)

    @Provides @Singleton
    fun localInference(backend: LiteRtLmBackend): LocalInferenceEngine = LiteRtLocalInferenceEngine(backend)

    @Provides @Singleton
    fun providers(
        source: RoomModelSource,
        database: AlAgentDatabase,
        secrets: SecretStore,
        http: HttpClient,
        inference: LocalInferenceEngine
    ): AiProviderRegistry {
        fun secret(alias: String): suspend () -> String? = {
            secrets.get(alias)?.toString(Charsets.UTF_8)
        }
        fun models(id: String): suspend () -> List<ai.alagent.core.model.ModelDescriptor> = { source.forProvider(id) }
        fun configuredBaseUrl(id: String, fallback: String): suspend () -> String = {
            database.providers().config(id)?.baseUrl?.takeIf(String::isNotBlank) ?: fallback
        }

        val all = mutableListOf<AiProvider>()
        all += LocalAiProvider(source::local, inference, source::localPath)
        all += OpenAiCompatibleProvider("openai", configuredBaseUrl("openai", "https://api.openai.com/v1"), secret("provider:openai:key"), models("openai"), http)
        all += OpenAiCompatibleProvider("openrouter", configuredBaseUrl("openrouter", "https://openrouter.ai/api/v1"), secret("provider:openrouter:key"), models("openrouter"), http)
        all += OpenAiCompatibleProvider("groq", configuredBaseUrl("groq", "https://api.groq.com/openai/v1"), secret("provider:groq:key"), models("groq"), http)
        all += OpenAiCompatibleProvider("deepseek", configuredBaseUrl("deepseek", "https://api.deepseek.com"), secret("provider:deepseek:key"), models("deepseek"), http)
        all += OpenAiCompatibleProvider("mistral", configuredBaseUrl("mistral", "https://api.mistral.ai/v1"), secret("provider:mistral:key"), models("mistral"), http)
        all += OpenAiCompatibleProvider("ollama", configuredBaseUrl("ollama", "http://127.0.0.1:11434/v1"), { null }, models("ollama"), http)
        all += OpenAiCompatibleProvider("custom", configuredBaseUrl("custom", "https://127.0.0.1.invalid/v1"), secret("provider:custom:key"), models("custom"), http)
        all += AnthropicProvider(secret("provider:anthropic:key"), models("anthropic"), http)
        all += GeminiProvider(secret("provider:gemini:key"), models("gemini"), http)
        return AiProviderRegistry(all)
    }

    @Provides @Singleton
    fun router(registry: AiProviderRegistry): ModelRouter = CapabilityModelRouter(registry.all())

    @Provides @Singleton fun eventBus() = AgentEventBus()
    @Provides @Singleton fun executionGate() = AgentExecutionGate()
    @Provides @Singleton fun planner(): Planner = DirectPlanner()
    @Provides @Singleton fun promptBuilder() = PromptBuilder()
    @Provides @Singleton fun contextAssembler() = ContextAssembler()
    @Provides @Singleton fun toolFilter() = ToolEligibilityFilter()
    @Provides @Singleton fun taskController() = TaskController()
    @Provides @Singleton fun loopDetection() = LoopDetectionPolicy()
    @Provides @Singleton fun preconditions(): ToolPreconditionValidator = ToolPreconditionValidator.Basic
    @Provides @Singleton fun recovery() = RecoveryPhaseRunner()

    @Provides @Singleton
    fun runtimeContext(database: AlAgentDatabase): RuntimeContextProvider = DatabaseRuntimeContextProvider(database)

    @Provides @Singleton
    fun turnRecorder(database: AlAgentDatabase, json: Json): TurnRecorder = DatabaseTurnRecorder(database, json)

    @Provides @Singleton
    fun runRecorder(database: AlAgentDatabase): AgentRunRecorder = DatabaseAgentRunRecorder(database)

    @Provides @Singleton
    fun perception(): PerceptionProvider = AccessibilityPerceptionProvider()

    @Provides @Singleton
    fun verificationRouting(settings: AppSettingsStore, connectivity: ConnectivityProvider): VerificationRoutingContextProvider =
        DefaultVerificationRoutingContextProvider(settings, connectivity)

    @Provides @Singleton
    fun completionProbe(
        router: ModelRouter,
        routing: VerificationRoutingContextProvider,
        workspace: AtomicFileStore
    ): CompletionCriterionProbe = ModelCompletionCriterionProbe(
        router,
        routing,
        WorkspaceCompletionProbe(workspace)
    )

    @Provides @Singleton
    fun planningPhase(
        planner: Planner,
        router: ModelRouter,
        promptBuilder: PromptBuilder,
        contextAssembler: ContextAssembler,
        contextProvider: RuntimeContextProvider,
        connectivity: ConnectivityProvider,
        toolFilter: ToolEligibilityFilter,
        registry: ToolRegistry
    ) = PlanningPhaseRunner(planner, router, promptBuilder, contextAssembler, contextProvider, connectivity, toolFilter, registry)

    @Provides @Singleton
    fun executionPhase(
        registry: ToolRegistry,
        preconditions: ToolPreconditionValidator,
        policy: PolicyEngine,
        approvals: ApprovalManager,
        eventBus: AgentEventBus
    ) = ExecutionPhaseRunner(registry, preconditions, policy, approvals, eventBus)

    @Provides @Singleton
    fun verificationPhase(probe: CompletionCriterionProbe) = VerificationPhaseRunner(probe)

    @Provides @Singleton
    fun runtime(
        taskController: TaskController,
        perception: PerceptionProvider,
        planning: PlanningPhaseRunner,
        execution: ExecutionPhaseRunner,
        verification: VerificationPhaseRunner,
        recovery: RecoveryPhaseRunner,
        loops: LoopDetectionPolicy,
        gate: AgentExecutionGate,
        turnRecorder: TurnRecorder,
        runRecorder: AgentRunRecorder,
        events: AgentEventBus
    ): AgentRuntime = AgentRuntime(
        taskController = taskController,
        perception = perception,
        planning = planning,
        execution = execution,
        verification = verification,
        recovery = recovery,
        loops = loops,
        executionGate = gate,
        stateUpdater = AppRuntimeStateUpdater,
        turnRecorder = turnRecorder,
        runRecorder = runRecorder,
        events = events
    )
}
