package com.driverlab.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.driverlab.AppGraph
import com.driverlab.MainActivity
import com.driverlab.R
import com.driverlab.core.Fmt
import com.driverlab.core.RunManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps a test run alive (foreground service + partial wake lock) and shows progress. */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppGraph.runs.stop()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIF_ID, build(AppGraph.runs.live.value, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job?.isActive == true) return START_NOT_STICKY
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriverLab:run").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        val nm = getSystemService(NotificationManager::class.java)
        job = scope.launch {
            val updater = launch {
                while (isActive) {
                    nm.notify(NOTIF_ID, build(AppGraph.runs.live.value, true))
                    delay(1000)
                }
            }
            try {
                AppGraph.runs.runPending(this)
            } finally {
                updater.cancel()
                val live = AppGraph.runs.live.value
                ServiceCompat.stopForeground(this@RunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                nm.notify(DONE_ID, build(live, false))
                wake?.let { if (it.isHeld) it.release() }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun build(live: RunManager.Live?, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, if (ongoing) AppGraph.CHANNEL_RUNS else AppGraph.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (live == null) return b.setContentTitle("DriverLab").setContentText("Starting tests").setOngoing(ongoing).build()
        val c = live.counts
        val summary = "${c.passed} passed · ${c.failed + c.crashed} failed · ${c.skipped} not supported"
        if (ongoing) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentTitle("Testing ${live.driverName}")
                .setContentText("${Fmt.count(live.done)} / ${Fmt.count(live.total)} · $summary")
                .setProgress(1000, (live.fraction * 1000).toInt(), live.total <= 0)
                .setOngoing(true)
                .addAction(0, "Stop", stop)
        } else {
            val title = when (live.phase) {
                RunManager.Phase.DONE -> "Tests finished: ${live.driverName}"
                RunManager.Phase.STOPPED -> "Tests stopped: ${live.driverName}"
                else -> "Tests could not finish: ${live.driverName}"
            }
            b.setContentTitle(title).setContentText(live.message ?: summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary + (live.message?.let { "\n$it" } ?: "")))
                .setAutoCancel(true)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.driverlab.STOP"
        const val NOTIF_ID = 1
        const val DONE_ID = 2
    }
}
