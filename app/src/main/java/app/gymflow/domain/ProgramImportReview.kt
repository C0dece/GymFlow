package app.gymflow.domain

internal data class ProgramInput(val uri: String, val name: String, val text: String, val targetId: String?)
internal data class ImportReview(val program: Program, val newExercises: List<Exercise>, val current: Program?)

internal const val NEW_EXERCISE = "new"

internal fun ProgramDraft.exercises(): List<Exercise> = template.days.flatMap { it.exercises }.map { it.exercise }.distinctBy { it.id }

internal fun ProgramInput.currentProgram(draft: ProgramDraft, programs: List<Program>): Program? =
    if (targetId != null) programs.firstOrNull { it.id == targetId }
    else programs.firstOrNull { it.importSource?.uri == uri } ?: programs.firstOrNull { it.id == draft.template.id }

internal fun defaultMapping(exercises: List<Exercise>, catalog: List<Exercise>, previous: Program?): Map<String, String> =
    exercises.mapNotNull { exercise ->
        val saved = previous?.importSource?.exerciseLinks?.get(exercise.id)
        val exact = catalog.filter { ProgramImport.key(it.name) == ProgramImport.key(exercise.name) }.singleOrNull()
        val match = catalog.firstOrNull { it.id == saved || it.id == exercise.id } ?: exact
        match?.let { exercise.id to it.id }
    }.toMap()

internal fun reviewImport(
    input: ProgramInput, draft: ProgramDraft, edited: String, programs: List<Program>,
    catalog: List<Exercise>, choices: Map<String, String>, newLoads: Map<String, Load> = emptyMap(),
): ImportReview {
    val current = input.currentProgram(draft, programs)
    require(input.targetId == null || current != null) { "Локальная программа больше недоступна." }
    if (current != null) {
        val source = requireNotNull(current.importSource) {
            "Программа с этим идентификатором уже существует. Задайте другой program_id для отдельной копии."
        }
        require(current.importTemplateFingerprint() == source.templateFingerprint) {
            "В шаблоне есть локальные правки. Для отдельного импорта задайте другой program_id и выберите новый файл."
        }
        require(current.id == draft.template.id) {
            "Выбранный файл относится к другой программе."
        }
    }

    val resolved = draft.exercises().associate { exercise ->
        val choice = requireNotNull(choices[exercise.id]) { "Выберите упражнение для «${exercise.name}»." }
        val mapped = if (choice == NEW_EXERCISE) {
            require(catalog.none { it.id == exercise.id || ProgramImport.key(it.name) == ProgramImport.key(exercise.name) }) {
                "Упражнение «${exercise.name}» уже есть. Выберите его в каталоге."
            }
            exercise.copy(load = newLoads[exercise.id] ?: exercise.load)
        } else requireNotNull(catalog.firstOrNull { it.id == choice }) { "Упражнение больше недоступно." }
        exercise.id to mapped
    }
    val sourceProgram = draft.template
    val result = sourceProgram.copy(
        id = current?.id ?: sourceProgram.id,
        anchor = current?.anchor ?: sourceProgram.anchor,
        enabled = current?.enabled ?: sourceProgram.enabled,
        participantIds = current?.participantIds ?: sourceProgram.participantIds,
        completionCycle = sourceProgram.completionCycle?.let { cycle ->
            cycle.copy(extensions = current?.completionCycle?.extensions ?: cycle.extensions)
        },
        days = sourceProgram.days.map { day -> day.copy(exercises = day.exercises.map {
            it.copy(exercise = resolved.getValue(it.exercise.id))
        }) },
        importSource = null,
    )
    // Validate before the save action is enabled.
    val source = ProgramSource(input.uri, input.name, draft.revision, digest(edited), result.importTemplateFingerprint(),
        draft.original, resolved.mapValues { it.value.id })
    val complete = result.copy(importSource = source)
    Markdown.write(complete)
    return ImportReview(complete, resolved.values.filter { ex -> catalog.none { it.id == ex.id } }.distinctBy { it.id }, current)
}

internal fun ExercisePlan.restLabel(): String? = restSeconds?.let { low ->
    val high = restMaxSeconds ?: low
    if (low == high) "$low с" else "$low-$high с"
}
