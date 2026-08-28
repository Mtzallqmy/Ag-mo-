package ai.alagent.agent.runtime

fun interface GoalNormalizer { fun normalize(goal:String):String }
class DefaultGoalNormalizer:GoalNormalizer { override fun normalize(goal:String):String = goal.trim().replace(Regex("""\s+""")," ").also{require(it.isNotBlank()){ "Goal must not be blank" }} }
class TaskController(private val normalizer:GoalNormalizer=DefaultGoalNormalizer()) { fun create(session:AgentSession, goal:String)=AgentTask(ai.alagent.core.model.TaskId.new(),session.id,goal,normalizer.normalize(goal),System.currentTimeMillis()) }
