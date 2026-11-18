package app.gymflow.domain

import java.util.UUID
import kotlinx.serialization.Serializable

internal fun stableId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

@Serializable
internal data class LegacySession(
    val id: String,
    val title: String,
    val date: String,
    val status: SessionStatus = SessionStatus.PLANNED,
    val participants: List<Profile>,
    val exercises: List<ExerciseEntry> = emptyList(),
    val programId: String? = null,
    val programDayId: String? = null,
    val started: String? = null,
    val finished: String? = null,
    val metrics: Map<String, Metrics> = emptyMap(),
    val notes: String = "",
) {
    fun migrate(): Session {
        require(metrics.keys.all { id -> participants.any { it.id == id } })
        val ids = participants.map { it.id }.toSet()
        fun validateRoster(entry: ExerciseEntry) {
            require(entry.athletes.map { it.participantId }.distinct().size == entry.athletes.size)
            require(entry.athletes.all { it.participantId in ids })
            entry.replaced.forEach(::validateRoster)
        }
        exercises.forEach(::validateRoster)
        val workouts = participants.map { profile ->
            ParticipantWorkout(
                id = stableId("$id/${profile.id}"),
                profile = profile,
                templateId = programDayId,
                templateTitle = title,
                status = WorkoutStatus.valueOf(status.name),
                exercises = exercises.mapNotNull { it.forParticipant(profile.id) },
                metrics = metrics[profile.id] ?: Metrics(),
            )
        }
        return Session(id, title, date, status, workouts, programId, programDayId, started, finished, notes)
    }

    private fun ExerciseEntry.forParticipant(id: String): ExerciseEntry? {
        val athlete = athletes.find { it.participantId == id }
        val past = replaced.mapNotNull { it.forParticipant(id) }
        if (athlete == null && past.isEmpty()) return null
        return copy(
            plan = plan.copy(sets = plan.individual[id] ?: plan.sets, individual = emptyMap()),
            athletes = listOf(athlete ?: Athlete(id, emptyList(), skipped = true)),
            replaced = past,
        )
    }
}
