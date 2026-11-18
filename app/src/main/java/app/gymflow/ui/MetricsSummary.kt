package app.gymflow.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gymflow.R
import app.gymflow.domain.Metrics
import app.gymflow.domain.Profile
import app.gymflow.domain.Session
import java.time.Duration
import java.time.Instant

@Composable
fun MetricsSummary(session: Session, profile: Profile, close: () -> Unit, metrics: Metrics? = session.workout(profile.id)?.metrics) {
    EditorSheet(stringResource(R.string.read_metrics), close) {
        Muted(shortDate(session.date))
        val started = session.started
        val finished = session.finished
        if (started != null && finished != null) {
            val minutes = Duration.between(Instant.parse(started), Instant.parse(finished)).toMinutes().coerceAtLeast(0)
            Muted(stringResource(R.string.duration_minutes, minutes))
        }
        Text(profile.name)
        metrics?.bodyWeightKg?.let { Muted(stringResource(R.string.body_mass) + " " + number(it)) }
        metrics?.feeling?.let { Muted(stringResource(R.string.feeling_value, it)) }
        metrics?.cardioMinutes?.let { Muted(stringResource(R.string.cardio_value, it)) }
        metrics?.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (session.notes.isNotBlank()) Text(session.notes)
    }
}
