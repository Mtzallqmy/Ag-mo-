package ai.alagent.core.common

fun interface Clock { fun nowEpochMs(): Long
    companion object { val System = Clock { java.lang.System.currentTimeMillis() } }
}
