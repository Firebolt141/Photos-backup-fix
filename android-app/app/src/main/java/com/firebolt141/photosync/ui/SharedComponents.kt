package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CardTitle(icon: ImageVector, title: String, subtitle: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Numbered step heading used by the guided screens: (1) Choose your drive. */
@Composable
fun StepLabel(number: Int, title: String, done: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .background(
                    if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondary)
            else Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Short explanation box at the top of a screen. */
@Composable
fun InfoCard(text: String, icon: ImageVector = Icons.Default.Info, tone: Color = MaterialTheme.colorScheme.primary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment     = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp).padding(top = 2.dp), tint = tone)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FolderPickerCard(
    title:    String,
    subtitle: String,
    icon:     ImageVector,
    name:     String,
    enabled:  Boolean,
    onPick:   () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(icon, title, subtitle)

            if (name.isNotBlank()) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            FilledTonalButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (name.isBlank()) R.string.select_folder else R.string.change_folder))
            }
        }
    }
}

/**
 * Drive status card shared by the Home, Fix dates and Rename screens.
 * With [onDriveSelected] it also offers a Select/Change button.
 */
@Composable
fun DriveStatusCard(
    driveUri: String?,
    driveConnected: Boolean,
    onDriveSelected: ((Uri) -> Unit)? = null,
    onForget: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val drivePicker = if (onDriveSelected != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(onDriveSelected)
        }
    } else null
    val folderName = com.firebolt141.ubertrag.util.StorageHelper.folderLabel(driveUri, androidx.compose.ui.platform.LocalContext.current)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(Icons.Default.Storage, stringResource(R.string.backup_drive))

            val (icon, color, label) = when {
                driveConnected -> Triple(
                    Icons.Default.CheckCircle, MaterialTheme.colorScheme.secondary,
                    stringResource(R.string.drive_connected),
                )
                driveUri != null -> Triple(
                    Icons.Default.Warning, MaterialTheme.colorScheme.error,
                    stringResource(R.string.drive_not_connected),
                )
                else -> Triple(
                    Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(if (drivePicker != null) R.string.drive_none_pick else R.string.drive_none_elsewhere),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            }

            // Show the selected folder name so the user can verify the right root is picked
            if (folderName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Folder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        folderName,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (drivePicker != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { drivePicker.launch(null) }, enabled = enabled) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (driveUri == null) R.string.select_drive else R.string.change_drive))
                    }
                    if (driveUri != null && onForget != null) {
                        OutlinedButton(onClick = onForget, enabled = enabled) { Text(stringResource(R.string.forget)) }
                    }
                }
            }
        }
    }
}

/** Progress of a running job, with an optional Stop button. */
@Composable
fun JobProgressCard(
    title: String,
    done: Int,
    total: Int,
    current: String,
    extra: String = "",
    stopping: Boolean = false,
    onStop: (() -> Unit)? = null,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (stopping) stringResource(R.string.stopping_after_file) else title,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (total > 0) Text("$done / $total", style = MaterialTheme.typography.labelMedium)
            }
            if (total > 0) {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (current.isNotBlank()) {
                Text(
                    current,
                    style    = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (total > 0) append("${done * 100 / total}%")
                        if (extra.isNotBlank()) { if (isNotEmpty()) append(" · "); append(extra) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                if (onStop != null) {
                    TextButton(onClick = onStop, enabled = !stopping) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.stop))
                    }
                }
            }
            Text(
                stringResource(R.string.keeps_going),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Monospace live log; auto-scrolls to the newest line. */
@Composable
fun LogPanel(lines: List<String>, running: Boolean) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(stringResource(if (running) R.string.working else R.string.log), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(pluralStringResource(R.plurals.n_lines, lines.size, lines.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(8.dp),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = when {
                                line.startsWith("✓") -> MaterialTheme.colorScheme.secondary
                                line.startsWith("✗") -> MaterialTheme.colorScheme.error
                                line.startsWith("⚠") || line.startsWith("≠") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One "label: count" line in a result card. */
@Composable
fun ResultRow(label: String, count: Int, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Result card frame: title line with an icon, then [content], then an optional Done button. */
@Composable
fun ResultCard(
    title: String,
    ok: Boolean,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, null, Modifier.size(20.dp),
                    tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

