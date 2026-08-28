package ai.alagent.ai.provider.openai
import ai.alagent.ai.provider.api.*
import ai.alagent.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable private data class ChatReq(val model:String,val messages:List<Msg>,val tools:List<JsonObject>?=null,val stream:Boolean=false,@SerialName("max_tokens") val maxTokens:Int?=null)
@Serializable private data class Msg(val role:String,val content:String)
@Serializable private data class ChatResp(val choices:List<Choice> = emptyList(),val usage:Usage?=null)
@Serializable private data class Choice(val message:RespMsg,val finish_reason:String?=null)
@Serializable private data class RespMsg(val content:String?=null,@SerialName("tool_calls") val toolCalls:List<ToolCallDto>?=null)
@Serializable private data class ToolCallDto(val id:String,val function:Fn)
@Serializable private data class Fn(val name:String,val arguments:String)
@Serializable private data class Usage(@SerialName("prompt_tokens") val input:Int?=null,@SerialName("completion_tokens") val output:Int?=null)
class OpenAiCompatibleProvider(
 override val id:String,
 private val baseUrlProvider:suspend()->String,
 private val apiKey:suspend()->String?,
 private val models:suspend()->List<ModelDescriptor>,
 private val http:HttpClient
):AiProvider{
 constructor(id:String, baseUrl:String, apiKey:suspend()->String?, models:suspend()->List<ModelDescriptor>, http:HttpClient) : this(id, { baseUrl }, apiKey, models, http)
 override suspend fun listModels()=models()
 override fun stream(request:AiRequest):Flow<AiStreamEvent> = flow {
   val toolJson=request.tools.map{t->buildJsonObject{put("type","function");putJsonObject("function"){put("name",t.id);put("description",t.description);put("parameters",t.inputSchema)}}}.takeIf{it.isNotEmpty()}
   val baseUrl = baseUrlProvider()
   val resp=http.post(baseUrl.trimEnd('/')+"/chat/completions") { contentType(ContentType.Application.Json); apiKey()?.takeIf{it.isNotBlank()}?.let{header(HttpHeaders.Authorization,"Bearer $it")}; setBody(ChatReq(request.model.id,request.messages.map{Msg(it.role.name.lowercase(),it.content)},toolJson,false,request.maxOutputTokens)) }.body<ChatResp>()
   val choice=resp.choices.firstOrNull(); choice?.message?.content?.let{emit(AiStreamEvent.TextDelta(it))}; choice?.message?.toolCalls.orEmpty().forEach{tc->val args=runCatching{Json.parseToJsonElement(tc.function.arguments).jsonObject}.getOrElse{buildJsonObject{}};emit(AiStreamEvent.ToolCall(AiToolCall(tc.id,tc.function.name,args)))}; resp.usage?.let{emit(AiStreamEvent.Usage(TokenUsage(it.input,it.output)))}; emit(AiStreamEvent.Completed(choice?.finish_reason))
 }
}
data class OpenAiCompatibleProfile(val id:String,val baseUrl:String)
object StandardOpenAiCompatibleProfiles { val OPENAI=OpenAiCompatibleProfile("openai","https://api.openai.com/v1"); val GROQ=OpenAiCompatibleProfile("groq","https://api.groq.com/openai/v1"); val DEEPSEEK=OpenAiCompatibleProfile("deepseek","https://api.deepseek.com"); val MISTRAL=OpenAiCompatibleProfile("mistral","https://api.mistral.ai/v1"); fun ollama(base:String="http://127.0.0.1:11434/v1")=OpenAiCompatibleProfile("ollama",base); fun custom(id:String,base:String)=OpenAiCompatibleProfile(id,base) }
