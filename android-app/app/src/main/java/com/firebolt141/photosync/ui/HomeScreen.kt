package com.firebolt141.ubertrag.ui

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
import java.text.SimpleDateFormat
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
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            snackbar.showSnackbar(state.message, withDismissAction = true)
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
                        Text("Back up phone")
                        Text(
                            "Copy photos & videos to a USB drive or SD card",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
                },
                actions = {
                    IconButton(onClick = onViewQueue) {
                        BadgedBox(badge = {
                            if (state.pendingCount > 0) Badge { Text("${state.pendingCount}") }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Queue")
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
            StepLabel(1, "Choose your backup drive", done = state.driveConnected)
            DriveStatusCard(
                driveUri        = state.driveUri,
                driveConnected  = state.driveConnected,
                onDriveSelected = onDriveSelected,
                onForget        = onForgetDrive,
                enabled         = !copying,
            )

            // ── Step 2: scan ─────────────────────────────────────────────
            StepLabel(2, "Find photos to back up", done = state.queue.isNotEmpty() && !state.scanning)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Looks through your gallery and adds anything new to the list. Already-copied items are remembered.",
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
                            Text("Scanning…")
                        } else {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan phone")
                        }
                    }
                    if (state.scanMessage.isNotBlank() && !state.scanning) {
                        Text(state.scanMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("To copy", state.pendingCount, Icons.Default.Schedule, Modifier.weight(1f))
                        StatTile("Copied", state.copiedCount, Icons.Default.CheckCircle, Modifier.weight(1f))
                        StatTile("Already there", state.skippedCount, Icons.Default.RemoveCircleOutline, Modifier.weight(1f))
                    }
                    if (state.queue.isNotEmpty()) {
                        TextButton(onClick = onViewQueue, contentPadding = PaddingValues(0.dp)) {
                            Text("See the list (${state.queue.size})")
                        }
                    }
                }
            }

            // ── Step 3: copy ─────────────────────────────────────────────
            StepLabel(3, "Copy to the drive")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Files go into Year / Month / Day folders, e.g. 2024 / March / March_15. " +
                            "Nothing is deleted from the phone. Files already on the drive are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Optional date range
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DateRange, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        val dateFmt = remember {
                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        }
                        Text(
                            if (hasDateFilter) {
                                val from = if (state.fromDateMs > 0) dateFmt.format(Date(state.fromDateMs)) else "the beginning"
                                val to   = if (state.toDateMs > 0) dateFmt.format(Date(state.toDateMs)) else "today"
                                "Only $from → $to"
                            } else "All dates",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasDateFilter) TextButton(onClick = onClearDateRange, enabled = !copying) { Text("Clear") }
                        TextButton(onClick = { showDatePicker = true }, enabled = !copying) {
                            Text(if (hasDateFilter) "Change" else "Limit dates")
                        }
                    }

                    if (copying) {
                        OutlinedButton(onClick = onStopCopy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop copying")
                        }
                    } else {
                        Button(
                            onClick  = onCopy,
                            enabled  = state.driveConnected && state.pendingCount > 0 && !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.pendingCount > 0) "Copy ${plural(state.pendingCount, "file")}" else "Copy")
                        }
                        val hint = when {
                            state.driveUri == null -> "Choose a drive first (step 1)."
                            !state.driveConnected -> "Connect the drive to copy."
                            state.pendingCount == 0 && state.queue.isEmpty() -> "Scan first (step 2)."
                            state.pendingCount == 0 -> "Everything in the list is already backed up."
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
                            Text("Retry ${plural(state.failedCount, "failed file")}")
                        }
                    }
                }
            }

            // ── Progress / result ────────────────────────────────────────
            AnimatedVisibility(visible = state.copyProgress != null, enter = fadeIn(), exit = fadeOut()) {
                state.copyProgress?.let { p ->
                    JobProgressCard(
                        title   = "Copying to the drive…",
                        done    = p.done,
                        total   = p.total,
                        current = p.currentName,
                        extra   = if (p.speedMBps > 0.01) "%.1f MB/s".format(p.speedMBps) else "",
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
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
            CardTitle(Icons.Default.PhotoLibrary, if (partial) "Only some photos are shared" else "Allow access to your photos")
            Text(
                if (partial) "You chose to share only selected photos, so only those can be backed up. Allow all photos and videos to back up everything."
                else "Übertrag needs to read your photos and videos to copy them. Nothing leaves your phone except to the drive you choose.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text("Allow") }
                OutlinedButton(onClick = onSettings) { Text("Open settings") }
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
        s.problem.isNotBlank() -> "Couldn't start"
        s.stoppedEarly.isNotBlank() -> "Stopped — ${s.stoppedEarly}"
        s.total == 0 -> "Nothing to copy"
        s.failed > 0 -> "Finished with problems"
        else -> "Backup complete"
    }
    ResultCard(title, ok, onDismiss) {
        if (s.problem.isNotBlank()) Text(s.problem, style = MaterialTheme.typography.bodySmall)
        if (s.copied > 0) ResultRow("Copied", s.copied, Icons.Default.CheckCircle)
        if (s.noDate > 0) ResultRow("Without a date (in no-date/)", s.noDate, Icons.AutoMirrored.Filled.HelpOutline)
        if (s.skipped > 0) ResultRow("Already on the drive", s.skipped, Icons.Default.RemoveCircleOutline)
        if (s.gone > 0) ResultRow("No longer on the phone", s.gone, Icons.Default.DeleteOutline)
        if (s.failed > 0) ResultRow("Failed", s.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
        if (s.bytes > 0) Text("%.1f MB written".format(s.bytes / 1_048_576.0), style = MaterialTheme.typography.labelSmall)
        if (s.stoppedEarly.isNotBlank() && s.problem.isBlank()) {
            Text("Everything copied so far is safe. Tap Copy again to continue.", style = MaterialTheme.typography.bodySmall)
        }
        if (s.failed > 0) TextButton(onClick = onViewQueue, contentPadding = PaddingValues(0.dp)) { Text("See which files failed") }
    }
}
