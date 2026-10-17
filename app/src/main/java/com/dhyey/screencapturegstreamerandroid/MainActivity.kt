package com.dhyey.screencapturegstreamerandroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dhyey.screencapturegstreamerandroid.overlay.ScreenVisualAids
import com.dhyey.screencapturegstreamerandroid.recording.BitrateTier
import com.dhyey.screencapturegstreamerandroid.recording.CaptureFps
import com.dhyey.screencapturegstreamerandroid.recording.CaptureResolution
import com.dhyey.screencapturegstreamerandroid.recording.DisplayCapabilities
import com.dhyey.screencapturegstreamerandroid.recording.QualityPreset
import com.dhyey.screencapturegstreamerandroid.recording.RecordingConfig
import com.dhyey.screencapturegstreamerandroid.recording.VideoCodecOption
import org.freedesktop.gstreamer.GStreamer

class MainActivity : ComponentActivity() {

    private val vm: ScreenCaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GStreamer.init(this)
        setContent { ScreenCapApp(vm) }
    }

    companion object {
        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("screencapturegstreamerandroid")
        }
    }
}

@Composable
fun ScreenCapApp(vm: ScreenCaptureViewModel) {
    val state by vm.state.collectAsState()
    val config by vm.config.collectAsState()
    val context = LocalContext.current
    val idle = state == ScreenCaptureViewModel.State.IDLE

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            vm.onPermissionGranted(result.resultCode, result.data!!)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        proceedAfterPermissions(vm, config, context, projectionLauncher::launch)
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        proceedAfterPermissions(vm, config, context, projectionLauncher::launch)
    }

    val writeSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        proceedAfterPermissions(vm, config, context, projectionLauncher::launch)
    }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        proceedAfterPermissions(vm, config, context, projectionLauncher::launch)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onStartClicked() {
        if (!config.isValid()) {
            Toast.makeText(context, "Select at least one source", Toast.LENGTH_SHORT).show()
            return
        }
        if (config.captureInternalAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "Media sound needs Android 10+", Toast.LENGTH_SHORT).show()
            return
        }

        if (config.effectiveShowTaps) {
            // Prefer Accessibility tap dots (reliable). System show_touches is often blocked.
            val a11yOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ScreenVisualAids.isTapServiceEnabled(context)
            val systemTapsOk = ScreenVisualAids.isShowTapsEnabled(context) ||
                ScreenVisualAids.canWriteSystemSettings(context)

            if (!a11yOk && !systemTapsOk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Enable Accessibility → CapMux – Show taps",
                        Toast.LENGTH_LONG,
                    ).show()
                    accessibilitySettingsLauncher.launch(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                } else {
                    Toast.makeText(
                        context,
                        "Allow modify system settings, or enable Developer options → Show taps",
                        Toast.LENGTH_LONG,
                    ).show()
                    writeSettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                }
                return
            }

            if (a11yOk && !Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "Allow display over other apps so taps appear in the video",
                    Toast.LENGTH_LONG,
                ).show()
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
                return
            }
        }

        if (config.needsMicPermission) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }

        beginCapture(vm, config, projectionLauncher::launch)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CapMux", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Record your screen, mic, and app sound",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )

                Spacer(modifier = Modifier.height(32.dp))
                StatusBadge(state)

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Sources",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                SourceToggle(
                    label = "Screen",
                    checked = config.captureScreen,
                    enabled = idle,
                    onCheckedChange = vm::setCaptureScreen,
                )
                SourceToggle(
                    label = "Microphone",
                    checked = config.captureMic,
                    enabled = idle,
                    onCheckedChange = vm::setCaptureMic,
                )
                SourceToggle(
                    label = "Media sound (internal)",
                    checked = config.captureInternalAudio,
                    enabled = idle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onCheckedChange = vm::setCaptureInternalAudio,
                )

                if (config.captureScreen) {
                    Spacer(modifier = Modifier.height(16.dp))
                    QualitySettingsSection(
                        config = config,
                        displayCaps = vm.displayCaps,
                        availableResolutions = vm.availableResolutions,
                        availableFps = vm.availableFpsOptions,
                        hevcAvailable = vm.hevcAvailable,
                        enabled = idle,
                        onPreset = vm::setQualityPreset,
                        onResolution = vm::setResolution,
                        onFps = vm::setFps,
                        onBitrate = vm::setBitrate,
                        onCodec = vm::setCodec,
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Screen extras",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SourceToggle(
                        label = "Show taps",
                        checked = config.showTaps,
                        enabled = idle,
                        onCheckedChange = vm::setShowTaps,
                    )
                    Text(
                        "Uses Accessibility (Android 13+) so taps appear in the video. " +
                            "Recording indicator is shown by Android in the status bar " +
                            "and is not written into the file.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Mode: ${config.summary()} → .${config.fileExtension}",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { onStartClicked() },
                    enabled = idle && config.isValid(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                ) {
                    Text("Start Recording", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { vm.stopCapture() },
                    enabled = state == ScreenCaptureViewModel.State.CAPTURING,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) {
                    Text("Stop & Save", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    if (state == ScreenCaptureViewModel.State.CAPTURING) {
                        "Recording in background…\nYou can leave the app."
                    } else {
                        "Video → Movies/ScreenCap/\nAudio-only → Music/ScreenCap/"
                    },
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun proceedAfterPermissions(
    vm: ScreenCaptureViewModel,
    config: RecordingConfig,
    context: android.content.Context,
    launchProjection: (Intent) -> Unit,
) {
    if (config.effectiveShowTaps) {
        val a11yOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ScreenVisualAids.isTapServiceEnabled(context)
        val systemTapsOk = ScreenVisualAids.isShowTapsEnabled(context) ||
            ScreenVisualAids.canWriteSystemSettings(context)
        if (!a11yOk && !systemTapsOk) {
            Toast.makeText(
                context,
                "Show taps needs Accessibility service or Developer options → Show taps",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (a11yOk && !Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Overlay permission needed so taps appear in the video",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
    }
    if (config.needsMicPermission) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            return
        }
    }
    beginCapture(vm, config, launchProjection)
}

private fun beginCapture(
    vm: ScreenCaptureViewModel,
    config: RecordingConfig,
    launchProjection: (Intent) -> Unit,
) {
    if (config.needsMediaProjection) {
        launchProjection(vm.projectionManager.createScreenCaptureIntent())
    } else {
        vm.startWithoutProjection()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualitySettingsSection(
    config: RecordingConfig,
    displayCaps: DisplayCapabilities,
    availableResolutions: List<CaptureResolution>,
    availableFps: List<CaptureFps>,
    hevcAvailable: Boolean,
    enabled: Boolean,
    onPreset: (QualityPreset) -> Unit,
    onResolution: (CaptureResolution) -> Unit,
    onFps: (CaptureFps) -> Unit,
    onBitrate: (BitrateTier) -> Unit,
    onCodec: (VideoCodecOption) -> Unit,
) {
    val quality = config.quality
    var showAdvanced by remember { mutableStateOf(false) }

    Text(
        "Quality",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Display ${displayCaps.sizeLabel} · ${displayCaps.refreshLabel}",
        fontSize = 12.sp,
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        quality.blurb(),
        fontSize = 12.sp,
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        QualityPreset.entries
            .filter { it != QualityPreset.CUSTOM }
            .forEach { preset ->
                FilterChip(
                    selected = quality.preset == preset,
                    onClick = { onPreset(preset) },
                    enabled = enabled,
                    label = { Text(preset.label) },
                )
            }
        if (quality.preset == QualityPreset.CUSTOM) {
            FilterChip(
                selected = true,
                onClick = {},
                enabled = enabled,
                label = { Text("Custom") },
            )
        }
    }

    TextButton(
        onClick = { showAdvanced = !showAdvanced },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (showAdvanced) "Hide advanced" else "Customize…")
    }

    if (showAdvanced) {
        OptionChipRow(
            title = "Resolution",
            enabled = enabled,
            options = availableResolutions.map { res ->
                val label = if (res == CaptureResolution.NATIVE) {
                    "Native (${displayCaps.sizeLabel})"
                } else {
                    res.label
                }
                res to label
            },
            selected = quality.resolution,
            onSelect = onResolution,
        )
        OptionChipRow(
            title = "Frame rate",
            enabled = enabled,
            options = availableFps.map { it to "${it.label} fps" },
            selected = quality.fps,
            onSelect = onFps,
        )
        OptionChipRow(
            title = "Bitrate",
            enabled = enabled,
            options = BitrateTier.entries.map { it to it.label },
            selected = quality.bitrate,
            onSelect = onBitrate,
        )
        OptionChipRow(
            title = "Codec",
            enabled = enabled,
            options = buildList {
                add(VideoCodecOption.H264 to VideoCodecOption.H264.label)
                if (hevcAvailable) {
                    add(VideoCodecOption.H265 to "${VideoCodecOption.H265.label} (HEVC)")
                }
            },
            selected = quality.codec,
            onSelect = onCodec,
        )
        if (!hevcAvailable) {
            Text(
                "H.265 hardware encoder not available on this device.",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChipRow(
    title: String,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SourceToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(label, fontSize = 15.sp)
    }
}

@Composable
fun StatusBadge(state: ScreenCaptureViewModel.State) {
    val isCapturing = state == ScreenCaptureViewModel.State.CAPTURING

    Surface(
        color = if (isCapturing) Color(0xFFFFEBEE) else Color(0xFFF5F5F5),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Surface(
                color = if (isCapturing) Color(0xFFE53935) else Color(0xFF9E9E9E),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(10.dp),
            ) {}
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                if (isCapturing) "RECORDING" else "IDLE",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCapturing) Color(0xFFE53935) else Color(0xFF757575),
            )
        }
    }
}
