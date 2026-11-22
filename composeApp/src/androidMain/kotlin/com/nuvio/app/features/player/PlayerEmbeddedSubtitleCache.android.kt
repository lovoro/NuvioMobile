package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Persistent cache for fully extracted embedded subtitle tracks. */
internal object PlayerEmbeddedSubtitleCache {
    private const val TAG = "NuvioEmbeddedCache"
    private const val DIRECTORY = "embedded_learning_subtitles"
    private const val MAGIC = 0x4E565343
    private const val VERSION = 1
    private const val MAX_CUES = 20_000
    private const val MAX_TEXT_BYTES = 256 * 1024
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun videoIdentity(url: String, headers: Map<String, String>): String {
        val source = buildString {
            append(url)
            headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                append('\n').append(name.lowercase()).append(':').append(value)
            }
        }
        return sha256(source)
    }

    fun cacheKey(url: String, headers: Map<String, String>, trackKey: String): String =
        sha256(videoIdentity(url, headers) + "|" + trackKey)

    suspend fun read(key: String): List<LearningSubtitleCue>? = withContext(Dispatchers.IO) {
        val file = cacheFile(key) ?: return@withContext null
        if (!file.isFile || file.length() <= 0L) return@withContext null
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return@use null
                val count = input.readInt()
                if (count !in 1..MAX_CUES) return@use null
                val cues = ArrayList<LearningSubtitleCue>(count)
                repeat(count) {
                    val start = input.readLong()
                    val end = input.readLong()
                    val length = input.readInt()
                    if (length !in 1..MAX_TEXT_BYTES) return@use null
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.isNotBlank() && end > start) cues += LearningSubtitleCue(start, end, text)
                }
                cues.takeIf { it.isNotEmpty() }
            }
        }.onFailure { Log.w(TAG, "Unable to read embedded subtitle cache", it) }.getOrNull()
    }

    suspend fun write(key: String, cues: List<LearningSubtitleCue>) = withContext(Dispatchers.IO) {
        if (cues.isEmpty() || cues.size > MAX_CUES) return@withContext
        val target = cacheFile(key) ?: return@withContext
        val temporary = File(target.parentFile, target.name + ".tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(cues.size)
                cues.forEach { cue ->
                    val bytes = cue.text.toByteArray(Charsets.UTF_8)
                    require(bytes.size in 1..MAX_TEXT_BYTES)
                    output.writeLong(cue.startTimeMs)
                    output.writeLong(cue.endTimeMs)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            if (target.exists() && !target.delete()) error("Unable to replace cache")
            if (!temporary.renameTo(target)) error("Unable to finalize cache")
            Log.i(TAG, "Saved embedded subtitle cache: cues=${cues.size} bytes=${target.length()}")
        }.onFailure {
            temporary.delete()
            Log.w(TAG, "Unable to save embedded subtitle cache", it)
        }
    }

    private fun cacheFile(key: String): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        return File(directory, "$key.bin")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
