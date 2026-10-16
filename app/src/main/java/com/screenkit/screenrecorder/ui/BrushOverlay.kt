package com.screenkit.screenrecorder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.screenkit.screenrecorder.CaptureIntents

/**
 * Fullscreen interactive drawing overlay with brush tool, color selection,
 * pass-through touch toggle, stroke width, undo, eraser, and clear screen options.
 */
class BrushOverlay(
    private val context: Context,
    private val onClose: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: FrameLayout? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var toolbarView: View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    // Drawing state
    private val strokePaths = mutableListOf<DrawnStroke>()
    private var currentColor = Color.parseColor("#FF4336") // Default Vibrant Red
    private var currentStrokeWidth = 14f
    private var isEraser = false
    private var isPassThrough = false

    private val colors = listOf(
        Color.parseColor("#FF4336"), // Red
        Color.parseColor("#4CAF50"), // Green
        Color.parseColor("#2196F3"), // Blue
        Color.parseColor("#FFEB3B"), // Yellow
        Color.parseColor("#FF9800"), // Orange
        Color.parseColor("#E91E63"), // Pink
        Color.WHITE,
    )

    companion object {
        @Volatile var activeInstance: BrushOverlay? = null
            private set

        val isShowing: Boolean get() = activeInstance != null

        fun toggle(context: Context, onStateChanged: () -> Unit = {}) {
            val existing = activeInstance
            if (existing != null) {
                existing.dismiss()
                onStateChanged()
                return
            }
            if (!Settings.canDrawOverlays(context)) {
                CaptureIntents.toast(context, "Allow display over other apps to use the brush tool.")
                return
            }
            val overlay = BrushOverlay(context.applicationContext) {
                onStateChanged()
            }
            overlay.show()
            onStateChanged()
        }

        fun dismissActive() {
            activeInstance?.dismiss()
        }

        fun setToolbarHiddenForScreenshot(hidden: Boolean) {
            activeInstance?.toolbarView?.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

    data class DrawnStroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val isEraser: Boolean,
    )

    private inner class DrawingCanvasView(context: Context) : View(context) {
        private var currentPath: Path? = null
        private val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        private var bufferBitmap: Bitmap? = null
        private var bufferCanvas: Canvas? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                bufferBitmap?.recycle()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bufferBitmap = bitmap
                bufferCanvas = Canvas(bitmap)
                redrawAllStrokes()
            }
        }

        fun redrawAllStrokes() {
            val canvas = bufferCanvas ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (stroke in strokePaths) {
                paint.strokeWidth = stroke.strokeWidth
                if (stroke.isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    paint.xfermode = null
                    paint.color = stroke.color
                }
                canvas.drawPath(stroke.path, paint)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bufferBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            currentPath?.let { path ->
                paint.strokeWidth = currentStrokeWidth
                if (isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    paint.xfermode = null
                    paint.color = currentColor
                }
                canvas.drawPath(path, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            TouchFeedbackOverlay.recordTouchEvent(event)
            if (isPassThrough) return false
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val path = Path().apply { moveTo(x, y) }
                    currentPath = path
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.lineTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentPath?.let { path ->
                        strokePaths.add(DrawnStroke(path, currentColor, currentStrokeWidth, isEraser))
                        currentPath = null
                        redrawAllStrokes()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun undo() {
            if (strokePaths.isNotEmpty()) {
                strokePaths.removeAt(strokePaths.size - 1)
                redrawAllStrokes()
            }
        }

        fun clear() {
            strokePaths.clear()
            redrawAllStrokes()
        }
    }

    private var canvasView: DrawingCanvasView? = null

    fun show() {
        if (rootLayout != null) return
        activeInstance?.takeIf { it !== this }?.dismiss()

        val root = FrameLayout(appContext)
        val drawingView = DrawingCanvasView(appContext)
        canvasView = drawingView
        root.addView(
            drawingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val cParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        canvasParams = cParams

        // Separate floating toolbar window so pass-through mode can make the canvas non-touchable
        // while keeping the toolbar interactive.
        val toolbar = buildToolbar()
        val tParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(18)
        }
        toolbarView = toolbar
        toolbarParams = tParams

        runCatching {
            windowManager.addView(root, cParams)
            windowManager.addView(toolbar, tParams)
            rootLayout = root
            activeInstance = this
        }
    }

    fun dismiss() {
        val root = rootLayout
        val tb = toolbarView
        if (root != null) runCatching { windowManager.removeView(root) }
        if (tb != null) runCatching { windowManager.removeView(tb) }
        rootLayout = null
        toolbarView = null
        canvasView = null
        if (activeInstance === this) {
            activeInstance = null
        }
        onClose()
    }

    private fun setPassThroughMode(passThrough: Boolean, modeBtn: TextView) {
        isPassThrough = passThrough
        val root = rootLayout ?: return
        val params = canvasParams ?: return
        params.flags = if (passThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        modeBtn.text = if (passThrough) "✎ Draw" else "👆 Tap App"
        modeBtn.background = GradientDrawable().apply {
            setColor(if (passThrough) 0xFFB7F36B.toInt() else 0xFF2A373E.toInt())
            cornerRadius = dp(10).toFloat()
        }
        modeBtn.setTextColor(if (passThrough) 0xFF0D1612.toInt() else Color.WHITE)
    }

    private fun buildToolbar(): View {
        val outer = FrameLayout(appContext).apply {
            setPadding(dp(12), 0, dp(12), 0)
        }
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE161F23.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x44B7F36B.toInt())
            }
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            elevation = dp(8).toFloat()
        }

        val toolsScrollView = HorizontalScrollView(appContext).apply {
            isHorizontalScrollBarEnabled = false
        }
        val toolsRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        lateinit var modeBtn: TextView

        // Color swatches
        colors.forEach { col ->
            val swatch = View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(2), if (col == currentColor && !isEraser) Color.WHITE else 0x44FFFFFF)
                }
                isClickable = true
                setOnClickListener {
                    isEraser = false
                    currentColor = col
                    if (isPassThrough) setPassThroughMode(false, modeBtn)
                    updateToolbarSwatches(toolsRow)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                rightMargin = dp(6)
            }
            toolsRow.addView(swatch, lp)
        }

        // Pass-through tap toggle button (lets user tap underlying app while keeping drawings!)
        modeBtn = makeButton("👆 Tap App") {
            setPassThroughMode(!isPassThrough, modeBtn)
        }
        toolsRow.addView(modeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
            rightMargin = dp(5)
        })

        // Eraser button
        val eraserBtn = makeButton("Eraser") {
            isEraser = true
            if (isPassThrough) setPassThroughMode(false, modeBtn)
            updateToolbarSwatches(toolsRow)
        }
        toolsRow.addView(eraserBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
            rightMargin = dp(5)
        })

        // Undo button
        val undoBtn = makeButton("Undo") {
            canvasView?.undo()
        }
        toolsRow.addView(undoBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
            rightMargin = dp(5)
        })

        // Clear button
        val clearBtn = makeButton("Clear") {
            canvasView?.clear()
        }
        toolsRow.addView(clearBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
            rightMargin = dp(5)
        })

        // Close / Exit button
        val closeBtn = makeButton("✕ Done", isAccent = true) {
            dismiss()
        }
        toolsRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)))

        toolsScrollView.addView(toolsRow)
        container.addView(toolsScrollView)
        outer.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        return outer
    }

    private fun updateToolbarSwatches(toolsRow: LinearLayout) {
        var colorIdx = 0
        for (i in 0 until toolsRow.childCount) {
            val child = toolsRow.getChildAt(i)
            if (colorIdx < colors.size && child.layoutParams.width == dp(24)) {
                val col = colors[colorIdx]
                (child.background as? GradientDrawable)?.apply {
                    setStroke(dp(2), if (col == currentColor && !isEraser) Color.WHITE else 0x44FFFFFF)
                }
                colorIdx++
            }
        }
    }

    private fun makeButton(text: String, isAccent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(appContext).apply {
            this.text = text
            textSize = 11f
            setTextColor(if (isAccent) 0xFF0D1612.toInt() else Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (isAccent) 0xFFB7F36B.toInt() else 0xFF2A373E.toInt())
                cornerRadius = dp(10).toFloat()
            }
            val padH = dp(10)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()
}
