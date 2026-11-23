package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.addonTransportBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Sends selected embedded subtitle batches through the installed Double Subtitles addon. */
internal object DoubleSubtitlesLocalTranslator {
    private const val TAG = "NuvioDoubleSubs"
    private const val ADDON_ID = "community.doublesubtitles"
    private const val MAX_BATCH_CUES = 50
    private const val MAX_BATCH_CHARS = 9_000
    private const val CACHE_DIRECTORY = "embedded_learning_subtitles"
    private data class CachedSubtitle(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val originalText: String,
        val translatedText: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun isSourceLanguageAllowed(context: Context, sourceLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedLanguage = normalizeLanguageCode(sourceLanguage) ?: return@withContext false
            AddonRepository.initialize()
            val state = AddonRepository.uiState.first { current ->
                current.addons.any { it.manifest?.id == ADDON_ID } ||
                    current.addons.none { it.isRefreshing }
            }
            val addon = state.addons.firstOrNull { it.enabled && it.manifest?.id == ADDON_ID }
                ?: return@withContext false
            matchesConfiguredSourceLanguage(addon.manifestUrl, normalizedLanguage)
        }

    suspend fun translate(
        context: Context,
        trackCacheKey: String,
        cues: List<LearningSubtitleCue>,
        priorityPositionMs: Long? = null,
        onBatchTranslated: suspend (List<LearningSubtitleCue>) -> Unit = {},
        requiredSourceLanguage: String? = null,
    ): List<LearningSubtitleCue> = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) return@withContext cues
        // Ensure the persisted addon list and its manifests have had a chance to load
        // before deciding that the user has not installed/enabled Double Subtitles.
        AddonRepository.initialize()
        val addonsState = AddonRepository.uiState.first { state ->
            state.addons.any { it.manifest?.id == ADDON_ID } ||
                state.addons.none { it.isRefreshing }
        }
        val addon = addonsState.addons.firstOrNull { managed ->
            managed.enabled && managed.manifest?.id == ADDON_ID
        }
        if (addon == null) {
            val installed = addonsState.addons.any { it.manifest?.id == ADDON_ID }
            Log.w(TAG, "Translation skipped: Double Subtitles addon ${if (installed) "is disabled" else "is not installed or its manifest failed to load"}")
            return@withContext cues
        }
        if (requiredSourceLanguage != null &&
            !matchesConfiguredSourceLanguage(addon.manifestUrl, requiredSourceLanguage)
        ) {
            Log.i(TAG, "Translation skipped: selected embedded subtitle language does not match the addon's configured source language")
            return@withContext cues
        }
        val manifestUrl = addon.manifestUrl
        Log.i(TAG, "Double Subtitles addon is enabled; starting embedded translation batch (${cues.size} cues)")
        val cleanManifestUrl = manifestUrl.substringBefore("?")
        if (!cleanManifestUrl.startsWith("http://", true) && !cleanManifestUrl.startsWith("https://", true)) return@withContext cues
        val baseUrl = addonTransportBaseUrl(cleanManifestUrl)
        if (baseUrl.isBlank()) return@withContext cues
        val query = manifestUrl.substringAfter("?", "")
        val endpoint = "$baseUrl/api/v1/translate" + if (query.isBlank()) "" else "?$query"
        // One track identity owns one JSON file beside its original ID.bin.
        // Endpoint hashes are buckets inside that JSON, keeping addon configurations isolated.
        val configId = sha256(endpoint)
        val cacheFile = cacheFile(context, trackCacheKey)
        val legacyTrackFile = legacyCacheFile(context, trackCacheKey, endpoint)
        val legacyEndpointFile = legacyEndpointCacheFile(context, endpoint)
        val currentCueIds = cues.mapTo(hashSetOf()) { cueIdentity(it) }
        val legacyTrackTranslations = readCache(legacyTrackFile, configId)
        val legacyEndpointTranslations = readCache(legacyEndpointFile, configId)
            .filterKeys { it in currentCueIds }
        val legacyTranslations = legacyTrackTranslations + legacyEndpointTranslations
        val translations = readCache(cacheFile, configId).toMutableMap().apply {
            legacyTranslations.forEach { (id, value) -> putIfAbsent(id, value) }
        }
        if (legacyTranslations.isNotEmpty()) {
            writeCache(cacheFile, translations, configId)
            if (legacyTrackFile.isFile &&
                readCache(cacheFile, configId).keys.containsAll(legacyTrackTranslations.keys)
            ) legacyTrackFile.delete()
        }
        // Upgrade legacy translation-only entries with the original cue data whenever available.
        var upgradedLegacyEntries = false
        cues.forEach { cue ->
            val id = cueIdentity(cue)
            val cached = translations[id]
            if (cached != null && cached.originalText.isBlank()) {
                translations[id] = cached.copy(
                    startTimeMs = cue.startTimeMs,
                    endTimeMs = cue.endTimeMs,
                    originalText = cue.text,
                )
                upgradedLegacyEntries = true
            }
        }
        if (upgradedLegacyEntries) writeCache(cacheFile, translations, configId)
        Log.i(TAG, "Translation endpoint prepared; cached subtitle pairs=${translations.size}")

        // Restore cache hits immediately, before constructing or sending any translation
        // requests. Cached cues are excluded from the missing list below, so they are never
        // sent to the translation API again.
        val cachedBatch = cues.mapNotNull { cue ->
            if (!cue.translationText.isNullOrBlank()) return@mapNotNull null
            translations[cueIdentity(cue)]?.translatedText
                ?.takeIf(String::isNotBlank)
                ?.let { cue.copy(translationText = it) }
        }
        if (cachedBatch.isNotEmpty()) {
            Log.i(TAG, "Restored ${cachedBatch.size} subtitle translations from local cache")
            onBatchTranslated(cachedBatch)
        }

        val missing = cues.filter { cue -> cue.text.isNotBlank() && translations[cueIdentity(cue)]?.translatedText.isNullOrBlank() && cue.translationText.isNullOrBlank() }
        // Manual translation starts at the cue currently on screen, then moves forward
        // through the remaining timeline. Earlier untranslated cues are backfilled last.
        // Automatic/background translation keeps its existing source order when no priority is supplied.
        val prioritizedMissing = priorityPositionMs?.let { positionMs ->
            val active = missing.filter { it.startTimeMs <= positionMs && it.endTimeMs > positionMs }
                .sortedByDescending { it.startTimeMs }
            val upcoming = missing.filter { it.startTimeMs > positionMs }
                .sortedBy { it.startTimeMs }
            val earlier = missing.filter { it.endTimeMs <= positionMs }
                .sortedByDescending { it.startTimeMs }
            active + upcoming + earlier
        } ?: missing
        val chunks = mutableListOf<List<LearningSubtitleCue>>()
        var current = mutableListOf<LearningSubtitleCue>()
        var currentChars = 0
        prioritizedMissing.forEach { cue ->
            val length = cue.text.length
            if (length > MAX_BATCH_CHARS) {
                Log.w(TAG, "Skipping oversized embedded subtitle cue")
            } else {
                if (current.isNotEmpty() && (current.size >= MAX_BATCH_CUES || currentChars + length > MAX_BATCH_CHARS)) {
                    chunks += current
                    current = mutableListOf()
                    currentChars = 0
                }
                current += cue
                currentChars += length
            }
        }
        if (current.isNotEmpty()) chunks += current
        chunks.forEachIndexed { chunkIndex, chunk ->
            runCatching {
                val requestCues = JSONArray()
                chunk.forEach { cue ->
                    requestCues.put(JSONObject().put("id", cueIdentity(cue)).put("start", cue.startTimeMs).put("end", cue.endTimeMs).put("text", cue.text))
                }
                val batchId = sha256("$trackCacheKey|$chunkIndex|${chunk.joinToString("|") { cueIdentity(it) }}").take(32)
                val requestBody = JSONObject().put("batchId", batchId).put("subtitles", requestCues).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(endpoint).header("Accept", "application/json").post(requestBody).build()
                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Double Subtitles returned HTTP ${response.code}")
                    val payload = JSONObject(responseText)
                    if (!payload.optBoolean("success", false)) error(payload.optString("error").ifBlank { "Translation request failed" })
                    val result = payload.optJSONArray("translations") ?: JSONArray()
                    var translatedInResponse = 0
                    for (index in 0 until result.length()) {
                        val item = result.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        val translatedText = item.optString("text").trim()
                        if (id.isNotBlank() && translatedText.isNotBlank()) {
                            val sourceCue = chunk.firstOrNull { cueIdentity(it) == id }
                            if (sourceCue != null) {
                                translations[id] = CachedSubtitle(
                                    startTimeMs = sourceCue.startTimeMs,
                                    endTimeMs = sourceCue.endTimeMs,
                                    originalText = sourceCue.text,
                                    translatedText = translatedText,
                                )
                                translatedInResponse++
                            }
                        }
                    }
                    if (translatedInResponse == 0) {
                        error("Translation API returned success but no usable translated cues")
                    }
                    Log.i(TAG, "Translation API returned $translatedInResponse translated cues")
                }
                writeCache(cacheFile, translations, configId)
                val translatedBatch = chunk.mapNotNull { cue ->
                    translations[cueIdentity(cue)]?.translatedText?.takeIf(String::isNotBlank)?.let { cue.copy(translationText = it) }
                }
                if (translatedBatch.isNotEmpty()) onBatchTranslated(translatedBatch)
                Log.i(TAG, "Translated embedded batch: cues=${chunk.size}")
            }.onFailure { error ->
                Log.e(TAG, "Embedded subtitle batch translation failed: ${error.message}", error)
            }
        }
        cues.map { cue ->
            val translated = translations[cueIdentity(cue)]?.translatedText ?: cue.translationText
            cue.copy(translationText = translated?.takeIf(String::isNotBlank))
        }
    }

    /** Restores only previously translated subtitle pairs; never persists raw subtitle cues. */
    suspend fun restoreCachedTranslations(
        context: Context,
        trackCacheKey: String,
        cues: List<LearningSubtitleCue>,
        requiredSourceLanguage: String? = null,
    ): List<LearningSubtitleCue> = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) return@withContext cues
        AddonRepository.initialize()
        val addonsState = AddonRepository.uiState.first { state ->
            state.addons.any { it.manifest?.id == ADDON_ID } ||
                state.addons.none { it.isRefreshing }
        }
        val addon = addonsState.addons.firstOrNull { managed ->
            managed.enabled && managed.manifest?.id == ADDON_ID
        } ?: return@withContext cues
        if (requiredSourceLanguage != null &&
            !matchesConfiguredSourceLanguage(addon.manifestUrl, requiredSourceLanguage)
        ) {
            Log.i(TAG, "Translation cache restore skipped: selected embedded subtitle language does not match the addon's configured source language")
            return@withContext cues
        }
        val manifestUrl = addon.manifestUrl
        val cleanManifestUrl = manifestUrl.substringBefore("?")
        if (!cleanManifestUrl.startsWith("http://", true) &&
            !cleanManifestUrl.startsWith("https://", true)
        ) return@withContext cues
        val baseUrl = addonTransportBaseUrl(cleanManifestUrl)
        if (baseUrl.isBlank()) return@withContext cues
        val query = manifestUrl.substringAfter("?", "")
        val endpoint = "$baseUrl/api/v1/translate" + if (query.isBlank()) "" else "?$query"
        val configId = sha256(endpoint)
        val cacheFile = cacheFile(context, trackCacheKey)
        val legacyTrackFile = legacyCacheFile(context, trackCacheKey, endpoint)
        val legacyEndpointFile = legacyEndpointCacheFile(context, endpoint)
        val currentCueIds = cues.mapTo(hashSetOf()) { cueIdentity(it) }
        val legacyTranslations = (readCache(legacyTrackFile, configId) +
            readCache(legacyEndpointFile, configId))
            .filterKeys { it in currentCueIds }
        val cache = readCache(cacheFile, configId).toMutableMap().apply {
            legacyTranslations.forEach { (id, value) -> putIfAbsent(id, value) }
        }
        if (cache.isEmpty()) return@withContext cues
        if (legacyTranslations.isNotEmpty()) {
            writeCache(cacheFile, cache, configId)
            if (legacyTrackFile.isFile) legacyTrackFile.delete()
        }

        var upgradedLegacyEntries = false
        val restored = cues.map { cue ->
            if (!cue.translationText.isNullOrBlank()) return@map cue
            val cached = cache[cueIdentity(cue)] ?: return@map cue
            if (cached.originalText.isBlank()) upgradedLegacyEntries = true
            cue.copy(translationText = cached.translatedText)
        }
        if (upgradedLegacyEntries) {
            val upgraded = cache.toMutableMap()
            cues.forEach { cue ->
                val id = cueIdentity(cue)
                val cached = upgraded[id] ?: return@forEach
                if (cached.originalText.isBlank()) {
                    upgraded[id] = cached.copy(
                        startTimeMs = cue.startTimeMs,
                        endTimeMs = cue.endTimeMs,
                        originalText = cue.text,
                    )
                }
            }
            writeCache(cacheFile, upgraded, configId)
        }
        val restoredCount = restored.count { !it.translationText.isNullOrBlank() }
        if (restoredCount > 0) Log.i(TAG, "Restored $restoredCount translated external subtitle cues from cache")
        restored
    }

    private fun cacheFile(context: Context, trackCacheKey: String): File {
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        val id = trackCacheKey.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } ?: sha256(trackCacheKey)
        return File(directory, "$id.json")
    }

    private fun legacyCacheFile(context: Context, trackCacheKey: String, endpoint: String): File {
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "translation_${sha256("$trackCacheKey|$endpoint")}.json")
    }

    private fun legacyEndpointCacheFile(context: Context, endpoint: String): File {
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "translation_endpoint_${sha256(endpoint)}.json")
    }

    private fun readCache(file: File, configId: String): Map<String, CachedSubtitle> = runCatching {
        if (!file.isFile) return@runCatching emptyMap()
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val buckets = json.optJSONObject("translations")
        if (buckets != null && !buckets.has(configId)) return@runCatching emptyMap()
        val entries = buckets?.optJSONObject(configId)?.optJSONObject("cues")
            ?: json.optJSONObject("cues")
        if (entries != null) {
            buildMap {
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = entries.optJSONObject(key) ?: continue
                    val translated = item.optString("translatedText").takeIf(String::isNotBlank) ?: continue
                    put(key, CachedSubtitle(
                        startTimeMs = item.optLong("startTimeMs"),
                        endTimeMs = item.optLong("endTimeMs"),
                        originalText = item.optString("originalText"),
                        translatedText = translated,
                    ))
                }
            }
        } else {
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    json.optString(key).takeIf(String::isNotBlank)?.let { translated ->
                        put(key, CachedSubtitle(0L, 0L, "", translated))
                    }
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(file: File, values: Map<String, CachedSubtitle>, configId: String) {
        runCatching {
            synchronized(file.absolutePath.intern()) {
                val root = if (file.isFile) runCatching {
                    JSONObject(file.readText(Charsets.UTF_8))
                }.getOrDefault(JSONObject()) else JSONObject()
                val buckets = root.optJSONObject("translations") ?: JSONObject()
                val merged = readCache(file, configId).toMutableMap().apply { putAll(values) }
                val entries = JSONObject()
                merged.forEach { (key, value) ->
                    entries.put(key, JSONObject()
                        .put("startTimeMs", value.startTimeMs)
                        .put("endTimeMs", value.endTimeMs)
                        .put("originalText", value.originalText)
                        .put("translatedText", value.translatedText))
                }
                buckets.put(configId, JSONObject().put("cues", entries))
                val json = JSONObject()
                    .put("version", 3)
                    .put("translations", buckets)
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(json.toString(), Charsets.UTF_8)
                if (file.exists() && !file.delete()) error("Unable to replace translation cache")
                if (!temporary.renameTo(file)) error("Unable to finalize translation cache")
            }
        }.onFailure { Log.w(TAG, "Unable to persist embedded subtitle cache", it) }
    }

    /**
     * Double Subtitles encodes its source/target language pair in the configured manifest path,
     * for example /configure/en/zh-CN/manifest.json. Embedded tracks are translated only when
     * their declared language matches that configured source language.
     */
    private fun matchesConfiguredSourceLanguage(manifestUrl: String, subtitleLanguage: String): Boolean {
        val pathSegments = runCatching {
            URI(manifestUrl).path.orEmpty().split('/').filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val configureIndex = pathSegments.indexOfLast { it.equals("configure", ignoreCase = true) }
        val configuredSource = pathSegments.getOrNull(configureIndex + 1) ?: return false
        val configuredCode = normalizeLanguageCode(configuredSource) ?: return false
        val subtitleCode = normalizeLanguageCode(subtitleLanguage) ?: return false
        return configuredCode == subtitleCode
    }

    /** Normalizes ISO-639-1 and ISO-639-2 track tags (en/eng, zh/zho/chi, etc.). */
    private fun normalizeLanguageCode(value: String): String? {
        val tag = value.trim().replace('_', '-')
        if (tag.isBlank()) return null
        val primary = tag.substringBefore('-').lowercase()
        if (primary.isBlank() || primary == "und") return null
        if (primary.length == 2) return primary
        if (primary.length == 3) {
            return Locale.getAvailableLocales()
                .firstOrNull { locale ->
                    runCatching { locale.isO3Language.equals(primary, ignoreCase = true) }
                        .getOrDefault(false)
                }
                ?.language
                ?.lowercase()
                ?: primary
        }
        return Locale.forLanguageTag(tag).language
            .takeIf { it.isNotBlank() && it != "und" }
            ?.lowercase()
    }

    private fun cueIdentity(cue: LearningSubtitleCue): String = sha256("${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}