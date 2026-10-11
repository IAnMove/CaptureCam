package com.capturecam.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Codifica la pantalla (H.264, desde [surface]) y el audio (AAC, desde [audio]) y lo escribe en un MP4.
 * Sustituye a MediaRecorder, que solo admite una fuente de audio y no puede recoger el sonido de las apps.
 *
 * Uso: crear, [start], pintar el VirtualDisplay en [surface] y al terminar [stop].
 *
 * Todo va en una sola línea de tiempo: la hora monotónica del sistema, que es también la de los fotogramas
 * de la superficie. A cada marca de tiempo se le resta el instante de inicio y el tiempo en pausa.
 */
class ScreenEncoder(
    fd: FileDescriptor,
    video: Settings.VideoParams,
    private val audio: AudioCapture?,
    /** Se llama (en un hilo propio) si el codificador de vídeo falla; el que llama debe parar. */
    private val onError: () -> Unit,
) {
    lateinit var surface: Surface
        private set

    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false
    private var stopped = false

    // Línea de tiempo (microsegundos).
    private var originUs = 0L
    @Volatile private var pausedTotalUs = 0L
    private var pausedAtUs = 0L
    private var resumedAtUs = 0L
    @Volatile private var paused = false
    /** Tras reanudar, el vídeo descarta fotogramas hasta el primer fotograma clave: los demás referencian los descartados. */
    @Volatile private var waitingKeyFrame = false

    // Estado del escritor MP4; el MediaMuxer no es seguro entre hilos, así que todo va bajo [lock].
    private val lock = Any()
    private var expectedTracks = if (audio != null) 2 else 1
    private var addedTracks = 0
    private var muxerStarted = false
    private val pending = ArrayList<Pending>()
    private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private var videoSamples = 0
    private var audioSamples = 0
    @Volatile private var audioTrack = -1
    private var lastAudioOutUs = -1L

    init {
        try {
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, video.width, video.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, video.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, video.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // Con la pantalla quieta el sistema no manda fotogramas; así el vídeo no se queda corto frente al audio.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = videoCodec.createInputSurface()

            if (audio != null) {
                val af = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, AudioCapture.SAMPLE_RATE, 2
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioCapture.FRAMES * 4)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec = codec
            }
        } catch (e: Exception) {
            releaseAll()
            runCatching { muxer.release() }
            throw e
        }
    }

    fun start() {
        originUs = nowUs()
        running = true
        videoCodec.start()
        audioCodec?.start()
        videoThread = Thread({ videoLoop() }, "cc-video").also { it.start() }
        if (audio != null) audioThread = Thread({ audioLoop() }, "cc-audio").also { it.start() }
    }

    fun pause() {
        if (paused) return
        pausedAtUs = nowUs()
        paused = true
    }

    fun resume() {
        if (!paused) return
        resumedAtUs = nowUs()
        pausedTotalUs += resumedAtUs - pausedAtUs
        waitingKeyFrame = true
        runCatching {
            videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        paused = false
    }

    /** Termina el archivo. Devuelve true si quedó un vídeo con al menos un fotograma. */
    fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        running = false
        runCatching { videoCodec.signalEndOfInputStream() }
        audioThread?.join(JOIN_MS)
        videoThread?.join(JOIN_MS)
        audio?.stop()

        var saved = false
        synchronized(lock) {
            if (muxerStarted) {
                // stop() lanza si alguna pista quedó sin muestras.
                try {
                    muxer.stop()
                    saved = videoSamples > 0
                } catch (e: Exception) {
                    Log.w(TAG, "MediaMuxer.stop() falló", e)
                }
            }
            muxerStarted = false
            runCatching { muxer.release() }
        }
        Log.i(TAG, "Fin: $videoSamples fotogramas, $audioSamples bloques de audio" +
            (audio?.let { ", picos apps=${it.peakApps} mic=${it.peakMic}" } ?: ""))
        releaseAll()
        return saved
    }

    private fun releaseAll() {
        runCatching { videoCodec.stop() }
        runCatching { videoCodec.release() }
        audioCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        if (this::surface.isInitialized) runCatching { surface.release() }
    }

    // ------------------------------------------------------------------ vídeo

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastPts = -1L
        try {
            while (true) {
                val i = videoCodec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = addTrack(videoCodec.outputFormat)
                } else if (i >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val data = videoCodec.getOutputBuffer(i)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && info.size > 0 && !config && track >= 0 && acceptVideo(info)) {
                        lastPts = max(info.presentationTimeUs - originUs - pausedTotalUs, lastPts + 1)
                        write(track, data, info, lastPts)
                        videoSamples++
                    }
                    videoCodec.releaseOutputBuffer(i, false)
                    if (eos) return
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Falló el codificador de vídeo", e)
                onError()
            }
        }
    }

    /** En pausa no se guarda nada; al reanudar, se espera al primer fotograma clave posterior. */
    private fun acceptVideo(info: MediaCodec.BufferInfo): Boolean {
        if (paused) return false
        if (!waitingKeyFrame) return true
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key && info.presentationTimeUs >= resumedAtUs) {
            waitingKeyFrame = false
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ audio

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val capture = audio ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(AudioCapture.FRAMES * 2)
        var anchorUs = -1L
        var blocks = 0L
        var lastPts = 0L
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            capture.start()
            while (running) {
                if (!capture.read(pcm)) {
                    Log.w(TAG, "La captura de audio terminó")
                    break
                }
                // Leer bloquea hasta completar el bloque, así que ahora es el final de su último muestreo.
                val nowUs = nowUs()
                if (anchorUs < 0) anchorUs = nowUs - blockUs(1)
                // Si el hilo se retrasó más que el buffer de AudioRecord, se perdió audio: lo que llega ahora es
                // más reciente de lo que dice la cuenta de bloques. Se recoloca para no adelantar el sonido.
                val lagUs = nowUs - (anchorUs + blockUs(blocks + 1))
                val bufferUs = blockUs(AudioCapture.BUFFER_BLOCKS.toLong())
                if (lagUs > bufferUs) {
                    Log.w(TAG, "Audio retrasado ${lagUs / 1000} ms: se perdieron muestras")
                    anchorUs += lagUs - (bufferUs - blockUs(1))
                }
                val rawPts = anchorUs + blockUs(blocks)
                blocks++
                if (paused) continue   // se lee igual para que el contador siga la hora real, pero no se guarda

                lastPts = max(rawPts - originUs - pausedTotalUs, lastPts + 1)
                // Si el codificador va justo, espera un poco (el buffer de AudioRecord aguanta unos 90 ms).
                var idx = -1
                for (attempt in 0 until INPUT_RETRIES) {
                    idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) break
                    drainAudio(codec, info, untilEos = false)
                }
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx) ?: continue
                    buf.clear()
                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                    codec.queueInputBuffer(idx, 0, pcm.size * 2, lastPts, 0)
                } else {
                    Log.w(TAG, "Codificador de audio ocupado: se pierde un bloque")
                }
                drainAudio(codec, info, untilEos = false)
            }
            finishAudio(codec, info, lastPts)
        } catch (e: Exception) {
            // Sin audio se puede seguir grabando vídeo.
            Log.w(TAG, "Falló el audio", e)
        } finally {
            if (audioTrack < 0) dropAudioTrack()
        }
    }

    private fun finishAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, lastPts: Long) {
        var idx = -1
        for (attempt in 0 until 50) {
            idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) break
            drainAudio(codec, info, untilEos = false)
        }
        if (idx >= 0) {
            codec.queueInputBuffer(idx, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainAudio(codec, info, untilEos = true)
        }
    }

    /** Pasa lo ya codificado al MP4. Con [untilEos] espera hasta el final de la secuencia (máximo un segundo). */
    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++idle > 100) return
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrack = addTrack(codec.outputFormat)
            } else if (i >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val data = codec.getOutputBuffer(i)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (data != null && info.size > 0 && !config && audioTrack >= 0) {
                    lastAudioOutUs = max(info.presentationTimeUs, lastAudioOutUs + 1)
                    write(audioTrack, data, info, lastAudioOutUs)
                    audioSamples++
                }
                codec.releaseOutputBuffer(i, false)
                if (eos) return
            }
        }
    }

    // ----------------------------------------------------------------- escritor

    private fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        val index = muxer.addTrack(format)
        addedTracks++
        startMuxerIfReady()
        index
    }

    /** El audio no llegó a arrancar: se graba solo el vídeo en vez de esperar una pista que no vendrá. */
    private fun dropAudioTrack() = synchronized(lock) {
        if (expectedTracks > 1) {
            expectedTracks--
            startMuxerIfReady()
        }
    }

    /** Con el lock tomado. El MP4 solo arranca cuando están todas las pistas; lo anterior espera en [pending]. */
    private fun startMuxerIfReady() {
        if (muxerStarted || addedTracks < expectedTracks) return
        muxer.start()
        muxerStarted = true
        for (p in pending) muxer.writeSampleData(p.track, p.data, p.info)
        pending.clear()
    }

    private fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) {
        data.position(info.offset)
        data.limit(info.offset + info.size)
        val out = MediaCodec.BufferInfo().apply { set(0, info.size, ptsUs, info.flags) }
        synchronized(lock) {
            if (muxerStarted) {
                out.offset = info.offset
                muxer.writeSampleData(track, data, out)
            } else {
                val copy = ByteBuffer.allocate(info.size)
                copy.put(data)
                copy.flip()
                pending.add(Pending(track, copy, out))
            }
        }
    }

    private fun nowUs() = System.nanoTime() / 1000

    /** Duración de [blocks] bloques de audio, en microsegundos. */
    private fun blockUs(blocks: Long) = blocks * AudioCapture.FRAMES * 1_000_000L / AudioCapture.SAMPLE_RATE

    companion object {
        private const val TAG = "CaptureCam"
        private const val TIMEOUT_US = 10_000L
        private const val JOIN_MS = 2_000L
        private const val INPUT_RETRIES = 6
    }
}
