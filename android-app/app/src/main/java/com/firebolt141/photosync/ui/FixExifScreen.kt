package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.util.StorageHelper
import androidx.compose.ui.platform.LocalContext
import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                        Text(stringResource(if (filenameMode) R.string.organize_title else R.string.fix_title))
                        Text(
                            stringResource(if (filenameMode) R.string.organize_subtitle else R.string.fix_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu)) }
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
                    stringResource(R.string.organize_info)
                )
                StepLabel(1, stringResource(R.string.organize_step_source), done = state.sourceUri.isNotBlank())
                FolderPickerCard(
                    title    = stringResource(R.string.source_folder),
                    subtitle = stringResource(R.string.subfolders_included),
                    icon     = Icons.Default.FolderOpen,
                    name     = StorageHelper.folderLabel(state.sourceUri, LocalContext.current),
                    enabled  = !state.running,
                    onPick   = { sourcePicker.launch(null) },
                )
                StepLabel(2, stringResource(R.string.organize_step_output), done = state.outputUri.isNotBlank())
                FolderPickerCard(
                    title    = stringResource(R.string.output_folder),
                    subtitle = stringResource(R.string.output_folder_sub),
                    icon     = Icons.AutoMirrored.Filled.DriveFileMove,
                    name     = StorageHelper.folderLabel(state.outputUri, LocalContext.current),
                    enabled  = !state.running,
                    onPick   = { outputPicker.launch(null) },
                )
                if (state.sourceUri.isNotBlank() && state.sourceUri == state.outputUri) {
                    Text(stringResource(R.string.source_output_differ), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                StepLabel(3, stringResource(R.string.step_options))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OptionSwitch(
                            stringResource(R.string.opt_trust_dates),
                            stringResource(R.string.opt_trust_dates_sub),
                            state.keepExistingDates, !state.running, vm::setKeepExistingDates,
                        )
                        OptionSwitch(
                            stringResource(R.string.opt_rename),
                            stringResource(R.string.opt_rename_sub),
                            state.renameToDate, !state.running, vm::setRenameToDate,
                        )
                    }
                }
            } else {
                InfoCard(
                    stringResource(R.string.fix_info)
                )
                DriveStatusCard(driveUri = state.driveUri, driveConnected = state.driveConnected)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OptionSwitch(
                            stringResource(R.string.opt_fix_wrong),
                            stringResource(R.string.opt_fix_wrong_sub),
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
                    title    = stringResource(if (filenameMode) R.string.sorting else R.string.checking_dates),
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
                    Text(stringResource(if (filenameMode) R.string.start_sorting else R.string.fix_dates))
                }
            }

            if (state.error.isNotBlank()) {
                ResultCard(stringResource(R.string.couldnt_run), ok = false, onDismiss = vm::clearResult) {
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
                ResultCard(stringResource(R.string.drive_not_connected_title), ok = false, onDismiss = onDismiss) {}
                return
            }
            ResultCard(stringResource(if (r.failed == 0) R.string.done else R.string.done_with_problems), ok = r.failed == 0, onDismiss = onDismiss) {
                ResultRow(stringResource(R.string.res_dates_written), r.fixed, Icons.Default.AutoFixHigh)
                if (r.corrected > 0) ResultRow(stringResource(R.string.res_wrong_corrected), r.corrected, Icons.Default.EditCalendar)
                ResultRow(stringResource(R.string.res_already_dated), r.alreadyHasDate, Icons.Default.CheckCircle)
                if (r.mismatched > r.corrected) ResultRow(stringResource(R.string.res_mismatch_unchanged), r.mismatched - r.corrected, Icons.Default.Warning)
                if (r.skipped > 0) ResultRow(stringResource(R.string.res_cant_store), r.skipped, Icons.Default.Block)
                if (r.failed > 0) ResultRow(stringResource(R.string.errors), r.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                r.notes.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (r.notes.size > 5) Text(stringResource(R.string.and_n_more_log, r.notes.size - 5), style = MaterialTheme.typography.bodySmall)
            }
        }
        is FixExifResult.FilenameMode -> {
            val r = result.r
            val title = when {
                r.stoppedEarly.isNotBlank() -> stringResource(R.string.sum_stopped, r.stoppedEarly)
                r.copied + r.alreadyExists + r.failed == 0 -> stringResource(R.string.no_media_found)
                r.failed > 0 -> stringResource(R.string.done_with_problems)
                else -> stringResource(R.string.done)
            }
            ResultCard(title, ok = r.failed == 0 && r.stoppedEarly.isBlank(), onDismiss = onDismiss) {
                ResultRow(stringResource(R.string.res_sorted), r.copied - r.noDate, Icons.Default.CalendarMonth)
                if (r.exifWritten > 0) ResultRow(stringResource(R.string.res_date_written_copy), r.exifWritten, Icons.Default.AutoFixHigh)
                if (r.keptExisting > 0) ResultRow(stringResource(R.string.res_kept_own), r.keptExisting, Icons.Default.CheckCircle)
                if (r.unsupported > 0) ResultRow(stringResource(R.string.res_sorted_no_write), r.unsupported, Icons.Default.Info)
                if (r.noDate > 0) ResultRow(stringResource(R.string.res_no_date), r.noDate, Icons.AutoMirrored.Filled.HelpOutline)
                if (r.alreadyExists > 0) ResultRow(stringResource(R.string.res_already_output), r.alreadyExists, Icons.Default.RemoveCircleOutline)
                if (r.failed > 0) ResultRow(stringResource(R.string.res_errors_error_dir), r.failed, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                if (r.ignored > 0) ResultRow(stringResource(R.string.res_other_left), r.ignored, Icons.Default.Description)
                if (r.archives > 0) {
                    Text(
                        pluralStringResource(R.plurals.zip_found_unzip, r.archives, r.archives),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (r.stoppedEarly.isNotBlank()) {
                    Text(stringResource(R.string.run_again_continue), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
