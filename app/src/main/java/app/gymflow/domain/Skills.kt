package app.gymflow.domain

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SkillLevel(
    val id: String = newId(), val name: String, val goal: MovementGoal,
    val description: String = "", val exerciseIds: Set<String> = emptySet(),
    val requires: Set<String> = emptySet(), val limitations: String = "",
) {
    init { require(name.isNotBlank() && id !in requires) }
}

@Serializable
@SerialName("movement_skill")
data class MovementSkill(
    override val id: String = newId(), val name: String, val levels: List<SkillLevel> = emptyList(),
    val limitations: String = "", val relatedSkills: Set<String> = emptySet(),
) : Document {
    init {
        require(name.isNotBlank() && id !in relatedSkills)
        val byId = levels.associateBy { it.id }
        require(byId.size == levels.size && levels.all { byId.keys.containsAll(it.requires) })
        val resolved = mutableSetOf<String>()
        repeat(levels.size) { levels.filter { resolved.containsAll(it.requires) }.forEach { resolved.add(it.id) } }
        require(resolved.size == levels.size)
    }
}

@Serializable
@SerialName("skill_attempt")
data class SkillAttempt(
    val profileId: String, val skillId: String, val levelId: String, val date: String,
    val measure: Measure, val value: Double, val note: String = "", override val id: String = newId(),
) : Document {
    init {
        LocalDate.parse(date)
        require(measure.accepts(value))
    }
}

internal fun masteredLevels(skill: MovementSkill, profileId: String, attempts: List<SkillAttempt>): Set<String> {
    val achieved = mutableSetOf<String>()
    val measured = attempts.filter { it.skillId == skill.id && it.profileId == profileId }.groupBy { it.levelId }
    repeat(skill.levels.size) {
        skill.levels.filter { it.id !in achieved && achieved.containsAll(it.requires) }.forEach { level ->
            if (measured[level.id].orEmpty().any { it.measure == level.goal.measure && level.goal.met(it.value) }) achieved.add(level.id)
        }
    }
    return achieved
}
