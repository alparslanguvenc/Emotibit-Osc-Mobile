package com.rotalab.emotibit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rotalab.emotibit.R
import com.rotalab.emotibit.protocol.LinkState
import com.rotalab.emotibit.protocol.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the EmotiBit connection alive with the screen off: foreground service, partial wake lock
 * and a low-latency Wi-Fi lock (time-sync replies must reach EmotiBit within 100 ms).
 */
class EmotiBitService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(this, NOTIF_STATUS, buildStatus(getString(R.string.notif_idle)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RotaLab:EmotiBit").apply { setReferenceCounted(false); acquire(WAKE_LOCK_MAX_MS) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RotaLab:EmotiBit").apply { setReferenceCounted(false); acquire() }

        HostController.io {
            HostController.host.start()
            HostController.savedTarget?.let { HostController.host.connect(it) }
        }

        scope.launch {
            HostController.host.state.map { st ->
                when {
                    st.recording == RecordingStatus.RECORDING || st.recording == RecordingStatus.STOPPING ->
                        getString(R.string.notif_recording, st.recordingFile ?: "", st.syncsInRecording) +
                            (if (st.link == LinkState.CONNECTED) "" else " · " + getString(R.string.link_waiting, st.targetDeviceId ?: ""))
                    st.link == LinkState.CONNECTED -> getString(R.string.link_connected, st.connectedDeviceId ?: "", st.connectedIp ?: "")
                    st.targetDeviceId != null -> getString(R.string.link_waiting, st.targetDeviceId ?: "")
                    else -> getString(R.string.link_searching)
                }
            }.distinctUntilChanged().collect { text ->
                ContextCompat.getSystemService(this@EmotiBitService, NotificationManager::class.java)
                    ?.notify(NOTIF_STATUS, buildStatus(text))
            }
        }
        scope.launch {
            HostController.host.events.collect { e ->
                val text = when (e.type) {
                    "connected" -> getString(R.string.notif_reconnected, e.fields["device"] ?: "")
                    "connection_lost" -> if (isRecording()) getString(R.string.notif_lost, e.fields["device"] ?: "") else null
                    "record_stopped" -> getString(R.string.notif_stopped, e.fields["file"] ?: "")
                    "safe_stop_timeout" -> getString(R.string.safe_timeout)
                    else -> null
                }
                if (text != null) alert(text)
            }
        }
    }

    private fun isRecording(): Boolean = HostController.host.state.value.recording.let {
        it == RecordingStatus.RECORDING || it == RecordingStatus.STOPPING
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        Thread { HostController.host.stop() }.start()
        wakeLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildStatus(text: String): Notification = NotificationCompat.Builder(this, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat_emotibit)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent())
        .build()

    private fun alert(text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_emotibit)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_ALERTS = "alerts"
        private const val NOTIF_STATUS = 1
        private const val ALERT_ID = 2
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EmotiBitService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmotiBitService::class.java))
        }
    }
}
