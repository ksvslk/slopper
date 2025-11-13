package com.indrek.slopper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.indrek.slopper.R

class SelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @ColorInt
    private fun resolveThemeColor(@AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private val primaryColor = ContextCompat.getColor(context, R.color.colorPrimary)
    private val onPrimaryColor = ContextCompat.getColor(context, R.color.colorOnPrimary)
    private val translucentPrimaryColor = (primaryColor and 0x00FFFFFF) or 0x44000000.toInt()


    private val borderPaint = Paint().apply {
        color = primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 6f // Thickness of the main selection border
    }

    private val handlePaint = Paint().apply {
        color = primaryColor // Use primary color for the handle itself
        style = Paint.Style.FILL // Fill the handle shape
        strokeCap = Paint.Cap.ROUND // Rounded ends for the handle line
        isAntiAlias = true // Smoother drawing
    }

    private val fillPaint = Paint().apply {
        color = translucentPrimaryColor
        style = Paint.Style.FILL
    }

    // The main selection rectangle
    private val selectionRect = RectF()

    private enum class TouchMode { IDLE, DRAGGING, RESIZING_TOP, RESIZING_BOTTOM }
    private var currentMode = TouchMode.IDLE

    private var lastTouchY = 0f

    private val edgeTouchZone = 60f // Touch zone remains the same
    // --- INCREASED HANDLE WIDTH ---
    private val handleWidth = 100f // *** Increased from 60f to 100f ***
    // --- END INCREASED HANDLE WIDTH ---
    private val handleHeight = 16f // Thickness remains at 16f


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
        // Draw a simple, centered rounded rectangle as the handle
        val halfWidth = handleWidth / 2
        val halfHeight = handleHeight / 2
        val cornerRadius = handleHeight / 2 // Fully rounded ends

        // Define the rectangle for the handle
        val handleRect = RectF(
            centerX - halfWidth,
            y - halfHeight,
            centerX + halfWidth,
            y + halfHeight
        )

        // Draw the rounded rectangle
        canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchZone = getTouchZone(event.x, event.y)

                when (touchZone) {
                    TouchZone.TOP_EDGE -> currentMode = TouchMode.RESIZING_TOP
                    TouchZone.BOTTOM_EDGE -> currentMode = TouchMode.RESIZING_BOTTOM
                    TouchZone.INSIDE ->  currentMode = TouchMode.DRAGGING
                    TouchZone.OUTSIDE -> return false
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

                // Keep bounds within the screen
                constrainRectToBounds()

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

    private fun constrainRectToBounds() {
        val viewHeight = height.toFloat()
        val rectHeight = selectionRect.height()

        // Prevent top edge from going off-screen
        if (selectionRect.top < 0f) {
            selectionRect.offset(0f, -selectionRect.top)
        }
        // Prevent bottom edge from going off-screen
        if (selectionRect.bottom > viewHeight) {
            selectionRect.offset(0f, viewHeight - selectionRect.bottom)
        }
        // Ensure minimum height hasn't caused top to go off-screen
        if (selectionRect.top < 0f) {
            selectionRect.top = 0f
            // Adjust bottom if needed after setting top to 0
            if(selectionRect.height() < 100f) {
                selectionRect.bottom = 100f
            }
        }

    }

    private enum class TouchZone {
        TOP_EDGE, BOTTOM_EDGE, INSIDE, OUTSIDE
    }

    private fun getTouchZone(x: Float, y: Float): TouchZone {
        // Use a slightly larger touch zone around the drawn handle position
        val handleTouchMargin = edgeTouchZone // Use existing zone for simplicity

        // Check if touch is near the top edge handle center
        if (y >= selectionRect.top - handleTouchMargin &&
            y <= selectionRect.top + handleTouchMargin &&
            x >= selectionRect.left && // Allow touching anywhere horizontally along the edge
            x <= selectionRect.right) {
            return TouchZone.TOP_EDGE
        }

        // Check if touch is near the bottom edge handle center
        if (y >= selectionRect.bottom - handleTouchMargin &&
            y <= selectionRect.bottom + handleTouchMargin &&
            x >= selectionRect.left &&
            x <= selectionRect.right) {
            return TouchZone.BOTTOM_EDGE
        }

        // Check if touch is inside the rectangle (excluding edges already checked)
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