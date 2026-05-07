package com.firebolt141.ubertrag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixExifScreen(
    state:           UiState,
    onFixMissingExif: () -> Unit,
    onOpenDrawer:    () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fix Missing EXIF Dates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Write dates to drive files missing them", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Info ─────────────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment     = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        Modifier.size(20.dp).padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Reads the year / month / day folder names on your drive and writes " +
                        "EXIF date tags into JPEG, PNG, and WebP files that are missing them. " +
                        "HEIC, RAW, and video files are skipped — Android cannot write EXIF to those formats.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Drive status ─────────────────────────────────────────────
            DriveStatusCard(state)

            // ── Run / progress ───────────────────────────────────────────
            if (state.driveConnected) {
                Button(
                    onClick  = onFixMissingExif,
                    enabled  = !state.fixingExif,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.fixingExif) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.fixingExif) "Writing EXIF dates…" else "Start EXIF Fix")
                }

                if (state.fixingExif && state.exifFixStatus.isNotBlank()) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors   = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                state.exifFixStatus,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }

                if (!state.fixingExif && state.exifFixStatus.isNotBlank()) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(state.exifFixStatus, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
