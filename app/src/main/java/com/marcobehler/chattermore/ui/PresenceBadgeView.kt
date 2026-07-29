package com.marcobehler.chattermore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.UserPresence

class PresenceBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var presence: UserPresence = UserPresence.OFFLINE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val surfaceColor = ContextCompat.getColor(context, R.color.surface)
    private val activeColor = ContextCompat.getColor(context, R.color.success)
    private val awayColor = ContextCompat.getColor(context, R.color.warning)
    private val offlineColor = ContextCompat.getColor(context, R.color.message_metadata)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f
        val borderWidth = 2f * density

        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawCircle(centerX, centerY, radius, paint)

        val indicatorRadius = (radius - borderWidth).coerceAtLeast(0f)
        when (presence) {
            UserPresence.ACTIVE -> {
                paint.color = activeColor
                canvas.drawCircle(centerX, centerY, indicatorRadius, paint)
            }

            UserPresence.AWAY -> {
                paint.color = awayColor
                canvas.drawCircle(centerX, centerY, indicatorRadius, paint)
                paint.color = surfaceColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.15f * density
                canvas.drawLine(centerX, centerY, centerX, centerY - 2.2f * density, paint)
                canvas.drawLine(centerX, centerY, centerX + 1.9f * density, centerY + 1.1f * density, paint)
            }

            UserPresence.OFFLINE -> {
                paint.color = offlineColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f * density
                canvas.drawCircle(
                    centerX,
                    centerY,
                    (indicatorRadius - paint.strokeWidth / 2f).coerceAtLeast(0f),
                    paint,
                )
            }
        }
    }
}
