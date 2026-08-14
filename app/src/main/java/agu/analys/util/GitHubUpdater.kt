package agu.analys.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import agu.analys.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkName: String
)

object GitHubUpdater {
    const val DEFAULT_REPO = "agus27712/analisa-pasar"

    suspend fun fetchLatestRelease(repo: String): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val normalizedRepo = normalizeRepo(repo)
            val url = URL("https://api.github.com/repos/$normalizedRepo/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AnalisaPasar/${BuildConfig.VERSION_NAME}")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseString)
            val tagName = json.optString("tag_name", "").trim()
            val latestVersion = normalizeVersion(tagName)
            val currentVersion = normalizeVersion(BuildConfig.VERSION_NAME)
            if (latestVersion.isEmpty() || compareVersions(latestVersion, currentVersion) <= 0) {
                return@withContext null
            }

            val body = json.optString("body", "Pembaruan tersedia.")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl = ""
            var apkName = "update.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val browserUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && browserUrl.isNotBlank()) {
                    apkName = name
                    apkUrl = browserUrl
                    break
                }
            }
            if (apkUrl.isBlank()) return@withContext null
            GitHubReleaseInfo(tagName, body, apkUrl, apkName)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        apkName: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            require(apkUrl.startsWith("https://", ignoreCase = true))
            val url = URL(apkUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AnalisaPasar/${BuildConfig.VERSION_NAME}")
                connect()
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("Download APK gagal: HTTP ${connection.responseCode}")

            val fileLength = connection.contentLengthLong
            val downloadDir = context.getExternalFilesDir(null) ?: context.cacheDir
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val safeName = apkName.substringAfterLast('/').ifBlank { "analisa-pasar-update.apk" }
            val apkFile = File(downloadDir, safeName)

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val data = ByteArray(16 * 1024)
                    var total = 0L
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(data)
                        if (count < 0) break
                        total += count
                        output.write(data, 0, count)
                        if (fileLength > 0) {
                            val progress = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                    output.flush()
                    if (total <= 0L) throw IllegalStateException("APK kosong")
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                installApk(context, apkFile)
            }
            true
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { onProgress(-1) }
            false
        }
    }

    fun installApk(context: Context, file: File) {
        if (!file.exists() || file.length() <= 0L) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Installer failure is surfaced through the existing download progress state.
        }
    }

    private fun normalizeRepo(repo: String): String {
        val value = repo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        if (value.isBlank() || value == "user/nama-repo") return DEFAULT_REPO
        return if (value.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) value else DEFAULT_REPO
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
