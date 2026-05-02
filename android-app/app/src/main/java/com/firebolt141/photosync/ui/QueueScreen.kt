package com.firebolt141.photosync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
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
import com.firebolt141.photosync.data.CopyStatus
import com.firebolt141.photosync.data.QueueItem
import java.text.SimpleDateFormat
import java.util.*

private enum class Filter { All, Pending, Copied, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    items: List<QueueItem>,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf(Filter.All) }

    val visible = when (filter) {
        Filter.All     -> items
        Filter.Pending -> items.filter { it.status == CopyStatus.PENDING }
        Filter.Copied  -> items.filter { it.status == CopyStatus.COPIED  }
        Filter.Failed  -> items.filter { it.status == CopyStatus.FAILED || it.status == CopyStatus.SKIPPED }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${items.size} total", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick  = { filter = f },
                        label    = { Text(f.name) },
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
                            "Nothing to show",
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
}

@Composable
private fun QueueRow(item: QueueItem) {
    val fmt     = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    val dateStr = item.dateTaken?.let { fmt.format(Date(it)) } ?: "Unknown date"

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
                if (item.status == CopyStatus.FAILED && item.errorMsg != null) {
                    Text(
                        item.errorMsg,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
