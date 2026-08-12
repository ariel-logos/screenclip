package dev.screenclip

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Action { COPY, SAVE, COPY_AND_SAVE }

/**
 * The whole selection UI: draws the frozen frame, the scrim, the adjustable
 * selection and its handles, and hosts the action bar.
 *
 * Because the frame is already captured before this view exists, nothing here
 * ever needs hiding — the bar may sit right on top of the region of interest and
 * the crop is still clean.
 */
@SuppressLint("ViewConstructor")
class OverlayRoot(context: Context, frozen: Bitmap) : FrameLayout(context) {

    var onAction: ((Action) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /** Fired once this view can definitively no longer draw, so the frame can be freed. */
    var onDetached: (() -> Unit)? = null

    /** Owned by the service. Nulled on detach; never recycled here — removeView is async. */
    private var frame: Bitmap? = frozen

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density

    // ---- coordinate spaces -------------------------------------------------
    // View space is where touches live; frame space is where pixels live. They are
    // identical in the ordinary full-screen case, but the crop must still go through
    // the inverse transform — assuming 1:1 is how you ship an off-by-a-scale-factor bug.
    private val imageRect = RectF()
    private val frameToView = Matrix()
    private val viewToFrame = Matrix()
    private val bounds = Rect()

    private val selection = Rect()
    private var hasSelection = false

    /**
     * What the selection was before "whole screen" replaced it, so it can come back.
     * Having had *no* selection is a perfectly good previous state — going back to
     * "Drag to select" is what the button should do when that is where you came from.
     */
    private val previous = Rect()
    private var previousHadSelection = false
    private var canRevert = false

    // ---- gesture state -----------------------------------------------------
    private enum class Mode { IDLE, ARMED_NEW, DRAG_NEW, MOVE, RESIZE }

    private var mode = Mode.IDLE
    private var activeHandle = 0
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private val startRect = Rect()
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var minSize = 1
    private var busy = false
    private var backCallback: Any? = null

    // ---- paint -------------------------------------------------------------
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint().apply { color = 0x99000000.toInt() }
    private val borderShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = 0x80000000.toInt()
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val readoutBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    private val pill = RectF()

    private val bar: LinearLayout

    /** Meaningless until there is a selection, so they stay dimmed until there is one. */
    private val outputs: List<View>

    /** Doubles as "back to the previous selection" once the whole screen is selected. */
    private val whole: ImageButton

    init {
        // A ViewGroup with no background sets WILL_NOT_DRAW, and onDraw would never
        // run — the overlay would be completely blank.
        setWillNotDraw(false)
        isFocusableInTouchMode = true

        // A Service context carries no <application> theme, so widgets built from it
        // are unstyled. Material dark, because the bar sits over an arbitrary screenshot.
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material)

        whole = iconButton(themed, R.drawable.ic_expand, R.string.action_select_all) {
            toggleWholeScreen()
        }
        val copy = iconButton(themed, R.drawable.ic_copy, R.string.action_copy) {
            fire(Action.COPY)
        }
        val save = iconButton(themed, R.drawable.ic_save, R.string.action_save) {
            fire(Action.SAVE)
        }
        val both = iconButton(themed, R.drawable.ic_copy_save, R.string.action_both) {
            fire(Action.COPY_AND_SAVE)
        }
        val cancel = iconButton(themed, R.drawable.ic_close, R.string.action_cancel) {
            if (!busy) onCancel?.invoke()
        }
        outputs = listOf(copy, save, both)

        bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Without this, taps on the bar's padding fall through and start a new drag.
            isClickable = true
            val p = dp(4f).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xF21C1C1E.toInt())
            }
            // Visible from the start: "select the whole screen" is most useful before
            // any drag has happened, so it cannot live behind a first selection.
            addView(whole)
            addView(divider(themed))
            addView(copy)
            addView(save)
            addView(both)
            addView(cancel)
        }
        updateOutputs()
        addView(
            bar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            },
        )
    }

    /**
     * Icon-only, so the label lives in the content description (TalkBack) and the
     * tooltip (long press) instead — an unlabelled control with neither is just a
     * shape.
     */
    private fun iconButton(ctx: Context, icon: Int, label: Int, onClick: () -> Unit) =
        ImageButton(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(icon)
            imageTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0x59FFFFFF, Color.WHITE),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // Background comes from borderlessButtonStyle — a borderless ripple.
            contentDescription = context.getString(label)
            tooltipText = context.getString(label)
            val size = dp(48f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            val p = dp(12f).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }

    /** Separates changing the selection from doing something with it. */
    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1f).toInt(), dp(22f).toInt()).apply {
            leftMargin = dp(2f).toInt()
            rightMargin = dp(2f).toInt()
        }
        setBackgroundColor(0x33FFFFFF)
    }

    private fun updateOutputs() {
        val usable = hasSelection && !busy
        outputs.forEach { it.isEnabled = usable }
    }

    private val isWholeScreen: Boolean
        get() = hasSelection && selection == bounds

    private fun toggleWholeScreen() {
        if (busy) return
        if (isWholeScreen && canRevert) {
            if (previousHadSelection) {
                selection.set(previous)
                hasSelection = true
            } else {
                selection.setEmpty()
                hasSelection = false
            }
            canRevert = false
        } else {
            previousHadSelection = hasSelection && selection != bounds
            if (previousHadSelection) previous.set(selection) else previous.setEmpty()
            selection.set(bounds)
            hasSelection = true
            canRevert = true
        }
        if (hasSelection) clampSelection()
        publishExclusions()
        updateOutputs()
        syncWholeButton()
        invalidate()
    }

    /** Flips only while the button actually has somewhere to go back to. */
    private fun syncWholeButton() {
        val revert = isWholeScreen && canRevert
        whole.setImageResource(
            if (revert) R.drawable.ic_collapse else R.drawable.ic_expand,
        )
        val label = context.getString(
            when {
                !revert -> R.string.action_select_all
                previousHadSelection -> R.string.action_previous
                // Nothing to restore — going back means going back to no selection.
                else -> R.string.action_clear
            },
        )
        whole.contentDescription = label
        whole.tooltipText = label
    }

    // ---- geometry ----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        remap()
    }

    private fun remap() {
        val f = frame ?: return
        if (width == 0 || height == 0 || f.width == 0 || f.height == 0) return
        val scale = min(width.toFloat() / f.width, height.toFloat() / f.height)
        val dw = f.width * scale
        val dh = f.height * scale
        val dx = (width - dw) / 2f
        val dy = (height - dh) / 2f
        imageRect.set(dx, dy, dx + dw, dy + dh)
        frameToView.setScale(scale, scale)
        frameToView.postTranslate(dx, dy)
        if (!frameToView.invert(viewToFrame)) viewToFrame.reset()
        bounds.set(dx.roundToInt(), dy.roundToInt(), (dx + dw).roundToInt(), (dy + dh).roundToInt())
        minSize = min(dp(24f).toInt(), min(bounds.width(), bounds.height()) / 2).coerceAtLeast(1)
        if (hasSelection) {
            clampSelection()
            publishExclusions()
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        (bar.layoutParams as LayoutParams).apply {
            leftMargin = i.left + dp(8f).toInt()
            rightMargin = i.right + dp(8f).toInt()
            topMargin = i.top + dp(12f).toInt()
            bottomMargin = i.bottom + dp(12f).toInt()
        }
        bar.requestLayout()
        return insets
    }

    /** The selection in frame pixels — the only legal way to produce a crop. */
    fun frameCrop(): Rect? {
        if (!hasSelection || selection.isEmpty) return null
        val f = frame ?: return null
        val r = RectF(selection)
        viewToFrame.mapRect(r)
        val out = Rect()
        r.round(out)
        if (!out.intersect(0, 0, f.width, f.height) || out.isEmpty) return null
        return out
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        if (f.isRecycled) return
        canvas.drawBitmap(f, null, imageRect, bitmapPaint)

        if (!hasSelection) {
            canvas.drawRect(imageRect, scrim)
            canvas.drawText(
                context.getString(R.string.overlay_hint),
                imageRect.centerX(),
                imageRect.centerY(),
                hintPaint,
            )
            return
        }

        // Four slabs rather than clipOutRect + drawColor: no clip churn per move frame.
        val l = selection.left.toFloat()
        val t = selection.top.toFloat()
        val r = selection.right.toFloat()
        val b = selection.bottom.toFloat()
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, t, scrim)
        canvas.drawRect(imageRect.left, b, imageRect.right, imageRect.bottom, scrim)
        canvas.drawRect(imageRect.left, t, l, b, scrim)
        canvas.drawRect(r, t, imageRect.right, b, scrim)

        canvas.drawRect(selection, borderShadow)
        canvas.drawRect(selection, border)
        drawCorners(canvas)
        drawReadout(canvas)
    }

    /** L-brackets drawn *inward* so they never clip against a screen edge. */
    private fun drawCorners(canvas: Canvas) {
        val arm = min(dp(20f), min(selection.width(), selection.height()) / 3f)
        if (arm <= 0f) return
        val l = selection.left.toFloat()
        val t = selection.top.toFloat()
        val r = selection.right.toFloat()
        val b = selection.bottom.toFloat()
        bracket(canvas, l, t, arm, arm)
        bracket(canvas, r, t, -arm, arm)
        bracket(canvas, l, b, arm, -arm)
        bracket(canvas, r, b, -arm, -arm)
    }

    private fun bracket(canvas: Canvas, x: Float, y: Float, ax: Float, ay: Float) {
        canvas.drawLine(x, y, x + ax, y, handlePaint)
        canvas.drawLine(x, y, x, y + ay, handlePaint)
    }

    private fun drawReadout(canvas: Canvas) {
        val crop = frameCrop() ?: return
        val label = "${crop.width()} × ${crop.height()}"
        val padH = dp(10f)
        val padV = dp(5f)
        val w = readout.measureText(label) + padH * 2
        val h = readout.textSize + padV * 2
        val left = (selection.exactCenterX() - w / 2f)
            .coerceIn(imageRect.left + dp(8f), max(imageRect.left + dp(8f), imageRect.right - w - dp(8f)))
        var top = selection.top - h - dp(8f)
        if (top < imageRect.top + dp(4f)) top = selection.bottom + dp(8f)
        if (top + h > imageRect.bottom - dp(4f)) top = selection.top + dp(8f)
        pill.set(left, top, left + w, top + h)
        canvas.drawRoundRect(pill, h / 2f, h / 2f, readoutBg)
        canvas.drawText(label, pill.centerX(), pill.bottom - padV - readout.descent(), readout)
    }

    // ---- touch -------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (busy) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                startRect.set(selection)
                mode = if (!hasSelection) {
                    Mode.DRAG_NEW
                } else {
                    val handle = handleAt(downX, downY)
                    when {
                        handle != 0 -> { activeHandle = handle; Mode.RESIZE }
                        selection.contains(downX.roundToInt(), downY.roundToInt()) -> Mode.MOVE
                        // Not DRAG_NEW yet: a tap must be a no-op, never an abort.
                        else -> Mode.ARMED_NEW
                    }
                }
                if (mode == Mode.DRAG_NEW) setFromDrag(downX, downY)
                bar.alpha = 0.2f
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (mode == Mode.ARMED_NEW) {
                    if (hypot(x - downX, y - downY) < slop) return true
                    mode = Mode.DRAG_NEW
                    hasSelection = false
                }
                when (mode) {
                    Mode.DRAG_NEW -> {
                        setFromDrag(x, y)
                        hasSelection = true
                    }
                    Mode.MOVE -> selection.set(moved(x - downX, y - downY))
                    Mode.RESIZE -> selection.set(resized(activeHandle, x - downX, y - downY))
                    else -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                settle()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // Aborts the gesture, not the capture.
                if (mode == Mode.MOVE || mode == Mode.RESIZE) selection.set(startRect)
                mode = Mode.IDLE
                bar.alpha = 1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun settle() {
        if (mode == Mode.DRAG_NEW && (selection.width() < slop || selection.height() < slop)) {
            // Barely a drag: keep whatever was there before rather than leaving a speck.
            if (startRect.isEmpty) {
                hasSelection = false
                selection.setEmpty()
            } else {
                selection.set(startRect)
                hasSelection = true
            }
        }
        mode = Mode.IDLE
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (hasSelection) clampSelection()
        publishExclusions()
        // A fresh drag replaces whatever "go back" was pointing at.
        if (!isWholeScreen) canRevert = false
        updateOutputs()
        syncWholeButton()
        bar.alpha = 1f
        invalidate()
    }

    private fun setFromDrag(x: Float, y: Float) {
        selection.set(
            min(downX, x).roundToInt().coerceIn(bounds.left, bounds.right),
            min(downY, y).roundToInt().coerceIn(bounds.top, bounds.bottom),
            max(downX, x).roundToInt().coerceIn(bounds.left, bounds.right),
            max(downY, y).roundToInt().coerceIn(bounds.top, bounds.bottom),
        )
    }

    /**
     * Move and resize are pure functions of the rect as it was at ACTION_DOWN plus the
     * total delta. Mutating the live rect incrementally is what produces grab-offset
     * jump, slow creep and min-size jam.
     */
    private fun moved(dx: Float, dy: Float): Rect {
        val loX = bounds.left - startRect.left
        val hiX = bounds.right - startRect.right
        val loY = bounds.top - startRect.top
        val hiY = bounds.bottom - startRect.bottom
        val ox = if (loX <= hiX) dx.roundToInt().coerceIn(loX, hiX) else 0
        val oy = if (loY <= hiY) dy.roundToInt().coerceIn(loY, hiY) else 0
        return Rect(startRect).apply { offset(ox, oy) }
    }

    /** Clamps on overshoot; never flips, so the handle you grabbed stays the handle you hold. */
    private fun resized(handle: Int, dx: Float, dy: Float): Rect {
        var l = startRect.left
        var t = startRect.top
        var r = startRect.right
        var b = startRect.bottom
        if (handle and LEFT != 0) {
            l = (startRect.left + dx).roundToInt().coerceIn(bounds.left, startRect.right - minSize)
        }
        if (handle and RIGHT != 0) {
            r = (startRect.right + dx).roundToInt().coerceIn(startRect.left + minSize, bounds.right)
        }
        if (handle and TOP != 0) {
            t = (startRect.top + dy).roundToInt().coerceIn(bounds.top, startRect.bottom - minSize)
        }
        if (handle and BOTTOM != 0) {
            b = (startRect.bottom + dy).roundToInt().coerceIn(startRect.top + minSize, bounds.bottom)
        }
        return Rect(l, t, r, b)
    }

    private fun handleAt(x: Float, y: Float): Int {
        // Capped so a small selection keeps a reachable middle for MOVE.
        val reach = min(dp(28f), min(selection.width(), selection.height()) / 3f)
            .coerceAtLeast(dp(10f))
        var best = 0
        var bestDistance = Float.MAX_VALUE
        fun corner(flag: Int, cx: Int, cy: Int) {
            val d = hypot(x - cx, y - cy)
            // Nearest corner wins: a fixed test order silently biases every small rect
            // towards top-left.
            if (d <= reach && d < bestDistance) {
                bestDistance = d
                best = flag
            }
        }
        corner(LEFT or TOP, selection.left, selection.top)
        corner(RIGHT or TOP, selection.right, selection.top)
        corner(LEFT or BOTTOM, selection.left, selection.bottom)
        corner(RIGHT or BOTTOM, selection.right, selection.bottom)
        if (best != 0) return best

        val edge = min(dp(22f), min(selection.width(), selection.height()) / 3f)
            .coerceAtLeast(dp(8f))
        val spansY = y >= selection.top - edge && y <= selection.bottom + edge
        val spansX = x >= selection.left - edge && x <= selection.right + edge
        if (spansY && abs(x - selection.left) <= edge) return LEFT
        if (spansY && abs(x - selection.right) <= edge) return RIGHT
        if (spansX && abs(y - selection.top) <= edge) return TOP
        if (spansX && abs(y - selection.bottom) <= edge) return BOTTOM
        return 0
    }

    private fun clampSelection() {
        if (selection.width() < minSize) selection.right = selection.left + minSize
        if (selection.height() < minSize) selection.bottom = selection.top + minSize
        var dx = 0
        var dy = 0
        if (selection.left < bounds.left) dx = bounds.left - selection.left
        if (selection.right > bounds.right) dx = bounds.right - selection.right
        if (selection.top < bounds.top) dy = bounds.top - selection.top
        if (selection.bottom > bounds.bottom) dy = bounds.bottom - selection.bottom
        selection.offset(dx, dy)
        selection.set(
            selection.left.coerceAtLeast(bounds.left),
            selection.top.coerceAtLeast(bounds.top),
            selection.right.coerceAtMost(bounds.right),
            selection.bottom.coerceAtMost(bounds.bottom),
        )
    }

    /** Ask the system not to steal edge drags for Back. Only the bottom ~200dp is honoured. */
    private fun publishExclusions() {
        if (!hasSelection) {
            systemGestureExclusionRects = emptyList()
            return
        }
        val e = dp(24f).toInt()
        systemGestureExclusionRects = listOf(
            Rect(selection.left - e, selection.top - e, selection.left + e, selection.bottom + e),
            Rect(selection.right - e, selection.top - e, selection.right + e, selection.bottom + e),
        )
    }

    // The bar used to hop to the top when the selection reached the bottom edge. It
    // never had to: the crop comes from the frozen bitmap, so the bar is never in the
    // output — it was only avoiding *visually* covering the selection, and a bar that
    // moves under you is worse than one that overlaps.

    // ---- lifecycle / dismissal --------------------------------------------

    private fun fire(action: Action) {
        if (busy || !hasSelection) return
        onAction?.invoke(action)
    }

    /** Latch the UI the moment an action starts; a double tap must not encode twice. */
    fun setBusy() {
        busy = true
        for (i in 0 until bar.childCount) bar.getChildAt(i).isEnabled = false
    }

    private fun handleBack() {
        if (busy) return
        if (mode != Mode.IDLE) {
            if (mode == Mode.MOVE || mode == Mode.RESIZE) selection.set(startRect)
            mode = Mode.IDLE
            bar.alpha = 1f
            invalidate()
            return
        }
        onCancel?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Must live on the root: ViewGroup forwards keys only to the focused child, and
        // once Buttons are in the tree that is not this view.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBack() }
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backCallback = callback
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let {
                findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it)
            }
        }
        backCallback = null
        // Drop the reference first, then let the owner free the pixels: removeView is
        // asynchronous, so this is the earliest moment recycling is safe.
        frame = null
        super.onDetachedFromWindow()
        onDetached?.invoke()
        onDetached = null
    }

    private companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
    }
}
