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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firebolt141.ubertrag.util.TakeoutResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeoutScreen(
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    vm: TakeoutViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onSourceSelected) }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onOutputSelected) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Import Google Takeout")
                        Text(
                            "Restore dates, places and captions",
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
            InfoCard(
                "Google Takeout strips dates and places out of your photos and puts them in .json files next to them. " +
                    "This reads those files and copies every photo and video into Output / Year / Month / Day with the " +
                    "right date (and GPS and caption for JPEG/PNG/WebP). Your Takeout folder is not changed."
            )

            StepLabel(1, "Unzip the Takeout download", done = state.sourceUri.isNotBlank())
            Text(
                "Takeout arrives as .zip files. In the Files app tap each zip → Extract. Extract all parts into the same folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FolderPickerCard(
                title    = "Takeout folder",
                subtitle = "The extracted \"Takeout\" folder (or \"Google Photos\" inside it)",
                icon     = Icons.Default.FolderZip,
                name     = state.sourceName,
                enabled  = !state.running,
                onPick   = { sourcePicker.launch(null) },
            )

            StepLabel(2, "Where to put the photos", done = state.outputUri.isNotBlank())
            FolderPickerCard(
                title    = "Output folder",
                subtitle = "Your backup drive or a new empty folder",
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
                        "Keep dates already in photos",
                        "Recommended. Camera dates are usually more precise than Google's",
                        state.skipIfHasExif, !state.running, vm::setSkipIfHasExif,
                    )
                    OptionSwitch(
                        "Rename copies to their date",
                        "2024-03-15_14-30-22.jpg instead of the original name",
                        state.renameToDate, !state.running, vm::setRenameToDate,
                    )
                }
            }

            if (state.running) {
                JobProgressCard(
                    title    = "Importing…",
                    done     = state.done,
                    total    = state.total,
                    current  = state.currentFile,
                    stopping = state.stopping,
                    onStop   = vm::stop,
                )
            } else {
                Button(
                    onClick  = vm::startProcessing,
                    enabled  = state.sourceUri.isNotBlank() && state.outputUri.isNotBlank() && state.sourceUri != state.outputUri,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start import")
                }
            }

            if (!state.running) state.result?.let { TakeoutResultCard(it, vm::clearResult) }

            if (state.logLines.isNotEmpty()) LogPanel(lines = state.logLines, running = state.running)

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TakeoutResultCard(r: TakeoutResult, onDismiss: () -> Unit) {
    if (r.errorMsg.isNotBlank()) {
        ResultCard("Could not start", ok = false, onDismiss = onDismiss) {
            Text(r.errorMsg, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val title = when {
        r.stoppedEarly.isNotBlank() -> "Stopped — ${r.stoppedEarly}"
        r.total == 0 -> "No photos or videos found"
        r.errors > 0 -> "Done, with problems"
        else -> "Done — ${plural(r.total, "file")}"
    }
    ResultCard(title, ok = r.errors == 0 && r.stoppedEarly.isBlank() && r.total > 0, onDismiss = onDismiss) {
        if (r.fixed > 0) ResultRow("Date from Google's .json", r.fixed, Icons.Default.AutoFixHigh)
        if (r.fromFilename > 0) ResultRow("Date from the file name", r.fromFilename, Icons.Default.TextFields)
        if (r.keptExisting > 0) ResultRow("Kept the photo's own date", r.keptExisting, Icons.Default.CheckCircle)
        if (r.unsupported > 0) ResultRow("Sorted, date not written (HEIC/video)", r.unsupported, Icons.Default.Info)
        if (r.noDate > 0) ResultRow("No date found (in no-date/)", r.noDate, Icons.AutoMirrored.Filled.HelpOutline)
        if (r.alreadyThere > 0) ResultRow("Already in the output (duplicates/earlier run)", r.alreadyThere, Icons.Default.RemoveCircleOutline)
        if (r.errors > 0) ResultRow("Errors (copied to error/)", r.errors, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
        if (r.ignored > 0) ResultRow("Other files left alone", r.ignored, Icons.Default.Description)
        if (r.archives > 0) {
            Text(
                "${plural(r.archives, "zip file")} still packed. Extract ${if (r.archives == 1) "it" else "them"} (Files app → tap the zip → Extract) and run again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (r.total == 0 && r.archives == 0) {
            Text("Pick the folder you extracted the Takeout zips into.", style = MaterialTheme.typography.bodySmall)
        }
        if (r.stoppedEarly.isNotBlank()) {
            Text("Everything done so far is safe. Run it again to continue — finished files are skipped.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
