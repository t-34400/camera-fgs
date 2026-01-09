package com.t34400.camera_fgs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CameraFgsScreen()
        }
    }
}

@Composable
private fun CameraFgsScreen() {
    val context = LocalContext.current

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraService(context)
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    startCameraService(context)
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        ) {
            Text("Start FGS + Camera")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { stopCameraService(context) }
        ) {
            Text("Stop")
        }
    }
}

private fun startCameraService(context: android.content.Context) {
    val intent = Intent(context, CameraFgService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopCameraService(context: android.content.Context) {
    context.stopService(Intent(context, CameraFgService::class.java))
}
