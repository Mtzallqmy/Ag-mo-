# Build and release

Requirements: JDK 21 or 17, Android SDK 36, Gradle wrapper 8.11.1. Build with `./gradlew assembleDebug`; run JVM tests with `./gradlew test`; run `python3 scripts/architecture_lint.py` on every change. CI should also run Android lint, KSP/Room schema export, migration instrumentation tests, dependency/license scanning, unit tests, and release signing only from protected secrets.

No API key belongs in source, Gradle properties committed to Git, Room, logs, or test fixtures. Release builds should use Play App Signing or an organization-controlled keystore and SBOM/license inventory.
