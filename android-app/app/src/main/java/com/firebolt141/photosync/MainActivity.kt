package com.firebolt141.ubertrag

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.firebolt141.ubertrag.ui.AppDrawerContent
import com.firebolt141.ubertrag.ui.FixExifScreen
import com.firebolt141.ubertrag.ui.HomeScreen
import com.firebolt141.ubertrag.ui.MainViewModel
import com.firebolt141.ubertrag.ui.QueueScreen
import com.firebolt141.ubertrag.ui.RenameFoldersScreen
import com.firebolt141.ubertrag.ui.StartScreen
import com.firebolt141.ubertrag.ui.TakeoutScreen
import com.firebolt141.ubertrag.ui.theme.UbertragTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UbertragTheme {
                val nav         = rememberNavController()
                val vm: MainViewModel = viewModel()
                val state       = vm.state.collectAsStateWithLifecycle().value
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope       = rememberCoroutineScope()

                val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route

                fun openDrawer()  { scope.launch { drawerState.open()  } }
                fun closeDrawer() { scope.launch { drawerState.close() } }

                fun navigateTo(route: String) {
                    closeDrawer()
                    nav.navigate(route) {
                        popUpTo("start") { saveState = true }
                        launchSingleTop = true
                        restoreState    = true
                    }
                }

                // Back closes the drawer first
                BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

                ModalNavigationDrawer(
                    drawerState   = drawerState,
                    drawerContent = {
                        AppDrawerContent(currentRoute = currentRoute, onNavigate = ::navigateTo)
                    },
                ) {
                    NavHost(navController = nav, startDestination = "start") {
                        composable("start") {
                            StartScreen(state = state, onNavigate = ::navigateTo, onOpenDrawer = ::openDrawer)
                        }
                        composable("home") {
                            HomeScreen(
                                state               = state,
                                onScan              = vm::startScan,
                                onCopy              = vm::startCopy,
                                onStopCopy          = vm::stopCopy,
                                onDriveSelected     = vm::onDriveSelected,
                                onForgetDrive       = vm::forgetDrive,
                                onViewQueue         = { nav.navigate("queue") { launchSingleTop = true } },
                                onDateRangeSelected = vm::setDateRange,
                                onClearDateRange    = vm::clearDateRange,
                                onRetryFailed       = vm::retryFailed,
                                onDismissSummary    = vm::dismissSummary,
                                onDismissMessage    = vm::dismissMessage,
                                onPermissionsChanged = vm::refreshAccess,
                                onOpenDrawer        = ::openDrawer,
                            )
                        }
                        composable("queue") {
                            QueueScreen(
                                items         = state.queue,
                                onBack        = { if (!nav.popBackStack()) navigateTo("home") },
                                onClearCopied = vm::clearCopied,
                                onRequeueAll  = vm::requeueAll,
                            )
                        }
                        composable("organize") {
                            FixExifScreen(onOpenDrawer = ::openDrawer, filenameMode = true)
                        }
                        composable("fix-exif") {
                            FixExifScreen(onOpenDrawer = ::openDrawer, filenameMode = false)
                        }
                        composable("rename-folders") {
                            RenameFoldersScreen(
                                state        = state,
                                onCheck      = { vm.renameOldFolders(dryRun = true) },
                                onRename     = { vm.renameOldFolders(dryRun = false) },
                                onOpenDrawer = ::openDrawer,
                            )
                        }
                        composable("takeout") {
                            TakeoutScreen(onBack = { nav.popBackStack() }, onOpenDrawer = ::openDrawer)
                        }
                    }
                }
            }
        }
    }
}
