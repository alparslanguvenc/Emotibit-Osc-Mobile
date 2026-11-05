package com.rotalab.emotibit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rotalab.emotibit.R
import com.rotalab.emotibit.protocol.HostEvent

/** Localised one-line description of a host event for the on-screen log. */
@Composable
fun eventText(e: HostEvent): String {
    fun f(k: String) = e.fields[k] ?: ""
    return when (e.type) {
        "host_started" -> stringResource(R.string.ev_host_started, f("dataPort"), f("controlPort"))
        "host_stopped" -> stringResource(R.string.ev_host_stopped)
        "target_set" -> stringResource(R.string.ev_target_set, f("device"))
        "device_found" -> stringResource(R.string.ev_device_found, f("device"), f("ip"))
        "connecting" -> stringResource(R.string.ev_connecting, f("device"))
        "connected" -> stringResource(R.string.ev_connected, f("device"))
        "connection_lost" -> stringResource(R.string.ev_connection_lost, f("device"))
        "connect_timeout" -> stringResource(R.string.ev_connect_timeout, f("device"))
        "disconnected" -> stringResource(R.string.ev_disconnected)
        "record_start_sent" -> stringResource(R.string.ev_record_start_sent)
        "record_started" -> stringResource(R.string.ev_record_started, f("file"))
        "recording_in_progress" -> stringResource(R.string.ev_recording_in_progress, f("file"))
        "record_start_unconfirmed" -> stringResource(R.string.ev_record_start_unconfirmed)
        "record_stop_sent" -> stringResource(R.string.ev_record_stop_sent)
        "record_stopped" -> stringResource(R.string.ev_record_stopped, f("file"), f("syncsInRecording"))
        "record_stop_unconfirmed_retry" -> stringResource(R.string.ev_record_stop_unconfirmed_retry)
        "safe_stop_requested" -> stringResource(R.string.ev_safe_stop_requested)
        "safe_stop_cancelled" -> stringResource(R.string.ev_safe_stop_cancelled)
        "safe_stop_timeout" -> stringResource(R.string.ev_safe_stop_timeout)
        "note_sent" -> stringResource(R.string.ev_note_sent, f("text"))
        "marker_sent" -> stringResource(R.string.ev_marker_sent, f("label"))
        "power_mode_sent" -> stringResource(R.string.ev_power_mode_sent, f("mode"))
        "power_mode" -> stringResource(R.string.ev_power_mode, f("mode"))
        "device_data_overflow" -> stringResource(R.string.ev_device_data_overflow)
        "manual_target" -> stringResource(R.string.ev_manual_target, f("ip"))
        else -> e.type + if (e.fields.isEmpty()) "" else " " + e.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
