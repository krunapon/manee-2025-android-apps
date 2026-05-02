package th.ac.kkw.intentdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

// OverlayView.kt
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var landmarks: List<NormalizedLandmark> = emptyList()

    private val pointPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.FILL
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    fun setLandmarks(lms: List<NormalizedLandmark>) {
        landmarks = lms
        invalidate()
    }

    fun clear() {
        landmarks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in landmarks) {
            canvas.drawCircle(
                p.x() * width,
                p.y() * height,
                8f, pointPaint
            )
        }
    }
}
