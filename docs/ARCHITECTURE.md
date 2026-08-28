# AL Agent architecture

AL Agent uses **Session → Task → Turn**. `AgentSession` owns configuration and identity only; `TaskController` normalizes goals; `AgentRuntime` orchestrates pure-Kotlin planning/policy/tool/verification phases. Android `Activity`, `Service`, and `AccessibilityService` are lifecycle/adaptor layers, never reasoning owners.

## Turn invariant

Every side-effecting action follows: **Plan → policy/preconditions → approval if required → execute → fresh observe → verify → memory/state update → retry/replan/complete**. `ToolExecutionResult.success` is transport/execution success only and cannot complete a task by itself.

## Dependency direction

Presentation → domain/runtime → ports (`AiProvider`, `Tool`, memory, policy) → Android/network/database adapters. Optional Termux/Shizuku/SSH/OpenClaw modules depend inward; core never depends on them.

## Progressive tools

Tools are tagged CORE/UI/FILES/NETWORK/SYSTEM/MCP/ADVANCED. `ToolEligibilityFilter` emits only categories relevant to the normalized goal. Tool schemas are immutable descriptors and all calls pass through `PolicyEngine`.
