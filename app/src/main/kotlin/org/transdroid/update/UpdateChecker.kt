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
package org.transdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.transdroid.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The update manifest published at the repository root; bumped for every release. */
@Serializable
data class UpdateInfo(
    val versionCode: Long = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val notes: String = "",
)

/**
 * Checks the project's update manifest and hands a downloaded APK to the system installer.
 *
 * The manifest is a small JSON file at the repository root, so publishing a release only means
 * bumping `versionCode` there. Two mirrors are consulted: raw GitHub is uncached and therefore
 * authoritative, jsDelivr is the fallback for networks where raw is blocked. jsDelivr serves
 * `@main` through a CDN cache that can lag a release by hours, so every reachable mirror is
 * compared and the highest `versionCode` wins rather than whichever happened to answer first.
 */
object UpdateChecker {

    private val manifestUrls = listOf(
        "https://raw.githubusercontent.com/lingxishi/transdroid3-mod/main/update.json",
        "https://cdn.jsdelivr.net/gh/lingxishi/transdroid3-mod@main/update.json",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the newer release, null when this build is already current, and throws [IOException]
     * when no mirror could be reached.
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        var reachable = false
        var best: UpdateInfo? = null
        for (url in manifestUrls) {
            val body = try {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (body == null) continue
            reachable = true
            val info = try {
                json.decodeFromString<UpdateInfo>(body)
            } catch (e: Exception) {
                continue
            }
            // Keep scanning instead of returning here: a cached mirror can report an older
            // version than the release that actually shipped, and the newer one must win.
            if (info.versionCode > BuildConfig.VERSION_CODE &&
                info.apkUrl.isNotBlank() &&
                (best == null || info.versionCode > best.versionCode)
            ) {
                best = info
            }
        }
        if (!reachable) throw IOException("no update manifest reachable")
        best
    }

    /** Downloads the APK into the cache dir; [onProgress] receives 0..100. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "transdroid-${info.versionName}.apk")
        val request = Request.Builder().url(info.apkUrl).build()
        var expectedTotal = 0L
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response body")
            val total = body.contentLength()
            expectedTotal = total
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        if (target.length() <= 0) throw IOException("downloaded file is empty")
        // A truncated body or an HTML error page saved as .apk makes the installer fail silently,
        // which looks exactly like "tapped allow and nothing happened".
        if (expectedTotal > 0 && target.length() != expectedTotal) {
            val got = target.length()
            target.delete()
            throw IOException("incomplete download: $got of $expectedTotal bytes")
        }
        val magic = ByteArray(2)
        target.inputStream().use { it.read(magic) }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            target.delete()
            throw IOException("downloaded file is not an APK")
        }
        target
    }

    /** True when Android still needs the user to allow installs from this app. */
    fun needsInstallPermission(context: Context): Boolean =
        !context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // Settings screen unavailable on some ROMs; the install intent will surface it instead.
        }
    }

    /** Hands a downloaded APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
