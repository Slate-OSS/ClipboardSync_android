package com.finitecode.clipboardsync

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ClipboardSyncService : Service() {

    private var networkManager: NetworkManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "clipboard_sync_channel"

        fun start(context: Context, macIp: String, deviceId: String) {
            val intent = Intent(context, ClipboardSyncService::class.java).apply {
                putExtra("mac_ip", macIp)
                putExtra("device_id", deviceId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardSyncService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire wake lock to keep network alive
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ClipboardSync::NetworkWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val macIp = intent?.getStringExtra("mac_ip") ?: return START_NOT_STICKY
        val deviceId = intent.getStringExtra("device_id") ?: return START_NOT_STICKY

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        // Initialize network manager
        networkManager = NetworkManager(deviceId) { message ->
            // Handle received messages
            sendBroadcast(Intent("clipboard_sync.MESSAGE_RECEIVED").apply {
                putExtra("type", message.type)
                putExtra("payload", message.payload)
                putExtra("fromDeviceId", message.fromDeviceId)
            })
        }

        networkManager?.onConnectionStatusChanged = { status ->
            updateNotification(status.displayString())
        }

        // Connect to Mac
        networkManager?.connect(macIp)

        return START_STICKY // Restart service if killed
    }

    override fun onDestroy() {
        networkManager?.cleanup()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps clipboard syncing in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clipboard Sync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    fun sendMessageFromApp(message: SyncMessage): Boolean {
        return networkManager?.sendMessage(message) ?: false
    }
}
