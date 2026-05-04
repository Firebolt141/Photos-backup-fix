package com.firebolt141.photosync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.firebolt141.photosync.ui.HomeScreen
import com.firebolt141.photosync.ui.MainViewModel
import com.firebolt141.photosync.ui.QueueScreen
import com.firebolt141.photosync.ui.theme.PhotoSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSyncTheme {
                val nav   = rememberNavController()
                val vm: MainViewModel = viewModel()
                val state = vm.state.collectAsStateWithLifecycle().value

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            state               = state,
                            onScan              = vm::startScan,
                            onCopy              = vm::startCopy,
                            onDriveSelected     = vm::onDriveSelected,
                            onForgetDrive       = vm::forgetDrive,
                            onViewQueue         = { nav.navigate("queue") },
                            onDateRangeSelected = vm::setDateRange,
                            onClearDateRange    = vm::clearDateRange,
                            onRetryFailed       = vm::retryFailed,
                        )
                    }
                    composable("queue") {
                        QueueScreen(
                            items         = state.queue,
                            onBack        = { nav.popBackStack() },
                            onClearCopied = vm::clearCopied,
                        )
                    }
                }
            }
        }
    }
}
