package com.mekromn.continuitybrain

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.continuitybrain.ui.BrainApp
import com.mekromn.continuitybrain.ui.BrainViewModel
import com.mekromn.continuitybrain.ui.theme.ContinuityBrainTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The bridge remains usable if notification permission is declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ContinuityBrainTheme {
                val brainViewModel: BrainViewModel = viewModel()
                BrainApp(brainViewModel)
            }
        }
    }
}
