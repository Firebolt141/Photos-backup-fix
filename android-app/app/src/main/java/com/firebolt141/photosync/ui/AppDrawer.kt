package com.firebolt141.ubertrag.ui

import androidx.compose.runtime.remember
import com.firebolt141.ubertrag.util.AppLanguage
import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.stringResource
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.drawer_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Item(Icons.Default.Explore, stringResource(R.string.nav_start), "start", currentRoute, onNavigate)

            Section(stringResource(R.string.nav_section_phone))
            Item(Icons.Default.PhoneAndroid, stringResource(R.string.nav_home), "home", currentRoute, onNavigate)
            Item(Icons.AutoMirrored.Filled.List, stringResource(R.string.nav_queue), "queue", currentRoute, onNavigate)

            Section(stringResource(R.string.nav_section_folder))
            Item(Icons.Default.FolderZip, stringResource(R.string.nav_takeout), "takeout", currentRoute, onNavigate)
            Item(Icons.Default.CalendarMonth, stringResource(R.string.nav_organize), "organize", currentRoute, onNavigate)

            Section(stringResource(R.string.nav_section_drive))
            Item(Icons.Default.AutoFixHigh, stringResource(R.string.nav_fix), "fix-exif", currentRoute, onNavigate)
            Item(Icons.Default.DriveFileRenameOutline, stringResource(R.string.nav_rename), "rename-folders", currentRoute, onNavigate)

            Section(stringResource(R.string.nav_section_language))
            LanguagePicker()
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

/** English / 日本語 / follow the phone. The screen redraws in the new language. */
@Composable
private fun LanguagePicker() {
    val current = remember { AppLanguage.current() }
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.lang_system),
        AppLanguage.ENGLISH to "English",
        AppLanguage.JAPANESE to "日本語",
    )
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (tag, label) ->
            FilterChip(
                selected = current == tag,
                onClick  = { if (current != tag) AppLanguage.set(tag) },
                label    = { Text(label) },
            )
        }
    }
}
