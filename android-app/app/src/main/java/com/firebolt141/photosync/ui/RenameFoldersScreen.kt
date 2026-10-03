package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameFoldersScreen(
    state:        UiState,
    onCheck:      () -> Unit,
    onRename:     () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val r = state.rename
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.rename_title))
                        Text(
                            stringResource(R.string.rename_subtitle),
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
                stringResource(R.string.rename_info)
            )

            DriveStatusCard(driveUri = state.driveUri, driveConnected = state.driveConnected)

            if (state.driveConnected) {
                StepLabel(1, stringResource(R.string.rename_step_check), done = r.preview != null)
                OutlinedButton(onClick = onCheck, enabled = !r.running, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rename_check_drive))
                }

                r.preview?.let { p ->
                    ResultCard(r.status.text(), ok = true) {
                        p.changes.take(30).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                        if (p.changes.size > 30) Text(stringResource(R.string.and_n_more, p.changes.size - 30), style = MaterialTheme.typography.bodySmall)
                    }
                }

                StepLabel(2, stringResource(R.string.rename_step_rename), done = r.result != null)
                Button(
                    onClick  = onRename,
                    enabled  = !r.running && (r.preview?.changes?.isNotEmpty() ?: true),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DriveFileRenameOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rename_folders))
                }

                if (r.running) {
                    JobProgressCard(title = stringResource(R.string.working), done = 0, total = 0, current = r.status.text())
                }

                r.result?.let { res ->
                    ResultCard(r.status.text(), ok = res.errors == 0) {
                        if (res.renamed > 0) ResultRow(stringResource(R.string.rename_folders_renamed), res.renamed, Icons.Default.DriveFileRenameOutline)
                        if (res.merged > 0) ResultRow(stringResource(R.string.rename_folders_merged), res.merged, Icons.AutoMirrored.Filled.MergeType)
                        if (res.filesMoved > 0) ResultRow(stringResource(R.string.rename_files_moved), res.filesMoved, Icons.AutoMirrored.Filled.DriveFileMove)
                        if (res.identical > 0) ResultRow(stringResource(R.string.rename_identical_left), res.identical, Icons.Default.ContentCopy)
                        if (res.errors > 0) ResultRow(stringResource(R.string.problems), res.errors, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                        res.problems.take(10).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!r.running && r.preview == null && r.result == null && !r.status.isEmpty) {
                    Text(r.status.text(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
