package com.firebolt141.ubertrag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Start here": asks what the person has and sends them to the right tool,
 * with one sentence on what each tool will do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    state: UiState,
    onNavigate: (String) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Übertrag") },
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
            Text("What would you like to do?", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pick what matches your photos. Every tool copies or fixes files without deleting anything, " +
                    "and every file ends up in a folder: Year / Month / Day, no-date/, or error/.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Photos on this phone")
            TaskCard(
                icon = Icons.Default.PhoneAndroid,
                title = "Back up this phone to a drive",
                body = "Copy the gallery to a USB drive or SD card, sorted by date. Run it again any time — only new photos are copied.",
                badge = if (state.pendingCount > 0) "${state.pendingCount} waiting" else "",
                onClick = { onNavigate("home") },
            )

            SectionLabel("A folder of photos")
            TaskCard(
                icon = Icons.Default.FolderZip,
                title = "I have a Google Takeout export",
                body = "Unzipped Takeout folders with .json files. Restores the real dates, places and captions and sorts everything by date.",
                onClick = { onNavigate("takeout") },
            )
            TaskCard(
                icon = Icons.Default.CalendarMonth,
                title = "Sort a messy folder by date",
                body = "Years of photos in random folders and sub-folders (old phones, WhatsApp, camera dumps). Copies them into Year / Month / Day.",
                onClick = { onNavigate("organize") },
            )

            SectionLabel("Your backup drive")
            TaskCard(
                icon = Icons.Default.AutoFixHigh,
                title = "Photos show the wrong date",
                body = "Writes each Year / Month / Day folder's date into photos on the drive that don't have one.",
                onClick = { onNavigate("fix-exif") },
            )
            TaskCard(
                icon = Icons.Default.DriveFileRenameOutline,
                title = "Update old folder names",
                body = "Turns 2024 / 01 / 15 folders from older versions into 2024 / January / January_15.",
                onClick = { onNavigate("rename-folders") },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TaskCard(icon: ImageVector, title: String, body: String, badge: String = "", onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge.isNotBlank()) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
