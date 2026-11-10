package com.digma.watchapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.digma.watchapp.service.WatchSyncService
import com.digma.watchapp.ui.dialogs.UpdateDialog
import com.digma.watchapp.ui.screens.DashboardScreen
import com.digma.watchapp.ui.screens.HistoryScreen
import com.digma.watchapp.ui.screens.ScanScreen
import com.digma.watchapp.ui.screens.SettingsScreen
import com.digma.watchapp.ui.theme.DigmaWatchTheme
import com.digma.watchapp.ui.theme.PrimaryCyan
import com.digma.watchapp.ui.viewmodel.WatchViewModel
import com.digma.watchapp.update.UpdateState

enum class NavigationTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Главная", Icons.Default.Dashboard),
    SCAN("Поиск", Icons.Default.BluetoothSearching),
    HISTORY("История", Icons.Default.History),
    SETTINGS("Настройки", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: WatchViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            WatchSyncService.start(this)
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Returned from unknown app sources settings
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        // Background check for updates on application launch
        viewModel.checkForUpdates(isManual = false)

        setContent {
            DigmaWatchTheme {
                val updateState by viewModel.updateState.collectAsState()

                MainScreen(viewModel = viewModel)

                UpdateDialog(
                    state = updateState,
                    currentVersion = viewModel.getCurrentVersionName(),
                    canInstallPackages = viewModel.canRequestPackageInstalls(),
                    onDownload = { releaseInfo -> viewModel.startDownload(releaseInfo) },
                    onCancelDownload = { viewModel.cancelDownload() },
                    onInstall = { apkFile ->
                        val result = viewModel.installUpdate(apkFile)
                        if (result is com.digma.watchapp.update.UpdateInstaller.InstallResult.PermissionRequired) {
                            requestInstallPermission()
                        }
                    },
                    onRequestInstallPermission = { requestInstallPermission() },
                    onDismiss = { ignore ->
                        val remoteVersion = (updateState as? UpdateState.Available)?.releaseInfo?.versionName
                        viewModel.dismissUpdateDialog(ignore = ignore, version = remoteVersion)
                    },
                    onRetry = { viewModel.checkForUpdates(isManual = true) }
                )
            }
        }
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            installPermissionLauncher.launch(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            WatchSyncService.start(this)
        }
    }
}

@Composable
fun MainScreen(viewModel: WatchViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = NavigationTab.values()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = androidx.compose.ui.graphics.Color.Black,
                            selectedTextColor = PrimaryCyan,
                            indicatorColor = PrimaryCyan
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = { selectedTab = 1 }
                )
                1 -> ScanScreen(viewModel = viewModel)
                2 -> HistoryScreen(viewModel = viewModel)
                3 -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
