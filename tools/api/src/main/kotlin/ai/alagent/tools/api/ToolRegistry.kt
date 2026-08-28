package ai.alagent.tools.api

class ToolRegistry(tools: Collection<Tool>) {
    private val byId = tools.associateBy { it.descriptor.id }.also { require(it.size == tools.size) { "Duplicate tool ids" } }
    fun require(id: String): Tool = byId[id] ?: error("Unknown tool: $id")
    fun get(id: String): Tool? = byId[id]
    fun descriptors(categories: Set<ToolCategory>? = null): List<ToolDescriptor> = byId.values.map { it.descriptor }.filter { categories == null || it.category in categories }
    fun select(ids: Set<String>): List<Tool> = ids.mapNotNull(byId::get)
}
