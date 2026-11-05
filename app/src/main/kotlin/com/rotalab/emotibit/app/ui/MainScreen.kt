package com.rotalab.emotibit.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rotalab.emotibit.R
import com.rotalab.emotibit.app.HostController
import com.rotalab.emotibit.app.MainActions
import com.rotalab.emotibit.protocol.EmotiBitHost.CommandResult
import com.rotalab.emotibit.protocol.EmotiBitTime
import com.rotalab.emotibit.protocol.HostEvent
import com.rotalab.emotibit.protocol.HostState
import com.rotalab.emotibit.protocol.LinkState
import com.rotalab.emotibit.protocol.RecordingStatus
import com.rotalab.emotibit.protocol.SignalBuffers
import com.rotalab.emotibit.protocol.TypeTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime

@Composable
fun MainScreen(actions: MainActions) {
    val st by HostController.host.state.collectAsStateWithLifecycle()
    val events by HostController.log.recent.collectAsStateWithLifecycle()
    MainContent(actions, st, events)
}

private val HostState.isRecording get() = recording == RecordingStatus.RECORDING || recording == RecordingStatus.STOPPING
private val HostState.isReady get() = link == LinkState.CONNECTED && controlChannelReady

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    actions: MainActions,
    st: HostState,
    events: List<HostEvent>,
    initialNowMs: Long = System.currentTimeMillis(),
    initialTab: Int = 0,
    signals: SignalBuffers = HostController.host.signals,
) {
    val host = HostController.host
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var flash by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(initialNowMs) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    fun resultMessage(r: CommandResult): Int? = when (r) {
        CommandResult.SENT -> null
        CommandResult.NOT_CONNECTED -> R.string.res_not_connected
        CommandResult.NO_CONTROL_CHANNEL -> R.string.res_no_control
        CommandResult.ALREADY_RECORDING -> R.string.res_already_recording
        CommandResult.NOT_RECORDING -> R.string.res_not_recording
        CommandResult.BUSY -> R.string.res_busy
    }

    /** Runs a socket command off the main thread and reports a failure in the snackbar. */
    fun command(block: () -> CommandResult) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            resultMessage(r)?.let { snackbar.showSnackbar(ctx.getString(it)) }
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val sendMarker: () -> Unit = {
        scope.launch {
            val label = withContext(Dispatchers.IO) { host.sendSyncMarker() }
            if (label != null) {
                flash = true
                beep()
                snackbar.showSnackbar(ctx.getString(R.string.marker_sent, label))
            } else {
                snackbar.showSnackbar(ctx.getString(R.string.res_not_connected))
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { langOpen = true }) { Icon(Icons.Outlined.Language, stringResource(R.string.menu_language)) }
                    DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.language_system)) }, onClick = { langOpen = false; actions.setLanguage(null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.language_en)) }, onClick = { langOpen = false; actions.setLanguage("en") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.language_tr)) }, onClick = { langOpen = false; actions.setLanguage("tr") })
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { menuOpen = false; dialog = "about" },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_quit)) }, leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null) },
                            onClick = { menuOpen = false; dialog = "quit" },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text(stringResource(R.string.tab_control)) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.MonitorHeart, null) }, label = { Text(stringResource(R.string.tab_live)) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 1) {
                LiveSignals(st, signals, markerEnabled = st.isReady, onMarker = sendMarker)
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SessionCard(
                        st, now,
                        onStart = { command { host.startRecording() } },
                        onStop = { dialog = "stop" },
                        onCancelSafe = { HostController.io { host.cancelSafeStop() } },
                        onStopNow = { command { host.stopRecordingNow() } },
                    )
                    StatRow(st)
                    MarkerCard(st, onMarker = sendMarker, onNote = { text -> command { host.sendNote(text) } })
                    DeviceCard(st, actions, onWifiOff = { dialog = "wifi" }, onHibernate = { dialog = "hibernate" })
                    GuideCard()
                    LogCard(events.take(40), onShare = actions::shareLog)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (flash) {
                val haptic = LocalHapticFeedback.current
                LaunchedEffect(Unit) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(350); flash = false
                }
                Box(Modifier.fillMaxSize().background(Color.White))
            }
        }
    }

    when (dialog) {
        "stop" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Outlined.Stop, null) },
            title = { Text(stringResource(R.string.stop_title)) },
            text = { Text(stringResource(R.string.stop_text, 3)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = { dialog = null; command { host.requestSafeStop() } }) { Text(stringResource(R.string.stop_safe)) }
                    TextButton(onClick = { dialog = null; command { host.stopRecordingNow() } }, enabled = st.link == LinkState.CONNECTED) {
                        Text(stringResource(R.string.stop_now), color = StatusColors.record)
                    }
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
        "wifi" -> ConfirmDialog(Icons.Outlined.WifiOff, R.string.wifi_off_title, R.string.wifi_off_text, R.string.wifi_off_confirm, onDismiss = { dialog = null }) {
            command { host.setPowerMode(TypeTag.MODE_WIRELESS_OFF) }
        }
        "hibernate" -> ConfirmDialog(Icons.Outlined.Bedtime, R.string.hibernate_title, R.string.hibernate_text, R.string.hibernate_confirm, onDismiss = { dialog = null }) {
            command { host.setPowerMode(TypeTag.MODE_HIBERNATE) }
        }
        "quit" -> ConfirmDialog(
            Icons.Outlined.PowerSettingsNew,
            R.string.quit_title,
            if (st.isRecording) R.string.quit_recording_warning else R.string.quit_idle,
            R.string.quit_confirm,
            onDismiss = { dialog = null },
        ) { actions.quit() }
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.about_text, actions.versionName())) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun beep() {
    try {
        val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        Thread { Thread.sleep(600); tg.release() }.start()
    } catch (_: Exception) {
    }
}

@Composable
private fun ConfirmDialog(icon: ImageVector, title: Int, text: Int, confirm: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/** Small rounded label with a coloured dot, e.g. "● Live". */
@Composable
private fun StatusPill(text: String, color: Color, pulsing: Boolean = false) {
    val alpha = if (pulsing && !LocalStaticRendering.current) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
    } else 1f
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).alpha(alpha).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun linkPill(st: HostState): Pair<String, Color> = when {
    st.link == LinkState.STOPPED -> stringResource(R.string.pill_stopped) to StatusColors.idle
    st.link == LinkState.CONNECTED -> stringResource(R.string.pill_live) to StatusColors.ok
    st.link == LinkState.CONNECTING -> stringResource(R.string.pill_connecting) to StatusColors.warn
    st.targetDeviceId != null -> stringResource(R.string.pill_out_of_range) to StatusColors.warn
    else -> stringResource(R.string.pill_searching) to StatusColors.idle
}

private fun formatDuration(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

@Composable
private fun SessionCard(st: HostState, nowMs: Long, onStart: () -> Unit, onStop: () -> Unit, onCancelSafe: () -> Unit, onStopNow: () -> Unit) {
    val recording = st.isRecording
    val container by animateColorAsState(if (recording) StatusColors.recordContainer else MaterialTheme.colorScheme.primaryContainer, label = "c")
    val onContainer = if (recording) StatusColors.onRecordContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Device + link
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sensors, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.connectedDeviceId ?: st.targetDeviceId ?: stringResource(R.string.no_device), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    st.connectedIp?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.75f)) }
                }
                val (pill, color) = linkPill(st)
                StatusPill(pill, color)
            }

            // Recording state + timer
            val (label, pulsing) = when (st.recording) {
                RecordingStatus.UNKNOWN -> stringResource(if (st.link == LinkState.CONNECTED) R.string.rec_unknown else R.string.rec_ready_connect) to false
                RecordingStatus.NOT_RECORDING -> stringResource(R.string.rec_ready) to false
                RecordingStatus.STARTING -> stringResource(R.string.rec_starting) to true
                RecordingStatus.RECORDING -> stringResource(R.string.rec_recording) to true
                RecordingStatus.STOPPING -> stringResource(R.string.rec_stopping) to true
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        val a = if (LocalStaticRendering.current) 1f else {
                            val t = rememberInfiniteTransition(label = "rec")
                            t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "r").value
                        }
                        Icon(Icons.Filled.FiberManualRecord, null, tint = StatusColors.record, modifier = Modifier.size(16.dp).alpha(a))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (recording) StatusColors.record else onContainer)
                }
                val start = st.recordingFile?.let { EmotiBitTime.parse(it) }
                val elapsed = if (recording && start != null && nowMs > 0) Duration.between(start, LocalDateTime.now()) else Duration.ZERO
                Text(
                    formatDuration(elapsed),
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
                    modifier = Modifier.alpha(if (recording) 1f else 0.35f),
                )
                if (recording && st.recordingFile != null) {
                    Text(st.recordingFile!!, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.75f))
                }
            }

            // Time syncs
            if (recording) {
                val n = st.syncsInRecording
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n >= 2) Icon(Icons.Outlined.CheckCircle, null, tint = StatusColors.ok, modifier = Modifier.size(18.dp))
                        if (n >= 2) Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.syncs_line, n, st.syncsThisConnection), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (n < 2) {
                        LinearProgressIndicator(progress = { n / 2f }, modifier = Modifier.fillMaxWidth(), color = StatusColors.warn)
                        Text(stringResource(R.string.syncs_start_wait), style = MaterialTheme.typography.bodySmall)
                    } else if (st.safeStop == null) {
                        Text(stringResource(R.string.syncs_start_ok_short), style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.8f))
                    }
                    if (st.link != LinkState.CONNECTED && st.recordingStatusAtMs != null) {
                        val ago = Duration.ofMillis(HostController.host.nowMs() - st.recordingStatusAtMs!!)
                        Text(stringResource(R.string.rec_last_confirmed, formatDuration(ago)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (st.error == "record_start_unconfirmed") Text(stringResource(R.string.err_record_start_unconfirmed), color = StatusColors.record, style = MaterialTheme.typography.bodySmall)

            // Safe stop progress
            AnimatedVisibility(st.safeStop != null) {
                val ss = st.safeStop
                if (ss != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val msg = when {
                                ss.timedOut -> stringResource(R.string.safe_timeout)
                                st.link != LinkState.CONNECTED -> stringResource(R.string.safe_waiting_link)
                                else -> stringResource(R.string.safe_collecting, st.syncsThisConnection.coerceAtMost(ss.requiredSyncs), ss.requiredSyncs)
                            }
                            Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (st.link == LinkState.CONNECTED && !ss.timedOut) {
                                LinearProgressIndicator(progress = { st.syncsThisConnection.coerceAtMost(ss.requiredSyncs) / ss.requiredSyncs.toFloat() }, modifier = Modifier.fillMaxWidth())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onCancelSafe) { Text(stringResource(R.string.safe_cancel)) }
                                if (ss.timedOut) Button(onClick = onStopNow, colors = ButtonDefaults.buttonColors(containerColor = StatusColors.record)) { Text(stringResource(R.string.stop_now)) }
                            }
                        }
                    }
                }
            }

            // Actions
            if (!recording) {
                Button(
                    onClick = onStart,
                    enabled = st.isReady && st.recording == RecordingStatus.NOT_RECORDING,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusColors.record, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.FiberManualRecord, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_start), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                FilledTonalButton(
                    onClick = onStop,
                    enabled = st.safeStop == null && st.recording != RecordingStatus.STOPPING,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = StatusColors.record),
                ) {
                    Icon(Icons.Outlined.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_stop), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun modeName(tag: String?): String = when (tag) {
    TypeTag.MODE_NORMAL_POWER -> stringResource(R.string.mode_MN)
    TypeTag.MODE_LOW_POWER -> stringResource(R.string.mode_ML)
    TypeTag.MODE_MAX_LOW_POWER -> stringResource(R.string.mode_MM)
    TypeTag.MODE_WIRELESS_OFF -> stringResource(R.string.mode_MO)
    TypeTag.MODE_HIBERNATE -> stringResource(R.string.mode_MH)
    null -> "–"
    else -> tag
}

@Composable
private fun StatRow(st: HostState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(Icons.Outlined.BatteryChargingFull, stringResource(R.string.stat_battery), st.batteryPercent?.let { "%.0f %%".format(it) } ?: "–", Modifier.weight(1f))
        StatTile(Icons.Outlined.Tune, stringResource(R.string.stat_mode), modeName(st.powerMode), Modifier.weight(1f))
        StatTile(Icons.Outlined.Speed, stringResource(R.string.stat_data), if (st.link == LinkState.CONNECTED) stringResource(R.string.stat_data_value, st.dataPacketsPerSecond) else "–", Modifier.weight(1f))
    }
}

@Composable
private fun MarkerCard(st: HostState, onMarker: () -> Unit, onNote: (String) -> Unit) {
    SectionCard(stringResource(R.string.section_markers), Icons.Outlined.FlashOn) {
        val connected = st.isReady
        FilledTonalButton(onClick = onMarker, enabled = connected, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
            Icon(Icons.Outlined.FlashOn, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_marker))
            if (st.markerCount > 0) Text("  ·  ${st.markerCount}", modifier = Modifier.alpha(0.7f))
        }
        Text(stringResource(R.string.marker_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var note by remember { mutableStateOf("") }
        val send = { if (note.isNotBlank()) { onNote(note); note = "" } }
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(180) },
            placeholder = { Text(stringResource(R.string.note_label)) }, singleLine = true, enabled = connected,
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = send, enabled = connected && note.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.note_send)) }
            },
        )
    }
}

@Composable
private fun DeviceCard(st: HostState, actions: MainActions, onWifiOff: () -> Unit, onHibernate: () -> Unit) {
    SectionCard(stringResource(R.string.section_connection), Icons.Outlined.Sensors) {
        if (st.link != LinkState.STOPPED) {
            if (st.networks.isEmpty()) Text(stringResource(R.string.no_network), color = StatusColors.warn, style = MaterialTheme.typography.bodyMedium)
            else Text(stringResource(R.string.networks_label, st.networks.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.link_stopped), style = MaterialTheme.typography.bodyMedium)
        }
        if (st.link == LinkState.CONNECTED && !st.controlChannelReady) Text(stringResource(R.string.control_not_ready), style = MaterialTheme.typography.bodySmall)
        if (st.devices.isEmpty() && st.link != LinkState.STOPPED) {
            Text(stringResource(R.string.link_searching), style = MaterialTheme.typography.bodyMedium)
        }
        for (d in st.devices) {
            val status = when {
                d.id == st.connectedDeviceId -> stringResource(R.string.pill_live)
                d.available -> stringResource(R.string.device_available)
                d.busy && HostController.host.nowMs() - d.lastSeenMs < 5000 -> stringResource(R.string.device_busy)
                else -> stringResource(R.string.device_unseen)
            }
            ListItem(
                headlineContent = { Text(d.id, fontWeight = FontWeight.Medium) },
                supportingContent = { Text("${d.ip} · $status" + if (d.id == st.targetDeviceId) " · " + stringResource(R.string.device_target) else "") },
                leadingContent = { Icon(Icons.Outlined.Sensors, null, tint = if (d.id == st.connectedDeviceId) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = {
                    if (d.id == st.targetDeviceId) TextButton(onClick = { HostController.forget() }) { Text(stringResource(R.string.device_forget)) }
                    else Button(onClick = { HostController.connect(d.id) }, enabled = st.link != LinkState.CONNECTED) { Text(stringResource(R.string.device_connect)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (st.devices.isEmpty() && st.link != LinkState.STOPPED) {
            var ip by remember { mutableStateOf("") }
            OutlinedTextField(
                value = ip, onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.manual_ip_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = { TextButton(onClick = { val v = ip; HostController.io { HostController.host.addManualTarget(v) } }, enabled = ip.count { it == '.' } == 3) { Text(stringResource(R.string.manual_ip_add)) } },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onWifiOff, enabled = st.isReady, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.WifiOff, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_wifi_off_short), maxLines = 1)
            }
            OutlinedButton(onClick = onHibernate, enabled = st.isReady && st.recording == RecordingStatus.NOT_RECORDING, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Bedtime, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_hibernate_short), maxLines = 1)
            }
        }
        var ignoring by remember { mutableStateOf(actions.isIgnoringBatteryOptimizations()) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ignoring = actions.isIgnoringBatteryOptimizations()
            }
            lifecycle.lifecycle.addObserver(obs)
            onDispose { lifecycle.lifecycle.removeObserver(obs) }
        }
        if (ignoring) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = StatusColors.ok, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.battery_opt_ok), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(stringResource(R.string.battery_opt_hint), style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            TextButton(onClick = actions::requestIgnoreBatteryOptimizations) { Text(stringResource(R.string.btn_battery_opt)) }
        }
    }
}

@Composable
private fun GuideCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        stringResource(R.string.section_guide), Icons.Outlined.Route,
        trailing = { IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) } },
    ) {
        AnimatedVisibility(open) { Text(stringResource(R.string.guide_text), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun LogCard(events: List<HostEvent>, onShare: () -> Unit) {
    SectionCard(
        stringResource(R.string.section_log), Icons.Outlined.History,
        trailing = { IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.log_share)) } },
    ) {
        if (events.isEmpty()) Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (e in events) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    e.wallTime.substring(11, 19.coerceAtMost(e.wallTime.length)),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(66.dp).padding(top = 2.dp),
                )
                Text(eventText(e), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
