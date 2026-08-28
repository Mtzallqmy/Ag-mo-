package ai.alagent.skills.api
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
@Serializable data class SkillManifest(val id:String,val name:String,val version:String,val description:String,val permissions:Set<String> = emptySet(),val allowedTools:Set<String> = emptySet(),val inputSchema:JsonObject?=null,val outputSchema:JsonObject?=null,val examples:List<String> = emptyList())
data class SkillPackage(val manifest:SkillManifest,val instructions:String,val assets:List<String> = emptyList(),val templates:List<String> = emptyList())
interface SkillRegistry { fun all():List<SkillPackage>; fun get(id:String):SkillPackage? }
