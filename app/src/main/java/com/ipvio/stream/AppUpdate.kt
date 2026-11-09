package com.ipvio.stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val code: Long, val name: String, val apkUrl: String, val notes: String)

/**
 * Aktualisierung aus der App (Nachtrag 17). Die Update-Adresse zeigt auf eine kleine JSON-Datei:
 * {"versionCode": 2, "versionName": "1.1", "apk": "ipvio.apk", "notes": "Was ist neu"}
 * Ohne eingetragene Adresse bleibt die Funktion unsichtbar/inaktiv.
 */
object AppUpdate {
    /** Fest eingebaute Update-Adresse (leer = keine); Nutzer können eine eigene in den Einstellungen eintragen. */
    const val DEFAULT_URL = ""

    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_upd")
    fun url(c: Context) = prefs(c).getString("url", "").orEmpty().ifBlank { DEFAULT_URL }
    fun setUrl(c: Context, v: String) = prefs(c).edit().putString("url", v.trim()).apply()

    fun currentCode(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** Liefert null, wenn die installierte Version aktuell ist. */
    suspend fun check(c: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val u = url(c)
            if (u.isBlank()) error("Keine Update-Adresse eingetragen")
            Net.base.newCall(Request.Builder().url(u).header("User-Agent", "IPVIO Stream AHV").build()).execute().use { r ->
                if (!r.isSuccessful) error("Fehler ${r.code}")
                val j = JSONObject(r.body?.string().orEmpty())
                val code = j.optLong("versionCode", 0L)
                val apk = j.optString("apk")
                if (code <= 0L || apk.isBlank()) error("Update-Datei unvollständig")
                if (!UpdateLogic.isNewer(currentCode(c), code)) null
                else UpdateInfo(code, j.optString("versionName", code.toString()), UpdateLogic.resolve(u, apk), j.optString("notes"))
            }
        }
    }

    /** Lädt die APK in den Zwischenspeicher; [onProgress] liefert Prozent (0..100) oder -1 bei unbekannter Größe. */
    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "ipvio-update.apk")
            val client = Net.withTimeouts(15, 60)
            client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "IPVIO Stream AHV").build()).execute().use { r ->
                if (!r.isSuccessful) error("Fehler ${r.code}")
                val body = r.body ?: error("Leere Antwort")
                val total = body.contentLength()
                var done = 0L
                var last = -2
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            out
        }
    }

    /** Öffnet den System-Installer. Fehlt die Erlaubnis „Apps aus unbekannten Quellen“, öffnet sich stattdessen deren Einstellung. */
    fun install(c: Context, apk: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", apk)
        return runCatching {
            c.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }
}
