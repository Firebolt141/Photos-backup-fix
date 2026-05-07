package com.firebolt141.ubertrag.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixExifScreen(
    onOpenDrawer: () -> Unit,
    vm: FixExifViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val sourcePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::onSourceSelected) }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::onOutputSelected) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fix Missing EXIF Dates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.filenameMode) "Copy & tag files using filename dates"
                            else "Write dates to drive files missing them",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
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

            // ── Mode toggle ──────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Filename Date Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Extract dates from filenames instead of folder structure",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked         = state.filenameMode,
                            onCheckedChange = vm::setFilenameMode,
                            enabled         = !state.running,
                        )
                    }

                    // Mode description
                    val (modeIcon, modeText) = if (state.filenameMode) {
                        Icons.Default.TextFields to
                        "Scans any folder, reads dates like IMG_20240315, copies files to output/2024/March/March 15/ and writes EXIF. Works on Screenshots, WhatsApp, or any flat folder."
                    } else {
                        Icons.Default.Storage to
                        "Reads your drive's year / month / day folder names and writes EXIF into JPEG, PNG, WebP files that are missing it. HEIC, RAW, and video are skipped."
                    }
                    Row(
                        verticalAlignment     = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(modeIcon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(modeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Drive mode: drive status ─────────────────────────────────
            if (!state.filenameMode) {
                DriveStatusCard(
                    state = UiState(driveUri = state.driveUri, driveConnected = state.driveConnected)
                )
            }

            // ── Filename mode: source + output pickers ───────────────────
            if (state.filenameMode) {
                FolderPickerCard(
                    title    = "Source Folder",
                    subtitle = "Any folder with files to process (Screenshots, WhatsApp, etc.)",
                    icon     = Icons.Default.FolderOpen,
                    name     = state.sourceName,
                    enabled  = !state.running,
                    onPick   = { sourcePicker.launch(null) },
                )
                FolderPickerCard(
                    title    = "Output Folder",
                    subtitle = "Where organized files go — will be structured as year/month/day",
                    icon     = Icons.Default.DriveFileMove,
                    name     = state.outputName,
                    enabled  = !state.running,
                    onPick   = { outputPicker.launch(null) },
                )
            }

            // ── Start button ─────────────────────────────────────────────
            val canStart = !state.running && if (state.filenameMode)
                state.sourceUri.isNotBlank() && state.outputUri.isNotBlank()
            else
                state.driveConnected

            Button(
                onClick  = vm::startFix,
                enabled  = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (state.running) "Processing…" else "Start EXIF Fix")
            }

            // ── Progress bar ─────────────────────────────────────────────
            if (state.running && state.total > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${state.done} / ${state.total}", style = MaterialTheme.typography.labelSmall)
                        if (state.currentFile.isNotBlank()) {
                            Text(
                                state.currentFile,
                                style    = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Live log panel ───────────────────────────────────────────
            if (state.logLines.isNotEmpty()) {
                LogPanel(lines = state.logLines, running = state.running)
            }

            // ── Result summary ───────────────────────────────────────────
            state.result?.let { ResultSummaryCard(it) }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LogPanel(lines: List<String>, running: Boolean) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(
                    if (running) "Processing…" else "Log",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text("${lines.size} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.small,
                    )
                    .padding(8.dp),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            line,
                            style      = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 11.sp,
                            ),
                            color      = when {
                                line.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                line.startsWith("✗") -> MaterialTheme.colorScheme.error
                                line.startsWith("⚠") -> MaterialTheme.colorScheme.tertiary
                                line.startsWith("─") -> MaterialTheme.colorScheme.onSurfaceVariant
                                else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(result: FixExifResult) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Done", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            when (result) {
                is FixExifResult.DriveMode -> {
                    val r = result.r
                    ResultRow("EXIF written",              r.fixed,          Icons.Default.AutoFixHigh)
                    if (r.alreadyHasDate > 0) ResultRow("Already had date", r.alreadyHasDate, Icons.Default.SkipNext)
                    if (r.skipped > 0) ResultRow("Skipped (HEIC/video)",   r.skipped,        Icons.Default.Warning)
                    if (r.failed  > 0) ResultRow("Errors",                  r.failed,         Icons.Default.ErrorOutline)
                }
                is FixExifResult.FilenameMode -> {
                    val r = result.r
                    ResultRow("Copied to output",          r.copied,         Icons.Default.ContentCopy)
                    ResultRow("EXIF written",               r.exifWritten,    Icons.Default.AutoFixHigh)
                    if (r.noDate        > 0) ResultRow("No date in filename",   r.noDate,       Icons.Default.HelpOutline)
                    if (r.alreadyExists > 0) ResultRow("Already at destination", r.alreadyExists, Icons.Default.SkipNext)
                    if (r.unsupported   > 0) ResultRow("Copied (HEIC/video — no EXIF)", r.unsupported, Icons.Default.Warning)
                    if (r.failed        > 0) ResultRow("Errors",                r.failed,       Icons.Default.ErrorOutline)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$label: $count", style = MaterialTheme.typography.bodySmall)
    }
}
