package com.quotawatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quotawatch.QuotaWatchApp
import com.quotawatch.R
import com.quotawatch.api.QuotaSnapshot
import com.quotawatch.data.QuotaRepository
import com.quotawatch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the periodic quota refresh alive while the app is backgrounded.
 *
 * The refresh loop used to live in viewModelScope, so Android 12+'s cached-app freezer suspended
 * it whenever the app left the foreground — the BLE-connected M5 then went stale, and a process
 * kill stopped refreshing entirely (bd m5-tc1). A foreground service is exempt from the freezer,
 * so the loop keeps ticking with the phone in a pocket.
 *
 * Gated by the auto-refresh toggle: MainActivity starts it (from a foreground context, per the
 * Android 12+ FGS-start restriction) when auto-refresh is enabled and stops it when disabled.
 */
class QuotaRefreshService : Service() {

    companion object {
        const val TAG = "QuotaRefreshService"
        private const val CHANNEL_ID = "quota_refresh"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, QuotaRefreshService::class.java)
            // Must be called from a foreground context (Activity) — background starts are blocked
            // on Android 12+. Callers gate on that; this just forwards the intent.
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuotaRefreshService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private lateinit var repository: QuotaRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = (application as QuotaWatchApp).repository
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground within the 5s ANR window before doing anything else.
        try {
            startForegroundCompat(NOTIFICATION_ID, buildNotification(repository.quotas.value))
        } catch (e: SecurityException) {
            // A connectedDevice FGS started without holding a qualifying runtime permission
            // (BLUETOOTH_CONNECT/SCAN) throws on Android 14+/targetSdk 35. The primary fix gates
            // the start on granted BLE permissions (MainActivity); this is defense in depth so a
            // start/grant race can't crash the process. Bail out cleanly instead.
            Log.e(TAG, "startForeground denied — missing BLE permission at start time", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // onStartCommand can be re-delivered (redeliveries, repeated start calls); only wire up
        // the loop and notification collector once.
        if (!started) {
            started = true

            // Keep the ongoing notification's quota summary current on every snapshot change —
            // manual/foreground refreshes update it too, not just this service's ticks.
            scope.launch {
                repository.quotas.collect { snapshot ->
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(snapshot))
                }
            }

            // Periodic refresh. Delay first so we don't double-fire with the ON_START
            // refresh-if-stale that runs when the app is in the foreground; matches the prior
            // viewModelScope cadence.
            scope.launch {
                while (isActive) {
                    delay(QuotaRepository.AUTO_REFRESH_INTERVAL_MS)
                    if (repository.autoRefreshEnabled.value) {
                        repository.doRefresh()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps quota numbers fresh while the app is backgrounded"
                setShowBadge(false)
            }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(snapshot: QuotaSnapshot): Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuotaWatch")
            .setContentText(summarize(snapshot))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(snapshot.timestamp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Compact one-line summary of current quotas for the ongoing notification. */
    private fun summarize(snapshot: QuotaSnapshot): String {
        if (snapshot.quotas.isEmpty()) return "Waiting for first refresh…"
        return snapshot.quotas.joinToString(" · ") { q ->
            if (q.limit > 0) "${q.name} ${"%.0f".format(q.percent * 100)}%"
            else "${q.name} ${q.used.toInt()}${q.unit}"
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
