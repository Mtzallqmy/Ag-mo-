from pathlib import Path
import sys
import re

root = Path(__file__).resolve().parents[1]
settings = (root / "settings.gradle.kts").read_text()
errors: list[str] = []

required_modules = [
    ":app", ":core:model", ":core:common", ":core:security", ":core:network", ":core:database",
    ":core:logging", ":core:files", ":ai:provider-api", ":ai:provider-openai", ":ai:provider-anthropic",
    ":ai:provider-google", ":ai:provider-openrouter", ":ai:provider-local", ":ai:inference", ":agent:runtime",
    ":agent:cognition", ":agent:planning", ":agent:memory", ":agent:policy", ":agent:evaluation", ":agent:subagents",
    ":tools:api", ":tools:android", ":tools:accessibility", ":tools:intents", ":tools:files", ":tools:notifications",
    ":tools:clipboard", ":tools:web", ":tools:mcp", ":tools:termux", ":tools:ssh", ":tools:shizuku", ":skills:api",
    ":skills:runtime", ":features:home", ":features:chat", ":features:agents", ":features:models", ":features:providers",
    ":features:skills", ":features:memory", ":features:automations", ":features:settings", ":features:debug",
    ":service:agent", ":service:local-api", ":compat:openclaw", ":benchmark", ":test-utils"
]
for module in required_modules:
    if f'include("{module}")' not in settings:
        errors.append(f"missing module {module}")

# The agent runtime must remain portable/pure Kotlin.
for path in (root / "agent/runtime").rglob("*.kt"):
    source = path.read_text()
    for forbidden in ["import android.", "import androidx.compose", "import androidx.activity", "AccessibilityService"]:
        if forbidden in source:
            errors.append(f"runtime Android/UI dependency in {path}: {forbidden}")

# Presentation must not bypass abstractions and talk directly to Room entities or provider implementations.
for feature in (root / "features").glob("*"):
    for path in feature.rglob("*.kt"):
        source = path.read_text()
        if re.search(r"import\s+ai\.alagent\.core\.database\..*Entity", source):
            errors.append(f"Room entity leaked into presentation: {path}")
        if re.search(r"import\s+ai\.alagent\.ai\.provider\.(openai|anthropic|google|openrouter|local)", source):
            errors.append(f"concrete provider leaked into presentation: {path}")

for path in root.rglob("*.kt"):
    source = path.read_text()
    lowered = source.lower()
    if "globalscope" in lowered:
        errors.append(f"GlobalScope forbidden: {path}")
    if re.search(r'(api[_-]?key|password|secret|token)\s*=\s*"[^"\n]{8,}"', source, re.I):
        errors.append(f"possible embedded secret: {path}")
    if "Log.d" in source or "Log.v" in source:
        if any(word in lowered for word in ["password", "api key", "authorization", "secret"]):
            errors.append(f"potential sensitive debug logging: {path}")

# Copyleft research projects are design references only; their package names must not be imported.
for path in root.rglob("*.kt"):
    source = path.read_text()
    if "Vali-98" in source or "chatterui" in source.lower() or "com.termux" in source:
        errors.append(f"prohibited third-party source/package reference in clean-room code: {path}")

print(f"checked {sum(1 for _ in root.rglob('*.kt'))} Kotlin files")
if errors:
    print("\n".join("ERROR: " + error for error in errors))
    sys.exit(1)
print("architecture lint: PASS")
