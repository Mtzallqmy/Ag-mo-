package ai.alagent.ai.provider.google
import ai.alagent.ai.provider.api.*
import ai.alagent.core.model.ModelDescriptor
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
@Serializable private data class Req(val contents:List<Content>)
@Serializable private data class Content(val role:String,val parts:List<Part>)
@Serializable private data class Part(val text:String)
@Serializable private data class Resp(val candidates:List<Candidate> = emptyList())
@Serializable private data class Candidate(val content:Content?=null,@kotlinx.serialization.SerialName("finishReason") val finishReason:String?=null)
class GeminiProvider(private val key:suspend()->String?,private val models:suspend()->List<ModelDescriptor>,private val http:HttpClient):AiProvider { override val id="gemini";override suspend fun listModels()=models();override fun stream(request:AiRequest)=flow<AiStreamEvent>{val url="https://generativelanguage.googleapis.com/v1beta/models/${request.model.id}:generateContent?key=${key().orEmpty()}";val r=http.post(url){contentType(ContentType.Application.Json);setBody(Req(request.messages.filter{it.role!=AiMessage.Role.SYSTEM}.map{Content(if(it.role==AiMessage.Role.ASSISTANT)"model" else "user",listOf(Part(it.content)))}))}.body<Resp>();r.candidates.firstOrNull()?.let{c->c.content?.parts?.forEach{emit(AiStreamEvent.TextDelta(it.text))};emit(AiStreamEvent.Completed(c.finishReason))}} }
