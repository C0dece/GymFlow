package app.gymflow

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gymflow.data.Journal
import app.gymflow.data.Record
import app.gymflow.domain.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryLoadTest {
    @Test fun roomAndMarkdownWith100And1000Sessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = listOf(Profile(name = "Анна"), Profile(name = "Павел"), Profile(name = "Олег"))
        val catalog = List(500) { Exercise(name = "Упражнение ${it + 1}", load = Load.MACHINE) }
        val day = LocalDate.of(2026, 11, 18)
        val database = Room.inMemoryDatabaseBuilder(context, Journal::class.java).build()
        try {
            val dao = database.records()
            val scope = "load-test"
            for (entry in catalog) dao.put(Record(scope, entry.id, Markdown.write(entry)))
            for (size in listOf(100, 1000)) {
                for (i in (if (size == 100) 0 else 100) until size) {
                    val new = Session(title = "Занятие ${i + 1}", date = day.minusDays(i.toLong()).toString(),
                        participants = profiles).copy(notes = if (i == 0) "Длинная заметка о занятии. ".repeat(4000) else "")
                        .addExercise(ExercisePlan(catalog[i % catalog.size], listOf(SetPlan(20.0), SetPlan(22.0))), profiles.map { it.id }.toSet())
                        .let { session -> session.copy(workouts = session.workouts.map { workout ->
                            workout.copy(exercises = workout.exercises.map { item ->
                                item.copy(athletes = item.athletes.map { p -> p.copy(sets = p.sets.map(SetEntry::toggle), rir = 2) })
                            })
                        }) }.finish()
                    dao.put(Record(scope, new.id, Markdown.write(new)))
                }
                val started = SystemClock.elapsedRealtime()
                val docs = withContext(Dispatchers.Default) { dao.all(scope).map { requireNotNull(Markdown.read(it.markdown)).document } }
                val training = docs.filterIsInstance<Session>()
                assertEquals(size, training.size)
                assertEquals(500, docs.filterIsInstance<Exercise>().size)
                assertEquals(size, performance(training, profiles.first().id).size)
                assertEquals(3, training.last().participants.size)
                assertEquals(1, training.count { it.notes.length > 100_000 })
                val policies = listOf(ProgressionStrategy.AUTO, ProgressionStrategy.REPS, ProgressionStrategy.WAVE)
                    .mapIndexed { i, strategy -> ProgressionPolicy(ProgressionScope(profileId = profiles[i].id), ruleSet = RuleSet(strategy)) }
                val engine = ProgressionEngine(ProgressionIndex(training), policies)
                val calculated = profiles.flatMap { p ->
                    catalog.map { e -> engine.decide(p.id, null, ExercisePlan(e, listOf(SetPlan(22.0)))) }
                }
                assertEquals(1500, calculated.size)
                assertEquals(3, calculated.distinctBy { it.strategy }.size)
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i("GymFlowQA", "$size sessions + 500 catalog records, decode, statistics and 1500 decisions: $elapsed ms")
            }
        } finally { database.close() }
    }
}
