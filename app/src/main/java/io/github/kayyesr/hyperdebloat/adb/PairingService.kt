package io.github.kayyesr.hyperdebloat.adb

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
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import io.github.kayyesr.hyperdebloat.MainActivity
import io.github.kayyesr.hyperdebloat.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Pairing helper. Android closes the "Pair device with pairing code" dialog as soon as you leave Settings,
 * so the code is typed into this service's notification instead (the same approach Shizuku uses).
 */
class PairingService : Service() {

    companion object {
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_PAIR = "pair"
        private const val ACTION_STOP = "stop"
        private const val KEY_CODE = "code"

        private val _status = MutableStateFlow("")
        /** Pairing progress text, shown on the connect screen as well as in the notification. */
        val status: StateFlow<String> = _status

        private val _port = MutableStateFlow(-1)
        /** Pairing port once found, so the code can also be typed in the app (split screen). */
        val port: StateFlow<Int> = _port

        fun start(context: Context) {
            val i = Intent(context, PairingService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PairingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var searching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            ACTION_PAIR -> {
                showForeground(notification("Pairing...", null, withReply = false))
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                worker.execute {
                    try { pair(code) } catch (_: InterruptedException) { /* closing */ }
                }
            }
            else -> {
                showForeground(notification("Waiting for the pairing code",
                    "In Wireless debugging, tap Pair device with pairing code.", withReply = false))
                startSearch()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSearch() {
        if (searching) return
        if (AdbManager.get(this).isConnected) {
            _status.value = "Already connected."
            finish()
            return
        }
        searching = true
        _status.value = "Waiting. Tap Pair device with pairing code in Wireless debugging."
        worker.execute {
            // Keep looking for up to 10 minutes while the user finds the setting.
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            var port = -1
            try {
                while (port <= 0 && System.currentTimeMillis() < deadline && searching) {
                    port = AdbManager.get(this).findPairingPort(15_000)
                }
            } catch (_: InterruptedException) {
                // Cancelled (the service is closing): stop quietly. Letting this escape would close the whole app.
                return@execute
            }
            if (!searching) return@execute
            searching = false
            if (port > 0) {
                _port.value = port
                _status.value = "Now type the 6-digit code into the HyperDebloat notification."
                update(notification("Enter the pairing code", "Tap Enter code and type the 6 digits shown in Settings.", withReply = true))
            } else {
                _status.value = "Timed out. Tap Start pairing again."
                finish()
            }
        }
    }

    private fun pair(code: String) {
        val port = _port.value
        if (port <= 0) { startSearch(); return }
        val err = AdbManager.get(this).pairAndConnect(port, code)
        if (err == null) {
            _status.value = "Paired and connected."
            update(notification("Paired", "Go back to HyperDebloat.", withReply = false, openApp = true))
            Thread.sleep(4000)
            finish()
        } else {
            // A wrong code closes the pairing dialog and the next one uses a new port, so look for it again.
            _port.value = -1
            update(notification("Wrong code?", "Open Pair device with pairing code again for a new code.", withReply = false))
            Thread.sleep(2500)
            startSearch()
            _status.value = "That didn't work. Open Pair device with pairing code again."
        }
    }

    private fun finish() {
        searching = false
        AdbManager.get(this).stopPairingSearch()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForeground(n: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun update(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun notification(title: String, text: String?, withReply: Boolean, openApp: Boolean = false): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_broom)
            .setContentTitle(title)
            .setOngoing(!openApp)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (text != null) b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        b.setContentIntent(open)
        if (withReply) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()
            val pi = PendingIntent.getForegroundService(
                this, 1, Intent(this, PairingService::class.java).setAction(ACTION_PAIR),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Enter code", pi).addRemoteInput(input).build())
        }
        if (!openApp) {
            val stop = PendingIntent.getService(this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Cancel", stop)
        }
        return b.build()
    }

    override fun onDestroy() {
        searching = false
        worker.shutdownNow()
        super.onDestroy()
    }
}
