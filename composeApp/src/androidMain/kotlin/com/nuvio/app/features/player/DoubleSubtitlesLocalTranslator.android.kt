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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Sends selected embedded subtitle batches through the installed Double Subtitles addon. */
internal object DoubleSubtitlesLocalTranslator {
    private const val TAG = "NuvioDoubleSubs"
    private const val ADDON_ID = "community.doublesubtitles"
    private const val MAX_BATCH_CUES = 50
    private const val MAX_BATCH_CHARS = 9_000
    private const val CACHE_DIRECTORY = "embedded_learning_subtitles"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun translate(context: Context, trackCacheKey: String, cues: List<LearningSubtitleCue>): List<LearningSubtitleCue> = withContext(Dispatchers.IO) {
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
        val manifestUrl = addon.manifestUrl
        Log.i(TAG, "Double Subtitles addon is enabled; starting embedded translation batch (${cues.size} cues)")
        val cleanManifestUrl = manifestUrl.substringBefore("?")
        if (!cleanManifestUrl.startsWith("http://", true) && !cleanManifestUrl.startsWith("https://", true)) return@withContext cues
        val baseUrl = addonTransportBaseUrl(cleanManifestUrl)
        if (baseUrl.isBlank()) return@withContext cues
        val query = manifestUrl.substringAfter("?", "")
        val endpoint = "$baseUrl/api/v1/translate" + if (query.isBlank()) "" else "?$query"
        val cacheFile = cacheFile(context, sha256("$trackCacheKey|$endpoint"))
        val translations = readCache(cacheFile).toMutableMap()
        Log.i(TAG, "Translation endpoint prepared; cached translations=${translations.size}")
        val missing = cues.filter { cue -> cue.text.isNotBlank() && translations[cueIdentity(cue)].isNullOrBlank() && cue.translationText.isNullOrBlank() }
        val chunks = mutableListOf<List<LearningSubtitleCue>>()
        var current = mutableListOf<LearningSubtitleCue>()
        var currentChars = 0
        missing.forEach { cue ->
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
                            translations[id] = translatedText
                            translatedInResponse++
                        }
                    }
                    if (translatedInResponse == 0) {
                        error("Translation API returned success but no usable translated cues")
                    }
                    Log.i(TAG, "Translation API returned $translatedInResponse translated cues")
                }
                writeCache(cacheFile, translations)
                Log.i(TAG, "Translated embedded batch: cues=${chunk.size}")
            }.onFailure { error ->
                Log.e(TAG, "Embedded subtitle batch translation failed: ${error.message}", error)
            }
        }
        cues.map { cue ->
            val translated = translations[cueIdentity(cue)] ?: cue.translationText
            cue.copy(translationText = translated?.takeIf(String::isNotBlank))
        }
    }

    private fun cacheFile(context: Context, key: String): File {
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "translation_$key.json")
    }
    private fun readCache(file: File): Map<String, String> = runCatching {
        if (!file.isFile) return@runCatching emptyMap()
        val json = JSONObject(file.readText(Charsets.UTF_8))
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                json.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
            }
        }
    }.getOrDefault(emptyMap())
    private fun writeCache(file: File, values: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            values.forEach { (key, value) -> json.put(key, value) }
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) error("Unable to replace translation cache")
            if (!temporary.renameTo(file)) error("Unable to finalize translation cache")
        }.onFailure { Log.w(TAG, "Unable to persist embedded translation cache", it) }
    }
    private fun cueIdentity(cue: LearningSubtitleCue): String = sha256("${cue.startTimeMs}|${cue.endTimeMs}|${cue.text}")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}