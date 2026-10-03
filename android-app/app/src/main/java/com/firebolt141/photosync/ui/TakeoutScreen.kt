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
                        Text(stringResource(R.string.takeout_title))
                        Text(
                            stringResource(R.string.takeout_subtitle),
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
            InfoCard(
                stringResource(R.string.takeout_info)
            )

            StepLabel(1, stringResource(R.string.takeout_step_unzip), done = state.sourceUri.isNotBlank())
            Text(
                stringResource(R.string.takeout_unzip_how),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FolderPickerCard(
                title    = stringResource(R.string.takeout_folder),
                subtitle = stringResource(R.string.takeout_folder_sub),
                icon     = Icons.Default.FolderZip,
                name     = StorageHelper.folderLabel(state.sourceUri, LocalContext.current),
                enabled  = !state.running,
                onPick   = { sourcePicker.launch(null) },
            )

            StepLabel(2, stringResource(R.string.takeout_step_output), done = state.outputUri.isNotBlank())
            FolderPickerCard(
                title    = stringResource(R.string.output_folder),
                subtitle = stringResource(R.string.takeout_output_sub),
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
                        stringResource(R.string.opt_keep_dates),
                        stringResource(R.string.opt_keep_dates_sub),
                        state.skipIfHasExif, !state.running, vm::setSkipIfHasExif,
                    )
                    OptionSwitch(
                        stringResource(R.string.opt_rename),
                        stringResource(R.string.opt_rename_sub),
                        state.renameToDate, !state.running, vm::setRenameToDate,
                    )
                }
            }

            if (state.running) {
                JobProgressCard(
                    title    = stringResource(R.string.importing),
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
                    Text(stringResource(R.string.start_import))
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
        ResultCard(stringResource(R.string.could_not_start), ok = false, onDismiss = onDismiss) {
            Text(r.errorMsg, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val title = when {
        r.stoppedEarly.isNotBlank() -> stringResource(R.string.sum_stopped, r.stoppedEarly)
        r.total == 0 -> stringResource(R.string.no_media_found)
        r.errors > 0 -> stringResource(R.string.done_with_problems)
        else -> pluralStringResource(R.plurals.done_n_files, r.total, r.total)
    }
    ResultCard(title, ok = r.errors == 0 && r.stoppedEarly.isBlank() && r.total > 0, onDismiss = onDismiss) {
        if (r.fixed > 0) ResultRow(stringResource(R.string.res_date_json), r.fixed, Icons.Default.AutoFixHigh)
        if (r.fromFilename > 0) ResultRow(stringResource(R.string.res_date_name), r.fromFilename, Icons.Default.TextFields)
        if (r.keptExisting > 0) ResultRow(stringResource(R.string.res_kept_own), r.keptExisting, Icons.Default.CheckCircle)
        if (r.unsupported > 0) ResultRow(stringResource(R.string.res_sorted_no_write), r.unsupported, Icons.Default.Info)
        if (r.noDate > 0) ResultRow(stringResource(R.string.res_no_date), r.noDate, Icons.AutoMirrored.Filled.HelpOutline)
        if (r.alreadyThere > 0) ResultRow(stringResource(R.string.res_already_output_dup), r.alreadyThere, Icons.Default.RemoveCircleOutline)
        if (r.errors > 0) ResultRow(stringResource(R.string.res_errors_error_dir), r.errors, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
        if (r.ignored > 0) ResultRow(stringResource(R.string.res_other_left_short), r.ignored, Icons.Default.Description)
        if (r.archives > 0) {
            Text(
                pluralStringResource(R.plurals.zips_still_packed, r.archives, r.archives),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (r.total == 0 && r.archives == 0) {
            Text(stringResource(R.string.takeout_pick_extracted), style = MaterialTheme.typography.bodySmall)
        }
        if (r.stoppedEarly.isNotBlank()) {
            Text(stringResource(R.string.run_again_continue), style = MaterialTheme.typography.bodySmall)
        }
    }
}
