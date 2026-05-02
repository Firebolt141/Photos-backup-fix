package com.firebolt141.photosync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt141.photosync.data.CopyStatus
import com.firebolt141.photosync.data.QueueItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    items: List<QueueItem>,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center
            ) {
                Text("Queue is empty", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(items, key = { it.id }) { item ->
                    QueueRow(item)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem) {
    val fmt     = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    val dateStr = item.dateTaken?.let { fmt.format(Date(it)) } ?: "unknown date"

    ListItem(
        headlineContent  = {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(dateStr) },
        trailingContent   = { StatusChip(item.status) },
    )
}

@Composable
private fun StatusChip(status: CopyStatus) {
    val color = when (status) {
        CopyStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer
        CopyStatus.COPIED  -> MaterialTheme.colorScheme.primaryContainer
        CopyStatus.FAILED  -> MaterialTheme.colorScheme.errorContainer
        CopyStatus.SKIPPED -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val text = status.name.lowercase().replaceFirstChar { it.uppercase() }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
