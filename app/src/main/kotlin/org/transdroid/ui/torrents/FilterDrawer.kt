/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.ui.torrents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.transdroid.R
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.TorrentStatus
import org.transdroid.ui.theme.accentColor
import org.transdroid.ui.theme.labelColor
import org.transdroid.util.formatBytes

/**
 * The list screen's primary navigation and filtering surface, replacing the pre-redesign inline
 * filter-chip rows: brand + server switcher, name filter, single-select status filter, multi-select
 * label filter, multi-select tracker filter, and the entry point into Settings. Mirrors
 * design/mockups/transdroid-m3-filter-drawer.html: a fixed header (brand, server pill, filter field)
 * above one continuously-scrolling region that holds the status list, the label list, the tracker
 * list (not in the original mockup - added once [org.transdroid.protocol.Torrent.trackers] existed
 * to filter on) and, at its very end, the Settings row - nothing here is pinned to the screen
 * bottom, matching the reference markup.
 */
@Composable
fun FilterDrawerContent(
    ui: TorrentsUiState,
    onSwitchProfile: (String) -> Unit,
    onSetFilter: (TorrentFilter) -> Unit,
    onToggleLabel: (String) -> Unit,
    onToggleTracker: (String) -> Unit,
    onSetNameQuery: (String) -> Unit,
    onSelectErrorReason: (TorrentFilter, String?) -> Unit,
    onExpandTrackerGroup: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            Text(
                stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp),
            )
            Spacer(Modifier.height(9.dp))
            ui.activeProfile?.let { activeProfile ->
                ServerSelector(
                    activeProfile = activeProfile,
                    allProfiles = ui.allProfiles,
                    hasError = ui.error != null,
                    hasLoaded = ui.hasLoaded,
                    torrentCount = ui.torrents.size,
                    onSwitchProfile = onSwitchProfile,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        DrawerFilterField(
            value = ui.nameQuery,
            onValueChange = onSetNameQuery,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).padding(top = 8.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            var statesExpanded by rememberSaveable { mutableStateOf(true) }
            var labelsExpanded by rememberSaveable { mutableStateOf(false) }
            var trackersExpanded by rememberSaveable { mutableStateOf(false) }
            var errorBucketExpanded by rememberSaveable { mutableStateOf(false) }
            var trackerBucketExpanded by rememberSaveable { mutableStateOf(false) }

            // qBittorrent keeps announce wording out of the torrent payload, so the per-tracker
            // messages are fetched when the group is opened rather than on every refresh.
            LaunchedEffect(trackerBucketExpanded) {
                if (trackerBucketExpanded) onExpandTrackerGroup()
            }

            CollapsibleSectionHeader(
                title = stringResource(R.string.drawer_status_header),
                summary = if (ui.filter != TorrentFilter.ALL) ui.filter.label() else null,
                expanded = statesExpanded,
                onToggle = { statesExpanded = !statesExpanded },
            )
            if (statesExpanded) {
            TorrentFilter.entries.forEach { filter ->
                val isBucket = filter == TorrentFilter.ERROR || filter == TorrentFilter.TRACKER_ISSUES
                // Expanding is deliberately separate from selecting: tapping a failure group only
                // reveals its causes, so the sheet neither closes nor jumps to the torrent list.
                val bucketExpanded = when (filter) {
                    TorrentFilter.ERROR -> errorBucketExpanded
                    TorrentFilter.TRACKER_ISSUES -> trackerBucketExpanded
                    else -> false
                }
                // Once the daemon has been probed, the tracker group reports every failing
                // tracker rather than only the torrents its own error flag covers.
                val bucketCount =
                    if (filter == TorrentFilter.TRACKER_ISSUES && ui.trackerReasonTotal > 0) {
                        ui.trackerReasonTotal
                    } else {
                        ui.countFor(filter)
                    }
                DrawerItemRow(
                    icon = filter.icon(),
                    label = filter.label(),
                    count = bucketCount,
                    selected = ui.filter == filter,
                    onClick = {
                        when (filter) {
                            TorrentFilter.ERROR -> errorBucketExpanded = !errorBucketExpanded
                            TorrentFilter.TRACKER_ISSUES -> trackerBucketExpanded = !trackerBucketExpanded
                            else -> onSetFilter(filter)
                        }
                    },
                )
                if (isBucket && bucketExpanded) {
                    val reasons = ui.reasonCountsFor(filter)
                    // "All" is always present so the whole group stays reachable after expanding.
                    DrawerSubItemRow(
                        label = stringResource(R.string.reason_all),
                        count = bucketCount,
                        selected = ui.filter == filter && ui.errorReasonFilter == null,
                        onClick = { onSelectErrorReason(filter, null) },
                    )
                    if (filter == TorrentFilter.TRACKER_ISSUES && ui.trackerReasonsFailed) {
                        DrawerSubItemRow(
                            label = stringResource(R.string.reason_failed),
                            count = null,
                            selected = false,
                            onClick = {},
                        )
                    }
                    if (filter == TorrentFilter.TRACKER_ISSUES && ui.trackerReasonsLoading) {
                        // Probing every torrent takes a moment; say so instead of showing a
                        // count that is about to change.
                        DrawerSubItemRow(
                            label = stringResource(R.string.reason_loading),
                            count = null,
                            selected = false,
                            onClick = {},
                        )
                    }
                    reasons.forEach { reason ->
                        DrawerSubItemRow(
                            label = reasonLabel(reason.key),
                            count = reason.count,
                            sizeBytes = reason.totalBytes,
                            selected = ui.filter == filter && ui.errorReasonFilter == reason.key,
                            onClick = { onSelectErrorReason(filter, reason.key) },
                        )
                    }
                }
            }
            }

            if (ui.availableLabels.isNotEmpty()) {
                DrawerDivider()
                CollapsibleSectionHeader(
                    title = stringResource(R.string.drawer_labels_header),
                    summary = ui.labelFilters.size.takeIf { it > 0 }?.let { it.toString() },
                    expanded = labelsExpanded,
                    onToggle = { labelsExpanded = !labelsExpanded },
                )
                if (labelsExpanded) {
                ui.availableLabels.forEach { label ->
                    val (dotColor, _) = labelColor(label)
                    DrawerItemRow(
                        dotColor = dotColor,
                        label = label,
                        count = ui.countForLabel(label),
                        selected = label in ui.labelFilters,
                        onClick = { onToggleLabel(label) },
                    )
                }
                if (ui.hasUnlabeledTorrents) {
                    DrawerItemRow(
                        hollowDot = true,
                        label = stringResource(R.string.drawer_no_label),
                        count = ui.countForLabel(NO_LABEL),
                        selected = NO_LABEL in ui.labelFilters,
                        onClick = { onToggleLabel(NO_LABEL) },
                    )
                }
            }
            }

            if (ui.availableTrackers.isNotEmpty()) {
            DrawerDivider()
            CollapsibleSectionHeader(
                title = stringResource(R.string.drawer_trackers_header),
                summary = ui.trackerFilters.size.takeIf { it > 0 }?.let { it.toString() },
                expanded = trackersExpanded,
                onToggle = { trackersExpanded = !trackersExpanded },
            )
            if (trackersExpanded) {
            ui.availableTrackers.forEach { tracker ->
                    DrawerItemRow(
                        icon = Icons.Rounded.Public,
                        label = tracker,
                        count = ui.countForTracker(tracker),
                        selected = tracker in ui.trackerFilters,
                        onClick = { onToggleTracker(tracker) },
                    )
                }
                }
            }

            DrawerDivider()
            DrawerItemRow(
                icon = Icons.Rounded.Settings,
                label = stringResource(R.string.torrents_settings),
                count = null,
                selected = false,
                onClick = onOpenSettings,
                badge = ui.availableUpdateName?.let { stringResource(R.string.update_available_badge) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * A group title that collapses its rows. [summary] keeps the current selection visible while the
 * group is closed, so a filter never becomes invisible just because its section was folded.
 */
@Composable
private fun CollapsibleSectionHeader(
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * The active server as a pill, matching design/mockups/transdroid-m3-filter-drawer.html. With
 * more than one configured server it's a dropdown: tapping it opens a menu to switch, and it
 * carries the chevron affordance; with only one server there's nothing to select, so the chevron
 * is dropped and the pill just displays the connection state.
 */
@Composable
private fun ServerSelector(
    activeProfile: ServerProfile,
    allProfiles: List<ServerProfile>,
    hasError: Boolean,
    hasLoaded: Boolean,
    torrentCount: Int,
    onSwitchProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = allProfiles.size > 1
    Box(modifier) {
        ServerPill(
            profile = activeProfile,
            hasError = hasError,
            hasLoaded = hasLoaded,
            torrentCount = torrentCount,
            showChevron = canSwitch,
            onClick = { if (canSwitch) expanded = true },
        )
        if (canSwitch) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                allProfiles.forEach { profile ->
                    val isActive = profile.id == activeProfile.id
                    DropdownMenuItem(
                        text = { Text(profile.displayName) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (isActive) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            expanded = false
                            if (!isActive) onSwitchProfile(profile.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerPill(
    profile: ServerProfile,
    hasError: Boolean,
    hasLoaded: Boolean,
    torrentCount: Int,
    showChevron: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = showChevron, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (text, dotColor) = when {
                    hasError -> stringResource(R.string.drawer_connection_error) to MaterialTheme.colorScheme.error
                    hasLoaded -> stringResource(R.string.drawer_connected_count, torrentCount) to
                        TorrentStatus.SEEDING.accentColor
                    else -> stringResource(R.string.drawer_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showChevron) {
            Icon(
                Icons.Rounded.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DrawerFilterField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.drawer_filter_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.drawer_clear_filter),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A single pill-shaped row shared by the status list, the label list and the Settings entry. */
@Composable
private fun DrawerItemRow(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    dotColor: Color? = null,
    hollowDot: Boolean = false,
    badge: String? = null,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                icon != null -> Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
                hollowDot -> Box(
                    Modifier
                        .size(13.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
                )
                dotColor != null -> Box(Modifier.size(13.dp).clip(RoundedCornerShape(5.dp)).background(dotColor))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

/** Human label for a normalised reason key; HTTP failures show their status code verbatim. */
@Composable
private fun reasonLabel(key: String): String = when (key) {
    KEY_LOCAL_NO_DATA -> stringResource(R.string.reason_no_data)
    KEY_LOCAL_CORRUPT -> stringResource(R.string.reason_corrupt)
    KEY_LOCAL_OTHER -> stringResource(R.string.reason_local_other)
    KEY_TRACKER_OTHER -> stringResource(R.string.reason_tracker_other)
    else -> trackerTextOf(key) ?: key
}

/** Indented, slightly shorter row used for the expanded reasons under a failure bucket. */
@Composable
private fun DrawerSubItemRow(
    label: String,
    count: Int?,
    sizeBytes: Long? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 12.dp, top = 1.dp, bottom = 1.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (sizeBytes != null && sizeBytes > 0) "$count · ${formatBytes(sizeBytes)}" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

private fun TorrentFilter.icon(): ImageVector = when (this) {
    TorrentFilter.ALL -> Icons.Rounded.ListAlt
    TorrentFilter.ACTIVE -> Icons.Rounded.Speed
    TorrentFilter.DOWNLOADING -> Icons.Rounded.Download
    TorrentFilter.SEEDING -> Icons.Rounded.Upload
    TorrentFilter.COMPLETED -> Icons.Rounded.CheckCircle
    TorrentFilter.PAUSED -> Icons.Rounded.Pause
    TorrentFilter.TRACKER_ISSUES -> Icons.Rounded.Public
    TorrentFilter.ERROR -> Icons.Rounded.ErrorOutline
}
