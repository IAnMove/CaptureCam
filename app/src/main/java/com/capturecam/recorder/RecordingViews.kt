package com.capturecam.recorder

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Celda con proporción fija (más alta que ancha, como una pantalla de móvil). */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        // Recorta la miniatura a las esquinas redondeadas del fondo (por XML solo vale desde Android 12).
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec((width * RATIO).toInt(), MeasureSpec.EXACTLY)
        )
    }

    private companion object {
        const val RATIO = 1.55f
    }
}

object RecordingCell {
    /** Infla una celda (reutilizando [convertView] si la hay) y la rellena con [recording]. */
    fun bind(
        context: Context, parent: ViewGroup?, convertView: View?, recording: Recording
    ): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_recording, parent, false)
        view.findViewById<TextView>(R.id.duration).text =
            RecorderService.formatTime(recording.durationMs)
        Thumbs.load(context, recording.uri, view.findViewById<ImageView>(R.id.thumb))
        return view
    }
}
