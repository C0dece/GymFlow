package app.gymflow

import android.os.ParcelFileDescriptor
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
import androidx.test.uiautomator.Until
import app.gymflow.data.Theme
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
        val moved = files.single { it.endsWith("/${later.id}.md") }
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
        withActiveFile { _, session ->
            val corrected = session.otherWorkouts.single()
            assertEquals(3, corrected.exercises.last().athletes.single().sets.size)
            assertEquals(2, corrected.completed)
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
        ui.onNodeWithText("Упражнение пропущено").assertIsDisplayed()
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
        assertTrue(raw.contains("gymflow_schema: 3"))
        assertTrue(raw.contains("## Партнёр QA · Грудь QA"))
        assertTrue(raw.contains("21.5 × 9"))
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
            val filename = disk.single { it.endsWith("/$id.md") }
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
        assertTrue(upgraded.contains("gymflow_schema: 3"))
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

    private fun createVault() {
        assertEquals("ready", shell("mkdir -p '$path' && test -d '$path' && printf ready"))
    }

    private fun scrollThemeBack() {
        click("nav_settings")
        scrollClick("theme_dark")
        ui.waitUntil(10_000) { runBlocking { app.repository.settings.values.first().theme == Theme.DARK } }
    }

    private fun selectFolder() {
        click("nav_settings")
        await("settings_choose_folder")
        val previousFolder = runBlocking { app.repository.settings.values.first().folder }
        click("settings_choose_folder")
        if (previousFolder.isNotBlank()) {
            ui.onNodeWithText("Выбрать папку", substring = false).performClick()
        }
        device.waitForIdle()
        var target = device.wait(Until.findObject(By.text(folderName)), 1200)
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
            target = device.wait(Until.findObject(By.text(folderName)), 1600)
            repeat(6) {
                if (target == null) {
                    val x = device.displayWidth / 2
                    device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 3, 30)
                    target = device.wait(Until.findObject(By.text(folderName)), 750)
                }
            }
        }
        if (target == null) {
            screenshot("picker-folder-missing")
            val file = File(requireNotNull(app.getExternalFilesDir("qa")), "picker-folder-missing.xml")
            device.dumpWindowHierarchy(file)
            error("Папка $folderName не видна в DocumentsUI")
        }
        checkNotNull(target).click()
        requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Use this folder", Pattern.CASE_INSENSITIVE))), 5000)).click()
        requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))), 5000)).click()
        ui.waitUntil(15_000) { runBlocking { app.repository.settings.values.first().folder.contains(folderName) } }
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
    private fun scrollClick(tag: String) { await(tag); ui.onNodeWithTag(tag).performScrollTo().performClick() }

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
