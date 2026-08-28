package ai.alagent.skills.runtime

import ai.alagent.skills.api.SkillPackage

data class SkillScanResult(val safe: Boolean, val findings: List<String>)

class SkillSecurityScanner {
    private val executablePatterns = listOf(
        Regex("""(?i)\b(curl|wget)\b.*\|\s*(sh|bash)"""),
        Regex("""(?i)chmod\s+\+x"""),
        Regex("""(?i)rm\s+-rf\s+/"""),
        Regex("""(?i)Runtime\.getRuntime\(\)\.exec"""),
        Regex("""(?i)ProcessBuilder\s*\(""")
    )

    fun scan(skill: SkillPackage): SkillScanResult {
        val findings = executablePatterns
            .filter { it.containsMatchIn(skill.instructions) }
            .map { "Prohibited executable pattern: ${it.pattern}" }
        return SkillScanResult(findings.isEmpty(), findings)
    }
}

class SkillValidator(private val knownTools: Set<String>) {
    fun validate(skill: SkillPackage): List<String> = buildList {
        if (skill.manifest.id.isBlank()) add("Missing id")
        if (skill.manifest.name.isBlank()) add("Missing name")
        if (skill.manifest.version.isBlank()) add("Missing version")
        if (skill.instructions.isBlank()) add("Missing SKILL.md instructions")
        val unknown = skill.manifest.allowedTools - knownTools
        if (unknown.isNotEmpty()) add("Unknown tools: $unknown")
        if (skill.manifest.allowedTools.size > 64) add("Skill requests an excessive tool surface")
    }
}
