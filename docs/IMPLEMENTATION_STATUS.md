# Implementation status

AL Agent is a **production-oriented Android foundation with a verified debug build**, not a claim that every provider, device, privileged integration, or release path is production-certified.

## Verified baseline

The `main` branch has passed the complete Android CI baseline:

- architecture policy checks;
- JVM and Android unit tests;
- `:app:lintDebug`;
- `:app:assembleDebug`.

The current stable build baseline remains Android SDK 36 / AGP 8.10.0 / Gradle 8.11.1 / Kotlin 2.2.0. Platform upgrades are intentionally separated from correctness fixes.

## Implemented

### Agent runtime

- Session → Task → Turn runtime with a thin session model.
- Plan → Act → Observe → Verify → Retry/Replan control flow.
- Tool execution does not imply task success; fresh evidence and completion criteria are evaluated separately.
- Typed/versioned prompt sections and context assembly.
- Planning dependency graph and step state transitions.
- Progressive tool eligibility and explicit per-session tool allowlists.
- Preconditions, risk policy, approval gates, per-tool timeouts, retry/turn/tool/token/cost budgets, loop detection, and recovery decisions.
- Structured turn records and runtime event flow without persisting raw chain-of-thought.

### Models and providers

- Provider abstraction and model capability routing for local/cloud models.
- OpenAI-compatible provider profiles, including OpenAI, OpenRouter-compatible paths, Groq, DeepSeek, Mistral, Ollama, and custom compatible endpoints.
- Anthropic and Gemini provider adapters.
- Local provider bridge and local inference session abstractions.
- LiteRT-LM backend implementation and model lifecycle/session management.
- llama.cpp JNI boundary for an optional native backend.
- Device/model compatibility metadata including context, RAM, quantization, format, accelerators, capabilities, source, and checksum.

### Model storage and downloads

- App-private model storage.
- Resumable partial downloads with HTTPS transport, range requests, free-space checks, SHA-256 validation, atomic promotion, pause/resume/retry, and interrupted-download recovery.
- Foreground WorkManager execution for long model downloads.

### Android tools

- Accessibility snapshot mapping, pruning, relevance scoring, signatures, live-node matching, gestures/global actions, and an AccessibilityService boundary.
- File read/write/list/copy/move/delete tools confined to an app-controlled root.
- Intent/open-app/open-URL/share/open-file and constrained intent operations.
- Clipboard read/write tools with policy/approval boundaries.
- Notification-listener snapshot/read/action support.
- Device/package information tools.
- HTTP request and file-download tools with outbound network policy checks.
- Tool registry, schemas, risk levels, permissions, timeouts, observations, and verification contracts.

### Persistence and security

- Room database version 3 with entities for sessions, tasks, turns, messages, tool calls, observations, verification results, memories, todos, scratchpad, skills, models/downloads, providers/configs, schedules, audit events, attachments, prompt versions, and evaluation runs.
- Explicit Room migrations 1→2 and 2→3 with exported schemas.
- Repository/domain boundaries so feature UI does not directly depend on Room entities.
- Android Keystore-backed secret storage; provider secrets are not stored in Room.
- `allowBackup=false` and cleartext networking denied by default; loopback cleartext is scoped to localhost/127.0.0.1 for local services.
- Structured logging/redaction boundaries.

### Skills, automation, API and UI

- Data-only skill manifests, loader, validator, security scanner, staged installer, registry, and runtime tool allowlists.
- Skills reject symlinks and executable/binary payloads; downloaded code is not executed by the skills runtime.
- One-time and periodic WorkManager automations with foreground execution when needed and background agent coordination.
- Local loopback API for health, models, providers, sessions, chat, agent run/stop/status, tools, skills, memory search, and event streaming.
- Non-loopback local API mode requires explicit remote-access configuration and secure transport.
- Compose Material 3 application with `MainViewModel`, navigation, chat, agent runs, models, providers, skills, memory, automations, settings, debug/audit, approval dialogs, local model import, and model download flows.
- Evaluation metric/domain scaffolding and regression-task definitions.

## Partially implemented / not yet parity-complete

### Provider parity

The provider abstraction is usable, but cloud adapters are not yet behaviorally equivalent:

- OpenAI-compatible currently supports tool schema submission, tool-call parsing, and usage parsing, but its current Flow wraps a non-streaming request rather than full SSE streaming.
- Anthropic currently handles basic text responses but does not yet expose full native tool-use, usage, system-message, structured-output, and streaming parity through the common event contract.
- Gemini currently handles basic text generation but does not yet expose full function-calling, usage, system instruction, structured-output, and streaming parity through the common event contract.

This is a release-priority item for agentic cloud use.

### Optional integrations

- MCP: basic Streamable-HTTP JSON-RPC client exists; a complete tool adapter and broader transport coverage remain.
- OpenClaw compatibility module exists structurally but has no functional Kotlin adapter yet.
- SSH: profile/policy boundary exists; no production SSH transport is implemented.
- Shizuku: privileged capability/bridge contracts exist; no production client/service binding is implemented.
- Termux: clean-room command-policy boundary exists; the optional external execution integration is not complete.
- llama.cpp: JNI contract exists, but native llama.cpp sources/build, ABI packaging, and release validation are not bundled.

## Release-blocking validation still required

- Room migration tests against exported historical schemas (at minimum 1→3 and 2→3).
- Broader regression coverage for runtime policy, approvals, budgets, downloads, automations, local API, skills, and provider parsing.
- Accessibility instrumentation across representative OEM/device versions.
- Performance, thermal, memory-pressure, model-load, and battery benchmarks; `:benchmark` and `:test-utils` still need substantive implementations.
- Provider tool-calling/streaming conformance tests against supported cloud providers.
- Transport-level DNS rebinding resistance in addition to URL/address validation.
- Custom launcher/adaptive icon and final product visual polish.
- R8/release-build validation, signed AAB pipeline, Play signing/credentials, SBOM/dependency governance, privacy/data-safety review, and release provenance.
- Real-device validation for WorkManager/foreground execution, notifications, accessibility permissions, model downloads, and local inference.

## Upgrade path

Do not combine platform migration with correctness/security fixes. After the hardening and provider-parity work is green, move Android SDK/AGP/Gradle/Kotlin forward in a dedicated migration pull request, resolve Gradle deprecations, and run Android behavior-change tests before raising `targetSdk`.
