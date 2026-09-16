package org.furo.rtcmstreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.furo.rtcmstreamer.gnss.GnssCapture
import org.furo.rtcmstreamer.gnss.GnssLogger
import org.furo.rtcmstreamer.net.SolDownlink
import org.furo.rtcmstreamer.rtcm.RtcmStreamer
import java.io.File

/**
 * App entry screen.
 * GNSS capture runs at 1 Hz from launch. Connect opens the RTCM uplink (MSM7) and the
 * solution downlink (.pos); Disconnect closes both. The East-North plot tracks the received solution.
 */
class StreamerActivity : ComponentActivity() {

    private val vm: StreamerViewModel by viewModels()
    private lateinit var capture: GnssCapture
    private lateinit var logger: GnssLogger
    private val streamer = RtcmStreamer()
    private val solDownlink = SolDownlink()

    // Files of the current Log / Connect session (for the share sheet on Stop log / Disconnect).
    private var rinexFile: File? = null
    private var rtcmFile: File? = null
    private var posFile: File? = null
    private var commsSaving = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.permissionGranted = granted
            if (granted) startCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logger = GnssLogger(File(getExternalFilesDir(null), "gnsslog"))
        capture = GnssCapture(this).apply {
            onSummary = { s -> vm.onSummary(s) }
            onEvent = { event ->
                streamer.onGnssEvent(event)
                vm.epochsSent = streamer.epochsSent
                if (vm.logging) logger.log(event, System.currentTimeMillis())
            }
        }
        streamer.uplink.onState = { connected, msg ->
            runOnUiThread {
                vm.connected = connected
                vm.connStatus = msg
            }
        }
        solDownlink.onState = { _, msg -> runOnUiThread { vm.solStatus = msg } }
        solDownlink.onSolution = { sol -> runOnUiThread { vm.onSolution(sol) } }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamerScreen(
                        vm = vm,
                        onToggleLog = ::toggleLogging,
                        onConnectToggle = ::toggleConnection,
                        onResetTrack = { vm.resetTrack() },
                    )
                }
            }
        }

        if (hasLocationPermission()) {
            vm.permissionGranted = true
            startCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onDestroy() {
        capture.stop()
        logger.stop()
        streamer.uplink.disconnect()
        solDownlink.disconnect()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // permission is checked before calling
    private fun startCapture() {
        capture.start()
    }

    // File-name stamp from the current GPS time ("yyyy-MM-dd-HH-mm-ss"), falling back to a
    // wall-clock stamp if no GNSS fix yet. Colons are illegal in file names, so all-hyphen.
    private fun fileStamp(): String {
        val g = vm.summary.gpst // "yyyy-MM-dd HH:mm:ss" (GPST) or ""
        return if (g.length >= 19) g.substring(0, 19).replace(' ', '-').replace(':', '-')
        else "t${System.currentTimeMillis()}"
    }

    private fun toggleLogging() {
        if (vm.logging) {
            logger.stop()
            streamer.stopRinex()
            vm.logging = false
            // Offer the just-recorded CSV + RINEX via the share sheet (cancelable).
            shareFiles(listOfNotNull(logger.currentFile, rinexFile), "Share log (CSV + RINEX)")
        } else {
            val stamp = fileStamp()
            logger.start(stamp)
            // RINEX OBS of the observations (written from obsd_t; independent of the connection).
            rinexFile = File(getExternalFilesDir(null), "rinex/rover_$stamp.obs")
            streamer.startRinex(rinexFile!!)
            vm.logPath = logger.currentFile?.absolutePath
            vm.logging = true
        }
    }

    /** Offer files via the Android share sheet (skips missing/empty; cancelable by the user). */
    private fun shareFiles(files: List<File>, title: String) {
        val uris = ArrayList<Uri>()
        for (f in files) {
            if (f.exists() && f.length() > 0) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
            }
        }
        if (uris.isEmpty()) return
        // ClipData carries the same URIs so the read-permission grant also reaches the share-sheet
        // preview (otherwise the chooser can't read file metadata).
        val clip = ClipData.newUri(contentResolver, "shared", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun toggleConnection() {
        if (vm.connected) {
            streamer.uplink.disconnect()
            solDownlink.disconnect()
            if (commsSaving) {
                streamer.stopRtcmSave()
                solDownlink.stopSave()
                commsSaving = false
                // Offer the recorded RTCM + .pos via the share sheet (cancelable).
                shareFiles(listOfNotNull(rtcmFile, posFile), "Share comms (RTCM + pos)")
            }
        } else {
            val host = vm.host.trim()
            // Save the sent RTCM3 / received .pos only if the "Save RTCM/Pos" toggle is on.
            commsSaving = vm.saveComms
            if (commsSaving) {
                val stamp = fileStamp()
                rtcmFile = File(getExternalFilesDir(null), "rtcm/rover_$stamp.rtcm3")
                posFile = File(getExternalFilesDir(null), "pos/rover_$stamp.pos")
                streamer.startRtcmSave(rtcmFile!!)
                solDownlink.startSave(posFile!!)
            }
            vm.rtcmPort.toIntOrNull()?.let { streamer.uplink.connect(host, it) }
            vm.solPort.toIntOrNull()?.let { solDownlink.connect(host, it) }
        }
    }
}

// Display order with short labels; the first element is the key into SatSummary.perConstellation.
private val CONSTELLATION_LABELS = listOf(
    "GPS" to "GPS",
    "GLONASS" to "GLO",
    "Galileo" to "GAL",
    "BeiDou" to "BDS",
    "QZSS" to "QZS",
)

@Composable
private fun StreamerScreen(
    vm: StreamerViewModel,
    onToggleLog: () -> Unit,
    onConnectToggle: () -> Unit,
    onResetTrack: () -> Unit,
) {
    val s = vm.summary
    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp)) {
        Text("RTCM Streamer", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))

        if (!vm.permissionGranted) {
            Text("Fine location permission required", color = MaterialTheme.colorScheme.error)
        }

        // GNSS monitor: GNSS time (updates at 1 Hz) + satellite counts, two lines.
        Text("GPST ${s.gpst}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sats:${s.total}  " +
                CONSTELLATION_LABELS.joinToString(" ") { (key, label) -> "$label:${s.perConstellation[key] ?: 0}" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(8.dp))
        Row {
            CompactField("Host", vm.host, { vm.host = it }, !vm.connected, false, Modifier.width(150.dp))
            Spacer(Modifier.width(6.dp))
            CompactField("RTCM", vm.rtcmPort, { vm.rtcmPort = it }, !vm.connected, true, Modifier.width(90.dp))
            Spacer(Modifier.width(6.dp))
            CompactField("Sol", vm.solPort, { vm.solPort = it }, !vm.connected, true, Modifier.width(90.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = onConnectToggle) {
                Text(if (vm.connected) "Disconnect" else "Connect")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onToggleLog) {
                Text(if (vm.logging) "Stop log" else "Start log")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onResetTrack) { Text("Clear") }
        }
        // When checked, a Connect session saves the sent RTCM3 + received .pos and offers them on
        // Disconnect. Read at Connect time, so it's disabled while connected.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.saveComms, onCheckedChange = { vm.saveComms = it }, enabled = !vm.connected)
            Text("Save RTCM/Pos on Connect", style = MaterialTheme.typography.bodySmall)
        }
        Text("${vm.connStatus} | sent ${vm.epochsSent} | ${vm.solStatus}",
            style = MaterialTheme.typography.bodySmall)

        val sol = vm.latest
        if (sol != null) {
            Text(
                buildAnnotatedString {
                    append("Solution status=")
                    withStyle(SpanStyle(color = qColor(sol.q))) { append(qLabel(sol.q)) }
                    append("   Sats=${sol.ns}")
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "LLH = ${"%.7f".format(sol.lat)}, ${"%.7f".format(sol.lon)}, ${"%.2f".format(sol.height)} m",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        PlotView(
            track = vm.track,
            pxPerMeter = vm.pxPerMeter,
            onZoom = { factor -> vm.setScale(vm.pxPerMeter * factor) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private fun qLabel(q: Int): String = when (q) {
    1 -> "FIX"; 2 -> "FLOAT"; 3 -> "SBAS"; 4 -> "DGPS"; 5 -> "SINGLE"; 6 -> "PPP"; else -> "—"
}

/** Compact text field (~2/3 the height of OutlinedTextField): a small caption + bordered input. */
@Composable
private fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    numeric: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
