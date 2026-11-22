package com.nuvio.app.features.player


/**
 * Decodes text subtitle blocks from already-fetched Matroska Cluster bytes.
 * Network access and track selection deliberately live outside this parser.
 */
internal object MatroskaSubtitleTextParser {
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL
    private const val DEFAULT_DURATION_MS = 2_500L
    private const val MAX_BLOCK_BYTES = 256 * 1024

    data class Track(
        val number: Int,
        val codecId: String,
        val codecPrivate: String? = null,
        val assFormat: List<String> = emptyList(),
    )

    data class Cue(val startTimeMs: Long, val endTimeMs: Long, val text: String)

    fun parseCluster(
        bytes: ByteArray,
        tracks: List<Track>,
        timestampScaleNs: Long,
    ): Map<Int, List<Cue>> {
        if (timestampScaleNs <= 0L) return emptyMap()
        val root = readElement(bytes, 0, bytes.size) ?: return emptyMap()
        if (root.id != ID_CLUSTER) return emptyMap()
        val clusterEnd = root.end.coerceAtMost(bytes.size)
        var clusterTimecode = 0L
        val blockRecords = mutableListOf<BlockRecord>()
        children(bytes, root.data, clusterEnd).forEach { element ->
            when (element.id) {
                ID_TIMESTAMP -> clusterTimecode = unsigned(bytes, element) ?: clusterTimecode
                ID_SIMPLE_BLOCK -> parseBlock(bytes, element)?.let {
                    blockRecords += BlockRecord(it, null)
                }
                ID_BLOCK_GROUP -> {
                    val nested = children(bytes, element.data, element.end.coerceAtMost(clusterEnd))
                    val block = nested.firstOrNull { it.id == ID_BLOCK }?.let { parseBlock(bytes, it) }
                    if (block != null) {
                        val duration = nested.firstOrNull { it.id == ID_BLOCK_DURATION }
                            ?.let { unsigned(bytes, it) }
                        blockRecords += BlockRecord(block, duration)
                    }
                }
            }
        }
        val byNumber = tracks.filter { it.codecId in TEXT_CODECS }.associateBy { it.number }
        val result = linkedMapOf<Int, MutableList<Cue>>()
        blockRecords.forEachIndexed { index, record ->
            val track = byNumber[record.block.trackNumber] ?: return@forEachIndexed
            val startTicks = clusterTimecode + record.block.relativeTimecode
            val startMs = ticksToMs(startTicks, timestampScaleNs) ?: return@forEachIndexed
            val explicitDuration = record.durationTicks?.let { ticksToMs(it, timestampScaleNs) }
                ?.takeIf { it > 0L }
            val nextStart = blockRecords.drop(index + 1).firstNotNullOfOrNull { next ->
                if (next.block.trackNumber != track.number) null
                else ticksToMs(clusterTimecode + next.block.relativeTimecode, timestampScaleNs)
            }
            val endMs = explicitDuration?.let { startMs + it }
                ?: nextStart?.takeIf { it > startMs }
                ?: startMs + DEFAULT_DURATION_MS
            val text = decodeText(track, record.block.payload) ?: return@forEachIndexed
            if (text.isNotBlank()) {
                result.getOrPut(track.number) { mutableListOf() }
                    .add(Cue(startMs, endMs.coerceAtLeast(startMs + 1L), text))
            }
        }
        return result.mapValues { (_, cues) ->
            cues.distinctBy { it.startTimeMs to it.text }.sortedBy { it.startTimeMs }
        }
    }

    private fun decodeText(track: Track, payload: ByteArray): String? {
        if (payload.isEmpty() || payload.size > MAX_BLOCK_BYTES) return null
        val raw = payload.toString(Charsets.UTF_8).trim('\u0000', '\r', '\n', ' ')
        if (raw.isBlank()) return null
        return when (track.codecId) {
            "S_TEXT/UTF8" -> raw
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
            "S_TEXT/ASS", "S_TEXT/SSA" -> decodeAssDialogue(track, raw)
            else -> null
        }
    }

    private fun decodeAssDialogue(track: Track, raw: String): String? {
        val dialogue = raw.lineSequence().firstOrNull {
            it.startsWith("Dialogue:", ignoreCase = true)
        } ?: return null
        val fields = track.assFormat.ifEmpty {
            listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        }
        val textIndex = fields.indexOfFirst { it.equals("text", true) }
        if (textIndex < 0) return null
        val values = dialogue.substringAfter(':').trimStart().split(',', limit = textIndex + 1)
        val text = values.getOrNull(textIndex) ?: return null
        return text.replace(Regex("\\{[^}]*}"), "")
            .replace("\\N", "\n", ignoreCase = false)
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
    }

    private data class Block(val trackNumber: Int, val relativeTimecode: Int, val payload: ByteArray)
    private data class BlockRecord(val block: Block, val durationTicks: Long?)
    private data class Element(val id: Long, val data: Int, val end: Int)

    private fun parseBlock(bytes: ByteArray, element: Element): Block? {
        var offset = element.data
        val end = element.end
        val trackVint = readVint(bytes, offset, end, stripMarker = true) ?: return null
        val trackNumber = trackVint.first.toInt()
        offset += trackVint.second
        if (trackNumber <= 0 || offset + 3 > end) return null
        val relative = ((bytes[offset].toInt() shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort().toInt()
        offset += 3 // signed relative timecode and flags
        if (offset >= end) return null
        return Block(trackNumber, relative, bytes.copyOfRange(offset, end))
    }

    private fun ticksToMs(ticks: Long, scaleNs: Long): Long? {
        val nanos = runCatching { Math.multiplyExact(ticks, scaleNs) }.getOrNull() ?: return null
        return nanos / 1_000_000L
    }

    private fun children(bytes: ByteArray, start: Int, end: Int): List<Element> {
        val result = mutableListOf<Element>()
        var offset = start
        var count = 0
        while (offset < end && count++ < 200_000) {
            val child = readElement(bytes, offset, end) ?: break
            if (child.end <= offset) break
            result += child
            offset = child.end
        }
        return result
    }

    private fun readElement(bytes: ByteArray, offset: Int, limit: Int): Element? {
        val id = readVint(bytes, offset, limit, stripMarker = false) ?: return null
        val size = readVint(bytes, offset + id.second, limit, stripMarker = true) ?: return null
        val data = offset + id.second + size.second
        val sizeValue = size.first
        if (sizeValue < 0L || sizeValue > Int.MAX_VALUE || data.toLong() + sizeValue > limit) return null
        return Element(id.first, data, data + sizeValue.toInt())
    }

    private fun readVint(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
        stripMarker: Boolean,
    ): Pair<Long, Int>? {
        if (offset < 0 || offset >= limit) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var length = 1
        while ((first and mask) == 0) {
            mask = mask ushr 1
            length++
            if (length > 8) return null
        }
        if (offset + length > limit) return null
        var value = (first and if (stripMarker) mask - 1 else 0xFF).toLong()
        for (i in 1 until length) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        return value to length
    }

    private fun unsigned(bytes: ByteArray, element: Element): Long? {
        val length = element.end - element.data
        if (length !in 1..8) return null
        var value = 0L
        for (i in element.data until element.end) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return value
    }

    private val TEXT_CODECS = setOf("S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA")
}
