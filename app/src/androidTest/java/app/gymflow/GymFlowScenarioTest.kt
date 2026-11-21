package app.gymflow

import android.os.ParcelFileDescriptor
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.gymflow.data.Theme
import app.gymflow.data.missingFile
import app.gymflow.domain.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 31)
class GymFlowScenarioTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as GymFlowApp
    private val root = "/sdcard/Documents"
    private val folderName = "GymFlow_QA_" + UUID.randomUUID().toString().take(8)
    private val path get() = "$root/$folderName"

    @Test fun programMarkdownFileImportsThroughSystemPickerAndReopens() {
        createVault()
        selectFolder()
        val sourceName = "$folderName-import.md"
        val external = InstrumentationRegistry.getArguments().getString("programSource")
        val raw = if (external != null) shell("cat '$external'") else """
            ---
            type: training-program
            program_schema: 1
            title: QA File Import
            schedule_mode: calendar
            weekdays:
              - MONDAY
              - THURSDAY
            ---
            ## Утро
            | Упражнение | Подходы × повторы | Отдых |
            | --- | --- | --- |
            | Тяга для импорта QA | 2 × 6-8 | 45-90 с |

            ## Вечер
            | Упражнение | Подходы × повторы | Отдых |
            | --- | --- | --- |
            | Тяга для импорта QA | 3 × 4-6 | 2 мин |
        """.trimIndent()
        val sourceFile = "$path/$sourceName"
        val draft = ProgramImport.read(raw)
        writeFile(sourceFile, raw)
        click("nav_program")
        scrollClick("import_program_file")
        val drawer = device.wait(Until.findObject(By.descContains("roots")), 4000)
        if (!device.hasObject(By.text(sourceName))) {
            drawer?.click()
            val model = shell("getprop ro.product.model").trim()
            val storage = device.wait(Until.findObject(By.text(model)), 3000)
                ?: device.findObject(By.text("Internal storage"))
            requireNotNull(storage).click()
            requireNotNull(device.wait(Until.findObject(By.text("Documents")), 5000)).click()
            var parent = device.wait(Until.findObject(By.text(folderName)), 1100)
            repeat(12) {
                if (parent == null) {
                    val x = device.displayWidth / 2
                    device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 4, 25)
                    parent = device.wait(Until.findObject(By.text(folderName)), 400)
                }
            }
            requireNotNull(parent).click()
        }
        requireNotNull(device.wait(Until.findObject(By.text(sourceName)), 5000)).click()
        await("create_missing_exercises")
        screenshot("program-import-preview")
        scrollClick("create_missing_exercises")
        ui.waitUntil(15_000) { runCatching { ui.onNodeWithTag("confirm_program_import").assertIsEnabled() }.isSuccess }
        scrollClick("confirm_program_import")
        val repo = app.repository
        ui.waitUntil(15_000) { runBlocking { repo.library.first().programs.any { it.id == draft.template.id } } }
        runBlocking { repo.flush() }
        ui.activityRule.scenario.recreate()
        click("nav_program")
        ui.onAllNodesWithText(draft.template.title).onFirst().performScrollTo().assertIsDisplayed()
        val state = runBlocking { repo.library.first() }
        val actual = state.programs.single { it.id == draft.template.id }
        assertEquals(draft.template.days.map { it.title }, actual.days.map { it.title })
        assertEquals(draft.template.days.map { it.exercises.size }, actual.days.map { it.exercises.size })
        assertEquals(draft.template.completionCycle, actual.completionCycle)
        assertEquals(raw, shell("cat '$sourceFile'"))
        assertTrue(state.sessions.none { it.status == SessionStatus.COMPLETED })
        val roundtrip = Markdown.read(Markdown.write(actual))?.document
        assertEquals(actual, roundtrip)

        if (external == null) {
            val profile = Profile(name = "Импорт QA")
            val active = scheduledSession(actual, actual.days.first(), LocalDate.now(), listOf(profile)).start()
            runBlocking { repo.update { listOf(profile, active) }; repo.flush() }
            val updated = raw.replace("2 × 6-8", "4 × 6-8")
            writeFile(sourceFile, updated)
            val refresh = ui.onAllNodesWithText("Проверить источник")
            if (refresh.fetchSemanticsNodes().isEmpty()) ui.onNodeWithText(actual.title).performScrollTo().performClick()
            ui.onNodeWithText("Проверить источник").performScrollTo().performClick()
            ui.waitUntil(15_000) { runCatching { ui.onNodeWithTag("confirm_program_import").assertIsEnabled() }.isSuccess }
            scrollClick("confirm_program_import")
            ui.waitUntil(15_000) { runBlocking {
                repo.library.first().programs.singleOrNull()?.days?.first()?.exercises?.single()?.sets?.size == 4
            } }
            val refreshed = runBlocking { repo.library.first() }
            assertEquals(actual.id, refreshed.programs.single().id)
            assertEquals(actual.days.first().id, refreshed.programs.single().days.first().id)
            assertEquals(active, refreshed.sessions.single { it.id == active.id })
            assertEquals(updated, shell("cat '$sourceFile'"))
        }
    }

    @Test fun completionCycleWithRestOptionalBlocksAndCalendarPartner() {
        createVault()
        selectFolder()
        val athlete = Profile(name = "Цикл QA")
        val partner = Profile(name = "Календарь QA")
        val date = LocalDate.now()
        val strength = TrainingBlock(type = BlockType.MAIN, name = "Силовой блок QA")
        val cardio = TrainingBlock(type = BlockType.CARDIO, name = "Кардио блок QA", requirement = PlanBlock.OPTIONAL)
        val skill = TrainingBlock(type = BlockType.MOBILITY, name = "Навык блок QA", requirement = PlanBlock.OPTIONAL)
        val first = WorkoutTemplate(title = "Верх цикла QA", blocks = listOf(strength, cardio, skill), exercises = listOf(
            ExercisePlan(Exercise(name = "Жим цикла QA", load = Load.MACHINE), listOf(SetPlan(90.0, role = SetRole.RAMP), SetPlan(20.0)),
                blockId = strength.id, restSeconds = 120, autoRest = true),
            ExercisePlan(Exercise(name = "Дорожка цикла QA", cardio = CardioType.TREADMILL),
                List(2) { SetPlan(weightKnown = false, repsKnown = false) }, blockId = cardio.id),
            ExercisePlan(Exercise(name = "Баланс цикла QA"), listOf(SetPlan(role = SetRole.TECHNIQUE)), blockId = skill.id),
        ))
        val next = first.copy(id = newId(), title = "Низ цикла QA")
        val cycle = Program(title = "Программа цикла QA", days = listOf(first, next), enabled = true, cycle = true,
            anchor = date.toString(), participantIds = listOf(athlete.id), completionCycle = CompletionCycle(7, targetSessions = 3))
        val fixed = WorkoutTemplate(title = "Календарная тренировка QA", weekday = date.plusDays(1).dayOfWeek, exercises = listOf(
            ExercisePlan(Exercise(name = "Тяга партнёра QA", load = Load.MACHINE), listOf(SetPlan(35.0)))))
        val calendar = Program(title = "Календарная программа QA", days = listOf(fixed), enabled = true,
            anchor = date.toString(), participantIds = listOf(partner.id))
        val repo = app.repository
        writeFile("$path/cycle-inbound.md", Markdown.write(cycle))
        runBlocking {
            repo.refresh()
            repo.settings.update(repo.settings.values.first().copy(athlete = athlete.id, rotation = false))
            repo.update { listOf(athlete, partner, calendar) }
            repo.schedule(date)
            val appointment = repo.library.first().sessions.single { it.programId == cycle.id }
            repo.editSession(appointment.id) { it.reschedule(date.plusDays(1)) }
            repo.flush()
        }
        fun completed() = runBlocking { cyclePosition(cycle, athlete.id, repo.library.first().sessions).completed }
        scrollClick("prepare_cycle_${athlete.id}")
        await("plan_preview")
        assertEquals(0, completed())
        screenshot("cycle-plan")
        click("confirm_start_plan")
        scrollClick("complete_set_0")
        await("rest_remaining")
        scrollClick("complete_set_1")
        ui.activityRule.scenario.recreate()
        await("rest_remaining")
        screenshot("cycle-rest")
        assertEquals(0, completed())
        if (InstrumentationRegistry.getArguments().getString("cycleCheckpoint") == "true") {
            withActiveFile { _, session ->
                assertEquals(1, session.workout(athlete.id)?.cycleSlot?.sequence)
                assertEquals(2, session.workout(athlete.id)?.completed)
                assertNotNull(session.workout(athlete.id)?.restUntil)
            }
            return
        }
        click("rest_stop")
        click("next_exercise")
        val cardioId = runBlocking { repo.library.first().active()!!.workout(athlete.id)!!.exercises[1].id }
        val cardioSet = hasTestTag("complete_set_0") and hasAnyAncestor(hasTestTag("exercise_card_$cardioId"))
        ui.waitUntil(10_000) { ui.onAllNodes(cardioSet).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(cardioSet).performScrollTo().performClick()
        replace("measure_DURATION", "600")
        replace("measure_DISTANCE", "1500")
        hideKeyboard()
        scrollClick("save_set")
        click("exercise_menu")
        click("skip_training_block")
        click("next_exercise")
        click("exercise_menu")
        click("skip_training_block")
        click("workout_menu")
        click("add_person")
        chooseTemplate(fixed.title)
        ui.onNodeWithText(partner.name).performScrollTo().performClick()
        await("participant_1")
        scrollClick("add_set")
        scrollClick("complete_set_0")
        scrollClick("complete_set_1")
        click("participant_0")
        click("workout_menu")
        click("finish_person")
        click("confirm_finish_person")
        ui.waitUntil(12_000) { completed() == 1 }
        withActiveFile { _, session ->
            assertTrue(session.workout(athlete.id)?.cycleCredited == true)
            assertEquals(date.toString(), session.date)
            assertEquals(date.plusDays(1).toString(), session.scheduledFor)
            assertEquals(WorkoutStatus.ACTIVE, session.workout(partner.id)?.status)
            assertEquals(2, session.workout(partner.id)?.completed)
            assertNull(session.workout(partner.id)?.cycleSlot)
            val aerobic = session.workout(athlete.id)!!.exercises[1].athletes.single().sets
            assertEquals(600.0, aerobic.first().actual!!.measurements.getValue(Measure.DURATION), 0.0)
            assertEquals(SetStatus.SKIPPED, aerobic.last().status)
            assertEquals(20.0, performance(listOf(session), athlete.id).first { it.exercise.name == "Жим цикла QA" }.maxKg, 0.0)
        }
        click("participant_1")
        click("workout_menu")
        click("finish_group")
        click("confirm_finish")
        await("start_training")
        assertEquals(1, completed())
        ui.onNodeWithText("Завершено 1 из 3").performScrollTo().assertIsDisplayed()
        val zip = File(app.cacheDir, "qa-cycle-${newId()}.zip")
        try {
            runBlocking { repo.export(android.net.Uri.fromFile(zip), false) }
            java.util.zip.ZipFile(zip).use { archive ->
                val documents = archive.entries().asSequence().filter { it.name.endsWith(".md") }.mapNotNull { file ->
                    archive.getInputStream(file).bufferedReader().use { Markdown.read(it.readText())?.document }
                }.toList()
                assertTrue(cycle in documents && calendar in documents)
                assertEquals(1, cyclePosition(cycle, athlete.id, documents.filterIsInstance<Session>()).completed)
            }
        } finally { zip.delete() }

    }

    @Test fun adaptiveMovementsPersistSeparatelyThroughSafAndExport() {
        createVault()
        selectFolder()
        val main = Profile(name = "Движения QA")
        val family = MovementFamily(name = "Семейство QA")
        val base = Exercise(name = "Базовая тяга QA", load = Load.MACHINE, movement = MovementDetails(family.id))
        val variant = Exercise(name = "Пауза QA", load = Load.MACHINE, movement = MovementDetails(
            family.id, base.id, VariationType.PAUSE, measures = setOf(Measure.HOLD)))
        val level = SkillLevel(name = "Удержание QA", goal = MovementGoal(Measure.HOLD, 12.0))
        val skill = MovementSkill(name = "Контроль QA", levels = listOf(level))
        writeFile("$path/inbound-family.md", Markdown.write(family))
        val active = Session(title = "Движения QA", date = LocalDate.now().toString(), participants = listOf(main))
            .start().addExercise(ExercisePlan(base, List(3) { SetPlan(20.0) }))
        val repo = app.repository
        runBlocking {
            repo.refresh()
            repo.update { listOf(main, base, variant, skill, active) }
            repo.flush()
        }
        scrollClick("open_progression")
        scrollClick("open_movements")
        ui.onNodeWithText(family.name).performScrollTo().performClick()
        screenshot("movements-family")
        ui.onNodeWithText(base.name).performScrollTo().performClick()
        scrollClick("movement_track")
        scrollClick("track_role")
        scrollClick("track_role_ACTIVE")
        replace("track_target", "8")
        hideKeyboard()
        scrollClick("save_track")
        ui.waitUntil(15_000) { runBlocking {
            repo.library.first().movementTracks.any { it.profileId == main.id && it.exerciseId == base.id && it.role == MovementRole.ACTIVE }
        } }
        click("movement_back")
        click("movement_back")
        scrollClick("movement_tab_1")
        ui.onNodeWithText("Фильтры каталога").performScrollTo().performClick()
        scrollClick("filter_mastery")
        scrollClick("filter_mastery_1")
        ui.onNodeWithText("Упражнения не найдены.").performScrollTo().assertIsDisplayed()
        scrollClick("movement_tab_2")
        ui.onNodeWithText(skill.name).performScrollTo().performClick()
        scrollClick("add_skill_attempt")
        replace("skill_attempt_value", "14")
        hideKeyboard()
        scrollClick("save_skill_attempt")
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Критерий подтверждён").fetchSemanticsNodes().isNotEmpty() }
        screenshot("movements-skill")
        click("movement_back")
        await("movement_tab_2")
        click("movement_back")
        click("progression_back")
        click("start_training")
        scrollClick("complete_set_0")
        click("workout_menu")
        click("training_volume")
        scrollClick("volume_MINIMUM")
        withActiveFile { _, session -> assertEquals(2, session.defaultWorkout.exercises.single().athletes.single().sets.count { it.deferred }) }
        click("workout_menu")
        click("training_volume")
        scrollClick("volume_NORMAL")
        click("exercise_menu")
        click("exercise_variations")
        scrollClick("use_variation_${variant.id}")
        ui.waitUntil(15_000) { ui.onAllNodesWithText(variant.name).fetchSemanticsNodes().isNotEmpty() }
        scrollClick("complete_set_0")
        replace("measure_HOLD", "14")
        hideKeyboard()
        screenshot("movements-set")
        scrollClick("save_set")
        withActiveFile { _, session ->
            val workout = session.defaultWorkout
            val row = workout.exercises.single()
            assertEquals(3, workout.originalPlan?.single()?.sets?.size)
            assertEquals(variant.id, row.exercise.id)
            assertEquals(1, row.replaced.single().athletes.single().sets.count { it.status == SetStatus.COMPLETED })
            val actual = row.athletes.single().sets.first().actual!!
            assertFalse(actual.weightKnown)
            assertFalse(actual.repsKnown)
            assertEquals(14.0, actual.measurements.getValue(Measure.HOLD), 0.0)
        }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) { ui.onAllNodesWithText(variant.name).fetchSemanticsNodes().isNotEmpty() }
        val zip = File(app.cacheDir, "qa-movements-${newId()}.zip")
        try {
            runBlocking { repo.export(android.net.Uri.fromFile(zip), false) }
            java.util.zip.ZipFile(zip).use { archive ->
                val documents = archive.entries().asSequence().filter { it.name.endsWith(".md") }.mapNotNull { file ->
                    archive.getInputStream(file).bufferedReader().use { Markdown.read(it.readText())?.document }
                }.toList()
                assertTrue(family in documents && skill in documents)
                assertEquals(14.0, documents.filterIsInstance<SkillAttempt>().single().value, 0.0)
                assertEquals(MovementRole.ACTIVE, documents.filterIsInstance<MovementTrack>().single().role)
            }
        } finally { zip.delete() }
    }

    @Test fun changesScheduledDayFromHomeWithOrWithoutSwap() {
        createVault()
        selectFolder()
        val main = Profile(name = "Расписание QA")
        fun day(name: String) = WorkoutTemplate(title = name, exercises = listOf(
            ExercisePlan(Exercise(name = "Упражнение $name"), listOf(SetPlan(20.0)))
        ))
        val back = day("Спина QA")
        val legs = day("Ноги QA")
        val date = LocalDate.now()
        val program = Program(title = "Сдвиг QA", days = listOf(back, legs), cycle = true, enabled = true,
            anchor = date.toString(), interval = 2, participantIds = listOf(main.id))
        val today = scheduledSession(program, back, date, listOf(main))
        val later = scheduledSession(program, legs, date.plusDays(2), listOf(main))
        val repo = app.repository
        runBlocking { repo.update { listOf(main, program, today, later) }; repo.flush() }
        fun choose(day: WorkoutTemplate, action: String) {
            scrollClick("planned_switch_day")
            scrollClick("planned_day_${day.id}")
            if (action == "day_swap") {
                ui.onNodeWithTag(action).performScrollTo()
                screenshot("planned-day-options")
            }
            scrollClick(action)
        }
        fun saved(id: String, day: WorkoutTemplate): Session {
            ui.waitUntil(15_000) { runBlocking {
                repo.library.first().sessions.any { it.id == id && it.defaultWorkout.templateId == day.id }
            } }
            return runBlocking { repo.library.first().sessions.single { it.id == id } }
        }
        await("planned_switch_day")
        screenshot("planned-day-home")
        choose(legs, "day_only_once")
        assertEquals(today.date, saved(today.id, legs).date)
        assertEquals(later, saved(later.id, legs))
        choose(back, "day_only_once")
        saved(today.id, back)
        choose(legs, "day_swap")
        assertEquals(today.date, saved(today.id, legs).date)
        assertEquals(later.date, saved(later.id, back).date)
        ui.activityRule.scenario.recreate()
        await("planned_switch_day")
        ui.onAllNodesWithText(legs.title).onFirst().assertIsDisplayed()
        runBlocking { repo.schedule(date); repo.schedule(date.plusDays(2)); repo.flush() }
        val files = shell("find '$path/Тренировки' -type f -name '*.md'").lineSequence().filter { it.startsWith(path) }.toList()
        assertEquals(2, files.size)
        val moved = files.single { Markdown.read(shell("cat '$it'"))?.document?.id == later.id }
        assertEquals(back.id, (Markdown.read(shell("cat '$moved'"))?.document as Session).defaultWorkout.templateId)
    }

    @Test fun switchesWorkoutDaysAndCorrectsCompletedMarkdownResults() {
        createVault()
        selectFolder()
        val partner = Profile(name = "Партнёр QA")
        val athlete = Profile(name = "Атлет QA")
        fun plan(name: String) = ExercisePlan(Exercise(name = name, load = Load.MACHINE), List(2) { SetPlan(20.0, 8, 10) })
        val legs = WorkoutTemplate(title = "Ноги QA", exercises = listOf(plan("Жим ногами QA"), plan("Сгибания QA")))
        val back = WorkoutTemplate(title = "Спина QA", exercises = listOf(plan("Тяга QA")))
        val program = Program(title = "Цикл QA", days = listOf(legs, back))
        val source = scheduledSession(program, legs, LocalDate.now(), listOf(partner, athlete)).start()
        runBlocking {
            val repository = app.repository
            repository.settings.update(repository.settings.values.first().copy(rotation = false, theme = Theme.DARK))
            repository.update { listOf(program, partner, athlete, source) }
            repository.flush()
        }

        click("home_switch_day")
        await("start_day_${back.id}")
        device.pressBack()
        click("participant_1")
        click("next_exercise")
        scrollClick("complete_set_0")
        click("switch_day")
        screenshot("day-picker")
        scrollClick("start_day_${back.id}")
        withActiveFile { _, session ->
            assertEquals(source.id, session.id)
            assertEquals(source.workout(partner.id), session.workout(partner.id))
            assertEquals(back.id, session.workout(athlete.id)?.templateId)
            assertEquals(1, session.otherWorkouts.single().completed)
        }
        ui.activityRule.scenario.recreate()
        await("exercise_title")
        ui.onNodeWithText("Тяга QA").assertIsDisplayed()
        device.pressBack()
        click("start_training")
        await("exercise_title")
        ui.onNodeWithText("Тяга QA").assertIsDisplayed()
        click("switch_day")
        scrollClick("start_day_${legs.id}")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Сгибания QA").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("complete_set_0").assertIsOn()
        click("workout_menu")
        click("finish_person")
        click("confirm_finish_person")
        withActiveFile { _, session -> assertEquals(WorkoutStatus.COMPLETED, session.workout(athlete.id)?.status) }
        click("switch_day")
        scrollClick("start_day_${back.id}")
        withActiveFile { _, session -> assertEquals(back.id, session.workout(athlete.id)?.templateId) }
        click("switch_day")
        scrollClick("review_day_${legs.id}")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Редактирование результатов").fetchSemanticsNodes().isNotEmpty() }
        screenshot("day-results")
        scrollClick("edit_set_0")
        replace("set_weight", "27,5")
        replace("set_reps", "9")
        hideKeyboard()
        scrollClick("save_set")
        withActiveFile { _, session ->
            val corrected = session.otherWorkouts.single()
            assertEquals(WorkoutStatus.COMPLETED, corrected.status)
            assertEquals(Actual(27.5, 9), corrected.exercises.last().athletes.single().sets.first().actual)
            assertEquals(27.5, performance(listOf(session), athlete.id).single().maxKg, 0.0)
            assertEquals(source.workout(partner.id), session.workout(partner.id))
        }
        ui.waitUntil(10_000) { runBlocking {
            app.repository.library.first().reviews.any { review ->
                review.sessionId == source.id && review.profileId == athlete.id && review.decisions.any { decision ->
                    decision.exerciseName == "Сгибания QA" && decision.evidence.any { it.maxWeightKg == 27.5 }
                }
            }
        } }
        scrollClick("add_set")
        scrollClick("save_set")
        scrollClick("rir_1")
        withActiveFile { _, session ->
            val corrected = session.otherWorkouts.single()
            assertEquals(3, corrected.exercises.last().athletes.single().sets.size)
            assertEquals(2, corrected.completed)
            assertEquals(1, corrected.exercises.last().athletes.single().rir)
        }
        click("switch_day")
        scrollClick("start_day_${legs.id}")
        withActiveFile { _, session ->
            assertEquals(legs.id, session.workout(athlete.id)?.templateId)
            assertEquals(0, session.workout(athlete.id)?.completed)
            assertEquals(1, session.otherWorkouts.count { it.templateId == legs.id && it.status == WorkoutStatus.COMPLETED })
            assertEquals(1, session.allWorkouts.count { it.profile.id == athlete.id && it.status == WorkoutStatus.ACTIVE })
        }
        click("switch_day")
        scrollClick("start_day_${back.id}")
        withActiveFile { _, session ->
            assertEquals(back.id, session.workout(athlete.id)?.templateId)
            assertEquals(3, session.allWorkouts.count { it.profile.id == athlete.id })
        }
        assertEquals(1, shell("find '$path/Тренировки' -type f -name '*.md'").lineSequence().count { it.startsWith(path) })
    }

    @Test fun independentWorkoutsUseSafAndSurviveActivityRecreation() {
        createVault()
        selectFolder()
        runBlocking { app.repository.settings.update(app.repository.settings.values.first().copy(rotation = true)) }
        click("nav_history")
        ui.onNodeWithText("История пока пуста").assertIsDisplayed()
        click("nav_home")
        fun plan(name: String, sets: Int) = ExercisePlan(Exercise(name = name, load = Load.MACHINE),
            List(sets) { SetPlan(weight = 20.0, repsMin = 8, repsMax = 10) })
        val program = Program(title = "Программа QA", days = listOf(
            WorkoutTemplate(title = "Спина QA", exercises = listOf(plan("Тяга QA", 3))),
            WorkoutTemplate(title = "Грудь QA", exercises = listOf(plan("Жим QA", 1))),
            WorkoutTemplate(title = "Ноги QA", exercises = listOf(plan("Платформа QA", 2), plan("Сгибания QA", 3))),
        ))
        runBlocking { app.repository.save(program); app.repository.flush() }
        screenshot("v2-home")

        click("view_plan")
        chooseTemplate("Спина QA")
        scrollClick("plan_exercise_0")
        ui.onNodeWithText("1. Тяга QA").assertIsDisplayed()
        screenshot("v2-preview")
        assertNull(findFile(SessionStatus.ACTIVE))
        assertNull(findFile(SessionStatus.PLANNED))
        device.pressBack()
        click("view_plan")
        chooseTemplate("Спина QA")
        click("confirm_start_plan")
        await("complete_set_0")
        ui.onNodeWithTag("participants_tabs").assertDoesNotExist()
        click("complete_set_0")
        withActiveFile { _, session ->
            assertEquals(1, session.workouts.size)
            assertEquals(1, session.workouts[0].completed)
        }
        click("edit_set_1")
        await("set_weight")
        replace("set_weight", "21,5")
        replace("set_reps", "9")
        screenshot("v2-set-editor")
        hideKeyboard()
        scrollClick("save_set")
        scrollClick("rir_2")
        withActiveFile { _, session ->
            val row = session.workouts[0].exercises[0].athletes.single()
            assertEquals(Actual(21.5, 9), row.sets[1].actual)
            assertEquals(2, row.rir)
        }
        addPerson("Партнёр QA", "Грудь QA")
        await("participant_1")
        ui.onNodeWithText("Жим QA").assertIsDisplayed()
        addPerson("Третий QA", "Ноги QA")
        await("participant_2")
        ui.onNodeWithText("Платформа QA").assertIsDisplayed()

        click("participant_0")
        scrollClick("complete_set_2")
        selected("participant_1")
        ui.onNodeWithText("Жим QA").assertIsDisplayed()
        scrollClick("complete_set_0")
        selected("participant_2")
        ui.onNodeWithTag("exercise_pager").performTouchInput { swipeLeft() }
        ui.waitUntil(12_000) { runCatching { ui.onNodeWithText("Сгибания QA").assertIsDisplayed() }.isSuccess }
        click("workout_menu")
        click("pin_person")
        scrollClick("complete_set_0")
        selected("participant_2")
        screenshot("v2-three-participants")
        click("participant_0")
        ui.onNodeWithText("Тяга QA").assertIsDisplayed()
        ui.onNodeWithTag("complete_set_0").assertIsOn()

        val savedGroup = waitForFile(SessionStatus.ACTIVE).second
        val groupId = savedGroup.id
        withActiveFile { _, session ->
            val members = session.workouts
            assertEquals(listOf(3, 1, 3), listOf(
                members[0].exercises[0].athletes.single().sets.size,
                members[1].exercises[0].athletes.single().sets.size,
                members[2].exercises[1].athletes.single().sets.size,
            ))
            assertEquals("Сгибания QA", members[2].exercises[members[2].currentIndex].exercise.name)
            assertEquals(listOf(3, 1, 1), members.map { it.completed })
        }
        ui.activityRule.scenario.recreate()
        await("participant_2")
        click("participant_2")
        ui.onNodeWithText("Сгибания QA").assertIsDisplayed()
        ui.onNodeWithTag("complete_set_0").assertIsOn()

        click("participant_0")
        click("workout_menu")
        click("finish_person")
        click("confirm_finish_person")
        withActiveFile { _, session -> assertEquals(WorkoutStatus.COMPLETED, session.workouts[0].status) }
        click("participant_2")
        addExercise("Дополнительный подход QA")
        ui.waitUntil(12_000) { runCatching { ui.onNodeWithText("Дополнительный подход QA").assertIsDisplayed() }.isSuccess }
        click("exercise_menu")
        ui.onNodeWithText("Заменить упражнение").performClick()
        replace("exercise_name", "Дополнительное упражнение QA")
        hideKeyboard()
        scrollClick("save_exercise")
        ui.waitUntil(12_000) { runCatching { ui.onNodeWithText("Дополнительное упражнение QA").assertIsDisplayed() }.isSuccess }
        click("exercise_menu")
        click("skip_exercise")
        ui.waitUntil(12_000) { ui.onAllNodesWithText("Упражнение пропущено").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Упражнение пропущено").performScrollTo().assertIsDisplayed()
        click("workout_menu")
        click("finish_group")
        click("confirm_finish")
        val (filename, saved) = waitForFile(SessionStatus.COMPLETED)
        assertEquals(groupId, saved.id)
        assertEquals(listOf(1, 1, 3), saved.workouts.map { it.exercises.size })
        assertEquals(WorkoutStatus.COMPLETED, saved.workouts[0].status)
        assertEquals(WorkoutStatus.CANCELLED, saved.workouts[2].status)
        assertEquals("Дополнительный подход QA", saved.workouts[2].exercises.last().replaced.single().exercise.name)
        val raw = shell("cat '$filename'")
        assertTrue(raw.contains("gymflow_schema: 7"))
        assertTrue(raw.contains("Имя: Партнёр QA"))
        assertTrue(raw.contains("Название дня: Грудь QA"))
        assertTrue(raw.contains("| 21.5 | 9 |"))
        val edit = raw + "\n## Заметка Obsidian\n\n[[Журнал]]\n"
        writeFile(filename, edit)
        runBlocking { app.repository.refresh() }
        val record = runBlocking { app.repository.library.first().records.first { it.id == saved.id } }
        assertTrue(Markdown.read(record.markdown)?.freeText.orEmpty().contains("[[Журнал]]"))
        click("nav_history")
        await("history_session")
        screenshot("v2-history")
        scrollClick("repeat_history")
        click("confirm_start_plan")
        await("participant_0")
        val copy = waitForFile(SessionStatus.ACTIVE).second
        assertNotEquals(saved.id, copy.id)
        assertEquals(saved.workouts.map { it.exercises.size }, copy.workouts.map { it.exercises.size })
        assertTrue(copy.workouts.flatMap { it.exercises }.flatMap { it.athletes.single().sets }.all { it.actual == null })
        assertEquals(edit, shell("cat '$filename'"))
        screenshot("v2-repeated")
        click("participant_2")
        click("next_exercise")
        withActiveFile { _, session -> assertEquals(1, session.workouts[2].currentIndex) }
    }

    @Test fun profileColorsSurviveRapidChangesAndActivityRecreation() {
        createVault()
        selectFolder()
        click("nav_settings")
        scrollClick("settings_add_person")
        replace("person_name", "Основной QA")
        hideKeyboard()
        scrollClick("save_person")
        ui.waitUntil(15_000) { runBlocking { app.repository.library.first().profiles.any { it.name == "Основной QA" } } }

        val partner = Profile(name = "Партнёр цвет QA")
        val third = Profile(name = "Третий цвет QA")
        runBlocking { app.repository.save(partner); app.repository.save(third); app.repository.flush() }
        val main = runBlocking { app.repository.library.first().profiles.single { it.name == "Основной QA" } }
        listOf(main, partner, third).forEachIndexed { index, profile ->
            scrollClick("profile_${profile.id}")
            for (color in 1..index + 1) click("profile_color_$color")
            scrollClick("save_profile")
        }
        val expected = mapOf(main.id to 0xFF87C16E.toInt(), partner.id to 0xFF7FB2EF.toInt(), third.id to 0xFFCF91EA.toInt())
        ui.waitUntil(15_000) { runBlocking {
            val profiles = app.repository.library.first().profiles.associateBy { it.id }
            expected.all { (id, color) -> profiles[id]?.color == color }
        } }
        runBlocking { app.repository.flush() }
        ui.activityRule.scenario.recreate()
        await("nav_settings")
        click("nav_settings")
        ui.waitUntil(15_000) { runBlocking {
            val profiles = app.repository.library.first().profiles.associateBy { it.id }
            expected.all { (id, color) -> profiles[id]?.color == color }
        } }
        scrollClick("profile_${partner.id}")
        await("profile_color_2")
        screenshot("v3-profile-colors")
        ui.onNodeWithTag("profile_color_2").assertIsSelected()
        scrollClick("save_profile")
        val disk = shell("find '$path' -type f -name '*.md'").lineSequence().filter { it.startsWith("$path/") }.toList()
        expected.forEach { (id, color) ->
            val filename = disk.single { Markdown.read(shell("cat '$it'"))?.document?.id == id }
            assertEquals(color, (Markdown.read(shell("cat '$filename'"))?.document as Profile).color)
        }
    }

    @Test fun progressionAcrossTwoTrainingsUsesSeparatePoliciesAndWritesReceipts() {
        createVault()
        selectFolder()
        val main = Profile(name = "Алексей прогресс QA")
        val partner = Profile(name = "Партнёр прогресс QA")
        val machine = Exercise(name = "Жим прогресс QA", load = Load.MACHINE)
        val baseline = ExercisePlan(machine, List(2) { SetPlan(20.0, 8, 10) })
        val program = Program(title = "Прогресс QA", days = listOf(WorkoutTemplate(title = "День прогрессии QA", exercises = listOf(baseline))))
        val earlier = Session(title = "Предыдущий QA", date = LocalDate.now().minusDays(2).toString(),
            participants = listOf(main)).start().addExercise(baseline)
        val entry = earlier.workouts.single().exercises.single()
        runBlocking {
            val repo = app.repository
            repo.save(main); repo.save(partner); repo.save(machine); repo.save(program)
            repo.settings.update(repo.settings.values.first().copy(athlete = main.id, rotation = false))
            repo.save(earlier.editAthlete(entry.id, main.id) { a ->
                a.copy(sets = a.sets.map(SetEntry::toggle), rir = 2)
            }.finish())
            repo.flush()
        }

        fun configure(name: String, choice: ProgressionStrategy) {
            scrollClick("open_progression")
            scrollClick("progression_profile")
            ui.onAllNodesWithText(name).onLast().performClick()
            scrollClick("configure_progression")
            scrollClick("strategy")
            scrollClick("strategy_${choice.name}")
            scrollClick("save_policy")
            await("configure_progression")
        }
        configure(partner.name, ProgressionStrategy.MAINTAIN)
        click("progression_back")
        configure(main.name, ProgressionStrategy.DOUBLE)
        click("progression_back")

        scrollClick("view_plan")
        chooseTemplate("День прогрессии QA")
        click("confirm_start_plan")
        scrollClick("complete_set_0")
        scrollClick("complete_set_1")
        scrollClick("rir_2")
        click("finish_workout")
        click("confirm_finish")
        await("start_training")
        ui.waitUntil(15_000) { runBlocking {
            app.repository.library.first().reviews.any {
                it.profileId == main.id && it.decisions.singleOrNull()?.proposed?.firstOrNull()?.weight == 21.0
            }
        } }
        configure(main.name, ProgressionStrategy.LINEAR)
        ui.waitUntil(15_000) { runBlocking {
            app.repository.library.first().policies.any { it.scope.profileId == main.id && it.ruleSet.strategy == ProgressionStrategy.LINEAR }
        } }
        click("progression_back")
        click("nav_history")
        ui.onAllNodesWithTag("repeat_history").onFirst().performScrollTo().performClick()
        scrollClick("plan_exercise_0")
        await("accept_progression_0")
        screenshot("v3-progression-recommendation")
        scrollClick("accept_progression_0")
        click("confirm_start_plan")
        await("complete_set_0")
        withActiveFile { _, session ->
            assertEquals(21.0, session.workouts.single().exercises.single().athletes.single().sets.first().plan.weight, 0.0)
            val receipt = session.progression.single()
            assertEquals(UserProgressionChoice.ACCEPTED, receipt.choice)
            assertEquals(ProgressionStrategy.LINEAR, receipt.decision.strategy)
            assertTrue(session.workouts.single().exercises.single().athletes.single().sets.all { it.actual == null })
        }
        scrollClick("complete_set_0")
        scrollClick("complete_set_1")
        scrollClick("rir_2")
        click("workout_menu")
        click("add_person")
        chooseTemplate("День прогрессии QA")
        ui.onNodeWithText(partner.name).performScrollTo().performClick()
        await("participant_1")
        scrollClick("complete_set_0")
        scrollClick("complete_set_1")
        scrollClick("rir_2")
        click("workout_menu")
        click("finish_group")
        click("confirm_finish")
        click("nav_home")
        await("start_training")
        ui.waitUntil(15_000) { runBlocking {
            app.repository.library.first().reviews.any {
                it.profileId == partner.id && it.decisions.singleOrNull()?.reason == ProgressionReason.DISABLED
            }
        } }
        val summary = runBlocking { app.repository.library.first() }
        val group = summary.sessions.first { it.status == SessionStatus.COMPLETED && it.workouts.size == 2 }
        assertEquals(listOf(21.0, 20.0), group.workouts.map { it.exercises.single().athletes.single().sets.first().actual!!.weight })
        assertEquals(2, summary.reviews.count { it.sessionId == group.id })
        assertEquals(group.id, summary.sessions.first { it.status == SessionStatus.COMPLETED }.id)
        ui.activityRule.scenario.recreate()
        await("start_training")
        scrollClick("open_progression")
        screenshot("v3-progression-history")
        scrollClick("progression_profile")
        ui.onAllNodesWithText(main.name).onLast().performClick()
        scrollClick("configure_progression")
        scrollClick("strategy")
        scrollClick("strategy_CUSTOM")
        scrollClick("custom_rules")
        scrollClick("add_rule")
        scrollClick("add_condition")
        scrollClick("save_rule")
        await("add_rule")
        screenshot("v3-progression-rules")
        scrollClick("save_policy")
        ui.waitUntil(15_000) { runBlocking {
            app.repository.library.first().policies.any { it.scope.profileId == main.id && it.ruleSet.strategy == ProgressionStrategy.CUSTOM &&
                it.ruleSet.rules.singleOrNull()?.conditions?.singleOrNull()?.kind == ConditionKind.ALL_WORK }
        } }
    }

    @Test fun unavailableSafFolderKeepsPendingSetsAcrossReopen() {
        createVault()
        selectFolder()
        val profile = Profile(name = "QA Pending")
        val exercise = Exercise(name = "QA Pending press")
        val before = Session(title = "QA Pending", date = LocalDate.now().toString(), participants = listOf(profile)).start()
            .addExercise(ExercisePlan(exercise, listOf(SetPlan(20.0, 8, 10))))
        val repo = app.repository
        runBlocking { repo.save(before); repo.flush() }
        val file = waitForFile(SessionStatus.ACTIVE).first
        val moving = "$path-unavailable"
        assertEquals("ready", shell("mv '$path' '$moving' && printf ready"))
        try {
            runBlocking {
                repo.editSession(before.id) {
                    it.editAthlete(before.workouts.single().exercises.single().id, profile.id) { a ->
                        a.copy(sets = a.sets.map(SetEntry::toggle))
                    }
                }
                repo.flush()
            }
            ui.activityRule.scenario.recreate()
            val state = runBlocking { repo.library.first() }
            assertEquals(1, state.active()?.workouts?.single()?.completed)
            assertTrue(state.records.single { it.id == before.id }.pending)
        } finally {
            assertEquals("ready", shell("mv '$moving' '$path' && printf ready"))
        }
        runBlocking { repo.refresh(); repo.flush() }
        assertEquals(1, (Markdown.read(shell("cat '$file'"))?.document as Session).workouts.single().completed)
        assertFalse(runBlocking { repo.library.first().records.single { it.id == before.id }.pending })
    }

    private fun chooseTemplate(title: String) {
        scrollClick("pick_template")
        click("template_$title")
    }

    private fun selected(tag: String) {
        ui.waitUntil(12_000) { runCatching { ui.onNodeWithTag(tag).assertIsSelected() }.isSuccess }
    }

    @Test fun legacySafRecordHasDurableBackupBeforeUpgrade() {
        createVault()
        val original = instrumentation.context.assets.open("session-v1.md").bufferedReader().use { it.readText() }
        val file = "$path/legacy.md"
        writeFile(file, original)
        selectFolder()
        val (_, imported) = waitForFile(SessionStatus.ACTIVE)
        assertEquals(original, shell("cat '$file'"))
        runBlocking {
            app.repository.editSession(imported.id) { it.copy(notes = "Миграция QA") }
            app.repository.flush()
        }
        val upgraded = shell("cat '$file'")
        assertTrue(upgraded.contains("gymflow_schema: 7"))
        assertEquals(imported.workouts, (Markdown.read(upgraded)?.document as Session).workouts)
        val backups = File(app.filesDir, "markdown-backups").walkTopDown().filter { it.isFile }.toList()
        assertTrue(backups.any { it.readText() == original })
    }

    @Test fun scheduledProgramAndLightTheme() {
        createVault()
        selectFolder()
        click("nav_program")
        click("new_program")
        replace("program_name", "План QA")
        hideKeyboard()
        scrollClick("save_program")
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Добавить день").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Добавить день").performClick()
        replace("day_name", "День QA")
        hideKeyboard()
        scrollClick("save_day")
        await("program_add_exercise")
        click("program_add_exercise")
        replace("exercise_name", "Присед")
        hideKeyboard()
        scrollClick("save_exercise")
        val planned = waitForFile(SessionStatus.PLANNED).second
        screenshot("v2-program")
        assertEquals(LocalDate.now().toString(), planned.date)
        assertEquals("Присед", planned.workouts.single().exercises.single().exercise.name)
        runBlocking { app.repository.maintain(); app.repository.maintain() }
        assertEquals(1, runBlocking { app.repository.library.first().sessions.count { it.id == planned.id } })

        click("nav_settings")
        await("theme_light")
        scrollClick("theme_light")
        ui.waitUntil(10_000) { runBlocking { app.repository.settings.values.first().theme == Theme.LIGHT } }
        screenshot("v2-settings-light")
        click("nav_home")
        screenshot("v2-home-light")
        scrollThemeBack()
    }

    @Test fun safDetectsConflictAndRestoresAfterUnsuccessfulWrite() {
        createVault()
        selectFolder()
        val person = Profile(name = "Тест")
        val initial = Session(title = "Конфликт", date = LocalDate.now().toString(), participants = listOf(person)).start()
        val repo = app.repository
        runBlocking { repo.save(initial); repo.flush() }
        val (file, stored) = waitForFile(SessionStatus.ACTIVE)
        assertEquals(initial, stored)
        runBlocking { repo.editSession(stored.id) { it.copy(notes = "Локальный результат") } }

        writeFile(file, Markdown.write(stored.copy(notes = "Внешняя правка"), "[[Внешняя заметка]]"))
        runBlocking { repo.flush() }
        assertTrue(shell("cat '$file'").contains("Внешняя правка"))
        assertTrue(runBlocking { repo.library.first().records.single { it.id == stored.id }.conflict != null })
        runBlocking { repo.resolve(stored.id, keepLocal = true) }
        val resolved = requireNotNull(Markdown.read(shell("cat '$file'")))
        assertEquals("Локальный результат", (resolved.document as Session).notes)
        assertEquals("[[Внешняя заметка]]", resolved.freeText)

        runBlocking { repo.editSession(stored.id) { it.copy(notes = "Журнал после обрыва") } }
        writeFile(file, "<!-- gymflow:json\n{")
        runBlocking { repo.refresh(); repo.flush() }
        assertTrue(shell("cat '$file'").endsWith("{"))
        val recovery = runBlocking { repo.library.first().records.single { it.id == stored.id } }
        assertNotNull(recovery.conflict)
        assertEquals("Журнал после обрыва", (Markdown.read(recovery.markdown)?.document as Session).notes)
        runBlocking { repo.resolve(stored.id, keepLocal = true) }
        assertEquals("Журнал после обрыва", (Markdown.read(shell("cat '$file'"))?.document as Session).notes)
    }

    @Test fun compactViewportAndLargerSystemText() {
        val previous = shell("settings get system font_scale").trim()
        try {
            shell("wm size 720x1280")
            shell("wm density 320")
            shell("settings put system font_scale 1.30")
            ui.waitUntil(15_000) { ui.activity.resources.configuration.fontScale >= 1.29f }
            await("start_training")
            ui.onNodeWithTag("start_training").assertIsDisplayed()
            screenshot("v2-compact")
            click("nav_settings")
            scrollClick("theme_light")
            ui.waitUntil(10_000) { runBlocking { app.repository.settings.values.first().theme == Theme.LIGHT } }
            screenshot("v2-compact-settings")
            scrollClick("theme_dark")
        } finally {
            if (previous == "null" || previous.isBlank()) shell("settings delete system font_scale")
            else shell("settings put system font_scale $previous")
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    @Test fun primaryScreensFitAtLargeFontAndOnTablet() {
        createVault()
        selectFolder()
        val previous = shell("settings get system font_scale").trim()
        try {
            shell("wm size 640x1280")
            shell("wm density 320")
            shell("settings put system font_scale 2.0")
            ui.waitUntil(15_000) { ui.activity.resources.configuration.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } }
            for ((route, screen) in listOf("home" to "home", "program" to "programs", "history" to "history",
                "notes" to "notes_page", "settings" to "settings")) {
                click("nav_$route")
                await(screen)
                if (route == "home") ui.onNodeWithTag("start_training").assertIsDisplayed()
                screenshot("audit-320dp-$route")
            }
            click("nav_program")
            scrollClick("new_program")
            screenshot("audit-320dp-program-editor")
            device.pressBack()
            click("nav_notes")
            ui.onNodeWithTag("new_sport_note").assertIsDisplayed()
            click("new_sport_note")
            await("note_body")
            screenshot("audit-320dp-note-editor")
            device.pressBack()
            shell("wm size 1840x2560")
            shell("settings put system font_scale 1.0")
            ui.waitUntil(15_000) { ui.activity.resources.configuration.screenWidthDp >= 840 }
            click("nav_home")
            screenshot("audit-920dp-home")
            click("nav_notes")
            screenshot("audit-920dp-notes")
        } finally {
            if (previous == "null" || previous.isBlank()) shell("settings delete system font_scale")
            else shell("settings put system font_scale $previous")
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    @Test fun notesSyncRecoverAfterMoveAndRestoreFromBackup() {
        createVault()
        selectFolder()
        val repo = app.repository
        val vault = runBlocking { repo.settings.values.first().vaultId }
        assertTrue(shell("cat '$path/Хранилище GymFlow.md'").contains("gymflow_vault: $vault"))
        val profile = Profile(name = "Участник заметок QA")
        val program = Program(title = "Программа заметок QA")
        val planned = Session(title = "Тренировка заметок QA", date = LocalDate.now().toString(), participants = listOf(profile))
        runBlocking { repo.save(profile); repo.save(program); repo.save(planned); repo.flush() }
        val file = waitForFile(SessionStatus.PLANNED).first

        click("start_training")
        scrollClick("plan_notes")
        click("new_workout_note")
        replace("note_body", "До занятия: [[Готовность]]")
        hideKeyboard()
        scrollClick("finish_note")
        device.pressBack()
        click("confirm_start_plan")
        await("workout")
        click("workout_menu")
        click("workout_notes")
        click("new_workout_note")
        replace("note_body", "Во время занятия")
        hideKeyboard()
        screenshot("audit-workout-note")
        scrollClick("finish_note")
        device.pressBack()
        click("finish_workout")
        click("confirm_finish")
        await("start_training")
        runBlocking { repo.flush() }
        val closed = Markdown.read(shell("cat '$file'"))?.document as Session
        assertEquals(SessionStatus.COMPLETED, closed.status)
        assertEquals(listOf("До занятия: [[Готовность]]", "Во время занятия"), closed.workoutNotes.map { it.body })

        click("nav_notes")
        click("new_sport_note")
        replace("sport_note_title", "Восстановление QA")
        replace("note_body", "После занятия. [[Сон]]")
        hideKeyboard()
        ui.onNodeWithText("Привязать тренировку").performScrollTo().performClick()
        scrollClick("note_link_${planned.id}")
        ui.onNodeWithText("Привязать программу").performScrollTo().performClick()
        scrollClick("note_link_${program.id}")
        screenshot("audit-sport-note")
        ui.onNodeWithText("Готово").performScrollTo().performClick()
        replace("notes_search", "Восстановление QA")
        hideKeyboard()
        screenshot("audit-notes-search")
        val note = runBlocking { repo.library.first().notes.single() }
        assertEquals(planned.id, note.sessionId)
        assertEquals(program.id, note.programId)
        runBlocking { repo.flush() }
        val noteFile = "$path/Заметки/Восстановление QA.md"
        val raw = shell("cat '$noteFile'")
        assertTrue(raw.contains("После занятия. [[Сон]]"))
        writeFile(noteFile, raw.replace("[[Сон]]", "[[Гидратация]]"))
        ui.waitUntil(30_000) { runBlocking { repo.library.first().notes.single().body.contains("[[Гидратация]]") } }

        replace("notes_search", "Во время занятия")
        hideKeyboard()
        ui.onNodeWithText(planned.title).performClick()
        await("note_body")
        replace("note_body", "Запись дополнена после занятия")
        hideKeyboard()
        scrollClick("finish_note")
        await("notes_page")
        runBlocking { repo.flush() }

        assertEquals("gone", shell("rm '$file' && printf gone"))
        runBlocking { repo.refresh() }
        assertEquals(missingFile, runBlocking { repo.library.first().records.single { it.id == planned.id }.conflict })
        runBlocking { repo.keepOnlyOnDevice(planned.id); repo.maintain() }
        assertEquals("missing", shell("test ! -e '$file' && printf missing"))
        runBlocking { repo.resolve(planned.id, true) }
        assertTrue(shell("cat '$file'").contains("Запись дополнена после занятия"))

        val movedName = "$folderName-moved"
        val moved = "$root/$movedName"
        assertEquals("moved", shell("mv '$path' '$moved' && printf moved"))
        runBlocking {
            repo.editDocument(note.id) { (it as SportNote).copy(body = "Изменено офлайн") }
            repo.maintain()
        }
        val offline = runBlocking { repo.library.first() }
        assertTrue(offline.records.single { it.id == note.id }.pending)
        assertNull(offline.records.single { it.id == note.id }.conflict)
        selectFolder(movedName)
        assertEquals(vault, runBlocking { repo.settings.values.first().vaultId })
        runBlocking { repo.refresh(); repo.flush() }
        assertTrue(shell("cat '$moved/Заметки/Восстановление QA.md'").contains("Изменено офлайн"))
        assertEquals("1", shell("find '$moved' -name 'Восстановление QA.md' | wc -l").trim())

        val backup = File.createTempFile("gymflow-audit-", ".zip", app.cacheDir)
        val archive = Uri.fromFile(backup)
        try {
            runBlocking { repo.export(archive, true) }
            val restoredName = "$folderName-restored"
            assertEquals("ready", shell("mkdir -p '$root/$restoredName' && printf ready"))
            selectFolder(restoredName)
            assertNotEquals(vault, runBlocking { repo.settings.values.first().vaultId })
            assertTrue(runBlocking { repo.library.first().documents.isEmpty() })
            runBlocking {
                assertEquals(4, repo.restore(archive).restored)
                assertEquals(0, repo.restore(archive).restored)
                repo.flush()
            }
            val restored = runBlocking { repo.library.first() }
            assertEquals("Изменено офлайн", restored.notes.single().body)
            assertEquals(2, restored.sessions.single().workoutNotes.size)
            assertEquals(planned.id, restored.sessions.single().id)
            click("nav_notes")
            replace("notes_search", "Восстановление QA")
            hideKeyboard()
            click("note_card_${note.id}")
            ui.onNodeWithText("Удалить заметку").performScrollTo().performClick()
            ui.onNodeWithText("Удалить", substring = false).performClick()
            ui.waitUntil(12_000) { runBlocking { repo.library.first().notes.isEmpty() } }
            runBlocking { repo.flush() }
            val tombstone = Markdown.read(shell("cat '$root/$restoredName/Заметки/Восстановление QA.md'"))?.document as SportNote
            assertTrue(tombstone.deleted)
            assertEquals("", tombstone.body)
        } finally {
            backup.delete()
        }
    }

    @Test fun readableVaultMigratesThenTracksExternalEditsById() {
        createVault()
        selectFolder()
        click("nav_settings")
        ui.onNodeWithTag("migrate_markdown").performScrollTo().assertIsDisplayed()
        screenshot("markdown-v7-migration-settings")
        click("nav_home")
        val repo = app.repository
        val exercise = Exercise(name = "Тяга блока")
        val day = WorkoutTemplate(title = "Спина", exercises = listOf(ExercisePlan(exercise, listOf(SetPlan(16.0)))))
        val program = Program(title = "Силовая программа", days = listOf(day))
        val person = Profile(name = "Участник QA")
        val session = Session(title = "Грудь и трицепс", date = LocalDate.now().toString(), participants = listOf(person))
            .copy(programId = program.id).start().addExercise(ExercisePlan(exercise, listOf(SetPlan(18.0))))
        val done = session.editWorkout(person.id) { workout ->
            workout.editExercise(workout.exercises.single().id) { entry ->
                entry.copy(athletes = entry.athletes.map { it.copy(sets = it.sets.map(SetEntry::toggle)) })
            }
        }.finish()
        val note = SportNote(title = "Цели на месяц", sessionId = done.id, body = "[[${exercise.id}|Упражнение]]")
        val legacy = listOf("Программы" to program, "Тренировки" to done, "Упражнения" to exercise, "Заметки" to note)
        legacy.forEach { (directory, document) ->
            shell("mkdir -p '$path/$directory'")
            writeFile("$path/$directory/${document.id}.md", Markdown.writeLegacy(document))
        }
        val userNote = "$path/Прочее.md"
        writeFile(userNote, "# Неизвестная запись\n\nОставить как есть.")
        val backup = File(requireNotNull(app.getExternalFilesDir("qa")), "markdown-v7-migration.zip")
        assertEquals(4, runBlocking { repo.migrateVault(Uri.fromFile(backup)) })
        assertEquals(0, runBlocking { repo.migrateVault(Uri.fromFile(backup)) })
        val programFile = "$path/Программы/Силовая программа.md"
        val sessionFile = "$path/Тренировки/${done.date} - Грудь и трицепс.md"
        val noteFile = "$path/Заметки/Цели на месяц.md"
        val exerciseFile = "$path/Упражнения/Тяга блока.md"
        for ((file, document) in listOf(programFile to program, sessionFile to done, exerciseFile to exercise)) {
            val raw = shell("cat '$file'")
            assertFalse(raw.contains("gymflow:json"))
            assertEquals(document, Markdown.read(raw)?.document)
        }
        assertTrue(shell("cat '$noteFile'").contains("[[Упражнения/Тяга блока|Упражнение]]"))
        assertTrue(shell("cat '$noteFile'").contains("gymflow_links:"))
        assertEquals("# Неизвестная запись\n\nОставить как есть.", shell("cat '$userNote'"))
        java.util.zip.ZipFile(backup).use { zip ->
            assertTrue(zip.getEntry("Переименования.tsv") != null)
            for ((directory, document) in legacy) {
                val original = zip.getInputStream(zip.getEntry("Исходники/$directory/${document.id}.md"))
                    .bufferedReader().use { it.readText() }
                assertEquals(document, Markdown.read(original)?.document)
            }
        }

        val moved = "$path/Архив/Предыдущие занятия/Новый заголовок файла.md"
        assertEquals("moved", shell("mkdir -p '$path/Архив/Предыдущие занятия' && mv '$sessionFile' '$moved' && printf moved"))
        val original = shell("cat '$moved'")
        val previous = done.defaultWorkout.exercises.single().athletes.single().sets.single().actual!!
        val changed = original.replace(" | ${previous.weight} | ${previous.reps} | ", " | 29.0 | 11 | ")
            .replace("# Грудь и трицепс", "# Тренировка после правки")
        assertNotEquals(original, changed)
        writeFile(moved, changed)
        writeFile(programFile, shell("cat '$programFile'").replace("# Силовая программа", "# Тяжёлая программа"))
        writeFile(noteFile, shell("cat '$noteFile'").replace("|Упражнение", "|Проверить подходы"))
        runBlocking { repo.refresh() }
        ui.activityRule.scenario.recreate()
        val state = runBlocking { repo.library.first() }
        assertEquals("Тяжёлая программа", state.programs.single().title)
        assertTrue(state.notes.single().body.contains("Проверить подходы"))
        val imported = state.sessions.single()
        assertEquals("Тренировка после правки", imported.title)
        assertEquals(29.0, imported.defaultWorkout.exercises.single().athletes.single().sets.single().actual!!.weight, 0.0)
        runBlocking {
            repo.editDocument(done.id) { (it as Session).copy(notes = "Записано из приложения") }
            repo.flush()
        }
        assertTrue(shell("cat '$moved'").contains("Записано из приложения"))
        assertEquals("missing", shell("test ! -e '$sessionFile' && printf missing"))
        assertEquals("gone", shell("rm '$programFile' && printf gone"))
        runBlocking { repo.refresh() }
        assertEquals(missingFile, runBlocking { repo.library.first().records.single { it.id == program.id }.conflict })
        runBlocking { repo.resolve(program.id, true) }
        assertTrue(shell("cat '$path/Программы/Тяжёлая программа.md'").contains("id: ${program.id}"))

        val staged = SportNote(title = "Локальный черновик", body = "Запись на устройстве")
        runBlocking { repo.save(staged) }
        val createdOutside = "$path/Архив/Внешняя заметка.md"
        val external = Markdown.write(staged.copy(title = "Внешняя заметка"))
        writeFile(createdOutside, external)
        runBlocking { repo.flush() }
        assertEquals(external, shell("cat '$createdOutside'"))
        assertNotNull(runBlocking { repo.library.first().records.single { it.id == staged.id }.conflict })
        assertEquals("missing", shell("test ! -e '$path/Заметки/Локальный черновик.md' && printf missing"))
    }

    @Test fun editsTimedActivitiesAndCancelsOrDeletesWorkouts() {
        createVault()
        selectFolder()
        val repo = app.repository
        val person = Profile(name = "Атлет QA")
        val day = ProgramDay(title = "Выносливость QA")
        val program = Program(title = "Тренировки на время QA", days = listOf(day), enabled = false)
        runBlocking { repo.save(person); repo.save(program); repo.flush() }
        fun field(tag: String, value: String) {
            await(tag)
            ui.onNodeWithTag(tag).performScrollTo()
            replace(tag, value)
            hideKeyboard()
        }
        click("nav_program")
        await("program_${program.id}")
        scrollClick("program_${program.id}")
        scrollClick("program_day_0")
        scrollClick("add_training_block")
        scrollClick("training_block_type")
        scrollClick("training_block_type_WARMUP")
        field("block_duration", "300")
        field("block_guidance", "Лёгкая ходьба")
        scrollClick("save_training_block")
        scrollClick("program_add_exercise")
        field("exercise_name", "Ходьба QA")
        scrollClick("exercise_mode")
        scrollClick("exercise_mode_CARDIO")
        scrollClick("exercise_cardio")
        scrollClick("exercise_cardio_WALK")
        scrollClick("plan_set_0")
        field("plan_0_duration", "600")
        ui.onNodeWithTag("plan_0_weight").assertDoesNotExist()
        screenshot("universal-cardio-editor")
        scrollClick("save_exercise")
        ui.waitUntil(12_000) { runBlocking { repo.library.first().programs.single().days.single().exercises.isNotEmpty() } }
        val edited = runBlocking { repo.library.first().programs.single() }
        assertEquals(300.0, edited.days.single().blocks.single().durationSeconds!!, 0.0)
        assertEquals("Лёгкая ходьба", edited.days.single().blocks.single().guidance)
        assertEquals(600.0, edited.days.single().exercises.single().sets.single().durationSeconds!!, 0.0)
        screenshot("universal-program-day")
        val saved = scheduledSession(edited, edited.days.single(), LocalDate.now(), listOf(person))
        runBlocking { repo.save(saved); repo.flush() }
        click("nav_home")
        screenshot("universal-home")
        fun startPlan() {
            scrollClick("start_training")
            click("confirm_start_plan")
            await("workout")
        }
        fun record(seconds: String) {
            scrollClick("edit_set_0")
            field("measure_DURATION", seconds)
            ui.onNodeWithTag("set_weight").assertDoesNotExist()
            scrollClick("save_set")
            withActiveFile { _, session ->
                assertEquals(seconds.toDouble(), session.defaultWorkout.exercises.single().athletes.single().sets.single().actual?.measurements?.get(Measure.DURATION))
            }
        }
        startPlan()
        record("580")
        scrollClick("workout_menu")
        scrollClick("cancel_workout")
        click("confirm_discard_workout")
        assertEquals(saved, waitForFile(SessionStatus.PLANNED).second)
        startPlan()
        record("620")
        click("finish_workout")
        click("confirm_finish")
        val (file, completed) = waitForFile(SessionStatus.COMPLETED)
        click("nav_history")
        scrollClick("history_session")
        scrollClick("workout_menu")
        scrollClick("delete_workout")
        click("confirm_discard_workout")
        ui.waitUntil(15_000) { runBlocking { repo.library.first().sessions.none { it.id == completed.id } } }
        ui.waitUntil(15_000) { (Markdown.read(shell("cat '$file'"))?.document as? Session)?.discarded == true }
        assertEquals(completed.copy(discarded = true), Markdown.read(shell("cat '$file'"))?.document)
        click("nav_home")
        scrollClick("start_training")
        await("workout")
        scrollClick("workout_menu")
        scrollClick("cancel_workout")
        click("confirm_discard_workout")
        ui.waitUntil(15_000) { runBlocking { repo.library.first().active() == null } }
        assertTrue(runBlocking { repo.library.first().sessions.isEmpty() })
    }

    @Test fun editsIndividualPlanSetsAndKeepsCompletedMarkdownHistory() {
        createVault()
        selectFolder()
        val repo = app.repository
        fun program() = runBlocking { repo.library.first().programs.single() }
        fun waitProgram(predicate: (Program) -> Boolean) {
            ui.waitUntil(15_000) { runBlocking { repo.library.first().programs.singleOrNull()?.let(predicate) == true } }
        }
        fun field(tag: String, value: String) {
            await(tag)
            ui.onNodeWithTag(tag).performScrollTo()
            replace(tag, value)
            hideKeyboard()
        }
        fun addDay(title: String) {
            scrollClick("add_day_btn")
            field("day_name", title)
            scrollClick("save_day")
            await("program_add_exercise")
        }
        fun addExerciseToDay(title: String) {
            scrollClick("program_add_exercise")
            field("exercise_name", title)
        }
        click("nav_program")
        click("new_program")
        field("program_name", "План с подходами QA")
        scrollClick("save_program")
        addDay("Спина QA")
        addExerciseToDay("Тяга QA")

        scrollClick("plan_set_0")
        field("plan_0_weight", "0")
        field("plan_0_reps", "12")
        field("plan_0_reps_max", "")
        scrollClick("plan_0_role")
        scrollClick("plan_0_role_WARMUP")
        scrollClick("plan_set_1")
        field("plan_1_weight", "22,5")
        field("plan_1_reps", "7")
        field("plan_1_reps_max", "9")
        ui.onNodeWithTag("plan_1_weight").performScrollTo().performClick()
        screenshot("program-editor-keyboard")
        hideKeyboard()
        scrollClick("plan_copy_1")
        scrollClick("plan_up_2")
        field("plan_1_weight", "25,5")
        scrollClick("plan_set_3")
        scrollClick("plan_remove_3")
        scrollClick("plan_undo")
        scrollClick("plan_remove_3")
        screenshot("program-editor-individual-sets")
        scrollClick("save_exercise")
        waitProgram { it.days.singleOrNull()?.exercises?.singleOrNull()?.sets?.map(SetPlan::weight) == listOf(0.0, 25.5, 22.5) }
        assertEquals(listOf(12 to 12, 7 to 9, 7 to 9), program().days.single().exercises.single().sets.map { it.repsMin to it.repsMax })
        assertEquals(SetRole.WARMUP, program().days.single().exercises.single().sets.first().purpose)

        scrollClick("edit_program_exercise_0")
        scrollClick("resize_plan_sets")
        field("plan_set_count", "35")
        scrollClick("apply_plan_count")
        await("plan_set_34")
        scrollClick("plan_set_0")
        field("plan_0_weight", "-5")
        ui.onNodeWithTag("save_exercise").assertIsNotEnabled()
        device.pressBack()
        await("edit_program_exercise_0")
        assertEquals(3, program().days.single().exercises.single().sets.size)

        addExerciseToDay("Разведение QA")
        scrollClick("save_exercise")
        waitProgram { it.days.single().exercises.size == 2 }
        scrollClick("program_exercise_up_1")
        waitProgram { it.days.single().exercises.first().exercise.name == "Разведение QA" }
        device.pressBack()
        addDay("Ноги QA")
        device.pressBack()
        scrollClick("program_day_up_1")
        waitProgram { it.days.map(ProgramDay::title) == listOf("Ноги QA", "Спина QA") }
        runBlocking { repo.flush() }
        val programFile = "$path/Программы/План с подходами QA.md"
        val original = shell("cat '$programFile'")
        assertEquals(program().id, Markdown.read(original)?.document?.id)
        val outside = original.replace(" | 25.5 | 7 | 9 | ", " | 27.75 | 7 | 9 | ")
        assertNotEquals(original, outside)
        writeFile(programFile, outside)
        runBlocking { repo.refresh() }
        ui.activityRule.scenario.recreate()
        waitProgram { it.days.last().exercises.last().sets[1].weight == 27.75 }

        click("nav_home")
        scrollClick("start_training")
        click("confirm_start_plan")
        await("workout")
        click("next_exercise")
        ui.waitUntil(12_000) { ui.onAllNodesWithText("Тяга QA").fetchSemanticsNodes().isNotEmpty() }
        scrollClick("edit_set_1")
        field("set_weight", "28,25")
        field("set_reps", "8")
        field("set_result_note", "Контрольная запись QA")
        scrollClick("save_set")
        ui.waitUntil(12_000) { runBlocking {
            repo.library.first().active()?.defaultWorkout?.exercises?.lastOrNull()?.athletes?.single()?.sets?.get(1)?.actual?.weight == 28.25
        } }
        click("finish_workout")
        click("confirm_finish")
        val (historyFile, history) = waitForFile(SessionStatus.COMPLETED)
        val set = history.defaultWorkout.exercises.last().athletes.single().sets[1]
        assertEquals(27.75, set.plan.weight, 0.0)
        assertEquals("Контрольная запись QA", set.actual?.note)
        assertEquals(8, set.actual?.reps)
        val before = shell("cat '$historyFile'")

        click("nav_program")
        await("program_day_1")
        scrollClick("program_day_1")
        scrollClick("edit_program_exercise_1")
        scrollClick("plan_set_1")
        field("plan_1_weight", "31,25")
        scrollClick("save_exercise")
        waitProgram { it.days.last().exercises.last().sets[1].weight == 31.25 }
        runBlocking { repo.flush() }
        assertEquals(before, shell("cat '$historyFile'"))
        assertEquals(1, shell("find '$path/Программы' -type f -name '*.md'").lineSequence().count { it.startsWith(path) })

        scrollClick("edit_program_exercise_1")
        scrollClick("plan_set_1")
        field("plan_1_weight", "99")
        val changedTitle = shell("cat '$programFile'").replace("# План с подходами QA", "# План поправлен снаружи QA")
        writeFile(programFile, changedTitle)
        runBlocking { repo.refresh() }
        waitProgram { it.title == "План поправлен снаружи QA" }
        scrollClick("save_exercise")
        ui.waitUntil(12_000) { ui.onAllNodesWithText("Сохранение не завершено").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Понятно").performClick()
        ui.onNodeWithTag("plan_1_weight").assertTextContains("99")
        assertEquals(31.25, program().days.last().exercises.last().sets[1].weight, 0.0)
        device.pressBack()
        await("edit_program_exercise_1")
        device.pressBack()
        scrollClick("archive_program")
        ui.onNodeWithText("В архив").performClick()
        waitProgram(Program::archived)
        ui.onNodeWithText("Архив программ").performScrollTo().performClick()
        ui.onNodeWithText("План поправлен снаружи QA").performScrollTo().performClick()
        scrollClick("archive_program")
        waitProgram { !it.archived }
        runBlocking { repo.flush() }
        assertEquals(before, shell("cat '$historyFile'"))
    }

    private fun createVault() {
        assertEquals("ready", shell("mkdir -p '$path' && test -d '$path' && printf ready"))
    }

    private fun scrollThemeBack() {
        click("nav_settings")
        scrollClick("theme_dark")
        ui.waitUntil(10_000) { runBlocking { app.repository.settings.values.first().theme == Theme.DARK } }
    }

    private fun selectFolder(selectedFolder: String = folderName) {
        click("nav_settings")
        await("settings_choose_folder")
        val previousFolder = runBlocking { app.repository.settings.values.first().folder }
        click("settings_choose_folder")
        if (previousFolder.isNotBlank()) {
            ui.onNodeWithText("Выбрать папку", substring = false).performClick()
        }
        device.waitForIdle()
        var target = device.wait(Until.findObject(By.text(selectedFolder)), 1200)
        if (target == null) {
            val documents = device.findObject(By.text("Documents"))
            if (documents != null) documents.click()
            else {
                val drawer = device.wait(Until.findObject(By.descContains("roots")), 4000)
                drawer?.click()
                val model = shell("getprop ro.product.model").trim()
                val storage = device.wait(Until.findObject(By.text(model)), 2500)
                    ?: device.findObject(By.text("Internal storage"))
                requireNotNull(storage) { "Internal storage root missing in DocumentsUI" }.click()
                requireNotNull(device.wait(Until.findObject(By.text("Documents")), 4000)).click()
            }
            target = device.wait(Until.findObject(By.text(selectedFolder)), 1600)
            if (target == null) {
                runCatching {
                    UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(35)
                        .scrollIntoView(UiSelector().text(selectedFolder))
                }
                target = device.wait(Until.findObject(By.text(selectedFolder)), 1200)
            }
            repeat(6) {
                if (target == null) {
                    val x = device.displayWidth / 2
                    device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 3, 30)
                    target = device.wait(Until.findObject(By.text(selectedFolder)), 750)
                }
            }
        }
        if (target == null) {
            screenshot("picker-folder-missing")
            val file = File(requireNotNull(app.getExternalFilesDir("qa")), "picker-folder-missing.xml")
            device.dumpWindowHierarchy(file)
            error("Папка $selectedFolder не видна в DocumentsUI")
        }
        val useFolder = By.text(Pattern.compile("Use this folder", Pattern.CASE_INSENSITIVE))
        val allow = By.text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))
        var confirm = false
        repeat(3) {
            if (!confirm) {
                val row = requireNotNull(target) { "Test folder went off screen" }
                val footer = device.findObject(useFolder)?.visibleBounds
                if (footer != null && row.visibleBounds.centerY() >= footer.top - 16) {
                    val x = device.displayWidth / 2
                    device.swipe(x, footer.top - 30, x, maxOf(footer.top - 280, 150), 20)
                    target = device.wait(Until.findObject(By.text(selectedFolder)), 2500)
                }
                checkNotNull(target).click()
                val button = device.wait(Until.findObject(useFolder), 1600)
                if (device.findObject(allow) == null) button?.click()
                val grant = device.wait(Until.findObject(allow), 2200)
                if (grant != null) {
                    if (device.hasObject(By.textContains(selectedFolder))) {
                        grant.click()
                        confirm = true
                    } else {
                        device.pressBack()
                        target = device.wait(Until.findObject(By.text(selectedFolder)), 3000)
                    }
                }
            }
        }
        check(confirm) { "DocumentsUI did not confirm the selected QA folder" }
        ui.waitUntil(15_000) { runBlocking { app.repository.settings.values.first().folder.endsWith(selectedFolder) } }
        click("nav_home")
    }

    private fun withActiveFile(check: (String, Session) -> Unit) {
        ui.waitUntil(15_000) {
            runCatching {
                val (name, session) = findFile(SessionStatus.ACTIVE) ?: return@waitUntil false
                check(name, session)
            }.isSuccess
        }
    }

    private fun waitForFile(status: SessionStatus): Pair<String, Session> {
        var result: Pair<String, Session>? = null
        ui.waitUntil(20_000) { result = findFile(status); result != null }
        return checkNotNull(result)
    }

    private fun findFile(status: SessionStatus): Pair<String, Session>? {
        val files = shell("find '$path' -type f -name '*.md'").lineSequence().filter { it.startsWith("$path/") }
        return files.firstNotNullOfOrNull { file ->
            val session = runCatching { Markdown.read(shell("cat '$file'"))?.document as? Session }.getOrNull()
            if (session?.status == status) file to session else null
        }
    }

    private fun addExercise(name: String, capture: String? = null) {
        if (ui.onAllNodesWithTag("add_exercise").fetchSemanticsNodes().isNotEmpty()) click("add_exercise")
        else click("quick_add")
        replace("exercise_name", name)
        hideKeyboard()
        capture?.let(::screenshot)
        scrollClick("save_exercise")
        await("exercise_title")
    }

    private fun addPerson(name: String, template: String? = null) {
        click("workout_menu")
        click("add_person")
        template?.let(::chooseTemplate)
        replace("person_name", name)
        hideKeyboard()
        scrollClick("save_person")
        ui.waitUntil(12_000) { ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun replace(tag: String, value: String) {
        await(tag)
        val node = ui.onNodeWithTag(tag)
        node.performClick()
        node.performTextReplacement(value)
    }

    private fun hideKeyboard() {
        var visible = false
        ui.runOnUiThread {
            val roots = ui.activity.window.decorView
            visible = ViewCompat.getRootWindowInsets(roots)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        if (visible || device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) device.pressBack()
    }

    private fun await(tag: String) = ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { await(tag); ui.onNodeWithTag(tag).performClick() }
    private fun scrollClick(tag: String) {
        await(tag)
        val node = ui.onNodeWithTag(tag)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        device.waitForIdle()
        SystemClock.sleep(450)
        val dir = requireNotNull(app.getExternalFilesDir("qa"))
        dir.mkdirs()
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }

    private fun shell(command: String): String {
        val descriptors = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use {
            it.appendLine(command)
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
    }

    private fun writeFile(file: String, text: String) {
        val encoded = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        shell("echo '$encoded' | base64 -d > '$file'")
    }
}
