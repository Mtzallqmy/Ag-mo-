package ai.alagent.ai.provider.anthropic
import ai.alagent.ai.provider.api.*
import ai.alagent.core.model.ModelDescriptor
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
@Serializable private data class Req(val model:String,@kotlinx.serialization.SerialName("max_tokens") val maxTokens:Int,val messages:List<Msg>)
@Serializable private data class Msg(val role:String,val content:String)
@Serializable private data class Resp(val content:List<Block> = emptyList(),@kotlinx.serialization.SerialName("stop_reason") val stopReason:String?=null)
@Serializable private data class Block(val type:String,val text:String?=null)
class AnthropicProvider(private val key:suspend()->String?,private val models:suspend()->List<ModelDescriptor>,private val http:HttpClient):AiProvider { override val id="anthropic";override suspend fun listModels()=models();override fun stream(request:AiRequest)=flow<AiStreamEvent>{val r=http.post("https://api.anthropic.com/v1/messages"){contentType(ContentType.Application.Json);header("x-api-key",key().orEmpty());header("anthropic-version","2023-06-01");setBody(Req(request.model.id,request.maxOutputTokens?:1024,request.messages.filter{it.role!=AiMessage.Role.SYSTEM}.map{Msg(it.role.name.lowercase(),it.content)}))}.body<Resp>();r.content.mapNotNull{it.text}.forEach{emit(AiStreamEvent.TextDelta(it))};emit(AiStreamEvent.Completed(r.stopReason))} }
