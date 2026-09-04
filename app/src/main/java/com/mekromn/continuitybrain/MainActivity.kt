package com.mekromn.continuitybrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.continuitybrain.ui.BrainApp
import com.mekromn.continuitybrain.ui.BrainViewModel
import com.mekromn.continuitybrain.ui.theme.ContinuityBrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContinuityBrainTheme {
                val brainViewModel: BrainViewModel = viewModel()
                BrainApp(brainViewModel)
            }
        }
    }
}
