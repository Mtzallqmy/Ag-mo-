package ai.alagent.core.security

interface SecretStore { fun put(alias:String, secret:ByteArray); fun get(alias:String):ByteArray?; fun remove(alias:String) }
