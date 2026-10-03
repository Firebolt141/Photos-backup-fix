package com.firebolt141.ubertrag.ui

import java.text.DateFormat
import androidx.compose.ui.platform.LocalConfiguration
import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt141.ubertrag.data.CopyStatus
import com.firebolt141.ubertrag.data.QueueItem
import java.util.*

private enum class Filter(val label: Int) {
    All(R.string.filter_all), Pending(R.string.filter_waiting), Copied(R.string.filter_copied),
    Skipped(R.string.filter_skipped), Failed(R.string.filter_failed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    items: List<QueueItem>,
    onBack: () -> Unit,
    onClearCopied: () -> Unit,
    onRequeueAll: () -> Unit = {},
) {
    var filter by remember { mutableStateOf(Filter.All) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showRequeueDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val visible = when (filter) {
        Filter.All     -> items
        Filter.Pending -> items.filter { it.status == CopyStatus.PENDING }
        Filter.Copied  -> items.filter { it.status == CopyStatus.COPIED  }
        Filter.Skipped -> items.filter { it.status == CopyStatus.SKIPPED }
        Filter.Failed  -> items.filter { it.status == CopyStatus.FAILED  }
    }

    val copiedCount  = items.count { it.status == CopyStatus.COPIED  }
    val skippedCount = items.count { it.status == CopyStatus.SKIPPED }
    val failedCount  = items.count { it.status == CopyStatus.FAILED  }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(pluralStringResource(R.plurals.n_total, items.size, items.size), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_remove_copied)) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            enabled = copiedCount > 0,
                            onClick = { showMenu = false; showClearDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_copy_all_again)) },
                            leadingIcon = { Icon(Icons.Default.Replay, null) },
                            enabled = items.isNotEmpty(),
                            onClick = { showMenu = false; showRequeueDialog = true },
                        )
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Filter.entries.forEach { f ->
                    val badge: String? = when (f) {
                        Filter.Pending -> items.count { it.status == CopyStatus.PENDING }.takeIf { it > 0 }?.toString()
                        Filter.Copied  -> if (copiedCount  > 0) "$copiedCount"  else null
                        Filter.Skipped -> if (skippedCount > 0) "$skippedCount" else null
                        Filter.Failed  -> if (failedCount  > 0) "$failedCount"  else null
                        else           -> null
                    }
                    FilterChip(
                        selected = filter == f,
                        onClick  = { filter = f },
                        label    = {
                            if (badge != null) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(f.label))
                                    Badge { Text(badge) }
                                }
                            } else {
                                Text(stringResource(f.label))
                            }
                        },
                        leadingIcon = if (filter == f) ({
                            Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize))
                        }) else null,
                    )
                }
            }

            HorizontalDivider()

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inbox,
                            null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(if (items.isEmpty()) R.string.queue_empty_scan else R.string.queue_empty_group),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(visible, key = { _, item -> item.id }) { _, item ->
                        QueueRow(item)
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }

    if (showRequeueDialog) {
        AlertDialog(
            onDismissRequest = { showRequeueDialog = false },
            icon    = { Icon(Icons.Default.Replay, null) },
            title   = { Text(stringResource(R.string.queue_copy_all_q)) },
            text    = { Text(pluralStringResource(R.plurals.queue_copy_all_body, items.size, items.size)) },
            confirmButton = {
                TextButton(onClick = { onRequeueAll(); showRequeueDialog = false }) { Text(stringResource(R.string.queue_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showRequeueDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon    = { Icon(Icons.Default.DeleteSweep, null) },
            title   = { Text(stringResource(R.string.queue_clear_q)) },
            text    = { Text(pluralStringResource(R.plurals.queue_clear_body, copiedCount, copiedCount)) },
            confirmButton = {
                TextButton(onClick = { onClearCopied(); showClearDialog = false }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun QueueRow(item: QueueItem) {
    // Queue dates are wall-clock time encoded as UTC ms: format them in UTC.
    val locale  = LocalConfiguration.current.locales[0]
    val fmt     = remember(locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    val dateStr = item.dateTaken?.let { fmt.format(Date(it)) } ?: stringResource(R.string.queue_no_date)

    val (statusIcon, statusTint) = when (item.status) {
        CopyStatus.PENDING -> Icons.Default.Schedule      to MaterialTheme.colorScheme.onSurfaceVariant
        CopyStatus.COPIED  -> Icons.Default.CheckCircle   to MaterialTheme.colorScheme.primary
        CopyStatus.FAILED  -> Icons.Default.ErrorOutline  to MaterialTheme.colorScheme.error
        CopyStatus.SKIPPED -> Icons.Default.RemoveCircle  to MaterialTheme.colorScheme.tertiary
    }

    val animatedTint by animateColorAsState(statusTint, label = "statusTint")

    ListItem(
        headlineContent = {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(dateStr, style = MaterialTheme.typography.bodySmall)
                when (item.status) {
                    CopyStatus.SKIPPED -> {
                        Text(
                            item.errorMsg ?: stringResource(R.string.queue_already_skipped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (item.absolutePath.isNotBlank()) {
                            Text(
                                item.absolutePath,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    CopyStatus.FAILED -> {
                        if (item.errorMsg != null) {
                            Text(
                                item.errorMsg,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.absolutePath.isNotBlank()) {
                            Text(
                                item.absolutePath,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    else -> { /* nothing extra */ }
                }
            }
        },
        leadingContent = {
            Box(
                modifier          = Modifier.size(40.dp),
                contentAlignment  = Alignment.Center,
            ) {
                val mimeIcon = if (item.mimeType.startsWith("video/"))
                    Icons.Default.Videocam else Icons.Default.Photo
                Icon(mimeIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Icon(statusIcon, null, Modifier.size(22.dp), tint = animatedTint)
        },
    )
}
