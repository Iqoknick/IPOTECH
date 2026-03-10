package com.example.ipotech

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom circular temperature gauge view with an industrial look.
 * Displays temperature with a semicircular gauge and color-coded zones.
 */
class TemperatureGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Temperature values
    private var currentTemp: Float = 0f
    private var minTemp: Float = 0f
    private var maxTemp: Float = 200f
    private var lowThreshold: Float = 120f
    private var highThreshold: Float = 130f

    // Paints
    private val backgroundArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.ROUND
    }

    private val coldZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.BUTT
        color = Color.parseColor("#2196F3") // Blue for cold
    }

    private val normalZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.BUTT
        color = Color.parseColor("#4CAF50") // Green for normal
    }

    private val hotZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.BUTT
        color = Color.parseColor("#F44336") // Red for hot
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val needleBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E2A2A")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8A8A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4A5A5A")
    }

    private val arcRect = RectF()
    private val startAngle = 135f
    private val sweepAngle = 270f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - 40f

        // Set up arc rectangle
        arcRect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        // Draw background arc
        backgroundArcPaint.color = Color.parseColor("#1E2A2A")
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, backgroundArcPaint)

        // Draw colored zones
        val coldAngle = ((lowThreshold - minTemp) / (maxTemp - minTemp)) * sweepAngle
        val normalAngle = ((highThreshold - lowThreshold) / (maxTemp - minTemp)) * sweepAngle
        val hotAngle = sweepAngle - coldAngle - normalAngle

        // Cold zone (0 to lowThreshold)
        canvas.drawArc(arcRect, startAngle, coldAngle, false, coldZonePaint)
        
        // Normal zone (lowThreshold to highThreshold)
        canvas.drawArc(arcRect, startAngle + coldAngle, normalAngle, false, normalZonePaint)
        
        // Hot zone (highThreshold to max)
        canvas.drawArc(arcRect, startAngle + coldAngle + normalAngle, hotAngle, false, hotZonePaint)

        // Draw tick marks
        drawTickMarks(canvas, centerX, centerY, radius)

        // Draw needle
        drawNeedle(canvas, centerX, centerY, radius - 40f)

        // Draw center circle
        canvas.drawCircle(centerX, centerY, 20f, needleBasePaint)
        canvas.drawCircle(centerX, centerY, 12f, needlePaint)

        // Draw temperature text
        textPaint.textSize = radius * 0.35f
        canvas.drawText(
            String.format("%.1f°C", currentTemp),
            centerX,
            centerY + radius * 0.45f,
            textPaint
        )

        // Draw label
        labelPaint.textSize = radius * 0.12f
        canvas.drawText(
            "TEMPERATURE",
            centerX,
            centerY + radius * 0.65f,
            labelPaint
        )
    }

    private fun drawTickMarks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (i in 0..10) {
            val angle = Math.toRadians((startAngle + (sweepAngle / 10) * i).toDouble())
            val innerRadius = radius - 15f
            val outerRadius = radius + 15f

            val startX = cx + innerRadius * cos(angle).toFloat()
            val startY = cy + innerRadius * sin(angle).toFloat()
            val endX = cx + outerRadius * cos(angle).toFloat()
            val endY = cy + outerRadius * sin(angle).toFloat()

            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, length: Float) {
        val tempPercentage = ((currentTemp - minTemp) / (maxTemp - minTemp)).coerceIn(0f, 1f)
        val angle = Math.toRadians((startAngle + sweepAngle * tempPercentage).toDouble())

        val needleX = cx + length * cos(angle).toFloat()
        val needleY = cy + length * sin(angle).toFloat()

        // Draw needle shadow
        needlePaint.color = Color.parseColor("#80FFFFFF")
        needlePaint.strokeWidth = 6f
        needlePaint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, needleX, needleY, needlePaint)

        // Draw needle
        needlePaint.color = when {
            currentTemp < lowThreshold -> Color.parseColor("#2196F3")
            currentTemp > highThreshold -> Color.parseColor("#F44336")
            else -> Color.parseColor("#4CAF50")
        }
        needlePaint.strokeWidth = 4f
        canvas.drawLine(cx, cy, needleX, needleY, needlePaint)

        needlePaint.style = Paint.Style.FILL
        needlePaint.color = Color.WHITE
    }

    fun setTemperature(temp: Float) {
        currentTemp = temp
        invalidate()
    }

    fun setThresholds(low: Float, high: Float) {
        lowThreshold = low
        highThreshold = high
        invalidate()
    }

    fun setRange(min: Float, max: Float) {
        minTemp = min
        maxTemp = max
        invalidate()
    }

    fun getTemperature(): Float = currentTemp
}
