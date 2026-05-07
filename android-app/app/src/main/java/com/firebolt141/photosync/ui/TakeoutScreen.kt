package com.firebolt141.ubertrag.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firebolt141.ubertrag.util.TakeoutResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeoutScreen(
    onBack: () -> Unit,
    vm: TakeoutViewModel = viewModel(),
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
                title = { Text("Process Google Takeout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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

            // ── Info banner ──────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        Modifier.size(20.dp).padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Reads JSON sidecars from your Takeout export and writes " +
                        "correct dates, GPS, and descriptions into JPEG / PNG / WebP files. " +
                        "HEIC, RAW, and video files are copied to the right date folder " +
                        "but cannot have EXIF written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Source folder ────────────────────────────────────────────
            FolderPickerCard(
                title    = "Takeout Source Folder",
                subtitle = "The folder exported from Google Takeout",
                icon     = Icons.Default.FolderZip,
                name     = state.sourceName,
                enabled  = !state.running,
                onPick   = { sourcePicker.launch(null) },
            )

            // ── Output folder ────────────────────────────────────────────
            FolderPickerCard(
                title    = "Output Folder",
                subtitle = "Where to copy files (drive, internal storage, etc.)",
                icon     = Icons.Default.DriveFileMove,
                name     = state.outputName,
                enabled  = !state.running,
                onPick   = { outputPicker.launch(null) },
            )

            // ── Options ──────────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tune, null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Options",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Skip files that already have a date",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Only write EXIF to files where it is missing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked         = state.skipIfHasExif,
                            onCheckedChange = vm::setSkipIfHasExif,
                            enabled         = !state.running,
                        )
                    }
                }
            }

            // ── Process button ───────────────────────────────────────────
            Button(
                onClick  = vm::startProcessing,
                enabled  = state.sourceUri.isNotBlank() && state.outputUri.isNotBlank() && !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (state.running) "Processing…" else "Start Processing")
            }

            // ── Progress ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = state.running || (state.total > 0 && state.result == null),
                enter   = fadeIn(),
                exit    = fadeOut(),
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Processing files…",
                                style      = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${state.done} / ${state.total}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        LinearProgressIndicator(
                            progress = {
                                if (state.total > 0) state.done.toFloat() / state.total else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (state.currentFile.isNotBlank()) {
                            Text(
                                state.currentFile,
                                style    = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // ── Results ──────────────────────────────────────────────────
            state.result?.let { ResultCard(it, vm::clearResult) }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FolderPickerCard(
    title:   String,
    subtitle: String,
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    name:    String,
    enabled: Boolean,
    onPick:  () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (name.isNotBlank()) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        name,
                        style    = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FilledTonalButton(
                onClick  = onPick,
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (name.isBlank()) "Select Folder" else "Change Folder")
            }
        }
    }
}

@Composable
private fun ResultCard(r: TakeoutResult, onDismiss: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Error state
            if (r.errorMsg.isNotBlank()) {
                Row(
                    verticalAlignment     = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.ErrorOutline, null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Could not start processing",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            r.errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Dismiss")
                }
                return@Column
            }

            // Success state
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Done — ${r.total} file${if (r.total == 1) "" else "s"} processed",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ResultRow("EXIF written (JSON sidecar)",         r.fixed,           Icons.Default.AutoFixHigh)
                ResultRow("EXIF written (filename date)",        r.fromFilename,    Icons.Default.TextFields)
                if (r.skippedExisting > 0)
                    ResultRow("Already had date — skipped",      r.skippedExisting, Icons.Default.SkipNext)
                if (r.noDate > 0)
                    ResultRow("No date found — copied as-is",    r.noDate,          Icons.Default.HelpOutline)
                if (r.unsupported > 0)
                    ResultRow("Copied (HEIC/video — no EXIF)",   r.unsupported,     Icons.Default.Warning)
                if (r.errors > 0)
                    ResultRow("Errors",                          r.errors,          Icons.Default.ErrorOutline)
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$label: $count", style = MaterialTheme.typography.bodySmall)
    }
}
