package app.gymflow

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.room.Room
import androidx.work.*
import app.gymflow.data.Journal
import app.gymflow.data.Repository
import app.gymflow.data.Settings
import app.gymflow.data.journalMigration
import java.util.concurrent.TimeUnit

class GymFlowApp : Application() {
    val repository by lazy {
        val database = Room.databaseBuilder(this, Journal::class.java, "gymflow.db").addMigrations(journalMigration).build()
        Repository(this, database, Settings(this))
    }

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "gymflow-schedule",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Maintenance>(1, TimeUnit.HOURS).build(),
        )
    }
}

class Maintenance(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as GymFlowApp).repository.maintain()
        Result.success()
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Log.w("GymFlow", "maintenance_error category=${failure.javaClass.simpleName}")
        Result.retry()
    }
}
