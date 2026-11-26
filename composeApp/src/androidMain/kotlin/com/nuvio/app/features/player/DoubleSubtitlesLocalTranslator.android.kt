package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.addonTransportBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private const val MAX_CONCURRENT_BATCHES = 3
    private const val MAX_RATE_LIMIT_RETRIES = 5
    private val TRANSIENT_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 7_000L)
    private const val PRIORITY_AHEAD_MS = 60_000L
    private const val CACHE_DIRECTORY = "embedded_learning_subtitles"
    private const val MAX_MEMORY_CACHE_TRACKS = 6
    private val memoryCache = object : java.util.LinkedHashMap<String, Map<String, CachedSubtitle>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<String, CachedSubtitle>>?,
        ): Boolean = size > MAX_MEMORY_CACHE_TRACKS
    }
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

    /** Reads both language codes from /configure/{source}/{target}/manifest.json. */
    suspend fun configuredLanguagePair(context: Context): Pair<String, String>? = withContext(Dispatchers.IO) {
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
        val source = segments.getOrNull(index + 1)?.takeIf { normalizeLanguageCode(it) != null }
        val target = segments.getOrNull(index + 2)?.takeIf { normalizeLanguageCode(it) != null }
        if (source == null || target == null) null else source to target
    }

    suspend fun configuredTargetLanguage(context: Context): String? =
        configuredLanguagePair(context)?.second

    suspend fun isTargetLanguageAllowed(context: Context, subtitleLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val subtitleCode = normalizeLanguageCode(subtitleLanguage) ?: return@withContext false
            val pair = configuredLanguagePair(context) ?: return@withContext false
            normalizeLanguageCode(pair.second) == subtitleCode
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

    /** Returns the reason shown when manual translation is unavailable. */
    suspend fun manualTranslationBlockMessage(context: Context, sourceLanguage: String): String? =
        withContext(Dispatchers.IO) {
            val pair = configuredLanguagePair(context)
                ?: return@withContext "未检测到有效插件，请检查插件配置。"
            // Source-language matching is an automatic-translation constraint only.
            // A user-initiated manual translation may translate any selected subtitle language.
            null
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
        PlayerLearningSubtitleState.recordTranslationDiagnostic("进入翻译器", "${cues.size} 条字幕")
        // Ensure the persisted addon list and its manifests have had a chance to load
        // before deciding that the user has not installed/enabled Double Subtitles.
        AddonRepository.initialize()
        PlayerLearningSubtitleState.recordTranslationDiagnostic("开始检查插件配置")
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
        PlayerLearningSubtitleState.recordTranslationDiagnostic("插件配置就绪")
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
        val cacheStartedAt = android.os.SystemClock.elapsedRealtime()
        val configId = sha256(endpoint)
        val cacheFile = cacheFile(context, trackCacheKey)
        // Compute each cue identity once instead of recalculating SHA-256 in every cache pass.
        val cueIds = cues.map { cueIdentity(it) }
        val cueIdByCue = java.util.IdentityHashMap<LearningSubtitleCue, String>(cues.size).apply {
            cues.forEachIndexed { index, cue -> put(cue, cueIds[index]) }
        }
        val currentCueIds = cueIds.toHashSet()

        val mainCacheStartedAt = android.os.SystemClock.elapsedRealtime()
        val mainTranslations = readCachedBucket(cacheFile, configId)
        val mainCacheReadMs = android.os.SystemClock.elapsedRealtime() - mainCacheStartedAt

        // Probe legacy cache files only when they exist; avoid unnecessary legacy IO on cache misses.
        val legacyStartedAt = android.os.SystemClock.elapsedRealtime()
        val legacyTrackFile = legacyCacheFile(context, trackCacheKey, endpoint)
        val legacyTrackTranslations = if (legacyTrackFile.isFile) readCachedBucket(legacyTrackFile, configId) else emptyMap()
        val legacyEndpointFile = legacyEndpointCacheFile(context, endpoint)
        val legacyEndpointTranslations = if (legacyEndpointFile.isFile) {
            readCachedBucket(legacyEndpointFile, configId).filterKeys { it in currentCueIds }
        } else emptyMap()
        val legacyTranslations = legacyTrackTranslations + legacyEndpointTranslations
        val legacyReadMs = android.os.SystemClock.elapsedRealtime() - legacyStartedAt

        val translations = mainTranslations.toMutableMap().apply {
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
        cues.forEachIndexed { index, cue ->
            val id = cueIds[index]
            val cached = translations[id]
            if (cached != null && cached.originalText.isBlank()) {
                translations[id] = cached.copy(startTimeMs = cue.startTimeMs, endTimeMs = cue.endTimeMs, originalText = cue.text)
                upgradedLegacyEntries = true
            }
        }
        if (upgradedLegacyEntries) writeCache(cacheFile, translations, configId)
        Log.i(TAG, "Translation cache timing: cueIds=${cueIds.size}, mainReadMs=$mainCacheReadMs, legacyReadMs=$legacyReadMs, totalMs=${android.os.SystemClock.elapsedRealtime() - cacheStartedAt}, mainFileExists=${cacheFile.isFile}, mainFileBytes=${if (cacheFile.isFile) cacheFile.length() else 0L}, legacyEntries=${legacyTranslations.size}, cachedEntries=${translations.size}")
        Log.i(TAG, "Translation endpoint prepared; cached subtitle pairs=${translations.size}")
        PlayerLearningSubtitleState.recordTranslationDiagnostic("读取翻译缓存完成", "缓存译文 ${translations.size} 条")

        // Restore cache hits immediately, before constructing or sending any translation
        // requests. Cached cues are excluded from the missing list below, so they are never
        // sent to the translation API again.
        val cachedBatch = cues.mapIndexedNotNull { index, cue ->
            if (!cue.translationText.isNullOrBlank()) return@mapIndexedNotNull null
            translations[cueIds[index]]?.translatedText
                ?.takeIf(String::isNotBlank)
                ?.let { cue.copy(translationText = it) }
        }
        if (cachedBatch.isNotEmpty()) {
            Log.i(TAG, "Restored ${cachedBatch.size} subtitle translations from local cache")
            onBatchTranslated(cachedBatch)
        }

        val missing = cues.filterIndexed { index, cue ->
            cue.text.isNotBlank() &&
                translations[cueIds[index]]?.translatedText.isNullOrBlank() &&
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

        // External subtitle files must be translated in full. Playback-window scheduling is
        // reserved for embedded/live tracks so a manual request never silently omits later cues.
        val isExternalTrack = trackCacheKey.startsWith("external|", ignoreCase = true)
        val usePlaybackWindow = positionMs != null && !isExternalTrack
        val phaseOneBatches = if (!usePlaybackWindow) {
            buildChunks(missing)
        } else {
            buildList {
                activeCue?.let { add(listOf(it)) }
                addAll(buildChunks(firstMinute))
            }
        }
        val phaseTwoBatches = if (usePlaybackWindow) buildChunks(backgroundWindow) else emptyList()
        val resultMutex = Mutex()
        val scheduledCount = phaseOneBatches.sumOf { it.size } + phaseTwoBatches.sumOf { it.size }
        val scheduleScope = when {
            isExternalTrack -> "full-track-external"
            positionMs == null -> "full-track"
            else -> "playback-window"
        }
        PlayerLearningSubtitleState.recordTranslationDiagnostic("批次准备完成", "待翻译 ${missing.size} 条，首批队列 ${phaseOneBatches.sumOf { it.size }} 条，共 ${phaseOneBatches.size + phaseTwoBatches.size} 批")
        Log.i(TAG, "Translation schedule: scope=${scheduleScope} " +
            "currentCue=${activeCue != null} phase1Batches=${phaseOneBatches.size} " +
            "phase1Cues=${phaseOneBatches.sumOf { it.size }} phase2Batches=${phaseTwoBatches.size} " +
            "phase2Cues=${phaseTwoBatches.sumOf { it.size }} deferredCues=${missing.size - scheduledCount}")

        suspend fun processBatch(
            chunk: List<LearningSubtitleCue>,
            phase: String,
            chunkIndex: Int,
            totalChunks: Int,
        ) {
            var pendingChunk = chunk
            var transientRetries = 0
            var rateLimitRetries = 0
            while (pendingChunk.isNotEmpty()) {
                // Reuse identities computed during cache inspection. Response matching is O(1).
                val chunkEntries = pendingChunk.map { cue ->
                    cue to (cueIdByCue[cue] ?: cueIdentity(cue))
                }
                val sourceCuesById = chunkEntries.associate { (cue, id) -> id to cue }
                val chunkIds = chunkEntries.map { it.second }
                val batchStartedAt = System.nanoTime()
                try {
                    val requestCues = JSONArray()
                    chunkEntries.forEach { (cue, id) ->
                        requestCues.put(JSONObject().put("id", id)
                            .put("start", cue.startTimeMs).put("end", cue.endTimeMs).put("text", cue.text))
                    }
                    val batchId = sha256("$trackCacheKey|$phase|$chunkIndex|" + chunkIds.joinToString("|")).take(32)
                    val requestJson = JSONObject().put("batchId", batchId).put("subtitles", requestCues).toString()
                    val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(endpoint).header("Accept", "application/json")
                        .post(requestBody).build()
                    val requestUrl = request.url.toString()
                    val requestHeaders = request.headers.toMultimap().entries.joinToString("\n") { (name, values) ->
                        "$name: ${values.joinToString(", ")}"
                    }
                    PlayerLearningSubtitleState.recordTranslationDiagnostic("第 ${chunkIndex + 1}/${totalChunks} 批发送请求", "${pendingChunk.size} 条")
                    Log.i(TAG, "Translation request started: phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId cues=${pendingChunk.size} chars=${pendingChunk.sumOf { it.text.length }}")
                    val batchTranslations = linkedMapOf<String, CachedSubtitle>()
                    var translatedInResponse = 0
                    var responseItemCount = 0
                    val requestStartedAtNanos = System.nanoTime()
                    client.newCall(request).execute().use { response ->
                        val responseHeadersAtNanos = System.nanoTime()
                        val headerWaitMs = (responseHeadersAtNanos - requestStartedAtNanos) / 1_000_000
                        PlayerLearningSubtitleState.recordTranslationDiagnostic(
                            "第 ${chunkIndex + 1}/${totalChunks} 批收到 HTTP 响应头",
                            "HTTP ${response.code}，等待 ${headerWaitMs} ms"
                        )
                        val responseText = response.body?.string().orEmpty()
                        val bodyReadFinishedAtNanos = System.nanoTime()
                        val bodyReadMs = (bodyReadFinishedAtNanos - responseHeadersAtNanos) / 1_000_000
                        val totalRequestMs = (bodyReadFinishedAtNanos - requestStartedAtNanos) / 1_000_000
                        PlayerLearningSubtitleState.recordTranslationDiagnostic(
                            "第 ${chunkIndex + 1}/${totalChunks} 批读取响应体完成",
                            "${responseText.length} 字符，读取 ${bodyReadMs} ms，总请求 ${totalRequestMs} ms"
                        )
                        PlayerLearningSubtitleState.enqueueTranslationRawResponse(
                            TranslationRawResponse(
                                batchNumber = chunkIndex + 1, totalBatches = totalChunks, cueCount = pendingChunk.size,
                                httpCode = response.code, headerWaitMs = headerWaitMs, bodyReadMs = bodyReadMs,
                                totalRequestMs = totalRequestMs, requestUrl = requestUrl, requestMethod = request.method,
                                requestHeaders = requestHeaders, requestBody = requestJson, body = responseText,
                            )
                        )
                        Log.i(TAG, "Translation raw response captured: phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId http=${response.code} headerWaitMs=$headerWaitMs bodyReadMs=$bodyReadMs totalMs=$totalRequestMs bodyChars=${responseText.length}")
                        if (PlayerLearningSubtitleState.rawResponseInspectionMode) {
                            PlayerLearningSubtitleState.recordTranslationDiagnostic(
                                "第 ${chunkIndex + 1}/${totalChunks} 批跳过响应解析",
                                "已保留接口原始响应，未解析 JSON、未写缓存、未回写字幕"
                            )
                            return
                        }
                        if (!response.isSuccessful) {
                            throw TranslationHttpException(response.code, "Double Subtitles returned HTTP ${response.code}: $responseText")
                        }
                        val payload = JSONObject(responseText)
                        if (!payload.optBoolean("success", false)) {
                            error(payload.optString("error").ifBlank { "Translation request failed" })
                        }
                        val result = payload.optJSONArray("translations") ?: JSONArray()
                        responseItemCount = result.length()
                        for (index in 0 until result.length()) {
                            val item = result.optJSONObject(index) ?: continue
                            val id = item.optString("id")
                            val translatedText = item.optString("text").trim()
                            val sourceCue = sourceCuesById[id]
                            if (sourceCue != null && translatedText.isNotBlank()) {
                                val previous = batchTranslations.put(id, CachedSubtitle(sourceCue.startTimeMs, sourceCue.endTimeMs, sourceCue.text, translatedText))
                                if (previous == null) translatedInResponse++
                            }
                        }
                        if (translatedInResponse == 0) error("Translation API returned success but no usable translated cues")
                    }
                    PlayerLearningSubtitleState.recordTranslationDiagnostic("第 ${chunkIndex + 1}/${totalChunks} 批解析响应完成", "匹配译文 $translatedInResponse/${pendingChunk.size} 条")
                    Log.i(TAG, "Translation response parsed: phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId responseItems=$responseItemCount matched=$translatedInResponse requested=${pendingChunk.size} missing=${pendingChunk.size-translatedInResponse}")
                    val translatedBatch = resultMutex.withLock {
                        translations.putAll(batchTranslations)
                        writeCache(cacheFile, translations.toMap(), configId)
                        chunkEntries.mapNotNull { (cue, id) ->
                            batchTranslations[id]?.translatedText?.takeIf(String::isNotBlank)?.let { cue.copy(translationText = it) }
                        }
                    }
                    if (translatedBatch.isNotEmpty()) {
                        Log.i(TAG, "Translation callback dispatching: phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId translated=${translatedBatch.size}")
                        onBatchTranslated(translatedBatch)
                        PlayerLearningSubtitleState.recordTranslationDiagnostic("第 ${chunkIndex + 1}/${totalChunks} 批回调完成")
                        Log.i(TAG, "Translation callback completed: phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId")
                    } else {
                        Log.w(TAG, "Translation callback skipped: empty translated batch phase=$phase index=${chunkIndex + 1}/$totalChunks batchId=$batchId")
                    }
                    val missingCues = chunkEntries.filter { (_, id) -> id !in batchTranslations }.map { it.first }
                    if (missingCues.isEmpty()) {
                        val elapsedMs = (System.nanoTime() - batchStartedAt) / 1_000_000
                        Log.i(TAG, "Translated embedded batch: phase=$phase index=${chunkIndex + 1}/$totalChunks cues=${chunk.size} translated=$translatedInResponse elapsedMs=$elapsedMs")
                        if (rateLimitRetries > 0) Log.i(TAG, "Translation rate limit recovered for batch")
                        return
                    }
                    if (transientRetries >= TRANSIENT_RETRY_DELAYS_MS.size) {
                        Log.w(TAG, "Translation batch partially completed after retries: phase=$phase index=${chunkIndex + 1}/$totalChunks translated=${chunk.size - missingCues.size} remaining=${missingCues.size}")
                        return
                    }
                    val delayMs = TRANSIENT_RETRY_DELAYS_MS[transientRetries++]
                    Log.w(TAG, "Translation batch returned partial results: phase=$phase index=${chunkIndex + 1}/$totalChunks translated=${chunk.size - missingCues.size} remaining=${missingCues.size} retryInMs=$delayMs")
                    kotlinx.coroutines.delay(delayMs)
                    pendingChunk = missingCues
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val elapsedMs = (System.nanoTime() - batchStartedAt) / 1_000_000
                    val statusCode = (error as? TranslationHttpException)?.statusCode
                    if (statusCode == 429) {
                        if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) {
                            Log.w(TAG, "Translation rate-limit retries exhausted: phase=$phase index=${chunkIndex + 1}/$totalChunks elapsedMs=$elapsedMs")
                            return
                        }
                        val delayMs = (3_000L shl rateLimitRetries.coerceAtMost(4)).coerceAtMost(60_000L)
                        rateLimitRetries++
                        Log.w(TAG, "Translation rate limited: phase=$phase index=${chunkIndex + 1}/$totalChunks http=429 retry=$rateLimitRetries/$MAX_RATE_LIMIT_RETRIES retryInMs=$delayMs elapsedMs=$elapsedMs")
                        kotlinx.coroutines.delay(delayMs)
                        continue
                    }
                    val retryable = statusCode == 408 || (statusCode != null && statusCode in 500..599) || error is java.io.IOException
                    if (retryable && transientRetries < TRANSIENT_RETRY_DELAYS_MS.size) {
                        val delayMs = TRANSIENT_RETRY_DELAYS_MS[transientRetries++]
                        Log.w(TAG, "Translation batch transient failure: phase=$phase index=${chunkIndex + 1}/$totalChunks http=${statusCode ?: "network"} retry=$transientRetries/${TRANSIENT_RETRY_DELAYS_MS.size} retryInMs=$delayMs elapsedMs=$elapsedMs error=${error.message}")
                        kotlinx.coroutines.delay(delayMs)
                        continue
                    }
                    Log.e(TAG, "Embedded subtitle batch failed: phase=$phase index=${chunkIndex + 1}/$totalChunks cues=${pendingChunk.size} elapsedMs=$elapsedMs retries=$transientRetries rateLimitRetries=$rateLimitRetries error=${error.message}", error)
                    return
                }
            }
        }
        // Process bounded waves. Complete current/priority work before background prefetch starts.
        suspend fun processBatchPhase(batches: List<List<LearningSubtitleCue>>, phase: String) {
            batches.withIndex().toList().chunked(MAX_CONCURRENT_BATCHES).forEach { wave ->
                coroutineScope {
                    wave.map { indexed ->
                        async(Dispatchers.IO) {
                            processBatch(indexed.value, phase, indexed.index, batches.size)
                        }
                    }.awaitAll()
                }
            }
        }
        coroutineScope {
            Log.i(TAG, "Translation parallel dispatch: scope=$scheduleScope maxConcurrent=$MAX_CONCURRENT_BATCHES phase1=${phaseOneBatches.size} phase2=${phaseTwoBatches.size}")
            processBatchPhase(phaseOneBatches, "priority")
            processBatchPhase(phaseTwoBatches, "background")
        }
        cues.mapIndexed { index, cue ->
            val translated = translations[cueIds[index]]?.translatedText ?: cue.translationText
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
        // Keep cue identities for this restore pass; never recompute SHA-256 while matching.
        val cueIds = cues.map { cueIdentity(it) }
        val currentCueIds = cueIds.toHashSet()
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
        val legacyTranslations = (readCachedBucket(legacyTrackFile, configId) +
            readCachedBucket(legacyEndpointFile, configId))
            .filterKeys { it in currentCueIds }
        val cache = readCachedBucket(cacheFile, configId).toMutableMap().apply {
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
        // Build one text index for legacy timing-tolerance matches. Each text bucket is
        // sorted by start time, so a cue probes only the nearby timing window instead of
        // scanning every cached subtitle for every current cue.
        val fallbackIndex = cache.entries.asSequence()
            .filter { it.value.originalText.isNotBlank() }
            .groupBy({ it.value.originalText }, { it.key to it.value })
            .mapValues { (_, entries) -> entries.sortedBy { it.second.startTimeMs } }
        val usedCacheIds = mutableSetOf<String>()
        var timingFallbackMatches = 0
        val restored = cues.mapIndexed { index, cue ->
            if (!cue.translationText.isNullOrBlank()) return@mapIndexed cue
            val exactId = cueIds[index]
            val exact = cache[exactId]?.let { exactId to it }
            val matched = exact ?: fallbackIndex[cue.text]
                ?.let { entries ->
                    var low = 0
                    var high = entries.size
                    val earliestStart = cue.startTimeMs - 1_500L
                    while (low < high) {
                        val middle = (low + high) ushr 1
                        if (entries[middle].second.startTimeMs < earliestStart) low = middle + 1
                        else high = middle
                    }
                    var best: Pair<String, CachedSubtitle>? = null
                    var bestScore = Long.MAX_VALUE
                    var index = low
                    while (index < entries.size) {
                        val entry = entries[index++]
                        val value = entry.second
                        if (value.startTimeMs > cue.startTimeMs + 1_500L) break
                        if (entry.first in usedCacheIds ||
                            kotlin.math.abs(value.endTimeMs - cue.endTimeMs) > 3_000L
                        ) continue
                        val score = kotlin.math.abs(value.startTimeMs - cue.startTimeMs) * 2L +
                            kotlin.math.abs(value.endTimeMs - cue.endTimeMs)
                        if (score < bestScore) {
                            best = entry
                            bestScore = score
                        }
                    }
                    best?.also { timingFallbackMatches++ }
                }
            val cached = matched?.second ?: return@mapIndexed cue
            usedCacheIds += matched.first
            if (cached.originalText.isBlank()) upgradedLegacyEntries = true
            cue.copy(translationText = cached.translatedText)
        }
        if (timingFallbackMatches > 0) {
            Log.i(TAG, "Cache restore timing fallback: matched=$timingFallbackMatches toleranceStartMs=1500 toleranceEndMs=3000")
        }
        if (upgradedLegacyEntries) {
            val upgraded = cache.toMutableMap()
            cues.forEachIndexed { index, cue ->
                val id = cueIds[index]
                val cached = upgraded[id] ?: return@forEachIndexed
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

    /**
     * Reuses a recently loaded track/config bucket across playback entry and translation calls.
     * The cache is deliberately bounded by track count so large subtitle tracks cannot grow
     * process memory without limit.
     */
    private fun readCachedBucket(file: File, configId: String): Map<String, CachedSubtitle> {
        val scopeKey = "${file.absolutePath}|$configId"
        synchronized(file.absolutePath.intern()) {
            synchronized(memoryCache) {
                memoryCache[scopeKey]?.let { return it }
            }
            val loaded = readCache(file, configId)
            synchronized(memoryCache) {
                memoryCache[scopeKey] = loaded
            }
            return loaded
        }
    }

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
                val scopeKey = "${file.absolutePath}|$configId"
                synchronized(memoryCache) {
                    memoryCache[scopeKey] = merged.toMap()
                }
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