# AL Agent

Native Kotlin/Compose **local-first mobile AI agent** production foundation.

Core invariant: **Plan → Act → Observe → Verify → Retry/Replan**. The runtime never equates `execute()==success` with goal completion.

See `docs/REPOSITORY_RESEARCH.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_MODEL.md`, and `docs/IMPLEMENTATION_STATUS.md`.

## Commands

```bash
python3 scripts/architecture_lint.py
./gradlew test
./gradlew assembleDebug
```

Apache-2.0. Reference repositories were used for architectural research; AL Agent baseline code is clean-room authored for this project.
