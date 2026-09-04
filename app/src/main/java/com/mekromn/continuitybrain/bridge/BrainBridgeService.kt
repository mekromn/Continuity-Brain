package com.mekromn.continuitybrain.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mekromn.continuitybrain.ContinuityBrainApplication
import com.mekromn.continuitybrain.MainActivity
import com.mekromn.continuitybrain.R

/**
 * Explicit, user-controlled foreground lifetime for the localhost bridge.
 * The service never listens on Wi-Fi/cellular interfaces and performs no
 * outbound network requests.
 */
class BrainBridgeService : Service() {
    private var server: LocalBrainServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge()
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        startBridge()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBridge() {
        if (server != null) return
        val app = application as ContinuityBrainApplication
        val tokenStore = BridgeTokenStore(app.repository)
        val created = LocalBrainServer(
            repository = app.repository,
            tokenProvider = tokenStore::token,
        )
        created.start()
        server = created
        app.repository.setEncryptedSetting(SETTING_ENABLED, "true")
    }

    private fun stopBridge() {
        server?.close()
        server = null
        (application as? ContinuityBrainApplication)
            ?.repository
            ?.setEncryptedSetting(SETTING_ENABLED, "false")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Continuity Brain bridge active")
        .setContentText("Private localhost access is available to paired Continuity clients")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Stop bridge",
            PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Continuity Brain bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown only while the user-enabled local knowledge bridge is running"
                setSound(null, null)
            },
        )
    }

    companion object {
        const val SETTING_ENABLED = "bridge.enabled.v1"
        private const val CHANNEL_ID = "continuity_brain_bridge"
        private const val NOTIFICATION_ID = 41027
        private const val ACTION_START = "com.mekromn.continuitybrain.bridge.START"
        private const val ACTION_STOP = "com.mekromn.continuitybrain.bridge.STOP"

        fun startIntent(context: Context) = Intent(context, BrainBridgeService::class.java).apply {
            action = ACTION_START
        }

        fun stopIntent(context: Context) = Intent(context, BrainBridgeService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
