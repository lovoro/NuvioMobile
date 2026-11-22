package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Best-effort Matroska text subtitle prefetcher. Reads Tracks, Cues and the indexed Clusters
 * using bounded HTTP byte ranges; the media stream itself is never downloaded.
 */
internal object MatroskaEmbeddedSubtitlePrefetcher {
    private const val PROBE = 512 * 1024
    private const val TAIL = 16 * 1024 * 1024
    // Long films and high-bitrate video can have thousands of indexed clusters.
    // Keep generous safety budgets, but never silently treat a truncated scan as complete.
    private const val MAX_CLUSTER = 32 * 1024 * 1024
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_REQUESTS = 40_100
    private const val EXTRACTION_TIMEOUT_MS = 900_000L
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class LoadResult(
        val cues: List<LearningSubtitleCue>,
        val isComplete: Boolean,
    )

    suspend fun load(
        url: String,
        headers: Map<String, String>,
        selectedTrackNumber: Int?,
    ): LoadResult {
        if (selectedTrackNumber == null || selectedTrackNumber <= 0 ||
            !(url.startsWith("http://", true) || url.startsWith("https://", true))
        ) return LoadResult(emptyList(), false)
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                try {
                    loadInternal(url, headers, selectedTrackNumber)
                } catch (_: Exception) {
                    LoadResult(emptyList(), false)
                }
            } ?: LoadResult(emptyList(), false)
        }
    }

    private suspend fun loadInternal(
        url: String,
        headers: Map<String, String>,
        selectedTrack: Int,
    ): LoadResult {
        val budget = Budget()
        val initial = range(url, headers, 0L, PROBE, budget) ?: return LoadResult(emptyList(), false)
        val segment = find(initial, ID_SEGMENT) ?: return LoadResult(emptyList(), false)
        val segmentData = segment.data.toLong()
        val top = children(initial, segment.data, segment.end)
        val tracksElement = top.firstOrNull { it.id == ID_TRACKS }
            ?: seekElement(initial, top, ID_TRACKS, segmentData, url, headers, budget)
            ?: return LoadResult(emptyList(), false)
        val tracksBytes = elementBytes(initial, tracksElement, url, headers, budget)
            ?: return LoadResult(emptyList(), false)
        val trackInfo = parseTrack(tracksBytes, selectedTrack) ?: return LoadResult(emptyList(), false)
        val scale = top.firstOrNull { it.id == ID_INFO }?.let {
            elementBytes(initial, it, url, headers, budget)?.let(::parseScale)
        } ?: DEFAULT_SCALE
        val cuesTop = top.firstOrNull { it.id == ID_CUES }
        val cuesElement = cuesTop ?: seekElement(initial, top, ID_CUES, segmentData, url, headers, budget)
        var cuesBytes = cuesElement?.let { elementBytes(initial, it, url, headers, budget) }
        if (cuesBytes == null) {
            val total = budget.totalLength ?: return LoadResult(emptyList(), false)
            val tailStart = (total - TAIL).coerceAtLeast(0L)
            val tail = range(url, headers, tailStart, minOf(TAIL.toLong(), total).toInt(), budget)
                ?: return LoadResult(emptyList(), false)
            val offset = findOffset(tail, ID_CUES) ?: return LoadResult(emptyList(), false)
            val e = readElement(tail, offset) ?: return LoadResult(emptyList(), false)
            if (e.end > tail.size) return LoadResult(emptyList(), false)
            cuesBytes = tail.copyOfRange(offset, e.end)
        }
        // Matroska Cues commonly index video/audio blocks rather than subtitle tracks.
        // Read every indexed Cluster, then filter actual subtitle blocks by TrackNumber
        // inside MatroskaSubtitleTextParser. Filtering CueTrack here can yield zero cues.
        val positions = parseCuePositions(cuesBytes)
            .distinctBy { it.second }
            .sortedBy { it.second }
        if (positions.isEmpty()) return LoadResult(emptyList(), false)
        val result = mutableListOf<LearningSubtitleCue>()
        var complete = true
        for ((_, clusterPosition) in positions) {
            if (budget.requests >= MAX_REQUESTS || budget.bytes >= MAX_BYTES) {
                complete = false
                break
            }
            val absolute = segmentData + clusterPosition
            val header = range(url, headers, absolute, 64, budget)
            if (header == null) {
                complete = false
                continue
            }
            val clusterHeader = readElement(header, 0)
            if (clusterHeader == null || clusterHeader.id != ID_CLUSTER ||
                clusterHeader.size <= 0 || clusterHeader.size > MAX_CLUSTER
            ) {
                complete = false
                continue
            }
            val totalSize = clusterHeader.headerSize + clusterHeader.size
            if (totalSize > budget.remainingBytes() || budget.requests >= MAX_REQUESTS) {
                complete = false
                break
            }
            val cluster = range(url, headers, absolute, totalSize, budget)
            if (cluster == null || cluster.size < totalSize) {
                complete = false
                continue
            }
            val decoded = MatroskaSubtitleTextParser.parseCluster(
                cluster,
                listOf(trackInfo),
                scale,
            )[selectedTrack].orEmpty()
            result += decoded.map { LearningSubtitleCue(it.startTimeMs, it.endTimeMs, it.text) }
        }
        return LoadResult(
            cues = result.distinctBy { it.startTimeMs to it.text }.sortedBy { it.startTimeMs },
            isComplete = complete,
        )
    }

    private fun parseTrack(bytes: ByteArray, wanted: Int): MatroskaSubtitleTextParser.Track? {
        val root = readElement(bytes, 0) ?: return null
        if (root.id != ID_TRACKS) return null
        for (entry in children(bytes, root.data, root.end).filter { it.id == ID_TRACK_ENTRY }) {
            var number = 0
            var type = 0L
            var codec = ""
            var codecPrivateText = ""
            for (child in children(bytes, entry.data, entry.end)) {
                when (child.id) {
                    ID_TRACK_NUMBER -> number = unsigned(bytes, child).toInt()
                    ID_TRACK_TYPE -> type = unsigned(bytes, child)
                    ID_CODEC_ID -> codec = utf8(bytes, child)
                    ID_CODEC_PRIVATE -> codecPrivateText = utf8(bytes, child)
                }
            }
            if (number == wanted && type == 0x11L && codec in setOf("S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA")) {
                val format = codecPrivateText.lineSequence().firstOrNull { it.trim().startsWith("Format:", true) }
                    ?.substringAfter(':')?.split(',')?.map(String::trim).orEmpty()
                return MatroskaSubtitleTextParser.Track(number, codec, codecPrivateText, format)
            }
        }
        return null
    }

    private fun parseCuePositions(bytes: ByteArray): List<Pair<Long, Long>> {
        val root = readElement(bytes, 0) ?: return emptyList()
        if (root.id != ID_CUES) return emptyList()
        val result = mutableListOf<Pair<Long, Long>>()
        children(bytes, root.data, root.end).filter { it.id == ID_CUE_POINT }.forEach { point ->
            val pointChildren = children(bytes, point.data, point.end)
            val time = pointChildren.firstOrNull { it.id == ID_CUE_TIME }?.let { unsigned(bytes, it) } ?: return@forEach
            // CueTrack often points to the video track, not the subtitle track. The actual
            // selected subtitle TrackNumber is filtered while decoding each Cluster's Blocks.
            pointChildren.filter { it.id == ID_CUE_TRACK_POSITIONS }.forEach { pos ->
                val p = children(bytes, pos.data, pos.end)
                val cluster = p.firstOrNull { it.id == ID_CUE_CLUSTER_POSITION }?.let { unsigned(bytes, it) }
                if (cluster != null) result += time to cluster
            }
        }
        return result
    }

    private suspend fun seekElement(
        bytes: ByteArray, top: List<Element>, wanted: Long, segmentData: Long,
        url: String, headers: Map<String, String>, budget: Budget,
    ): Element? {
        val seekHead = top.firstOrNull { it.id == ID_SEEK_HEAD } ?: return null
        val raw = elementBytes(bytes, seekHead, url, headers, budget) ?: return null
        val root = readElement(raw, 0) ?: return null
        for (seek in children(raw, root.data, root.end).filter { it.id == ID_SEEK }) {
            val parts = children(raw, seek.data, seek.end)
            val id = parts.firstOrNull { it.id == ID_SEEK_ID }?.let { binaryId(raw, it) }
            val position = parts.firstOrNull { it.id == ID_SEEK_POSITION }?.let { unsigned(raw, it) }
            if (id == wanted && position != null) {
                val absolute = segmentData + position
                val header = range(url, headers, absolute, 64, budget) ?: return null
                val element = readElement(header, 0) ?: return null
                if (element.id != wanted) return null
                return element.copy(
                    data = (absolute + element.data).toInt(),
                    end = (absolute + element.end).toInt(),
                )
            }
        }
        return null
    }

    private suspend fun elementBytes(
        initial: ByteArray, element: Element, url: String, headers: Map<String, String>, budget: Budget,
    ): ByteArray? {
        if (element.data >= 0 && element.end <= initial.size) return initial.copyOfRange(
            (element.data - element.headerSize).coerceAtLeast(0), element.end
        )
        val absolute = element.data.toLong() - element.headerSize
        val size = element.headerSize + element.size
        if (size <= 0 || size > 8 * 1024 * 1024) return null
        return range(url, headers, absolute, size, budget)
    }

    private suspend fun range(
        url: String, headers: Map<String, String>, start: Long, length: Int, budget: Budget,
    ): ByteArray? {
        if (length <= 0 || budget.requests >= MAX_REQUESTS || length.toLong() > budget.remainingBytes()) return null
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (k, v) ->
                if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true)) header(k, v)
            } }.build()
        val call = client.newCall(request)
        budget.requests++
        val bytes = suspendCancellableCoroutine<ByteArray?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val value = runCatching {
                        response.use {
                            if (it.code != 206 && !(it.code == 200 && start == 0L)) return@use null
                            val body = it.body ?: return@use null
                            budget.totalLength = it.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: budget.totalLength
                            val stream = body.byteStream()
                            val out = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val n = stream.read(out, offset, length - offset)
                                if (n < 0) break
                                offset += n
                            }
                            if (offset == 0) null else out.copyOf(offset)
                        }
                    }.getOrNull()
                    if (cont.isActive) cont.resume(value)
                }
            })
        }
        if (bytes != null) budget.bytes += bytes.size
        return bytes
    }

    private fun ticksToMs(ticks: Long, scaleNs: Long): Long =
        runCatching { Math.multiplyExact(ticks, scaleNs) / 1_000_000L }.getOrDefault(Long.MAX_VALUE)

    private fun parseScale(bytes: ByteArray): Long {
        val root = readElement(bytes, 0) ?: return DEFAULT_SCALE
        return children(bytes, root.data, root.end).firstOrNull { it.id == ID_TIMESTAMP_SCALE }
            ?.let { unsigned(bytes, it) }?.takeIf { it > 0L } ?: DEFAULT_SCALE
    }

    private fun find(bytes: ByteArray, id: Long): Element? {
        val offset = findOffset(bytes, id) ?: return null
        return readElement(bytes, offset)
    }

    private fun findOffset(bytes: ByteArray, id: Long): Int? {
        val needle = idBytes(id)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun idBytes(id: Long): ByteArray {
        var len = 1
        while (len < 4 && id ushr (len * 8) != 0L) len++
        return ByteArray(len) { i -> (id ushr ((len - 1 - i) * 8)).toByte() }
    }

    private fun children(bytes: ByteArray, start: Int, end: Int): List<Element> {
        val out = mutableListOf<Element>()
        val safeEnd = end.coerceAtMost(bytes.size)
        var p = start
        var n = 0
        while (p < safeEnd && n++ < 300_000) {
            val e = readElement(bytes, p) ?: break
            if (e.end > safeEnd || e.end <= p) break
            out += e
            p = e.end
        }
        return out
    }

    private fun readElement(bytes: ByteArray, offset: Int, limit: Int = bytes.size): Element? {
        val safeLimit = limit.coerceAtMost(bytes.size)
        val id = vint(bytes, offset, safeLimit, false) ?: return null
        val size = vint(bytes, offset + id.second, safeLimit, true) ?: return null
        val data = offset + id.second + size.second
        if (size.first < 0) return null
        val unknownSize = size.first == (1L shl (7 * size.second)) - 1L
        val actualSize = if (unknownSize) (safeLimit - data).coerceAtLeast(0).toLong() else size.first
        if (actualSize > Int.MAX_VALUE || data.toLong() + actualSize > Int.MAX_VALUE) return null
        return Element(
            id.first, data, data + actualSize.toInt(), actualSize.toInt(),
            id.second + size.second,
        )
    }

    private fun vint(b: ByteArray, off: Int, limit: Int, strip: Boolean): Pair<Long, Int>? {
        if (off < 0 || off >= limit) return null
        val first = b[off].toInt() and 255
        if (first == 0) return null
        var mask = 128; var len = 1
        while ((first and mask) == 0) { mask = mask ushr 1; len++; if (len > 8) return null }
        if (off + len > limit) return null
        var v = (first and if (strip) mask - 1 else 255).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 255)
        return v to len
    }

    private fun unsigned(b: ByteArray, e: Element): Long {
        if (e.size !in 1..8) return 0
        var v = 0L
        for (i in e.data until e.end) v = (v shl 8) or (b[i].toLong() and 255)
        return v
    }
    private fun binaryId(b: ByteArray, e: Element): Long {
        var v = 0L
        for (i in e.data until e.end) v = (v shl 8) or (b[i].toLong() and 255)
        return v
    }
    private fun utf8(b: ByteArray, e: Element) = b.decodeToString(e.data, e.end).trim('\u0000')
    private data class Element(val id: Long, val data: Int, val end: Int, val size: Int, val headerSize: Int)
    private data class Budget(var requests: Int = 0, var bytes: Long = 0, var totalLength: Long? = null) {
        fun remainingBytes() = (MAX_BYTES - bytes).coerceAtLeast(0L)
    }

    private const val DEFAULT_SCALE = 1_000_000L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L
    private const val ID_CLUSTER = 0x1F43B675L
}
