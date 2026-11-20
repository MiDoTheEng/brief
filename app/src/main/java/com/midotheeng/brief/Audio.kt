package com.midotheeng.brief

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** A piece of audio ready to send, and where it starts in the video (seconds). */
class Piece(val file: File, val mime: String, val startSec: Double)

/**
 * Pulls only the sound out of a video (no re-encoding, so it's quick) and cuts it
 * into pieces small enough for Groq's free tier (25 MB each).
 */
object Audio {
    private const val MAX_BYTES = 23L * 1024 * 1024
    private const val PIECE_SEC = 15 * 60L
    private val DIRECT = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "flac" to "audio/flac", "opus" to "audio/opus", "aac" to "audio/aac",
    )

    class NoSound : BriefException("This video has no sound, so there's nothing to listen to.")

    fun split(input: File, outDir: File): List<Piece> {
        outDir.mkdirs()
        val ext = input.extension.lowercase()
        // Small audio files go as they are.
        if (input.length() <= MAX_BYTES && ext in DIRECT) return listOf(Piece(input, DIRECT.getValue(ext), 0.0))

        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(input.path) } catch (e: Exception) {
                throw BriefException("Couldn't open this video file.")
            }
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw NoSound()
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            val (container, outExt, outMime) = when (mime) {
                "audio/mp4a-latm" -> Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4")
                "audio/opus", "audio/vorbis" -> Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "audio/webm")
                else -> {
                    if (input.length() <= MAX_BYTES) return listOf(Piece(input, guessMime(ext), 0.0))
                    throw BriefException("This video's sound format ($mime) isn't supported yet.")
                }
            }
            ex.selectTrack(track)
            val bufSize = maxOf(if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0, 1 shl 20)
            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()

            val pieces = mutableListOf<Piece>()
            var muxer: MediaMuxer? = null
            var outTrack = -1
            var pieceStartUs = 0L
            var pieceBytes = 0L
            var lastUs = -1L

            fun close() {
                muxer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                muxer = null
            }

            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (muxer == null || t - pieceStartUs >= PIECE_SEC * 1_000_000 || pieceBytes + size > MAX_BYTES) {
                    close()
                    val f = File(outDir, "piece_${pieces.size}.$outExt")
                    f.delete()
                    val m = MediaMuxer(f.path, container)
                    outTrack = m.addTrack(fmt)
                    m.start()
                    muxer = m
                    pieceStartUs = t
                    pieceBytes = 0
                    lastUs = -1
                    pieces += Piece(f, outMime, t / 1_000_000.0)
                }
                var rel = t - pieceStartUs
                if (rel <= lastUs) rel = lastUs + 1
                lastUs = rel
                info.set(0, size, rel, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer!!.writeSampleData(outTrack, buf, info)
                pieceBytes += size
                ex.advance()
            }
            close()
            if (pieces.isEmpty()) throw NoSound()
            return pieces.filter { it.file.length() > 1024 }.ifEmpty { throw NoSound() }
        } finally {
            ex.release()
        }
    }

    private fun guessMime(ext: String) = when (ext) {
        "mp4", "m4v", "mov", "3gp" -> "video/mp4"
        "webm", "mkv" -> "video/webm"
        else -> DIRECT[ext] ?: "application/octet-stream"
    }
}
