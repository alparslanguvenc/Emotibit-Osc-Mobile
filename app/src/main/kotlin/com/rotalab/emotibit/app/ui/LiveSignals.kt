package com.rotalab.emotibit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rotalab.emotibit.R
import com.rotalab.emotibit.protocol.HostState
import com.rotalab.emotibit.protocol.LinkState
import com.rotalab.emotibit.protocol.RecordingStatus
import com.rotalab.emotibit.protocol.SignalBuffers
import com.rotalab.emotibit.protocol.TypeTag
import kotlin.math.max

/** One plotted line: EmotiBit type tag and colour. */
private data class Line(val tag: String, val label: String, val color: Color)

/** One chart panel, like a panel in EmotiBit Oscilloscope. [minSpan] keeps noise from filling the panel. */
private data class Group(val key: String, val title: Int, val unit: String, val lines: List<Line>, val minSpan: Float, val decimals: Int, val stacked: Boolean = false)

private val X = Color(0xFFE63946)
private val Y = Color(0xFF2A9D8F)
private val Z = Color(0xFF457B9D)

private val GROUPS = listOf(
    Group("eda", R.string.sig_eda, "µS", listOf(Line("EA", "EDA", Color(0xFFF4A261))), 0.02f, 3),
    Group(
        "ppg", R.string.sig_ppg, "",
        listOf(Line("PI", "IR", Color(0xFF9B5DE5)), Line("PR", "Red", Color(0xFFE63946)), Line("PG", "Green", Color(0xFF2A9D8F))),
        50f, 0, stacked = true,
    ),
    Group("hr", R.string.sig_hr, "bpm", listOf(Line("HR", "HR", Color(0xFFD62839))), 10f, 0),
    Group("temp", R.string.sig_temp, "°C", listOf(Line("T1", "T1", Color(0xFFE76F51)), Line("TH", "TH", Color(0xFFF4A261)), Line("T0", "T0", Color(0xFFE9C46A))), 0.5f, 2),
    Group("acc", R.string.sig_acc, "g", listOf(Line("AX", "X", X), Line("AY", "Y", Y), Line("AZ", "Z", Z)), 0.2f, 2),
    Group("gyro", R.string.sig_gyro, "°/s", listOf(Line("GX", "X", X), Line("GY", "Y", Y), Line("GZ", "Z", Z)), 10f, 1),
    Group("mag", R.string.sig_mag, "µT", listOf(Line("MX", "X", X), Line("MY", "Y", Y), Line("MZ", "Z", Z)), 10f, 1),
)

private val WINDOWS_S = listOf(5, 10, 30, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSignals(st: HostState, signals: SignalBuffers, markerEnabled: Boolean, onMarker: () -> Unit) {
    // Keep the screen on while watching live signals.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var windowIndex by rememberSaveable { mutableIntStateOf(1) }
    var enabledKeys by rememberSaveable { mutableStateOf(setOf("eda", "ppg", "hr", "temp", "acc")) }
    // Redraw ~25 times a second; reading [frame] only invalidates drawing, not composition.
    var frame by remember { mutableLongStateOf(0L) }
    val static = LocalStaticRendering.current
    LaunchedEffect(Unit) {
        var last = 0L
        while (!static) {
            withFrameMillis { t -> if (t - last >= 40) { last = t; frame = t } }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusStrip(st, markerEnabled, onMarker)

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WINDOWS_S.forEachIndexed { i, w ->
                SegmentedButton(selected = windowIndex == i, onClick = { windowIndex = i }, shape = SegmentedButtonDefaults.itemShape(i, WINDOWS_S.size)) {
                    Text(stringResource(R.string.sig_window_s, w))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in GROUPS) {
                val on = g.key in enabledKeys
                FilterChip(
                    selected = on,
                    onClick = { enabledKeys = if (on) enabledKeys - g.key else enabledKeys + g.key },
                    label = { Text(stringResource(g.title)) },
                )
            }
        }

        val streaming = st.link == LinkState.CONNECTED && st.lastDataAtMs != null
        if (!streaming) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(
                    stringResource(if (st.powerMode == TypeTag.MODE_WIRELESS_OFF) R.string.sig_wifi_off else R.string.sig_no_stream),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }

        val windowMs = WINDOWS_S[windowIndex] * 1000.0
        for (g in GROUPS) {
            if (g.key !in enabledKeys) continue
            ChartPanel(g, signals, windowMs, frameProvider = { frame })
        }
        Text(stringResource(R.string.sig_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusStrip(st: HostState, markerEnabled: Boolean, onMarker: () -> Unit) {
    val recording = st.recording == RecordingStatus.RECORDING || st.recording == RecordingStatus.STOPPING
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (recording) StatusColors.recordContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (recording) StatusColors.onRecordContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val linkText = when (st.link) {
                LinkState.CONNECTED -> stringResource(R.string.sig_connected)
                LinkState.CONNECTING -> stringResource(R.string.sig_connecting)
                else -> stringResource(R.string.sig_disconnected)
            }
            Column(Modifier.weight(1f)) {
                Text(linkText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (st.link == LinkState.CONNECTED) StatusColors.ok else StatusColors.warn)
                Text(
                    if (recording) "● " + stringResource(R.string.rec_recording) + " · " + stringResource(R.string.sig_syncs, st.syncsInRecording)
                    else stringResource(R.string.rec_not_recording),
                    color = if (recording) StatusColors.record else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                )
            }
            FilledTonalButton(onClick = onMarker, enabled = markerEnabled) {
                Icon(Icons.Outlined.FlashOn, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sig_marker_short))
            }
        }
    }
}

private fun fmt(v: Float, decimals: Int): String = when (decimals) {
    0 -> "%.0f".format(v)
    1 -> "%.1f".format(v)
    2 -> "%.2f".format(v)
    else -> "%.3f".format(v)
}

@Composable
private fun ChartPanel(g: Group, signals: SignalBuffers, windowMs: Double, frameProvider: () -> Long) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val titleStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val smallStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val title = stringResource(g.title) + if (g.unit.isNotEmpty()) " (${g.unit})" else ""
    val noData = stringResource(R.string.sig_no_data)
    val height = when (g.key) { "hr" -> 110.dp; "ppg" -> 210.dp; else -> 150.dp }

    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Canvas(Modifier.fillMaxWidth().height(height).padding(horizontal = 14.dp, vertical = 10.dp)) {
            frameProvider() // subscribe to redraw ticks
            val now = signals.latestTimeMs
            val from = now - windowMs
            val snaps = g.lines.map { it to (if (now.isNaN()) null else signals.snapshot(it.tag, from)) }
                .filter { (_, s) -> s != null && s.size > 0 }

            val top = 22.dp.toPx()
            val left = 46.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val right = size.width
            val plotW = right - left
            val plotH = bottom - top

            // Title and latest values
            drawText(measurer, title, Offset(0f, 0f), titleStyle)
            var x = right
            for ((line, s) in snaps.reversed()) {
                val txt = "${line.label} ${fmt(s!!.values.last(), g.decimals)}"
                val layout = measurer.measure(txt, smallStyle.copy(color = line.color, fontWeight = FontWeight.Bold))
                x -= layout.size.width
                drawText(layout, topLeft = Offset(x, 3.dp.toPx()))
                x -= 8.dp.toPx()
            }

            // Grid
            if (!g.stacked) for (i in 0..2) {
                val y = top + plotH * i / 2f
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f,
                    pathEffect = if (i == 1) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null)
            }

            if (snaps.isEmpty()) {
                drawText(measurer, noData, Offset(left + 8.dp.toPx(), top + plotH / 2 - 8.dp.toPx()), smallStyle)
                return@Canvas
            }

            fun range(values: List<FloatArray>): Pair<Float, Float> {
                var lo = Float.POSITIVE_INFINITY
                var hi = Float.NEGATIVE_INFINITY
                for (arr in values) for (v in arr) { if (v < lo) lo = v; if (v > hi) hi = v }
                if (hi - lo < g.minSpan) {
                    val mid = (hi + lo) / 2
                    lo = mid - g.minSpan / 2; hi = mid + g.minSpan / 2
                }
                val pad = (hi - lo) * 0.08f
                return (lo - pad) to (hi + pad)
            }

            fun px(t: Double) = left + ((t - from) / windowMs * plotW).toFloat()

            // Stacked groups (PPG) give each line its own lane and scale, like separate Oscilloscope panels.
            val lanes = if (g.stacked) snaps.size else 1
            val laneH = plotH / lanes
            val shared = if (g.stacked) null else range(snaps.map { it.second!!.values })
            if (shared != null) {
                drawText(measurer, fmt(shared.second, g.decimals), Offset(0f, top - 2.dp.toPx()), smallStyle)
                drawText(measurer, fmt(shared.first, g.decimals), Offset(0f, bottom - 12.dp.toPx()), smallStyle)
            }

            snaps.forEachIndexed { lane, (line, sn) ->
                val s = sn!!
                val (lo, hi) = shared ?: range(listOf(s.values))
                val laneTop = top + (if (g.stacked) lane else 0) * laneH
                val laneBottom = laneTop + laneH
                fun py(v: Float) = laneBottom - (v - lo) / (hi - lo) * laneH
                if (g.stacked) {
                    drawText(measurer, line.label, Offset(0f, laneTop + laneH / 2 - 7.dp.toPx()), smallStyle.copy(color = line.color, fontWeight = FontWeight.Bold))
                    if (lane > 0) drawLine(gridColor, Offset(left, laneTop), Offset(right, laneTop), strokeWidth = 1f)
                }
                val path = Path()
                var started = false
                var prevT = Double.NaN
                // Thin out to about one point per pixel.
                val step = max(1, s.size / max(1, plotW.toInt()))
                val indices = (0 until s.size step step) + if ((s.size - 1) % step != 0) listOf(s.size - 1) else emptyList()
                for (i in indices) {
                    val t = s.times[i]
                    val gap = !prevT.isNaN() && t - prevT > SignalBuffers.GAP_MS
                    if (!started || gap) { path.moveTo(px(t), py(s.values[i])); started = true } else path.lineTo(px(t), py(s.values[i]))
                    prevT = t
                }
                val aperiodic = line.tag in SignalBuffers.APERIODIC
                drawPath(path, line.color, style = Stroke(width = if (aperiodic) 2.5.dp.toPx() else 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (aperiodic) for (k in 0 until s.size) drawCircle(line.color, 2.5.dp.toPx(), Offset(px(s.times[k]), py(s.values[k])))
            }
        }
    }
}
