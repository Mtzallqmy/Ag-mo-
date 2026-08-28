package ai.alagent.ai.inference
/** JNI boundary only. Native llama.cpp is intentionally an optional build flavor/submodule, never a core runtime dependency. */
internal object LlamaCppBindings {
    init { runCatching { System.loadLibrary("alagent_llama") } }
    external fun create(modelPath:String, contextSize:Int, gpuLayers:Int):Long
    external fun destroy(handle:Long)
    external fun cancel(handle:Long)
}
