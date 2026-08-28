# AL Agent

Native Kotlin/Jetpack Compose **local-first mobile AI agent** for Android.

The runtime is built around one invariant:

> **Plan → Act → Observe → Verify → Retry/Replan**

A tool reporting `execute().success == true` is not enough to mark a task complete. Completion must be supported by fresh evidence and explicit verification criteria.

## Current baseline

The repository currently targets:

- JDK 17
- Android SDK / compileSdk 36
- Android Gradle Plugin 8.10.0
- Gradle 8.11.1
- Kotlin 2.2.0
- Jetpack Compose + Material 3

GitHub Actions validates architecture rules, JVM/Android unit tests, Android lint, and a debug APK build on every pull request and push to `main`.

## Local build

Prerequisites:

1. JDK 17.
2. Android SDK platform 36 and build-tools 36.0.0.
3. Gradle 8.11.1 available on `PATH`.

This repository currently contains `gradle/wrapper/gradle-wrapper.properties`, but it does **not** yet contain the wrapper scripts/JAR (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`). Until a complete wrapper is committed, run Gradle directly:

```bash
python3 scripts/architecture_lint.py
gradle test testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions also publishes the debug APK and lint report as workflow artifacts.

## Architecture and implementation status

See:

- `docs/REPOSITORY_RESEARCH.md` — source-repository research and clean-room adoption decisions.
- `docs/ARCHITECTURE.md` — module boundaries and runtime architecture.
- `docs/SECURITY_MODEL.md` — permissions, approvals, risk policy, and secret handling.
- `docs/IMPLEMENTATION_STATUS.md` — what is implemented, partial, or still release-blocking.
- `docs/RELEASE_CHECKLIST.md` — validation required before a production release.

## Scope

AL Agent is local-first: a supported local model can be used without a cloud API key. Cloud providers remain optional and are routed through provider abstractions and capability checks. Privileged integrations such as SSH, Shizuku, Termux, MCP, and OpenClaw compatibility are optional surfaces and are not trusted as core runtime dependencies.

## License

Apache-2.0. Reference repositories were used for architecture and behavior research; AL Agent baseline code is clean-room authored for this project unless a file explicitly states otherwise.
