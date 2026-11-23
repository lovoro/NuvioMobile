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

    /**
     * Builds a playback identity that survives refreshed signed URLs and auth headers.
     * Keep content-identifying query parameters, but exclude short-lived credentials.
     */
    fun videoIdentity(url: String, headers: Map<String, String>): String {
        val stableUrl = stablePlaybackUrl(url)
        val stableHeaders = headers
            .filterNot { (name, _) -> isVolatileCredentialName(name) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val source = buildString {
            append(stableUrl)
            stableHeaders.forEach { (name, value) ->
                append('\n').append(name.lowercase()).append(':').append(value.trim())
            }
        }
        return sha256(source)
    }

    /**
     * Signed CDN URLs commonly rotate only their authentication query parameters.
     * Preserve all other parameters because they may identify the actual media item.
     */
    private fun stablePlaybackUrl(url: String): String {
        val withoutFragment = url.substringBefore('#')
        val queryIndex = withoutFragment.indexOf('?')
        if (queryIndex < 0) return withoutFragment
        val base = withoutFragment.substring(0, queryIndex)
        val query = withoutFragment.substring(queryIndex + 1)
        val stableParameters = query.split('&')
            .filter(String::isNotBlank)
            .filterNot { parameter ->
                val rawName = parameter.substringBefore('=')
                isVolatileCredentialName(rawName.lowercase())
            }
            .sorted()
        return if (stableParameters.isEmpty()) base else "$base?${stableParameters.joinToString("&")}"
    }

    private fun isVolatileCredentialName(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized in setOf(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "token", "access_token", "auth", "authorization_token", "auth_token",
            "access-token", "auth-token", "api_key", "apikey", "x-api-key",
            "session", "sessionid", "session_id", "jwt", "signature", "sig",
            "expires", "expiry", "policy", "key-pair-id", "hdnts", "hdnea",
            "se", "sp", "sv", "sr",
        ) || normalized.startsWith("x-amz-") ||
            normalized.startsWith("x-goog-") ||
            normalized.startsWith("x-ms-")
    }

    fun cacheKey(url: String, headers: Map<String, String>, trackKey: String): String =
        sha256(videoIdentity(url, headers) + "|" + trackKey)

    suspend fun read(key: String): List<LearningSubtitleCue>? = withContext(Dispatchers.IO) {
        val directory = cacheDirectory() ?: return@withContext null
        val reference = File(directory, "$key.ref")
        val referencedName = runCatching { reference.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        val referencedFile = referencedName
            ?.takeIf { it.startsWith("content_") && it.endsWith(".bin") && !it.contains('/') && !it.contains('\\') }
            ?.let { File(directory, it) }
        val file = referencedFile?.takeIf { it.isFile } ?: File(directory, "$key.bin")
        val cues = readCueFile(file) ?: return@withContext null
        // Migrate a legacy identity-named BIN on first read. This also collapses any
        // other byte-equivalent subtitle files left by earlier app versions.
        if (referencedFile == null && file.name == "$key.bin") {
            write(key, cues)
        }
        cues
    }

    suspend fun write(key: String, cues: List<LearningSubtitleCue>) = withContext(Dispatchers.IO) {
        if (cues.isEmpty() || cues.size > MAX_CUES) return@withContext
        val directory = cacheDirectory() ?: return@withContext
        runCatching {
            synchronized(directory.absolutePath.intern()) {
                val contentKey = sha256(buildString {
                    cues.forEach { cue ->
                        append(cue.startTimeMs).append('|')
                            .append(cue.endTimeMs).append('|')
                            .append(cue.text.length).append(':').append(cue.text).append('\n')
                    }
                })
                val canonical = File(directory, "content_$contentKey.bin")
                if (!canonical.isFile || canonical.length() <= 0L) {
                    val temporary = File(directory, canonical.name + ".tmp")
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
                    if (!temporary.renameTo(canonical)) {
                        temporary.delete()
                        if (!canonical.isFile) error("Unable to finalize canonical subtitle cache")
                    }
                }

                // Keep an identity-to-content pointer so the next playback can find the
                // content-addressed file without extracting the track again.
                writeReference(File(directory, "$key.ref"), canonical.name)

                // Collapse older identity-named BIN files with identical cue content.
                directory.listFiles { file -> file.isFile && file.extension == "bin" && file.name != canonical.name }
                    .orEmpty()
                    .forEach { duplicate ->
                        val existing = readCueFile(duplicate)
                        if (sameCues(existing, cues)) {
                            val duplicateKey = duplicate.nameWithoutExtension
                            writeReference(File(directory, "$duplicateKey.ref"), canonical.name)
                            duplicate.delete()
                        }
                    }
                Log.i(TAG, "Saved/reused embedded subtitle content cache: cues=${cues.size} bytes=${canonical.length()}")
            }
        }.onFailure { Log.w(TAG, "Unable to save embedded subtitle cache", it) }
    }

    private fun cacheDirectory(): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        return directory
    }

    private fun readCueFile(file: File): List<LearningSubtitleCue>? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
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
        }.onFailure { Log.w(TAG, "Unable to read embedded subtitle cache: ${file.name}", it) }.getOrNull()
    }

    private fun sameCues(first: List<LearningSubtitleCue>?, second: List<LearningSubtitleCue>): Boolean =
        first != null && first.size == second.size && first.indices.all { index ->
            first[index].startTimeMs == second[index].startTimeMs &&
                first[index].endTimeMs == second[index].endTimeMs &&
                first[index].text == second[index].text
        }

    private fun writeReference(file: File, targetName: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(targetName, Charsets.UTF_8)
        if (file.exists() && !file.delete()) error("Unable to replace subtitle cache reference")
        if (!temporary.renameTo(file)) error("Unable to finalize subtitle cache reference")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
