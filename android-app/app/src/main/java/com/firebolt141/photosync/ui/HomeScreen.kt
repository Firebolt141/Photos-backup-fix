package com.firebolt141.ubertrag.ui

import java.text.DateFormat
import androidx.compose.ui.platform.LocalConfiguration
import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.firebolt141.ubertrag.repository.CopySummary
import java.util.*

/** Gallery permissions for this Android version (+ notifications so progress is visible). */
internal fun mediaPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state:              UiState,
    onScan:             () -> Unit,
    onCopy:             () -> Unit,
    onStopCopy:         () -> Unit,
    onDriveSelected:    (Uri) -> Unit,
    onForgetDrive:      () -> Unit,
    onViewQueue:        () -> Unit,
    onDateRangeSelected: (fromMs: Long, toMs: Long) -> Unit,
    onClearDateRange:   () -> Unit,
    onRetryFailed:      () -> Unit,
    onDismissSummary:   () -> Unit,
    onDismissMessage:   () -> Unit,
    onPermissionsChanged: () -> Unit,
    onOpenDrawer:       () -> Unit,
) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionsChanged() }

    // Ask once on first open; afterwards the permission card offers it again.
    LaunchedEffect(state.mediaAccess) {
        if (!askedOnce && state.mediaAccess == MediaAccess.NONE) {
            askedOnce = true
            permLauncher.launch(mediaPermissions())
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onPermissionsChanged() }

    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message.text()
    LaunchedEffect(state.message) {
        if (!state.message.isEmpty) {
            snackbar.showSnackbar(messageText, withDismissAction = true)
            onDismissMessage()
        }
    }

    val hasDateFilter = state.fromDateMs > 0 || state.toDateMs > 0
    val copying       = state.copyProgress != null
    val isBusy        = state.scanning || copying

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title))
                        Text(
                            stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu)) }
                },
                actions = {
                    IconButton(onClick = onViewQueue) {
                        BadgedBox(badge = {
                            if (state.pendingCount > 0) Badge { Text("${state.pendingCount}") }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.queue))
                        }
                    }
                },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Permission ───────────────────────────────────────────────
            if (state.mediaAccess != MediaAccess.FULL) {
                PermissionCard(
                    partial = state.mediaAccess == MediaAccess.PARTIAL,
                    onAllow = { permLauncher.launch(mediaPermissions()) },
                    onSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }

            // ── Step 1: drive ────────────────────────────────────────────
            StepLabel(1, stringResource(R.string.step_choose_drive), done = state.driveConnected)
            DriveStatusCard(
                driveUri        = state.driveUri,
                driveConnected  = state.driveConnected,
                onDriveSelected = onDriveSelected,
                onForget        = onForgetDrive,
                enabled         = !copying,
            )

            // ── Step 2: scan ─────────────────────────────────────────────
            StepLabel(2, stringResource(R.string.step_find_photos), done = state.queue.isNotEmpty() && !state.scanning)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.scan_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick  = onScan,
                        enabled  = !isBusy && state.mediaAccess != MediaAccess.NONE,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.scanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.scanning))
                        } else {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.scan_phone))
                        }
                    }
                    if (!state.scanMessage.isEmpty && !state.scanning) {
                        Text(state.scanMessage.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(stringResource(R.string.stat_to_copy), state.pendingCount, Icons.Default.Schedule, Modifier.weight(1f))
                        StatTile(stringResource(R.string.stat_copied), state.copiedCount, Icons.Default.CheckCircle, Modifier.weight(1f))
                        StatTile(stringResource(R.string.stat_already_there), state.skippedCount, Icons.Default.RemoveCircleOutline, Modifier.weight(1f))
                    }
                    if (state.queue.isNotEmpty()) {
                        TextButton(onClick = onViewQueue, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.see_the_list, state.queue.size))
                        }
                    }
                }
            }

            // ── Step 3: copy ─────────────────────────────────────────────
            StepLabel(3, stringResource(R.string.step_copy))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.copy_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Optional date range
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DateRange, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        val locale = LocalConfiguration.current.locales[0]
                        val dateFmt = remember(locale) {
                            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        }
                        val fromLabel = if (state.fromDateMs > 0) dateFmt.format(Date(state.fromDateMs)) else stringResource(R.string.range_beginning)
                        val toLabel   = if (state.toDateMs > 0) dateFmt.format(Date(state.toDateMs)) else stringResource(R.string.range_today)
                        Text(
                            if (hasDateFilter) stringResource(R.string.range_only, fromLabel, toLabel)
                            else stringResource(R.string.range_all),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasDateFilter) TextButton(onClick = onClearDateRange, enabled = !copying) { Text(stringResource(R.string.clear)) }
                        TextButton(onClick = { showDatePicker = true }, enabled = !copying) {
                            Text(stringResource(if (hasDateFilter) R.string.change else R.string.limit_dates))
                        }
                    }

                    if (copying) {
                        OutlinedButton(onClick = onStopCopy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.stop_copying))
                        }
                    } else {
                        Button(
                            onClick  = onCopy,
                            enabled  = state.driveConnected && state.pendingCount > 0 && !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.pendingCount > 0) pluralStringResource(R.plurals.copy_n_files, state.pendingCount, state.pendingCount) else stringResource(R.string.copy))
                        }
                        val hint = when {
                            state.driveUri == null -> stringResource(R.string.hint_choose_drive_first)
                            !state.driveConnected -> stringResource(R.string.hint_connect_drive)
                            state.pendingCount == 0 && state.queue.isEmpty() -> stringResource(R.string.hint_scan_first)
                            state.pendingCount == 0 -> stringResource(R.string.hint_all_backed_up)
                            else -> ""
                        }
                        if (hint.isNotBlank()) {
                            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (state.failedCount > 0 && !isBusy) {
                        OutlinedButton(onClick = onRetryFailed, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(pluralStringResource(R.plurals.retry_n_failed, state.failedCount, state.failedCount))
                        }
                    }
                }
            }

            // ── Progress / result ────────────────────────────────────────
            AnimatedVisibility(visible = state.copyProgress != null, enter = fadeIn(), exit = fadeOut()) {
                state.copyProgress?.let { p ->
                    JobProgressCard(
                        title   = stringResource(R.string.copying_to_drive),
                        done    = p.done,
                        total   = p.total,
                        current = p.currentName,
                        extra   = if (p.speedMBps > 0.01) stringResource(R.string.speed_mbps, "%.1f".format(p.speedMBps)) else "",
                        onStop  = onStopCopy,
                    )
                }
            }
            if (state.copyProgress == null) state.lastSummary?.let { CopySummaryCard(it, onDismissSummary, onViewQueue) }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ── Date range picker dialog ──────────────────────────────────────────────
    // State is created inside the if-block so it always initialises from the
    // current ViewModel values when the dialog opens.
    if (showDatePicker) {
        val dateRangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = state.fromDateMs.takeIf { it > 0 },
            initialSelectedEndDateMillis   = state.toDateMs.takeIf   { it > 0 },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onDateRangeSelected(
                        dateRangeState.selectedStartDateMillis ?: 0L,
                        dateRangeState.selectedEndDateMillis ?: 0L,
                    )
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = dateRangeState, modifier = Modifier.height(500.dp))
        }
    }
}

@Composable
private fun PermissionCard(partial: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(Icons.Default.PhotoLibrary, stringResource(if (partial) R.string.perm_partial_title else R.string.perm_title))
            Text(
                stringResource(if (partial) R.string.perm_partial_body else R.string.perm_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.allow)) }
                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun CopySummaryCard(s: CopySummary, onDismiss: () -> Unit, onViewQueue: () -> Unit) {
    val ok = s.problem.isBlank() && s.failed == 0 && s.stoppedEarly.isBlank()
    val title = when {
        s.problem.isNotBlank() -> stringResource(R.string.sum_couldnt_start)
        s.stoppedEarly.isNotBlank() -> stringResource(R.string.sum_stopped, s.stoppedEarly)
        s.total == 0 -> stringResource(R.string.sum_nothing)
        s.failed > 0 -> stringResource(R.string.sum_problems)
        else -> stringResource(R.string.sum_complete)
    }
    ResultCard(title, ok, onDismiss) {
        if (s.problem.isNotBlank()) Text(s.problem, style = MaterialTheme.typography.bodySmall)
        if (s.copied > 0) ResultRow(stringResource(R.string.row_copied), s.copied, Icons.Default.CheckCircle)
        if (s.noDate > 0) ResultRow(stringResource(R.string.row_no_date), s.noDate, Icons.AutoMirrored.Filled.HelpOutline)
        if (s.skipped > 0) ResultRow(stringResource(R.string.row_on_drive), s.skipped, Icons.Default.RemoveCircleOutline)
        if (s.gone > 0) ResultRow(stringResource(R.string.row_gone), s.gone, Icons.Default.DeleteOutline)
        if (s.failed > 0) ResultRow(stringResource(R.string.row_failed), s.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
        if (s.bytes > 0) Text(stringResource(R.string.mb_written, "%.1f".format(s.bytes / 1_048_576.0)), style = MaterialTheme.typography.labelSmall)
        if (s.stoppedEarly.isNotBlank() && s.problem.isBlank()) {
            Text(stringResource(R.string.copy_continue_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (s.failed > 0) TextButton(onClick = onViewQueue, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.see_failed)) }
    }
}
