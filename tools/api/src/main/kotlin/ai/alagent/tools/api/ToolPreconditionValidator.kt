package ai.alagent.tools.api

fun interface ToolPreconditionValidator {
    /** @return null if valid, otherwise a user-safe failure reason. */
    fun validate(descriptor: ToolDescriptor, request: ToolRequest): String?

    companion object {
        val Basic = ToolPreconditionValidator { descriptor, request ->
            when {
                request.arguments.isEmpty() && descriptor.inputSchema.isNotEmpty() -> "Required tool arguments are missing"
                descriptor.timeoutMs <= 0 -> "Tool timeout is invalid"
                else -> null
            }
        }
    }
}
