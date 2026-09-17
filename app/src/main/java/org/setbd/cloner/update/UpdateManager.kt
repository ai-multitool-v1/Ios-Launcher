package org.setbd.cloner.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.setbd.cloner.BuildConfig
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater.
 *
 * Release APKs are hosted as GitHub Releases of the project repository
 * (already the distribution channel — no Play Store involved). The updater:
 *
 *  1. queries `releases/latest` on the GitHub API,
 *  2. compares the release tag with the installed versionName,
 *  3. shows a Material dialog (owned by the UI),
 *  4. downloads the APK with the system [DownloadManager] and hands the
 *     finished file to the standard package-installer intent.
 *
 * Install consent stays with the user and the platform:
 * REQUEST_INSTALL_PACKAGES + the unknown-sources dialog are Android's own
 * gates — nothing here bypasses them.
 */
class UpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionName: String,
        val tagName: String,
        val changelog: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    sealed class CheckResult {
        data object UpToDate : CheckResult()
        data class Available(val update: UpdateInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    suspend fun checkLatest(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "setbd-cloner-updater")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseRelease(body)
        } catch (t: Throwable) {
            ClonerLog.w(TAG, "update check failed", t)
            CheckResult.Error(t.message ?: "network error")
        }
    }

    private fun parseRelease(body: String): CheckResult {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isBlank()) return CheckResult.Error("no published release")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    size = asset.optLong("size")
                    break
                }
            }
        }
        val apk = apkUrl ?: return CheckResult.Error("release has no APK asset")
        val info = UpdateInfo(
            versionName = tag,
            tagName = json.optString("tag_name"),
            changelog = json.optString("body").takeIf { it.isNotBlank() } ?: "New version available.",
            apkUrl = apk,
            sizeBytes = size
        )
        return if (compareVersions(info.versionName, BuildConfig.VERSION_NAME) > 0) {
            CheckResult.Available(info)
        } else {
            CheckResult.UpToDate
        }
    }

    /** Starts a system-managed download of the release APK. */
    fun enqueueDownload(update: UpdateInfo): Long {
        val request = DownloadManager.Request(Uri.parse(update.apkUrl)).apply {
            setTitle("SETBD Cloner ${update.versionName}")
            setDescription("Downloading update…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "SETBD-Cloner-${update.versionName}.apk"
            )
            setMimeType("application/vnd.android.package-archive")
            addRequestHeader("User-Agent", "setbd-cloner-updater")
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        registerInstallReceiver(id)
        return id
    }

    private fun registerInstallReceiver(downloadId: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                context.unregisterReceiver(this)
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = manager.getUriForDownloadedFile(downloadId)
                if (uri != null) {
                    startInstall(uri)
                } else {
                    ClonerLog.w(TAG, "download finished but no file uri")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun startInstall(apkUri: Uri) {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            // Send the user to Android's own install-consent screen first.
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(install) }
            .onFailure { ClonerLog.e(TAG, "install intent failed", it) }
    }

    companion object {
        private const val TAG = "UpdateManager"

        /** Semantic-ish comparison: 1.2.10 > 1.2.9, ignoring leading "v". */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }
    }
}
