package com.firebolt141.ubertrag.ui

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
                        Text("Update folder names")
                        Text(
                            "Old 01 / 15 folders → January / January_15",
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
                "Older versions made folders like 2024 / 01 / 15 or 2024 / March / March 7. This gives them the " +
                    "current names (2024 / January / January_15, March_07) so everything sorts the same way. " +
                    "If both an old and a new folder exist for the same day, their photos are merged; " +
                    "identical files are never duplicated. Nothing is deleted."
            )

            DriveStatusCard(driveUri = state.driveUri, driveConnected = state.driveConnected)

            if (state.driveConnected) {
                StepLabel(1, "Check what would change", done = r.preview != null)
                OutlinedButton(onClick = onCheck, enabled = !r.running, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check the drive")
                }

                r.preview?.let { p ->
                    ResultCard(r.status, ok = true) {
                        p.changes.take(30).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                        if (p.changes.size > 30) Text("… and ${p.changes.size - 30} more", style = MaterialTheme.typography.bodySmall)
                    }
                }

                StepLabel(2, "Rename", done = r.result != null)
                Button(
                    onClick  = onRename,
                    enabled  = !r.running && (r.preview?.changes?.isNotEmpty() ?: true),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DriveFileRenameOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rename folders")
                }

                if (r.running) {
                    JobProgressCard(title = "Working…", done = 0, total = 0, current = r.status)
                }

                r.result?.let { res ->
                    ResultCard(r.status, ok = res.errors == 0) {
                        if (res.renamed > 0) ResultRow("Folders renamed", res.renamed, Icons.Default.DriveFileRenameOutline)
                        if (res.merged > 0) ResultRow("Folders merged", res.merged, Icons.AutoMirrored.Filled.MergeType)
                        if (res.filesMoved > 0) ResultRow("Files moved while merging", res.filesMoved, Icons.AutoMirrored.Filled.DriveFileMove)
                        if (res.identical > 0) ResultRow("Identical files left in the old folder", res.identical, Icons.Default.ContentCopy)
                        if (res.errors > 0) ResultRow("Problems", res.errors, Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
                        res.problems.take(10).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!r.running && r.preview == null && r.result == null && r.status.isNotBlank()) {
                    Text(r.status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
