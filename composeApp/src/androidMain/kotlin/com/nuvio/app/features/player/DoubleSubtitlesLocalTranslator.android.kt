package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.addonTransportBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Keep client batches aligned with the addon's Google batch envelope.
    private const val MAX_BATCH_CUES = 128
    private const val MAX_BATCH_CHARS = 5_000
    private const val PRIORITY_AHEAD_MS = 60_000L
    private const val CACHE_DIRECTORY = "embedded_learning_subtitles"
    private class TranslationHttpException(val statusCode: Int, message: String) : Exception(message)

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

    /** Reads the target language from /configure/{source}/{target}/manifest.json. */
    suspend fun configuredTargetLanguage(context: Context): String? = withContext(Dispatchers.IO) {
        AddonRepository.initialize()
        val state = AddonRepository.uiState.first { current ->
            current.addons.any { it.manifest?.id == ADDON_ID } || current.addons.none { it.isRefreshing }
        }
        val addon = state.addons.firstOrNull { it.enabled && it.manifest?.id == ADDON_ID }
            ?: return@withContext null
        val segments = runCatching {
            URI(addon.manifestUrl.substringBefore("?")).path.orEmpty().split('/').filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val index = segments.indexOfLast { it.equals("configure", true) }
        segments.getOrNull(index + 2)?.takeIf { normalizeLanguageCode(it) != null }
    }

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
            matchesConfiguredSourceLanguage(addon.manifestUrl, sourceLanguage)
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
            Log.i(TAG, "Translation skipped: selected subtitle language does not match the addon's configured first language")
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

        val missing = cues.filter { cue ->
            cue.text.isNotBlank() &&
                translations[cueIdentity(cue)]?.translatedText.isNullOrBlank() &&
                cue.translationText.isNullOrBlank()
        }
        val positionMs = priorityPositionMs
        val activeCue = positionMs?.let { position ->
            missing.filter { it.startTimeMs <= position && it.endTimeMs > position }
                .maxByOrNull { it.startTimeMs }
        }
        val firstMinute = if (positionMs != null) missing.filter { cue ->
            cue !== activeCue && cue.startTimeMs > positionMs &&
                cue.startTimeMs <= positionMs + PRIORITY_AHEAD_MS
        }.sortedBy { it.startTimeMs } else emptyList()
        val backgroundWindow = if (positionMs != null) missing.filter { cue ->
            cue !== activeCue && cue.startTimeMs > positionMs + PRIORITY_AHEAD_MS &&
                cue.startTimeMs <= positionMs + 5 * 60_000L
        }.sortedBy { it.startTimeMs } else emptyList()

        fun buildChunks(source: List<LearningSubtitleCue>): List<List<LearningSubtitleCue>> {
            val result = mutableListOf<List<LearningSubtitleCue>>()
            var current = mutableListOf<LearningSubtitleCue>()
            var currentChars = 0
            source.forEach { cue ->
                val length = cue.text.length
                if (length > MAX_BATCH_CHARS) {
                    if (current.isNotEmpty()) {
                        result += current
                        current = mutableListOf()
                        currentChars = 0
                    }
                    result += listOf(cue)
                    Log.w(TAG, "Sending oversized embedded subtitle cue in its own request: chars=$length")
                } else {
                    if (current.isNotEmpty() &&
                        (current.size >= MAX_BATCH_CUES || currentChars + length > MAX_BATCH_CHARS)
                    ) {
                        result += current
                        current = mutableListOf()
                        currentChars = 0
                    }
                    current += cue
                    currentChars += length
                }
            }
            if (current.isNotEmpty()) result += current
            return result
        }

        val phaseOneBatches = if (positionMs == null) {
            // Keep callers without a playback position compatible: translate their full cue list.
            buildChunks(missing)
        } else {
            buildList {
                activeCue?.let { add(listOf(it)) }
                addAll(buildChunks(firstMinute))
            }
        }
        val phaseTwoBatches = buildChunks(backgroundWindow)
        val resultMutex = Mutex()
        val scheduledCount = phaseOneBatches.sumOf { it.size } + phaseTwoBatches.sumOf { it.size }
        Log.i(TAG, "Translation schedule: currentCue=${activeCue != null} phase1Batches=${phaseOneBatches.size} " +
            "phase1Cues=${phaseOneBatches.sumOf { it.size }} phase2Batches=${phaseTwoBatches.size} " +
            "phase2Cues=${phaseTwoBatches.sumOf { it.size }} deferredCues=${missing.size - scheduledCount}")

        var throttleDelayMs = 0L
        var consecutiveRateLimits = 0

        suspend fun processBatch(
            chunk: List<LearningSubtitleCue>,
            phase: String,
            chunkIndex: Int,
            totalChunks: Int,
        ) {
            while (true) {
                val batchStartedAt = System.nanoTime()
                try {
                    val requestCues = JSONArray()
                    chunk.forEach { cue ->
                        requestCues.put(JSONObject().put("id", cueIdentity(cue))
                            .put("start", cue.startTimeMs).put("end", cue.endTimeMs).put("text", cue.text))
                    }
                    val batchId = sha256("$trackCacheKey|$phase|$chunkIndex|" +
                        chunk.joinToString("|") { cueIdentity(it) }).take(32)
                    val requestBody = JSONObject().put("batchId", batchId).put("subtitles", requestCues)
                        .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(endpoint).header("Accept", "application/json")
                        .post(requestBody).build()
                    val batchTranslations = linkedMapOf<String, CachedSubtitle>()
                    var translatedInResponse = 0
                    client.newCall(request).execute().use { response ->
                        val responseText = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw TranslationHttpException(response.code,
                                "Double Subtitles returned HTTP ${response.code}: $responseText")
                        }
                        val payload = JSONObject(responseText)
                        if (!payload.optBoolean("success", false)) {
                            error(payload.optString("error").ifBlank { "Translation request failed" })
                        }
                        val result = payload.optJSONArray("translations") ?: JSONArray()
                        for (index in 0 until result.length()) {
                            val item = result.optJSONObject(index) ?: continue
                            val id = item.optString("id")
                            val translatedText = item.optString("text").trim()
                            if (id.isNotBlank() && translatedText.isNotBlank()) {
                                val sourceCue = chunk.firstOrNull { cueIdentity(it) == id }
                                if (sourceCue != null) {
                                    batchTranslations[id] = CachedSubtitle(sourceCue.startTimeMs,
                                        sourceCue.endTimeMs, sourceCue.text, translatedText)
                                    translatedInResponse++
                                }
                            }
                        }
                        if (translatedInResponse == 0) error("Translation API returned success but no usable translated cues")
                    }
                    resultMutex.withLock {
                        translations.putAll(batchTranslations)
                        writeCache(cacheFile, translations.toMap(), configId)
                        val translatedBatch = chunk.mapNotNull { cue ->
                            translations[cueIdentity(cue)]?.translatedText?.takeIf(String::isNotBlank)
                                ?.let { cue.copy(translationText = it) }
                        }
                        if (translatedBatch.isNotEmpty()) onBatchTranslated(translatedBatch)
                    }
                    val elapsedMs = (System.nanoTime() - batchStartedAt) / 1_000_000
                    Log.i(TAG, "Translated embedded batch: phase=$phase index=${chunkIndex + 1}/$totalChunks " +
                        "cues=${chunk.size} translated=$translatedInResponse elapsedMs=$elapsedMs")
                    if (consecutiveRateLimits > 0) Log.i(TAG, "Translation rate limit recovered; resetting batch delay")
                    consecutiveRateLimits = 0
                    throttleDelayMs = 0L
                    return
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val elapsedMs = (System.nanoTime() - batchStartedAt) / 1_000_000
                    val statusCode = (error as? TranslationHttpException)?.statusCode
                    if (statusCode == 403 || statusCode == 429) {
                        consecutiveRateLimits++
                        throttleDelayMs = if (throttleDelayMs == 0L) 3_000L
                            else (throttleDelayMs * 2).coerceAtMost(60_000L)
                        Log.w(TAG, "Translation rate limited: phase=$phase index=${chunkIndex + 1}/$totalChunks " +
                            "http=$statusCode consecutive=$consecutiveRateLimits retryInMs=$throttleDelayMs elapsedMs=$elapsedMs")
                        kotlinx.coroutines.delay(throttleDelayMs)
                        continue
                    }
                    Log.e(TAG, "Embedded subtitle batch failed: phase=$phase index=${chunkIndex + 1}/$totalChunks " +
                        "cues=${chunk.size} elapsedMs=$elapsedMs error=${error.message}", error)
                    return
                }
            }
        }

        phaseOneBatches.forEachIndexed { index, batch ->
            processBatch(batch, "priority", index, phaseOneBatches.size)
        }
        phaseTwoBatches.forEachIndexed { index, batch ->
            processBatch(batch, "background", index, phaseTwoBatches.size)
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
        } ?: run {
            Log.w(TAG, "Cache restore stopped: addon missing or disabled; trackKeyHash=${sha256(trackCacheKey)} cues=${cues.size}")
            return@withContext cues
        }
        if (requiredSourceLanguage != null &&
            !matchesConfiguredSourceLanguage(addon.manifestUrl, requiredSourceLanguage)
        ) {
            Log.w(TAG, "Cache restore stopped: source-language mismatch; selected=${requiredSourceLanguage} trackKeyHash=${sha256(trackCacheKey)}")
            return@withContext cues
        }
        val manifestUrl = addon.manifestUrl
        val cleanManifestUrl = manifestUrl.substringBefore("?")
        if (!cleanManifestUrl.startsWith("http://", true) &&
            !cleanManifestUrl.startsWith("https://", true)
        ) {
            Log.w(TAG, "Cache restore stopped: invalid addon manifest URL")
            return@withContext cues
        }
        val baseUrl = addonTransportBaseUrl(cleanManifestUrl)
        if (baseUrl.isBlank()) {
            Log.w(TAG, "Cache restore stopped: addon transport base URL is blank")
            return@withContext cues
        }
        val query = manifestUrl.substringAfter("?", "")
        val endpoint = "$baseUrl/api/v1/translate" + if (query.isBlank()) "" else "?$query"
        val configId = sha256(endpoint)
        val cacheFile = cacheFile(context, trackCacheKey)
        val legacyTrackFile = legacyCacheFile(context, trackCacheKey, endpoint)
        val legacyEndpointFile = legacyEndpointCacheFile(context, endpoint)
        val currentCueIds = cues.mapTo(hashSetOf()) { cueIdentity(it) }
        val fileSummary = runCatching {
            if (!cacheFile.isFile) "missing"
            else {
                val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
                val buckets = root.optJSONObject("translations")
                val bucketExists = buckets?.has(configId) == true
                val bucketCueCount = buckets?.optJSONObject(configId)
                    ?.optJSONObject("cues")?.length() ?: 0
                "exists bytes=${cacheFile.length()} version=${root.optInt("version", -1)} configBucket=$bucketExists bucketCues=$bucketCueCount"
            }
        }.getOrElse { "unreadable error=${it.javaClass.simpleName}:${it.message}" }
        Log.i(TAG, "Cache restore inspect: trackKeyHash=${sha256(trackCacheKey)} file=${cacheFile.name} fileState={$fileSummary} configId=${configId.take(12)} currentCues=${cues.size} uniqueCueIds=${currentCueIds.size}")
        val legacyTranslations = (readCache(legacyTrackFile, configId) +
            readCache(legacyEndpointFile, configId))
            .filterKeys { it in currentCueIds }
        val cache = readCache(cacheFile, configId).toMutableMap().apply {
            legacyTranslations.forEach { (id, value) -> putIfAbsent(id, value) }
        }
        val matchedCueCount = currentCueIds.count { it in cache }
        Log.i(TAG, "Cache restore match: cacheEntries=${cache.size} legacyMatches=${legacyTranslations.size} matchedCueIds=$matchedCueCount unmatchedCueIds=${(currentCueIds.size - matchedCueCount)}")
        if (cache.isEmpty()) {
            Log.w(TAG, "Cache restore miss: no entries for current config/track; trackKeyHash=${sha256(trackCacheKey)} configId=${configId.take(12)}")
            return@withContext cues
        }
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
        val alreadyTranslated = cues.count { !it.translationText.isNullOrBlank() }
        Log.i(TAG, "Cache restore result: restored=$restoredCount alreadyTranslated=$alreadyTranslated total=${cues.size} cacheEntries=${cache.size}")
        if (restoredCount == 0 && matchedCueCount == 0) {
            Log.w(TAG, "Cache file exists but no cueIdentity matched; inspect subtitle timing/text identity. trackKeyHash=${sha256(trackCacheKey)}")
        }
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
                // File.renameTo maps to an atomic same-directory rename on Android/Linux.
                // Do not delete the previous cache first: a failed rename must not destroy it.
                if (!temporary.renameTo(file)) error("Unable to atomically replace translation cache")
            }
        }.onFailure { Log.w(TAG, "Unable to persist subtitle translation cache: ${file.name}", it) }
    }

    /**
     * Double Subtitles encodes its source/target language pair in the configured manifest path,
     * for example /configure/en/zh-CN/manifest.json. Embedded tracks are translated only when
     * their declared language matches that configured source language.
     */
    private fun matchesConfiguredSourceLanguage(
        manifestUrl: String,
        subtitleLanguage: String,
    ): Boolean {
        val pathSegments = runCatching {
            URI(manifestUrl).path.orEmpty().split('/').filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val configureIndex = pathSegments.indexOfLast { it.equals("configure", ignoreCase = true) }
        val configuredSource = pathSegments.getOrNull(configureIndex + 1)
        val configuredTarget = pathSegments.getOrNull(configureIndex + 2)
        val configuredCode = configuredSource?.let(::normalizeLanguageCode)
        val subtitleCode = normalizeLanguageCode(subtitleLanguage)
        val matches = configuredCode != null && subtitleCode != null && configuredCode == subtitleCode

        return matches
    }

    /** Normalizes ISO-639 tags and human-readable language names from subtitle providers. */
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

        // Subtitle providers may expose "English" instead of ISO tags such as "en" or "eng".
        // Locale.forLanguageTag("English") returns the literal language "english", which will
        // never match the add-on's configured ISO source code. Resolve display names explicitly.
        val displayName = tag.substringBefore('(').trim()
        Locale.getAvailableLocales()
            .firstOrNull { locale ->
                locale.language.isNotBlank() &&
                    locale.getDisplayLanguage(Locale.ENGLISH).equals(displayName, ignoreCase = true)
            }
            ?.language
            ?.lowercase()
            ?.let { return it }

        return Locale.forLanguageTag(tag).language
            .takeIf { it.isNotBlank() && it != "und" }
            ?.lowercase()
    }

    private fun cueIdentity(cue: LearningSubtitleCue): String = sha256("${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}