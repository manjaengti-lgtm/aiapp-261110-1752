// === ABOUT: In-app updates — finds the newest Duarbagori Reports build on GitHub, downloads it with progress, opens the installer ===
package com.arin.civicreport.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class UpdateInfo(
    val build: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String
)

class Updater(private val context: Context) {

    companion object {
        /** Public releases page; every user can read it without signing in. */
        private const val RELEASES = "https://api.github.com/repos/poisaingti-spec/aiapp-261110-1752/releases?per_page=60"
        private const val TAG_PREFIX = "duarbagori-"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The build number of the app that is running now. */
    val currentBuild: Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    val currentVersionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Returns the newest build if it is newer than this one, or null when up to date. Throws on network errors. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = http.newCall(
            Request.Builder().url(RELEASES)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DuarbagoriReports/$currentBuild")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException(
                if (r.code == 403 || r.code == 429) "Too many checks from this network. Try again in an hour."
                else "Update server error ${r.code}."
            )
            r.body!!.string()
        }
        val newest = Json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.content != "true" }
            .mapNotNull { rel ->
                val tag = rel["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
                val build = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return@mapNotNull null
                val apk = rel["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?: return@mapNotNull null
                UpdateInfo(
                    build = build,
                    apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                    sizeBytes = apk["size"]?.jsonPrimitive?.long ?: -1L,
                    notes = rel["body"]?.jsonPrimitive?.content.orEmpty().trim()
                )
            }
            .maxByOrNull { it.build }
        newest?.takeIf { it.build > currentBuild }
    }

    private fun updatesDir() = File(context.cacheDir, "updates").also { it.mkdirs() }

    fun fileFor(info: UpdateInfo) = File(updatesDir(), "DuarbagoriReports-${info.build}.apk")

    /** Downloads the APK, reporting progress from 0 to 1. Re-uses a finished download. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = fileFor(info)
        if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
            onProgress(1f); return@withContext target
        }
        updatesDir().listFiles()?.forEach { if (it != target) it.delete() }
        val part = File(target.path + ".part")
        part.delete()
        http.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "DuarbagoriReports").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code}).")
            val body = r.body ?: throw IllegalStateException("Empty download.")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var lastShown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastShown) { lastShown = pct; onProgress(done.toFloat() / total) }
                        }
                    }
                }
            }
            if (info.sizeBytes > 0 && part.length() != info.sizeBytes) {
                part.delete(); throw IllegalStateException("Download was incomplete. Please try again.")
            }
        }
        part.renameTo(target)
        onProgress(1f)
        target
    }

    /** Android asks once whether this app may install updates. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens Android's own install screen for the downloaded update. Complaints and settings are kept. */
    fun install(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
