package com.firebolt141.ubertrag.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun FolderPickerCard(
    title:    String,
    subtitle: String,
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    name:     String,
    enabled:  Boolean,
    onPick:   () -> Unit,
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

/** Drive status card shared by FixExifScreen and RenameFoldersScreen. */
@Composable
fun DriveStatusCard(
    driveUri: String?,
    driveConnected: Boolean,
    onDriveSelected: ((Uri) -> Unit)? = null,
) {
    val drivePicker = if (onDriveSelected != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(onDriveSelected)
        }
    } else null

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Storage, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("External Drive", style = MaterialTheme.typography.titleSmall)
            }

            val (icon, color, label) = when {
                driveConnected -> Triple(
                    Icons.Default.CheckCircle,
                    MaterialTheme.colorScheme.primary,
                    "Drive connected — ready",
                )
                driveUri != null -> Triple(
                    Icons.Default.Warning,
                    MaterialTheme.colorScheme.error,
                    "Drive not connected — plug it in and try again",
                )
                else -> Triple(
                    Icons.Default.Info,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "No drive selected. Select a drive on the Copy to Drive screen first.",
                )
            }

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            }

            if (!driveConnected && drivePicker != null) {
                FilledTonalButton(
                    onClick  = { drivePicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select Drive")
                }
            }
        }
    }
}
