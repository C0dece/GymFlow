package app.gymflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.gymflow.domain.restRemaining
import kotlinx.coroutines.delay

@Composable
internal fun RestTimer(deadline: String?, seconds: Int?, change: (Int?) -> Unit) {
    if (deadline == null && seconds == null) return
    val remaining by produceState(restRemaining(deadline), deadline) {
        value = restRemaining(deadline)
        while (value > 0) {
            delay(300L)
            value = restRemaining(deadline)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (deadline != null) {
            Text(if (remaining > 0) "Отдых · ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}" else "Отдых завершён",
                modifier = Modifier.testTag("rest_remaining"))
            TextButton(onClick = { change(null) }, modifier = Modifier.testTag("rest_stop")) { Text("Закрыть") }
        } else if (seconds != null) {
            TextButton(onClick = { change(seconds) }, modifier = Modifier.testTag("rest_start")) { Text("Отдых · $seconds с") }
        }
    }
}
