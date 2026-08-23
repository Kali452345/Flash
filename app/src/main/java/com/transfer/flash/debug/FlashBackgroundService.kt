package com.transfer.flash.debug

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background discovery host (P3.5/D-M4 skeleton).
 *
 * Type choice per current platform rules (research 2026-08-23, see
 * docs/android-platform-notes.md): `connectedDevice` — no dataSync-style
 * 6h/24h timeout applies, and the precondition is satisfied by our declared
 * CHANGE_WIFI_MULTICAST_STATE permission. The service hosts advertise+browse
 * while Flash is screened; actual background RECEIVING of messages/files is
 * deferred until C4 (always-on listener) + C5/C6 (receive pipeline) land.
 *
 * Started only from user-visible UI actions (Dev Console), which satisfies
 * background-start restrictions.
 */
class FlashBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        scope.launch {
            runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
                .onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        // Engine itself keeps running if the Dev Console wants it; stopping the
        // service only releases foreground priority. Full stop is explicit.
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flash nearby presence",
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Flash is discoverable")
            .setContentText("Nearby devices can find and reach this phone.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SERVICE"
        private const val CHANNEL_ID = "flash_discovery_bg"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, FlashBackgroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashBackgroundService::class.java))
        }
    }
}
