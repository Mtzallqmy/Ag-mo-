package ai.alagent.agent.memory

import kotlinx.coroutines.flow.Flow

data class WorkingMemory(val taskId:String, val planSummary:String?=null, val todos:List<String> = emptyList(), val scratchpad:List<String> = emptyList(), val currentObservation:String?=null)
data class ConversationMemoryItem(val id:String, val text:String, val timestampEpochMs:Long)
data class LongTermMemoryItem(val id:String, val kind:String, val text:String, val confidence:Double, val updatedAtEpochMs:Long)
interface EmbeddingProvider { suspend fun embed(text:String):FloatArray }
data class VectorMatch(val id:String, val score:Float)
interface VectorStore { suspend fun upsert(id:String, vector:FloatArray); suspend fun search(vector:FloatArray, limit:Int):List<VectorMatch>; suspend fun delete(id:String) }
interface MemoryRepository { suspend fun working(taskId:String):WorkingMemory?; suspend fun saveWorking(memory:WorkingMemory); suspend fun conversation(query:String, limit:Int):List<ConversationMemoryItem>; suspend fun longTerm(ids:Set<String>):List<LongTermMemoryItem> }
class MemoryRetriever(private val embeddings:EmbeddingProvider, private val vectors:VectorStore, private val repository:MemoryRepository) {
    suspend fun retrieve(query:String, limit:Int=8):List<LongTermMemoryItem> { val v=embeddings.embed(query); val matches=vectors.search(v,limit); return repository.longTerm(matches.map{it.id}.toSet()).sortedBy { item -> matches.firstOrNull{it.id==item.id}?.let{-it.score} ?: 0f } }
}
