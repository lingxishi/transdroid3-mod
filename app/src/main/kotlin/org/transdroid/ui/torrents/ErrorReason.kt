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

import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus

internal const val KEY_LOCAL_NO_DATA = "local_no_data"
internal const val KEY_LOCAL_CORRUPT = "local_corrupt"
internal const val KEY_LOCAL_OTHER = "local_other"
internal const val KEY_TRACKER_OTHER = "tracker_other"
internal const val KEY_TRACKER_TEXT_PREFIX = "tracker_text:"

/** One row of the error/tracker-issues breakdown: how many torrents share a reason, and their size. */
data class ErrorReasonCount(
    val key: String,
    val count: Int,
    val totalBytes: Long,
)


/**
 * Normalised reason key for why a torrent sits in the error or tracker-issues bucket.
 *
 * Both clients only expose free text, so the key is derived from the message: Transmission sends
 * `No data found…` / `…corrupt` for local failures and `Tracker HTTP response 522` for announce
 * failures, while qBittorrent's local errors arrive through the same `error` string.
 */
internal fun Torrent.errorReasonKey(): String? = when {
    status == TorrentStatus.ERROR -> when {
        error?.contains("No data found", ignoreCase = true) == true -> KEY_LOCAL_NO_DATA
        error?.contains("corrupt", ignoreCase = true) == true -> KEY_LOCAL_CORRUPT
        error?.contains("Verify", ignoreCase = true) == true -> KEY_LOCAL_CORRUPT
        else -> KEY_LOCAL_OTHER
    }
    trackerIssues -> {
        // Group by the daemon's own wording, verbatim: the same sentence is the same cause, and any
        // wording this build did not anticipate still becomes its own row instead of a catch-all.
        val text = error?.trim().orEmpty()
        if (text.isEmpty()) KEY_TRACKER_OTHER else "$KEY_TRACKER_TEXT_PREFIX$text"
    }
    else -> null
}

/** True when [key] is a torrent-level failure rather than a tracker announcement problem. */
internal fun isLocalReasonKey(key: String): Boolean = key.startsWith("local_")

/** The daemon's own wording encoded in a tracker reason key, or null when it sent none. */
internal fun trackerTextOf(key: String): String? =
    key.removePrefix(KEY_TRACKER_TEXT_PREFIX).takeIf { key.startsWith(KEY_TRACKER_TEXT_PREFIX) }
