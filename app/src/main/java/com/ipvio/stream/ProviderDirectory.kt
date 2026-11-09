package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Remote provider choices for the customer-facing Xtream login. */
data class ProviderTemplate(val id: String, val displayName: String, val serverUrl: String)

/** A last-good, locally cached service directory. Credentials are never sent to this directory. */
object ProviderDirectory {
    const val CONFIG_URL = "https://astrahosting.xyz/streamvault/config.json"
    private const val PREFS = "provider_directory"
    private const val KEY_JSON = "last_good_json"
    private const val KEY_FETCHED = "fetched_at"
    private const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
    private val http = Net.withTimeouts(10, 15)
    @Volatile private var lastSnapshot: Snapshot? = null

    /** Thread-safe in-memory snapshot for the local phone form; no Context or network needed per request. */
    fun cachedForPhoneImport(): List<ProviderTemplate> = lastSnapshot?.services.orEmpty()

    fun remember(snapshot: Snapshot?) { if (snapshot != null) lastSnapshot = snapshot }

    data class Snapshot(val version: Long, val ttlSeconds: Long, val services: List<ProviderTemplate>)
    data class Result(val snapshot: Snapshot?, val usingCache: Boolean, val error: String? = null)

    fun cached(context: Context): Snapshot? {
        val snapshot = parse(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null))
        if (snapshot != null) lastSnapshot = snapshot
        return snapshot
    }

    fun cachedAgeMs(context: Context): Long? {
        val fetchedAt = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FETCHED, 0L)
        return if (fetchedAt > 0L) (System.currentTimeMillis() - fetchedAt).coerceAtLeast(0L) else null
    }

    fun isFresh(context: Context, snapshot: Snapshot): Boolean {
        val age = cachedAgeMs(context) ?: return false
        val ttl = (snapshot.ttlSeconds * 1000L).coerceIn(60_000L, MAX_TTL_MS)
        return age < ttl
    }

    suspend fun refresh(context: Context): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cached = cached(appContext)
        if (cached != null && isFresh(appContext, cached)) return@withContext Result(cached, true)
        try {
            val request = Request.Builder().url(CONFIG_URL).header("Accept", "application/json").header("User-Agent", "IPVIOStream/1.0").build()
            val fresh = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                parse(response.body?.string() ?: throw java.io.IOException("Empty config response"))
                    ?: throw java.io.IOException("Invalid provider config")
            }
            val json = JSONObject()
                .put("version", fresh.version)
                .put("ttl_seconds", fresh.ttlSeconds)
                .put("services", org.json.JSONArray().also { array ->
                    fresh.services.forEach { item -> array.put(JSONObject().put("id", item.id).put("display_name", item.displayName).put("server_url", item.serverUrl)) }
                }).toString()
                .toString()
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, json)
                .putLong(KEY_FETCHED, System.currentTimeMillis())
                .apply()
            lastSnapshot = fresh
            Result(fresh, false)
        } catch (e: Exception) {
            if (cached != null) lastSnapshot = cached
            Result(cached, cached != null, e.message ?: "Network error")
        }
    }

    fun parse(raw: String?): Snapshot? {
        if (raw.isNullOrBlank() || raw.length > 512 * 1024) return null
        return try {
        val root = JSONObject(raw)
        val version = root.optLong("version", -1L)
        val ttl = root.optLong("ttl_seconds", 3600L).coerceIn(60L, 86_400L)
        val array = root.optJSONArray("services") ?: return null
        val services = ArrayList<ProviderTemplate>(array.length())
        val ids = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("display_name").trim()
            val url = item.optString("server_url").trim().trimEnd('/')
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || name.isBlank() || name.length > 100) continue
            val match = Regex("""^https://([^\s/?#:@]+)(?::\d{1,5})?$""", RegexOption.IGNORE_CASE).matchEntire(url) ?: continue
            val host = match.groupValues[1]
            if (host.isBlank() || host.contains('_') || !ids.add(id)) continue
            services.add(ProviderTemplate(id, name, url))
        }
        if (version < 0 || services.isEmpty()) null else Snapshot(version, ttl, services)
        } catch (_: Exception) { null }
    }
}
