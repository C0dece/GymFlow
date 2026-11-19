package app.gymflow.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.gymflow.domain.*

@Composable
internal fun BlockEditor(current: TrainingBlock?, close: () -> Unit, save: (TrainingBlock) -> Unit) {
    var type by remember { mutableStateOf(current?.type ?: BlockType.MAIN) }
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var guidance by remember { mutableStateOf(current?.guidance.orEmpty()) }
    var requirement by remember { mutableStateOf(current?.requirement ?: PlanBlock.REQUIRED) }
    var enabled by remember { mutableStateOf(current?.enabled ?: true) }
    var expanded by remember { mutableStateOf(current?.expandedOnly ?: false) }

    EditorSheet("Блок тренировки", close) {
        Choice(type, BlockType.entries, { it.label }, { type = it }, "training_block_type")
        Field(name, "Название", { name = it }, Modifier.testTag("block_name"))
        Field(guidance, "Рекомендуемые параметры и условия", { guidance = it }, singleLine = false)
        Choice(requirement, PlanBlock.entries, { it.label }, { requirement = it }, "training_block_requirement")
        FilterChip(enabled, { enabled = !enabled }, label = { Text("Включён") })
        FilterChip(expanded, { expanded = !expanded }, label = { Text("Только в расширенной или свободной тренировке") })
        Button(onClick = { save(TrainingBlock(current?.id ?: newId(), type, name.trim().ifBlank { type.label },
            requirement, guidance.trim(), enabled, expanded)) }, modifier = Modifier.testTag("save_training_block")) { Text("Сохранить") }
    }
}
