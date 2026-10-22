package com.capturecam.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Recodifica un MP4 de CaptureCam con menos bitrate: decodificador -> superficie de entrada del
 * codificador H.264 (sin pasar por la CPU), misma resolución y los mismos tiempos de cada fotograma.
 * El audio AAC no se toca: se copia muestra a muestra.
 *
 * Bloquea el hilo que lo llama hasta terminar. [cancelled] se consulta en cada vuelta.
 */
class VideoCompressor(
    private val context: Context,
    private val source: Uri,
    private val sourceBytes: Long,
    private val output: FileDescriptor,
    private val onProgress: (Int) -> Unit,
    private val cancelled: () -> Boolean,
) {
    class CancelledException : Exception()

    private val videoEx = MediaExtractor()
    private val audioEx = MediaExtractor()
    private val encInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()
    private val audioBuf = ByteBuffer.allocate(AUDIO_BUF_BYTES)

    private lateinit var encoder: MediaCodec
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioFormat: MediaFormat? = null
    private var rotation = 0
    private var durationUs = 0L
    private var lastPts = -1L
    private var lastPercent = -1
    private var lastActivity = 0L

    fun run() {
        var decoder: MediaCodec? = null
        var inputSurface: Surface? = null
        try {
            videoEx.setDataSource(context, source, null)
            val vIndex = findTrack(videoEx, "video/")
            if (vIndex < 0) error("El archivo no tiene vídeo")
            videoEx.selectTrack(vIndex)
            val vFormat = videoEx.getTrackFormat(vIndex)

            audioEx.setDataSource(context, source, null)
            val aIndex = findTrack(audioEx, "audio/")
            if (aIndex >= 0) {
                audioEx.selectTrack(aIndex)
                audioFormat = audioEx.getTrackFormat(aIndex)
            }

            val width = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
            durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION) else 0

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate(vFormat))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate(vFormat))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val dec = MediaCodec.createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(vFormat, inputSurface, null, 0)
            dec.start()

            transcode(dec)

            copyAudio(Long.MAX_VALUE)
            if (videoTrack < 0) error("No se generó ningún fotograma")
            muxer?.stop()
            muxerStarted = false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (this::encoder.isInitialized) {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
            }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoEx.release() }
            runCatching { audioEx.release() }
        }
    }

    private fun transcode(decoder: MediaCodec) {
        val decInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        lastActivity = SystemClock.elapsedRealtime()

        while (!encoderDone) {
            if (cancelled()) throw CancelledException()
            if (SystemClock.elapsedRealtime() - lastActivity > STALL_MS) error("El codificador no responde")

            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val buf = decoder.getInputBuffer(i)!!
                    val n = videoEx.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, n, videoEx.sampleTime, 0)
                        videoEx.advance()
                    }
                    lastActivity = SystemClock.elapsedRealtime()
                }
            }

            if (!decoderDone) {
                val o = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (o >= 0) {
                    // Al renderizar sobre la superficie del codificador, el tiempo del fotograma pasa
                    // tal cual a la salida.
                    decoder.releaseOutputBuffer(o, decInfo.size > 0)
                    lastActivity = SystemClock.elapsedRealtime()
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // El último fotograma se entrega a la superficie de forma asíncrona: un respiro
                        // para que llegue al codificador antes de cerrar la entrada.
                        Thread.sleep(LAST_FRAME_GRACE_MS)
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    }
                }
            }

            encoderDone = drainEncoder(if (decoderDone) TIMEOUT_US else 0L)
        }
    }

    /** Pasa al archivo lo que haya listo en el codificador. Devuelve true al llegar al final. */
    private fun drainEncoder(timeoutUs: Long): Boolean {
        var timeout = timeoutUs
        while (true) {
            val i = encoder.dequeueOutputBuffer(encInfo, timeout)
            timeout = 0L
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(encoder.outputFormat)
                i >= 0 -> {
                    lastActivity = SystemClock.elapsedRealtime()
                    val data = encoder.getOutputBuffer(i)
                    val config = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && encInfo.size > 0 && !config && muxerStarted) {
                        val pts = max(encInfo.presentationTimeUs, lastPts + 1)
                        encInfo.presentationTimeUs = pts
                        lastPts = pts
                        muxer?.writeSampleData(videoTrack, data, encInfo)
                        copyAudio(pts)
                        reportProgress(pts)
                    }
                    encoder.releaseOutputBuffer(i, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun startMuxer(videoFormat: MediaFormat) {
        val m = muxer ?: return
        if (muxerStarted) return
        videoTrack = m.addTrack(videoFormat)
        audioFormat?.let { audioTrack = m.addTrack(it) }
        if (rotation != 0) m.setOrientationHint(rotation)
        m.start()
        muxerStarted = true
    }

    /** Copia las muestras de audio hasta [untilUs], para que el archivo quede intercalado. */
    private fun copyAudio(untilUs: Long) {
        val m = muxer ?: return
        if (audioTrack < 0 || !muxerStarted) return
        while (true) {
            val t = audioEx.sampleTime
            if (t < 0 || t > untilUs) return
            audioBuf.clear()
            val n = audioEx.readSampleData(audioBuf, 0)
            if (n < 0) return
            val key = audioEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            audioInfo.set(0, n, t, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            m.writeSampleData(audioTrack, audioBuf, audioInfo)
            audioEx.advance()
        }
    }

    private fun reportProgress(ptsUs: Long) {
        if (durationUs <= 0) return
        val percent = (ptsUs * 100 / durationUs).toInt().coerceIn(0, 99)
        if (percent != lastPercent) {
            lastPercent = percent
            onProgress(percent)
        }
    }

    /**
     * Una parte del bitrate del original. Se estima con el tamaño del archivo, porque el MP4 no suele
     * decir su bitrate, y se le quita el audio. Nunca sube del 90 % del original.
     */
    private fun targetBitrate(vFormat: MediaFormat): Int {
        val audioBps = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }
            ?.getInteger(MediaFormat.KEY_BIT_RATE) ?: DEFAULT_AUDIO_BPS
        val srcBps = when {
            durationUs > 0 && sourceBytes > 0 -> sourceBytes * 8_000_000L / durationUs - audioBps
            vFormat.containsKey(MediaFormat.KEY_BIT_RATE) -> vFormat.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
            else -> DEFAULT_VIDEO_BPS
        }.coerceAtLeast(MIN_BPS)
        return (srcBps * KEEP_RATIO).toLong().coerceIn(MIN_BPS, max(MIN_BPS, srcBps * 9 / 10)).toInt()
    }

    private fun frameRate(vFormat: MediaFormat): Int {
        val declared = when {
            !vFormat.containsKey(MediaFormat.KEY_FRAME_RATE) -> 30
            else -> try {
                vFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (e: ClassCastException) {
                vFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
            }
        }
        return declared.coerceIn(24, 60)
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val STALL_MS = 20_000L
        const val LAST_FRAME_GRACE_MS = 150L
        const val AUDIO_BUF_BYTES = 256 * 1024
        const val DEFAULT_AUDIO_BPS = 128_000
        const val DEFAULT_VIDEO_BPS = 6_000_000L
        const val MIN_BPS = 300_000L

        /** Fracción del bitrate de vídeo que se conserva: el archivo queda en torno a la mitad o menos. */
        const val KEEP_RATIO = 0.45
    }
}
