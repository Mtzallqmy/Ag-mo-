# AL Agent release checklist

A green debug build is necessary but not sufficient for a production release. Complete this checklist for each candidate release.

## Build and source integrity

- [ ] Architecture policy passes.
- [ ] JVM and Android unit tests pass.
- [ ] Android lint passes with no unreviewed fatal findings.
- [ ] Release build succeeds with minification/R8 configuration enabled as intended.
- [ ] Version code/name and release notes are finalized.
- [ ] Dependency updates and known vulnerability alerts are reviewed.
- [ ] Open-source licenses/NOTICE are reviewed and an SBOM or equivalent dependency inventory is archived.
- [ ] Release commit/tag is immutable and traceable to the generated artifact.

## Database and persistence

- [ ] Room migration tests pass from every supported historical schema to the current schema.
- [ ] At minimum, current v3 requires verified 1→3 and 2→3 migrations.
- [ ] Upgrade preserves sessions, tasks, messages, memories, provider/model metadata, schedules, and audit records.
- [ ] Downgrade behavior is explicitly documented; destructive downgrade is never accidental.
- [ ] Secret/provider credentials remain outside Room and are protected by Android Keystore-backed storage.

## Agent safety and correctness

- [ ] Regression tests prove `execute().success` alone cannot complete a task.
- [ ] Approval gates are exercised for high-risk and sensitive operations.
- [ ] Tool allowlists, permissions, timeouts, turn/tool/token/cost budgets, loop detection, retries, and replanning are tested.
- [ ] Failure paths never silently report success.
- [ ] Audit/log output is reviewed for secrets, tokens, prompts containing sensitive data, and raw chain-of-thought leakage.

## Network and local API

- [ ] SSRF regression suite passes for private, loopback, link-local, multicast, documentation, benchmark, shared-address, and mixed-DNS targets.
- [ ] HTTP tools and download transports are reviewed for redirects and DNS re-resolution/rebinding behavior.
- [ ] TLS certificate validation is not disabled.
- [ ] Local API defaults to loopback only.
- [ ] Any non-loopback API mode requires explicit opt-in, authentication, and secure transport.
- [ ] Provider API keys never appear in URLs, logs, crash reports, Room, or exported files unless the provider protocol strictly requires a URL credential and the risk is explicitly reviewed.

## Provider conformance

For every advertised provider/model combination:

- [ ] Text generation.
- [ ] Streaming cancellation and timeout behavior.
- [ ] Tool/function schema submission.
- [ ] Tool-call argument parsing and malformed-argument handling.
- [ ] Tool-result round trip where supported.
- [ ] Structured/JSON output where advertised.
- [ ] Usage/token accounting where available.
- [ ] System/developer instruction mapping.
- [ ] Context/output limits.
- [ ] Network/error/rate-limit handling.
- [ ] Capability metadata matches actual behavior.

## Local models

- [ ] Model checksum is validated before activation.
- [ ] Interrupted/resumed download is tested.
- [ ] Insufficient-storage and corrupted-partial-file cases are tested.
- [ ] LiteRT-LM load/generate/cancel/release behavior is tested on supported devices.
- [ ] Low-, medium-, and high-memory devices are covered.
- [ ] Thermal and memory-pressure behavior is measured.
- [ ] Optional llama.cpp packaging is validated per ABI before advertising support.

## Android/device validation

Test representative current and minimum-supported Android versions, including at least one Pixel and major OEM variants where possible.

- [ ] Accessibility enable/disable/reconnect and UI-tree action behavior.
- [ ] Notification listener enable/disable and action behavior.
- [ ] Runtime notification permission behavior.
- [ ] WorkManager one-time and periodic automation execution.
- [ ] Foreground model-download/automation notifications.
- [ ] Background restrictions, Doze, battery saver, reboot, and process death recovery.
- [ ] File/content URI handling.
- [ ] App/URL intent handling and target verification.
- [ ] Local inference lifecycle across activity/process transitions.

## Privileged/optional integrations

Do not advertise an optional integration as production-ready until its row is complete.

- [ ] MCP transport/tool adapter conformance and server trust policy.
- [ ] SSH host-key verification, credential storage, command policy, cancellation, and timeout behavior.
- [ ] Shizuku availability/permission/revocation and operation allowlist.
- [ ] Termux external execution consent, command policy, result verification, and failure isolation.
- [ ] OpenClaw compatibility adapter protocol/version tests.

## Privacy and Play release

- [ ] `allowBackup`/data extraction policy remains intentional.
- [ ] Exported activities/services/receivers/providers are reviewed.
- [ ] Privacy policy and Play Data safety declarations match actual collection/transmission.
- [ ] Sensitive permissions and accessibility usage have clear in-product disclosure and user control.
- [ ] Production signing is externalized to protected CI/Play credentials; no signing secrets are committed.
- [ ] Signed AAB is generated and installed/tested from the release path.
- [ ] Crash reporting/analytics, if introduced, are opt-in/declared as required and redact sensitive agent data.

## Performance gates

- [ ] Startup and first-render regression measured.
- [ ] Agent-turn latency and tool latency measured.
- [ ] Peak RAM during model load/inference measured.
- [ ] Long-running automation/model-download battery impact measured.
- [ ] Accessibility snapshot size/token budget measured on large UI trees.
- [ ] No unbounded queues, flows, history growth, downloads, logs, or database queries identified.

A release is considered production-ready only after all applicable blocking items are complete and evidence is attached to the release or tracking issue.
