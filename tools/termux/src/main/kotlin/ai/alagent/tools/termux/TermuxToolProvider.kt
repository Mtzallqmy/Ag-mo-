package ai.alagent.tools.termux
/** Clean-room boundary: communicates with a separately installed Termux integration; no GPL Termux code is linked into AL Agent. */
data class AllowedCommand(val executable:String,val argumentPrefixes:Set<String> = emptySet())
class TermuxCommandPolicy(private val allowed:Set<AllowedCommand>){fun validate(argv:List<String>):Boolean{if(argv.isEmpty())return false;val rule=allowed.firstOrNull{it.executable==argv.first()}?:return false;return argv.drop(1).all{arg->!arg.contains(';')&&!arg.contains("&&")&&!arg.contains('|')&&!arg.contains('`')} }}
