package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu)) }
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
            Text(stringResource(R.string.start_question), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.start_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.start_section_phone))
            TaskCard(
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(R.string.start_backup_title),
                body = stringResource(R.string.start_backup_body),
                badge = if (state.pendingCount > 0) pluralStringResource(R.plurals.n_waiting, state.pendingCount, state.pendingCount) else "",
                onClick = { onNavigate("home") },
            )

            SectionLabel(stringResource(R.string.start_section_folder))
            TaskCard(
                icon = Icons.Default.FolderZip,
                title = stringResource(R.string.start_takeout_title),
                body = stringResource(R.string.start_takeout_body),
                onClick = { onNavigate("takeout") },
            )
            TaskCard(
                icon = Icons.Default.CalendarMonth,
                title = stringResource(R.string.start_organize_title),
                body = stringResource(R.string.start_organize_body),
                onClick = { onNavigate("organize") },
            )

            SectionLabel(stringResource(R.string.start_section_drive))
            TaskCard(
                icon = Icons.Default.AutoFixHigh,
                title = stringResource(R.string.start_fix_title),
                body = stringResource(R.string.start_fix_body),
                onClick = { onNavigate("fix-exif") },
            )
            TaskCard(
                icon = Icons.Default.DriveFileRenameOutline,
                title = stringResource(R.string.start_rename_title),
                body = stringResource(R.string.start_rename_body),
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
