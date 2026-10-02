package com.firebolt141.ubertrag.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Two tools sharing one screen (the route picks the mode):
 *  - "Sort a folder by date" (filename mode): copies any messy folder into
 *    output/Year/Month/Day using the date inside each file or in its name.
 *  - "Fix the backup drive" (drive mode): writes the folder's date into
 *    photos on the drive that have none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixExifScreen(
    onOpenDrawer: () -> Unit,
    filenameMode: Boolean = false,
    vm: FixExifViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Each route ("organize" / "fix-exif") has its own ViewModel, fixed to one mode.
    LaunchedEffect(filenameMode) {
        if (state.filenameMode != filenameMode) vm.setFilenameMode(filenameMode)
    }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onSourceSelected) }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onOutputSelected) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (filenameMode) "Sort a folder by date" else "Fix dates on the drive")
                        Text(
                            if (filenameMode) "Any folder → Year / Month / Day"
                            else "Give undated photos their folder's date",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
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
            if (filenameMode) {
                InfoCard(
                    "For a messy folder — camera dumps, WhatsApp, Screenshots, old phone backups, sub-folders and all. " +
                        "Each photo and video is copied to Output / Year / Month / Day using the date inside the file " +
                        "or in its name (IMG_20240315…). Files with no date go to no-date/ (keeping their sub-folders); " +
                        "anything unreadable goes to error/. Your originals are never changed."
                )
                StepLabel(1, "Folder to sort", done = state.sourceUri.isNotBlank())
                FolderPickerCard(
                    title    = "Source folder",
                    subtitle = "Sub-folders are included",
                    icon     = Icons.Default.FolderOpen,
                    name     = state.sourceName,
                    enabled  = !state.running,
                    onPick   = { sourcePicker.launch(null) },
                )
                StepLabel(2, "Where to put the sorted copies", done = state.outputUri.isNotBlank())
                FolderPickerCard(
                    title    = "Output folder",
                    subtitle = "e.g. your backup drive or a new empty folder",
                    icon     = Icons.AutoMirrored.Filled.DriveFileMove,
                    name     = state.outputName,
                    enabled  = !state.running,
                    onPick   = { outputPicker.launch(null) },
                )
                if (state.sourceUri.isNotBlank() && state.sourceUri == state.outputUri) {
                    Text("Source and output must be different folders.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                StepLabel(3, "Options")
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OptionSwitch(
                            "Trust dates already in photos",
                            "Recommended. Off = always use the date in the file name, when there is one.",
                            state.keepExistingDates, !state.running, vm::setKeepExistingDates,
                        )
                        OptionSwitch(
                            "Rename copies to their date",
                            "2024-03-15_14-30-22.jpg instead of the original name",
                            state.renameToDate, !state.running, vm::setRenameToDate,
                        )
                    }
                }
            } else {
                InfoCard(
                    "For a drive already sorted into Year / Month / Day folders. Photos with no date inside get " +
                        "the date of the folder they're in, so Google Photos and galleries show them on the right day. " +
                        "JPEG, PNG and WebP can be updated; HEIC, RAW and video can't store a date this way and are left as they are."
                )
                DriveStatusCard(driveUri = state.driveUri, driveConnected = state.driveConnected)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OptionSwitch(
                            "Also correct wrong dates",
                            "Photos whose date is more than a day away from their folder get the folder's day (time of day kept)",
                            state.fixMismatched, !state.running, vm::setFixMismatched,
                        )
                    }
                }
            }

            // ── Start / stop ─────────────────────────────────────────────
            val canStart = !state.running && if (filenameMode)
                state.sourceUri.isNotBlank() && state.outputUri.isNotBlank() && state.sourceUri != state.outputUri
            else state.driveConnected

            if (state.running) {
                JobProgressCard(
                    title    = if (filenameMode) "Sorting…" else "Checking dates…",
                    done     = state.done,
                    total    = state.total,
                    current  = state.currentFile,
                    stopping = state.stopping,
                    onStop   = vm::stop,
                )
            } else {
                Button(onClick = vm::startFix, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (filenameMode) Icons.Default.CalendarMonth else Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (filenameMode) "Start sorting" else "Fix dates")
                }
            }

            if (state.error.isNotBlank()) {
                ResultCard("Couldn't run", ok = false, onDismiss = vm::clearResult) {
                    Text(state.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            state.result?.let { if (!state.running) ResultSummaryCard(it, vm::clearResult) }

            if (state.logLines.isNotEmpty()) LogPanel(lines = state.logLines, running = state.running)

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun OptionSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ResultSummaryCard(result: FixExifResult, onDismiss: () -> Unit) {
    when (result) {
        is FixExifResult.DriveMode -> {
            val r = result.r
            if (r == null) {
                ResultCard("The drive isn't connected", ok = false, onDismiss = onDismiss) {}
                return
            }
            ResultCard(if (r.failed == 0) "Done" else "Done, with problems", ok = r.failed == 0, onDismiss = onDismiss) {
                ResultRow("Dates written", r.fixed, Icons.Default.AutoFixHigh)
                if (r.corrected > 0) ResultRow("Wrong dates corrected", r.corrected, Icons.Default.EditCalendar)
                ResultRow("Already had a date", r.alreadyHasDate, Icons.Default.CheckCircle)
                if (r.mismatched > r.corrected) ResultRow("Date doesn't match folder (unchanged)", r.mismatched - r.corrected, Icons.Default.Warning)
                if (r.skipped > 0) ResultRow("Can't store a date (HEIC/RAW/video)", r.skipped, Icons.Default.Block)
                if (r.failed > 0) ResultRow("Errors", r.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                r.notes.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (r.notes.size > 5) Text("… and ${r.notes.size - 5} more (see the log)", style = MaterialTheme.typography.bodySmall)
            }
        }
        is FixExifResult.FilenameMode -> {
            val r = result.r
            val title = when {
                r.stoppedEarly.isNotBlank() -> "Stopped — ${r.stoppedEarly}"
                r.copied + r.alreadyExists + r.failed == 0 -> "No photos or videos found"
                r.failed > 0 -> "Done, with problems"
                else -> "Done"
            }
            ResultCard(title, ok = r.failed == 0 && r.stoppedEarly.isBlank(), onDismiss = onDismiss) {
                ResultRow("Sorted into date folders", r.copied - r.noDate, Icons.Default.CalendarMonth)
                if (r.exifWritten > 0) ResultRow("Date written into the copy", r.exifWritten, Icons.Default.AutoFixHigh)
                if (r.keptExisting > 0) ResultRow("Kept the photo's own date", r.keptExisting, Icons.Default.CheckCircle)
                if (r.unsupported > 0) ResultRow("Sorted, date not written (HEIC/video)", r.unsupported, Icons.Default.Info)
                if (r.noDate > 0) ResultRow("No date found (in no-date/)", r.noDate, Icons.AutoMirrored.Filled.HelpOutline)
                if (r.alreadyExists > 0) ResultRow("Already in the output", r.alreadyExists, Icons.Default.RemoveCircleOutline)
                if (r.failed > 0) ResultRow("Errors (copied to error/)", r.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                if (r.ignored > 0) ResultRow("Other files left alone (not photos/videos)", r.ignored, Icons.Default.Description)
                if (r.archives > 0) {
                    Text(
                        "${plural(r.archives, "zip file")} found. Unzip them first (Files app → tap the zip → Extract), then run again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (r.stoppedEarly.isNotBlank()) {
                    Text("Everything done so far is safe. Run it again to continue — finished files are skipped.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
