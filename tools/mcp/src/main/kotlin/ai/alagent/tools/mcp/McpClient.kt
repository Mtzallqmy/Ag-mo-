package ai.alagent.tools.mcp
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
@Serializable data class McpJsonRpcRequest(val jsonrpc:String="2.0",val id:String,val method:String,val params:JsonElement?=null)
@Serializable data class McpJsonRpcResponse(val jsonrpc:String="2.0",val id:String?=null,val result:JsonElement?=null,val error:JsonElement?=null)
data class McpEndpoint(val url:String,val bearerToken:String?=null)
class StreamableHttpMcpClient(private val http:HttpClient,private val endpoint:McpEndpoint){suspend fun call(id:String,method:String,params:JsonElement?):McpJsonRpcResponse=http.post(endpoint.url){contentType(ContentType.Application.Json);endpoint.bearerToken?.let{header(HttpHeaders.Authorization,"Bearer $it")};setBody(McpJsonRpcRequest(id=id,method=method,params=params))}.body()}
