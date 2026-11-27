@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import java.io.EOFException
import kotlin.math.max

/** Observes transcoded subtitle samples while forwarding the original stream unchanged. */
internal class PlayerSubtitleTimestampCaptureExtractor(
    private val delegate: Extractor,
) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.getSniffFailureDetails()
    override fun init(output: ExtractorOutput) = delegate.init(CapturingExtractorOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

private class CapturingExtractorOutput(
    private val delegate: ExtractorOutput,
) : ExtractorOutput {
    private val textOutputs = mutableMapOf<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        if (type != C.TRACK_TYPE_TEXT) return delegate.track(id, type)
        return textOutputs.getOrPut(id) {
            CapturingTrackOutput(delegate.track(id, type))
        }
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

private class CapturingTrackOutput(
    private val delegate: TrackOutput,
) : TrackOutput {
    private val decoder = CueDecoder()
    private var format: Format? = null
    private var bytes = ByteArray(0)
    private var start = 0
    private var end = 0

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val chunk = ByteArray(length)
        val read = input.read(chunk, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            return if (allowEndOfInput) C.RESULT_END_OF_INPUT else throw EOFException()
        }
        if (read > 0) {
            append(chunk, 0, read)
            delegate.sampleData(ParsableByteArray(chunk, read), read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            append(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        capture(timeUs, size, offset)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        start = (end - offset).coerceIn(start, end)
        if (start == end) {
            start = 0
            end = 0
        }
    }

    private fun capture(timeUs: Long, size: Int, offset: Int) {
        val currentFormat = format ?: return
        if (currentFormat.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES) return
        if (timeUs == C.TIME_UNSET || size <= 0) return
        val sampleStart = end - offset - size
        if (sampleStart < start || sampleStart + size > end) return
        val parsed = runCatching {
            decoder.decode(timeUs, bytes, sampleStart, size)
        }.getOrNull() ?: return
        PlayerEmbeddedSubtitleCapture.captureTranscodedSample(currentFormat, parsed)
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val retained = end - start
        val required = retained + length
        if (bytes.size - end < length) {
            bytes.copyInto(bytes, 0, start, end)
            start = 0
            end = retained
            if (bytes.size < required) {
                bytes = bytes.copyOf(max(required, max(bytes.size * 2, 256)))
            }
        }
        data.copyInto(bytes, end, offset, offset + length)
        end += length
    }
}
