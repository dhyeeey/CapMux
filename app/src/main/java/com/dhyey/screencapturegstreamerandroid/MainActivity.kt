package com.dhyey.screencapturegstreamerandroid

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freedesktop.gstreamer.GStreamer

class MainActivity : ComponentActivity() {

    private val vm: ScreenCaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GStreamer.init(this);
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

    // 1. Launcher for MediaProjection permission dialog
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            vm.onPermissionGranted(result.resultCode, result.data!!)
        }
    }

    // 2. Launcher for Notification permission (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Service will still try to start even if denied, but notification won't show
    }

    // Request notification permission on startup for Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .navigationBarsPadding(),
                verticalArrangement   = Arrangement.Center,
                horizontalAlignment   = Alignment.CenterHorizontally
            ) {

                Text(
                    "Screen Capture",
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Powered by GStreamer + NDK",
                    fontSize = 13.sp,
                    color    = Color.Gray
                )

                Spacer(Modifier.height(48.dp))

                StatusBadge(state)

                Spacer(Modifier.height(48.dp))

                Button(
                    onClick  = { permLauncher.launch(vm.projectionManager.createScreenCaptureIntent()) },
                    enabled  = state == ScreenCaptureViewModel.State.IDLE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)
                    )
                ) {
                    Text("▶  Start Capturing", fontSize = 16.sp)
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick  = { vm.stopCapture() },
                    enabled  = state == ScreenCaptureViewModel.State.CAPTURING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828)
                    )
                ) {
                    Text("⬛  Stop & Save", fontSize = 16.sp)
                }

                Spacer(Modifier.height(32.dp))

                if (state == ScreenCaptureViewModel.State.CAPTURING) {
                    Text(
                        "Recording in background…\nYou can exit the app safely.",
                        fontSize   = 14.sp,
                        color      = Color.Gray,
                        textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    Text(
                        "Saved files go to\nMovies/ScreenCap/ on your device.",
                        fontSize   = 14.sp,
                        color      = Color.Gray,
                        textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: ScreenCaptureViewModel.State) {
    val isCapturing = state == ScreenCaptureViewModel.State.CAPTURING

    Surface(
        color  = if (isCapturing) Color(0xFFFFEBEE) else Color(0xFFF5F5F5),
        shape  = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row (
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            val dotColor = if (isCapturing) Color(0xFFE53935) else Color(0xFF9E9E9E)
            Surface (
                color    = dotColor,
                shape    = MaterialTheme.shapes.small,
                modifier = Modifier.size(10.dp)
            ) {}

            Spacer(Modifier.width(10.dp))

            Text(
                if (isCapturing) "RECORDING" else "IDLE",
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (isCapturing) Color(0xFFE53935) else Color(0xFF757575)
            )
        }
    }
}
