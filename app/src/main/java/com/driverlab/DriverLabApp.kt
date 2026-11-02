package com.driverlab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.driverlab.core.Catalog
import com.driverlab.core.CtsSetup
import com.driverlab.core.DriverRepository
import com.driverlab.core.FeatureChecker
import com.driverlab.core.FlagCheck
import com.driverlab.core.RunManager
import com.driverlab.core.SelectionStore
import com.driverlab.core.SessionStore
import com.driverlab.core.SettingsStore
import com.driverlab.core.SpeedManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DriverLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(AppGraph.CHANNEL_RUNS, "Test runs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of running GPU tests"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(AppGraph.CHANNEL_DONE, "Finished runs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a test run finishes"
            },
        )
    }
}

/** App-wide singletons. */
object AppGraph {
    const val CHANNEL_RUNS = "runs"
    const val CHANNEL_DONE = "done"

    lateinit var app: Application
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var setup: CtsSetup
        private set
    lateinit var catalog: Catalog
        private set
    lateinit var selection: SelectionStore
        private set
    lateinit var drivers: DriverRepository
        private set
    lateinit var sessions: SessionStore
        private set
    lateinit var runs: RunManager
        private set
    lateinit var flagCheck: FlagCheck
        private set
    lateinit var features: FeatureChecker
        private set
    lateinit var speed: SpeedManager
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(a: Application) {
        app = a
        settings = SettingsStore(a)
        setup = CtsSetup(a)
        catalog = Catalog(a)
        selection = SelectionStore(catalog)
        drivers = DriverRepository(a)
        sessions = SessionStore(a)
        runs = RunManager(a, drivers, selection, sessions, setup)
        flagCheck = FlagCheck(a, drivers, settings, scope)
        features = FeatureChecker(a, drivers, catalog, scope)
        speed = SpeedManager(a, drivers, sessions)
        runs.otherBusy = { speed.isActive }
        scope.launch(Dispatchers.IO) {
            catalog.loadIndex()
            drivers.reload()
            features.load()
            sessions.refresh()
            speed.refresh()
            setup.ensure()
            // Learn what the system driver reports the first time the app runs.
            val sys = drivers.get("system")?.probe
            // Re-check once when the stored result is missing or from the 0.1 build with the old version format.
            if (setup.state.value is CtsSetup.State.Ready && (sys == null || sys.driverVersion.contains("(Qualcomm)"))) {
                drivers.probe("system")
            }
        }
    }
}
