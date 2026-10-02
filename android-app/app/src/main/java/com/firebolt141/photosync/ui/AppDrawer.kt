package com.firebolt141.ubertrag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun AppDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text("Übertrag", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Photo backup & repair",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Item(Icons.Default.Explore, "Start here", "start", currentRoute, onNavigate)

            Section("This phone")
            Item(Icons.Default.PhoneAndroid, "Back up phone", "home", currentRoute, onNavigate)
            Item(Icons.AutoMirrored.Filled.List, "Backup list", "queue", currentRoute, onNavigate)

            Section("A folder of photos")
            Item(Icons.Default.FolderZip, "Import Google Takeout", "takeout", currentRoute, onNavigate)
            Item(Icons.Default.CalendarMonth, "Sort a folder by date", "organize", currentRoute, onNavigate)

            Section("Backup drive")
            Item(Icons.Default.AutoFixHigh, "Fix dates on the drive", "fix-exif", currentRoute, onNavigate)
            Item(Icons.Default.DriveFileRenameOutline, "Update folder names", "rename-folders", currentRoute, onNavigate)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, route: String, current: String?, onNavigate: (String) -> Unit) {
    NavigationDrawerItem(
        icon     = { Icon(icon, null) },
        label    = { Text(label) },
        selected = current == route,
        onClick  = { onNavigate(route) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp),
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
