package app.browservault.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf

/** Előtér-szolgáltatás: a vevő akkor is fut, ha az app a háttérben van. */
class ServerService : Service() {

    private var server: VaultServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setRunning(true)
        startForegroundNow("Indítás…")

        val config = Vault.config(this)
        val started = VaultServer(Vault.store(this), config) { line ->
            setActivity(line)
        }

        try {
            started.start()
            server = started
            setError(null)
            updateNotification("Fut · " + (Network.preferred() ?: "nincs hálózat") + ":" + config.port)
        } catch (error: Exception) {
            setError(error.message ?: "Nem sikerült elindítani a vevőt.")
            setRunning(false)
            stopSelf()
            return
        }

        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrowserVault::server")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        setRunning(false)
        super.onDestroy()
    }

    private fun startForegroundNow(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BrowserVault vevő",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "A vevő futásjelzője"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("BrowserVault fut")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "app.browservault.vault.START"
        const val ACTION_STOP = "app.browservault.vault.STOP"

        private const val CHANNEL_ID = "vault-server"
        private const val NOTIFICATION_ID = 41

        private val runningState = mutableStateOf(false)
        val running: Boolean get() = runningState.value

        private val errorState = mutableStateOf<String?>(null)
        val lastError: String? get() = errorState.value

        private val activityState = mutableStateOf<String?>(null)
        val lastActivity: String? get() = activityState.value

        internal fun setRunning(value: Boolean) {
            runningState.value = value
        }

        internal fun setError(value: String?) {
            errorState.value = value
        }

        internal fun setActivity(value: String) {
            activityState.value = value
        }

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
