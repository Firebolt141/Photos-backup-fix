package com.firebolt141.photosync.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt141.photosync.service.CopyProgress
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onScan: () -> Unit,
    onCopy: () -> Unit,
    onDriveSelected: (Uri) -> Unit,
    onForgetDrive: () -> Unit,
    onViewQueue: () -> Unit,
    onDateRangeSelected: (fromMs: Long, toMs: Long) -> Unit,
    onClearDateRange: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val dateRangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = state.fromDateMs.takeIf { it > 0 },
        initialSelectedEndDateMillis   = state.toDateMs.takeIf   { it > 0 },
    )

    val drivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onDriveSelected) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permLauncher.launch(perms.toTypedArray())
    }

    val hasDateFilter = state.fromDateMs > 0 || state.toDateMs > 0
    val isBusy        = state.scanning || state.copyProgress != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Photo Sync",
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                state.driveConnected -> "Drive connected"
                                state.driveUri != null -> "Drive not connected"
                                else -> "No drive selected"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.driveConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onViewQueue) {
                        BadgedBox(badge = {
                            if (state.pendingCount > 0) Badge { Text("${state.pendingCount}") }
                        }) {
                            Icon(Icons.Default.List, contentDescription = "Queue")
                        }
                    }
                },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Stats row ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    label    = "Pending",
                    value    = state.pendingCount,
                    icon     = Icons.Default.Schedule,
                    color    = MaterialTheme.colorScheme.secondaryContainer,
                    onColor  = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label    = "Copied",
                    value    = state.copiedCount,
                    icon     = Icons.Default.CheckCircle,
                    color    = MaterialTheme.colorScheme.primaryContainer,
                    onColor  = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Drive card ───────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardTitle(icon = Icons.Default.Storage, title = "External Drive")

                    val (driveIcon, driveColor, driveLabel) = when {
                        state.driveConnected -> Triple(
                            Icons.Default.CheckCircle,
                            MaterialTheme.colorScheme.primary,
                            "Connected"
                        )
                        state.driveUri != null -> Triple(
                            Icons.Default.Warning,
                            MaterialTheme.colorScheme.error,
                            "Not connected — reconnect your drive"
                        )
                        else -> Triple(
                            Icons.Default.Info,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            "No drive selected"
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(driveIcon, null, Modifier.size(18.dp), tint = driveColor)
                        Text(driveLabel, style = MaterialTheme.typography.bodyMedium, color = driveColor)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { drivePicker.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.driveUri == null) "Select Drive" else "Change Drive")
                        }
                        if (state.driveUri != null) {
                            OutlinedButton(onClick = onForgetDrive) { Text("Forget") }
                        }
                    }
                }
            }

            // ── Date range card ──────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardTitle(icon = Icons.Default.DateRange, title = "Date Range Filter")
                        Spacer(Modifier.weight(1f))
                        if (hasDateFilter) {
                            TextButton(
                                onClick = onClearDateRange,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    Text(
                        text = if (hasDateFilter) {
                            val from = if (state.fromDateMs > 0)
                                dateFmt.format(Date(state.fromDateMs)) else "Beginning"
                            val to   = if (state.toDateMs > 0)
                                dateFmt.format(Date(state.toDateMs)) else "Today"
                            "$from  →  $to"
                        } else {
                            "All dates (no filter active)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasDateFilter) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FilledTonalButton(
                        onClick  = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.EditCalendar, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasDateFilter) "Change Range" else "Set Date Range")
                    }
                }
            }

            // ── Action buttons ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick  = onScan,
                    enabled  = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.scanning) {
                        CircularProgressIndicator(
                            modifier     = Modifier.size(18.dp),
                            strokeWidth  = 2.dp,
                            color        = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning…")
                    } else {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan")
                    }
                }
                Button(
                    onClick  = onCopy,
                    enabled  = state.driveConnected && state.pendingCount > 0 && !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            }

            // ── Copy progress card (animated in/out) ─────────────────────
            AnimatedVisibility(
                visible = state.copyProgress != null,
                enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit    = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                state.copyProgress?.let { CopyProgressCard(it) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ── Date range picker dialog ──────────────────────────────────────────────
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val fromMs = dateRangeState.selectedStartDateMillis ?: 0L
                    val toMs   = dateRangeState.selectedEndDateMillis   ?: 0L
                    onDateRangeSelected(fromMs, toMs)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(
                state    = dateRangeState,
                modifier = Modifier.height(500.dp),
            )
        }
    }
}

// ── Shared card components ────────────────────────────────────────────────────

@Composable
private fun CardTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatCard(
    label: String,
    value: Int,
    icon: ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = onColor)
            Text(
                text       = "$value",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = onColor,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = onColor)
        }
    }
}

@Composable
private fun CopyProgressCard(progress: CopyProgress) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Copying files…",
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (progress.currentName.isNotEmpty()) {
                Text(
                    progress.currentName,
                    style    = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color    = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val pct = if (progress.total > 0) progress.done * 100 / progress.total else 0
                Text("$pct%", style = MaterialTheme.typography.labelSmall)
                if (progress.speedMBps > 0.01) {
                    Text(
                        "${"%.1f".format(progress.speedMBps)} MB/s",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
