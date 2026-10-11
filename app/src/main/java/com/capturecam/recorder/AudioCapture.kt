package com.capturecam.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Audio PCM de 16 bits, estéreo a 44,1 kHz: el sonido que emiten las apps (captura de reproducción de
 * MediaProjection), el micrófono, o los dos sumados.
 */
class AudioCapture private constructor(
    private var apps: AudioRecord?,
    private var mic: AudioRecord?,
) {
    private val appsBuf = ShortArray(FRAMES * 2)
    private val micBuf = ShortArray(FRAMES)

    /** Nivel máximo visto en cada fuente (0 a 32768); si el de las apps queda en 0, esa app no deja capturarla. */
    var peakApps = 0
        private set
    var peakMic = 0
        private set

    fun start() {
        apps?.startRecording()
        mic?.startRecording()
    }

    /**
     * Rellena [out] con [FRAMES] fotogramas intercalados (izquierda, derecha). Bloquea lo que dura un bloque,
     * unos 23 ms. Devuelve false cuando ya no queda ninguna fuente viva.
     */
    fun read(out: ShortArray): Boolean {
        apps?.let { if (!fill(it, appsBuf, FRAMES * 2)) { it.release(); apps = null } }
        mic?.let { if (!fill(it, micBuf, FRAMES)) { it.release(); mic = null } }
        val hasApps = apps != null
        val hasMic = mic != null
        if (!hasApps && !hasMic) return false

        for (i in 0 until FRAMES) {
            val m = if (hasMic) micBuf[i].toInt() else 0
            val l = if (hasApps) appsBuf[2 * i].toInt() else 0
            val r = if (hasApps) appsBuf[2 * i + 1].toInt() else 0
            if (hasApps) peakApps = max(peakApps, max(abs(l), abs(r)))
            if (hasMic) peakMic = max(peakMic, abs(m))
            // El micrófono es mono: va igual a los dos canales. Se satura en vez de desbordar.
            out[2 * i] = (l + m).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[2 * i + 1] = (r + m).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return true
    }

    fun stop() {
        for (r in listOfNotNull(apps, mic)) {
            runCatching { r.stop() }
            r.release()
        }
        apps = null
        mic = null
    }

    private fun fill(record: AudioRecord, buf: ShortArray, count: Int): Boolean {
        var got = 0
        while (got < count) {
            val n = record.read(buf, got, count - got, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                Log.w(TAG, "AudioRecord.read devolvió $n")
                return false
            }
            got += n
        }
        return true
    }

    companion object {
        private const val TAG = "CaptureCam"
        const val SAMPLE_RATE = 44_100

        /** Fotogramas por bloque: unos 23 ms, y un fotograma AAC mide justo 1024. */
        const val FRAMES = 1024

        /**
         * Bloques que caben en el buffer de cada AudioRecord (unos 370 ms). Si el hilo se retrasa más que
         * eso, el sistema sobrescribe audio sin avisar.
         */
        const val BUFFER_BLOCKS = 16

        /**
         * Crea las fuentes pedidas; null si no se pudo crear ninguna. Una fuente que falla (por ejemplo, el
         * sistema no deja capturar el sonido) se omite y se sigue con la otra.
         * El que llama tiene que haber comprobado ya el permiso RECORD_AUDIO.
         */
        @SuppressLint("MissingPermission")
        fun create(projection: MediaProjection, wantApps: Boolean, wantMic: Boolean): AudioCapture? {
            val apps = if (wantApps) try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format(AudioFormat.CHANNEL_IN_STEREO))
                    .setBufferSizeInBytes(bufferBytes(AudioFormat.CHANNEL_IN_STEREO, BUFFER_BLOCKS))
                    .build()
                    .initializedOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo capturar el sonido de las apps", e)
                null
            } else null

            val mic = if (wantMic) try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                    .setBufferSizeInBytes(bufferBytes(AudioFormat.CHANNEL_IN_MONO, BUFFER_BLOCKS))
                    .build()
                    .initializedOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir el micrófono", e)
                null
            } else null

            return if (apps == null && mic == null) null else AudioCapture(apps, mic)
        }

        private fun format(channelMask: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()

        /** Un buffer de [blocks] bloques como mínimo, para no perder audio si el hilo se retrasa. */
        private fun bufferBytes(channelMask: Int, blocks: Int): Int {
            val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            return max(min, FRAMES * channels * 2 * blocks)
        }

        private fun AudioRecord.initializedOrNull(): AudioRecord? {
            if (state == AudioRecord.STATE_INITIALIZED) return this
            release()
            return null
        }
    }
}
