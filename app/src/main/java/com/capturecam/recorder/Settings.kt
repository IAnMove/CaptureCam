package com.capturecam.recorder

import android.content.Context
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

/** Preferencias del usuario. Los valores "índice" apuntan a los arrays de strings.xml. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var resolution: Int
        get() = prefs.getInt("resolution", 0)
        set(v) = prefs.edit().putInt("resolution", v).apply()

    var fps: Int
        get() = prefs.getInt("fps", 1)
        set(v) = prefs.edit().putInt("fps", v).apply()

    var quality: Int
        get() = prefs.getInt("quality", 1)
        set(v) = prefs.edit().putInt("quality", v).apply()

    var orientation: Int
        get() = prefs.getInt("orientation", 0)
        set(v) = prefs.edit().putInt("orientation", v).apply()

    var mic: Boolean
        get() = prefs.getBoolean("mic", false)
        set(v) = prefs.edit().putBoolean("mic", v).apply()

    /** Sonido que emiten las apps (captura de reproducción). Activado por defecto. */
    var appAudio: Boolean
        get() = prefs.getBoolean("app_audio", true)
        set(v) = prefs.edit().putBoolean("app_audio", v).apply()

    var countdown: Boolean
        get() = prefs.getBoolean("countdown", true)
        set(v) = prefs.edit().putBoolean("countdown", v).apply()

    /** Si ya se explicó el permiso "Aparecer encima" (para no repetirlo en cada grabación). */
    var overlayPrompted: Boolean
        get() = prefs.getBoolean("overlay_prompted", false)
        set(v) = prefs.edit().putBoolean("overlay_prompted", v).apply()

    /** 0 = Películas/CaptureCam, 1 = Descargas/CaptureCam (por defecto). */
    var storage: Int
        get() = prefs.getInt("storage", 1)
        set(v) = prefs.edit().putInt("storage", v).apply()

    companion object {
        /** Lado corto objetivo por posición del spinner; 0 = resolución original. */
        private val SHORT_SIDE = intArrayOf(0, 1080, 720, 480)
        private val FPS = intArrayOf(24, 30, 60)

        /** Bits por píxel y fotograma: calidad baja / media / alta. */
        private val BITS_PER_PIXEL = doubleArrayOf(0.05, 0.09, 0.14)
    }

    class VideoParams(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    /** Calcula tamaño, fps y bitrate a partir de la pantalla actual y los ajustes. */
    fun videoParams(context: Context): VideoParams {
        val bounds = context.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        var w = bounds.width()
        var h = bounds.height()

        when (orientation) {
            1 -> if (w > h) w = h.also { h = w }   // vertical
            2 -> if (h > w) w = h.also { h = w }   // horizontal
        }

        val target = SHORT_SIDE.getOrElse(resolution) { 0 }
        if (target in 1 until min(w, h)) {
            val scale = target.toDouble() / min(w, h)
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
        }
        // Los codificadores H.264 exigen dimensiones pares.
        w = w and 1.inv()
        h = h and 1.inv()

        val fps = FPS.getOrElse(fps) { 30 }
        val bpp = BITS_PER_PIXEL.getOrElse(quality) { 0.09 }
        val bitrate = (w.toLong() * h * fps * bpp).toLong()
            .coerceIn(1_000_000L, 40_000_000L).toInt()
        return VideoParams(w, h, fps, bitrate)
    }
}
