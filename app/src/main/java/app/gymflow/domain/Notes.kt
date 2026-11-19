package app.gymflow.domain

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class WorkoutNote(
    val id: String = newId(),
    val created: String = Instant.now().toString(),
    val updated: String = created,
    @Transient val body: String = "",
)

@Serializable
@SerialName("sport_note")
data class SportNote(
    override val id: String = newId(),
    val title: String = "Новая заметка",
    val created: String = Instant.now().toString(),
    val updated: String = created,
    val sessionId: String? = null,
    val programId: String? = null,
    val deleted: Boolean = false,
    @Transient val body: String = "",
) : Document
