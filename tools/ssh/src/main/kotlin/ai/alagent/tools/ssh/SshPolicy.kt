package ai.alagent.tools.ssh
data class SshProfile(val id:String,val host:String,val port:Int=22,val username:String,val credentialAlias:String,val expectedHostKeySha256:String,val allowedCommands:Set<String>)
class SshCommandPolicy { fun allowed(profile:SshProfile,argv:List<String>)=argv.isNotEmpty() && argv.first() in profile.allowedCommands && argv.drop(1).none{it.contains(';')||it.contains("&&")||it.contains('|')} }
