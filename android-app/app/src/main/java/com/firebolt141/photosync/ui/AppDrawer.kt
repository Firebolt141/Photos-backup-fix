package com.firebolt141.ubertrag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    ModalDrawerSheet {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                "Übertrag",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Photo backup & repair",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        DrawerSectionLabel("Backup")
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.PhoneAndroid, null) },
            label    = { Text("Copy to Drive") },
            selected = currentRoute == "home",
            onClick  = { onNavigate("home") },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.List, null) },
            label    = { Text("View Queue") },
            selected = currentRoute == "queue",
            onClick  = { onNavigate("queue") },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        DrawerSectionLabel("Drive Utilities")
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.AutoFixHigh, null) },
            label    = { Text("Fix Missing EXIF Dates") },
            selected = currentRoute == "fix-exif",
            onClick  = { onNavigate("fix-exif") },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.DriveFileRenameOutline, null) },
            label    = { Text("Rename Drive Folders") },
            selected = currentRoute == "rename-folders",
            onClick  = { onNavigate("rename-folders") },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        DrawerSectionLabel("Import")
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.FolderZip, null) },
            label    = { Text("Process Google Takeout") },
            selected = currentRoute == "takeout",
            onClick  = { onNavigate("takeout") },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp),
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
