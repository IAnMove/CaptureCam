package com.capturecam.recorder

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * El 3, 2, 1 grande en mitad de la pantalla, por encima de cualquier app.
 * Necesita el permiso "Aparecer encima" (SYSTEM_ALERT_WINDOW).
 */
class CountdownOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var label: TextView? = null

    fun show(number: Int) {
        val view = label ?: create() ?: return
        view.text = number.toString()
        // Cada número entra grande y se encoge, para que se note el cambio.
        view.scaleX = 1.4f
        view.scaleY = 1.4f
        view.animate().scaleX(1f).scaleY(1f).setDuration(600).start()
    }

    /** Quita la ventana al instante; si se dejara para después saldría en los primeros fotogramas. */
    fun hide() {
        label?.let { view ->
            view.animate().cancel()
            runCatching { windowManager.removeViewImmediate(view) }
        }
        label = null
    }

    private fun create(): TextView? {
        val density = context.resources.displayMetrics.density
        val size = (200 * density).toInt()
        val view = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6000000.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        return try {
            windowManager.addView(view, params)
            label = view
            view
        } catch (e: Exception) {
            // Permiso retirado a mitad de camino: se sigue sin cuenta atrás en pantalla.
            android.util.Log.w("CaptureCam", "No se pudo mostrar la cuenta atrás", e)
            null
        }
    }
}
