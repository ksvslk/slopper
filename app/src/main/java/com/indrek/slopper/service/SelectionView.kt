package com.indrek.slopper.service

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val borderPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleLinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#4400FFFF") // Translucent Cyan
        style = Paint.Style.FILL
    }

    // The main selection rectangle
    private val selectionRect = RectF()

    private enum class TouchMode { IDLE, DRAGGING, RESIZING_TOP, RESIZING_BOTTOM }
    private var currentMode = TouchMode.IDLE

    private var lastTouchY = 0f

    private val edgeTouchZone = 60f // Touch zone for detecting edge touches
    private val handleWidth = 80f // Width of the horizontal handle bars
    private val handleThickness = 6f // Thickness of handle bars

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (selectionRect.isEmpty) {
            val prefs = context.getSharedPreferences("selection_view_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("rect_left")) {
                restoreSelectionRect()
            } else {
                // Initialize default position
                val horizontalPadding = 20f
                val left = horizontalPadding
                val right = w - horizontalPadding
                val top = 200f
                val initialHeight = 400f
                val bottom = top + initialHeight
                selectionRect.set(left, top, right, bottom)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw the translucent fill
        canvas.drawRect(selectionRect, fillPaint)

        // Draw the border
        canvas.drawRect(selectionRect, borderPaint)

        // Draw top edge handle (centered horizontal bar)
        val centerX = selectionRect.centerX()
        drawEdgeHandle(canvas, centerX, selectionRect.top)

        // Draw bottom edge handle (centered horizontal bar)
        drawEdgeHandle(canvas, centerX, selectionRect.bottom)
    }

    private fun drawEdgeHandle(canvas: Canvas, centerX: Float, y: Float) {
        // Draw a white horizontal bar with rounded ends
        val halfWidth = handleWidth / 2

        // Outer circle handles on the sides
        canvas.drawCircle(centerX - halfWidth, y, 12f, handlePaint)
        canvas.drawCircle(centerX + halfWidth, y, 12f, handlePaint)

        // Horizontal bar connecting them
        canvas.drawLine(
            centerX - halfWidth, y,
            centerX + halfWidth, y,
            handleLinePaint
        )

        // Three small vertical lines for grip indicator
        val lineHeight = 16f
        canvas.drawLine(centerX - 20f, y - lineHeight/2, centerX - 20f, y + lineHeight/2, handleLinePaint)
        canvas.drawLine(centerX, y - lineHeight/2, centerX, y + lineHeight/2, handleLinePaint)
        canvas.drawLine(centerX + 20f, y - lineHeight/2, centerX + 20f, y + lineHeight/2, handleLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchZone = getTouchZone(event.x, event.y)

                when (touchZone) {
                    TouchZone.TOP_EDGE -> {
                        currentMode = TouchMode.RESIZING_TOP
                    }
                    TouchZone.BOTTOM_EDGE -> {
                        currentMode = TouchMode.RESIZING_BOTTOM
                    }
                    TouchZone.INSIDE -> {
                        currentMode = TouchMode.DRAGGING
                    }
                    TouchZone.OUTSIDE -> {
                        return false
                    }
                }

                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY

                when (currentMode) {
                    TouchMode.DRAGGING -> {
                        selectionRect.offset(0f, dy)
                    }
                    TouchMode.RESIZING_TOP -> {
                        selectionRect.top += dy
                        // Prevent inverting the rectangle
                        if (selectionRect.top > selectionRect.bottom - 100f) {
                            selectionRect.top = selectionRect.bottom - 100f
                        }
                    }
                    TouchMode.RESIZING_BOTTOM -> {
                        selectionRect.bottom += dy
                        // Prevent inverting the rectangle
                        if (selectionRect.bottom < selectionRect.top + 100f) {
                            selectionRect.bottom = selectionRect.top + 100f
                        }
                    }
                    TouchMode.IDLE -> {}
                }

                invalidate() // Redraw the view
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP -> {
                currentMode = TouchMode.IDLE
                saveSelectionRect()
            }
        }
        return true
    }

    private enum class TouchZone {
        TOP_EDGE, BOTTOM_EDGE, INSIDE, OUTSIDE
    }

    private fun getTouchZone(x: Float, y: Float): TouchZone {
        // Check if touch is near the top edge (anywhere along the width)
        if (y >= selectionRect.top - edgeTouchZone &&
            y <= selectionRect.top + edgeTouchZone &&
            x >= selectionRect.left &&
            x <= selectionRect.right) {
            return TouchZone.TOP_EDGE
        }

        // Check if touch is near the bottom edge (anywhere along the width)
        if (y >= selectionRect.bottom - edgeTouchZone &&
            y <= selectionRect.bottom + edgeTouchZone &&
            x >= selectionRect.left &&
            x <= selectionRect.right) {
            return TouchZone.BOTTOM_EDGE
        }

        // Check if touch is inside the rectangle
        if (selectionRect.contains(x, y)) {
            return TouchZone.INSIDE
        }

        return TouchZone.OUTSIDE
    }

    fun getSelectionRectOnScreen(): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val offsetX = location[0]
        val offsetY = location[1]

        return Rect(
            (selectionRect.left + offsetX).toInt(),
            (selectionRect.top + offsetY).toInt(),
            (selectionRect.right + offsetX).toInt(),
            (selectionRect.bottom + offsetY).toInt()
        )
    }

    fun saveSelectionRect() {
        val prefs = context.getSharedPreferences("selection_view_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("rect_left", selectionRect.left)
            putFloat("rect_top", selectionRect.top)
            putFloat("rect_right", selectionRect.right)
            putFloat("rect_bottom", selectionRect.bottom)
            apply()
        }
    }

    fun restoreSelectionRect() {
        val prefs = context.getSharedPreferences("selection_view_prefs", Context.MODE_PRIVATE)
        if (prefs.contains("rect_left")) {
            val left = prefs.getFloat("rect_left", 0f)
            val top = prefs.getFloat("rect_top", 0f)
            val right = prefs.getFloat("rect_right", 0f)
            val bottom = prefs.getFloat("rect_bottom", 0f)
            selectionRect.set(left, top, right, bottom)
            invalidate()
        }
    }
}