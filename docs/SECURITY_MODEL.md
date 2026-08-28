# Security model

- Secrets are never persisted in Room. `AndroidKeystoreSecretStore` stores AES/GCM ciphertext in app-private preferences while the AES key remains in Android Keystore.
- Risk levels: READ_ONLY, LOW, MEDIUM, HIGH, CRITICAL.
- App tiers: NORMAL, CAUTIOUS, BLOCKED. BLOCKED targets are denied before execution; CAUTIOUS targets force approval.
- High-impact actions require an approval request showing tool, operation, target app, detected sensitive fields, reason and risk.
- Raw unrestricted shell is not a core tool. Termux, SSH and Shizuku integrations accept schema-bound/allowlisted operations only.
- Accessibility snapshots are pruned and ranked before model exposure; full raw trees should stay device-side.
- Local API binds to `127.0.0.1` by default. Non-loopback binding requires explicit enablement and bearer authentication; production remote exposure must add TLS or a secure tunnel.
- Structured logs must pass secret redaction and must never contain passwords/API keys.
