package com.mekromn.continuitybrain.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekromn.continuitybrain.ui.theme.BrainBlack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class BrainScreen(val label: String, val glyph: String) {
    Home("Brain", "◈"),
    Search("Search", "⌕"),
    Projects("Projects", "◇"),
    Timeline("Timeline", "↟"),
    Vault("Vault", "▣"),
}

@Composable
fun BrainApp(viewModel: BrainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(BrainScreen.Home) }
    val snackbarHost = remember { SnackbarHostState() }

    var pendingBackupPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupPassphrase by remember { mutableStateOf(false) }
    var showRestorePassphrase by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importExport) }

    val modelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::installEmbeddingModel) }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && passphrase != null) viewModel.createPortableBackup(uri, passphrase)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestorePassphrase = true
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Android permits the foreground service even when notification display
        // permission is denied; the system still surfaces it in active apps.
        viewModel.setBridgeEnabled(true)
    }

    if (showBackupPassphrase) {
        PassphraseDialog(
            title = "Encrypt portable backup",
            description = "Choose a passphrase you can remember on another device. It is never stored by Continuity Brain.",
            requireConfirmation = true,
            minimumLength = 12,
            confirmLabel = "Choose location",
            onDismiss = { showBackupPassphrase = false },
            onConfirm = { passphrase ->
                showBackupPassphrase = false
                pendingBackupPassphrase = passphrase
                backupCreateLauncher.launch(defaultBackupName())
            },
        )
    }

    if (showRestorePassphrase) {
        PassphraseDialog(
            title = "Unlock portable backup",
            description = "Enter the passphrase used when this .cbbrain backup was created.",
            requireConfirmation = false,
            minimumLength = 1,
            confirmLabel = "Restore",
            onDismiss = {
                showRestorePassphrase = false
                pendingRestoreUri = null
            },
            onConfirm = { passphrase ->
                val uri = pendingRestoreUri
                showRestorePassphrase = false
                pendingRestoreUri = null
                if (uri != null) viewModel.restorePortableBackup(uri, passphrase)
            },
        )
    }

    Scaffold(
        containerColor = BrainBlack,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF08080C),
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                BrainScreen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = {
                            Text(
                                text = item.glyph,
                                fontSize = 20.sp,
                                fontWeight = if (screen == item) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        label = { Text(item.label, fontSize = 10.sp) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            label = "brain-screen",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { target ->
            when (target) {
                BrainScreen.Home -> HomeScreen(
                    state = state,
                    onImport = {
                        importLauncher.launch(arrayOf("application/zip", "application/json", "text/json", "*/*"))
                    },
                    onOpenSearch = { query ->
                        viewModel.setQuery(query)
                        viewModel.search(query)
                        screen = BrainScreen.Search
                    },
                    onOpenVault = { screen = BrainScreen.Vault },
                )
                BrainScreen.Search -> SearchScreen(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onSearch = viewModel::search,
                )
                BrainScreen.Projects -> ProjectsScreen(
                    projects = state.projects,
                    onProject = { project ->
                        viewModel.setQuery(project.name)
                        viewModel.search(project.name)
                        screen = BrainScreen.Search
                    },
                )
                BrainScreen.Timeline -> TimelineScreen(state.timeline)
                BrainScreen.Vault -> VaultScreen(
                    state = state,
                    onImport = {
                        importLauncher.launch(arrayOf("application/zip", "application/json", "text/json", "*/*"))
                    },
                    onBridge = { enabled ->
                        if (!enabled) {
                            viewModel.setBridgeEnabled(false)
                        } else if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setBridgeEnabled(true)
                        }
                    },
                    onRotateToken = viewModel::rotateBridgeToken,
                    onCreateBackup = { showBackupPassphrase = true },
                    onRestoreBackup = {
                        restoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onInstallEmbeddingModel = {
                        modelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onBuildSemanticIndex = viewModel::buildSemanticIndex,
                )
            }
        }
    }
}

private fun defaultBackupName(): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        .format(Instant.now().atZone(ZoneId.systemDefault()))
    return "Continuity-Brain-$stamp.cbbrain"
}
