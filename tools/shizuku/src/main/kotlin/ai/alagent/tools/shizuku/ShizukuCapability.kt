package ai.alagent.tools.shizuku
sealed class PrivilegedOperation(val id:String){data class QueryPackage(val packageName:String):PrivilegedOperation("package_query");data class SetKnownSetting(val namespace:String,val key:String,val value:String):PrivilegedOperation("set_known_setting")}
interface ShizukuBridge { suspend fun execute(operation:PrivilegedOperation):String }
