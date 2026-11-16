package cl.alarmaiot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import cl.alarmaiot.R
import com.google.android.material.color.MaterialColors

/** Gráfico de línea simple con las últimas lecturas de distancia y el umbral de 20 cm. */
class DistanceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val values = ArrayDeque<Float>()
    var maxPoints = 60
    var maxCm = 100f
    var thresholdCm = 20f

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1D3B8B.toInt())
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x22000000)
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.estado_alerta)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = linePaint.color }
    private val path = Path()

    init {
        contentDescription = "Gráfico de distancia"
    }

    fun isEmpty() = values.isEmpty()

    fun setData(list: List<Float>) {
        values.clear()
        list.takeLast(maxPoints).forEach { values.addLast(it.coerceIn(0f, maxCm)) }
        invalidate()
    }

    fun add(value: Float) {
        values.addLast(value.coerceIn(0f, maxCm))
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 36f * density
        val top = 8f * density
        val right = width - 8f * density
        val bottom = height - 8f * density
        val h = bottom - top
        fun y(v: Float) = bottom - (v / maxCm) * h

        for (cm in listOf(0f, 50f, 100f)) {
            canvas.drawLine(left, y(cm), right, y(cm), gridPaint)
            canvas.drawText("${cm.toInt()}", 4f * density, y(cm) + 4f * density, textPaint)
        }
        val ty = y(thresholdCm)
        canvas.drawLine(left, ty, right, ty, thresholdPaint)
        canvas.drawText("20 cm", right - 40f * density, ty - 4f * density, textPaint)

        if (values.isEmpty()) {
            canvas.drawText("Esperando lecturas…", left + 8f * density, top + h / 2, textPaint)
            return
        }
        val step = (right - left) / (maxPoints - 1).coerceAtLeast(1)
        val offset = maxPoints - values.size
        path.reset()
        values.forEachIndexed { i, v ->
            val x = left + (offset + i) * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        canvas.drawPath(path, linePaint)
        canvas.drawCircle(right, y(values.last()), 4f * density, dotPaint)
        contentDescription = "Gráfico de distancia, última lectura ${values.last().toInt()} cm"
    }
}
