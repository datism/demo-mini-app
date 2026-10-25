package com.demo.imsdc.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.demo.imsdc.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "SlideToAnswer"

/**
 * SlideToAnswerView — Dual Swipe Action Circles (Samsung One UI / Google Pixel style)
 *
 * Bố cục:
 *   [🔴 Từ chối]  ──  Vuốt để trả lời  ──  [🟢 Trả lời]
 *
 * - Kéo nút Xanh (phải) sang trái vượt 55% hành trình → Nhận cuộc gọi.
 * - Kéo nút Đỏ (trái) sang phải vượt 55% hành trình → Từ chối cuộc gọi.
 * - Buông tay khi chưa đủ ngưỡng → Nảy đàn hồi Spring về vị trí ban đầu.
 * - Tap vào nút → Rung nhẹ + nhấp nháy chữ hướng dẫn.
 */
class SlideToAnswerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Callbacks ───────────────────────────────────────────────────────────
    var onAnswerListener: (() -> Unit)? = null
    var onRejectListener: (() -> Unit)? = null

    // ── Configuration ───────────────────────────────────────────────────────
    private val handleSize = dpToPx(72)
    private val handleMargin = dpToPx(40)
    private val activationThreshold = 0.55f
    private val pulseColor = Color.parseColor("#402CBA59")
    private val pulseColorReject = Color.parseColor("#40E53935")
    private val answerColor = Color.parseColor("#FF2CBA59")
    private val rejectColor = Color.parseColor("#FFE53935")

    // ── Child Views ─────────────────────────────────────────────────────────
    private val answerHandle: ImageView
    private val rejectHandle: ImageView
    private val guidanceText: TextView

    // ── Drag State ──────────────────────────────────────────────────────────
    private var isDraggingAnswer = false
    private var isDraggingReject = false
    private var dragStartX = 0f
    private var handleStartX = 0f
    private var maxDragDistance = 0f
    private var isActivated = false

    // ── Pulsing Animation ───────────────────────────────────────────────────
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulsePhase = 0f
    private val pulseAnimator: ValueAnimator

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        // ── Answer Handle (Green, left side) ────────────────────────────────
        answerHandle = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, android.R.drawable.ic_menu_call))
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.circle_green_button_bg)
            elevation = dpToPx(6).toFloat()
            scaleType = ImageView.ScaleType.CENTER
            val padding = dpToPx(18)
            setPadding(padding, padding, padding, padding)
        }
        val answerLp = LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            marginStart = handleMargin
        }
        addView(answerHandle, answerLp)

        // ── Reject Handle (Red, right side) ─────────────────────────────────
        rejectHandle = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_phone_decline))
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.circle_red_button_bg)
            elevation = dpToPx(6).toFloat()
            scaleType = ImageView.ScaleType.CENTER
            val padding = dpToPx(18)
            setPadding(padding, padding, padding, padding)
        }
        val rejectLp = LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = handleMargin
        }
        addView(rejectHandle, rejectLp)

        // ── Guidance Text ───────────────────────────────────────────────────
        guidanceText = TextView(context).apply {
            text = "Vuốt để trả lời"
            setTextColor(Color.parseColor("#B0FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        val textLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(guidanceText, textLp)

        // ── Pulsing Ripple Animation ────────────────────────────────────────
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── Drawing: Pulsing Ripples behind handles ─────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isActivated) return

        // Answer handle ripples (green, left side)
        drawPulseRipples(
            canvas,
            answerHandle.x + handleSize / 2f,
            answerHandle.y + handleSize / 2f,
            pulseColor,
            answerColor
        )

        // Reject handle ripples (red, right side)
        drawPulseRipples(
            canvas,
            rejectHandle.x + handleSize / 2f,
            rejectHandle.y + handleSize / 2f,
            pulseColorReject,
            rejectColor
        )
    }

    private fun drawPulseRipples(canvas: Canvas, cx: Float, cy: Float, color: Int, ringColor: Int) {
        val maxRadius = handleSize * 0.9f
        for (i in 0..2) {
            val phase = (pulsePhase + i * 0.33f) % 1f
            val radius = handleSize / 2f + maxRadius * phase
            val alpha = ((1f - phase) * 80).toInt().coerceIn(0, 255)
            pulsePaint.color = color
            pulsePaint.alpha = alpha
            pulsePaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, pulsePaint)
        }
    }

    // ── Touch Handling ──────────────────────────────────────────────────────
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (isTouchOnHandle(ev, answerHandle) || isTouchOnHandle(ev, rejectHandle)) {
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isActivated) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    isTouchOnHandle(event, answerHandle) -> {
                        isDraggingAnswer = true
                        isDraggingReject = false
                        dragStartX = event.rawX
                        handleStartX = answerHandle.translationX
                        maxDragDistance = (width - handleSize - handleMargin * 2).toFloat()
                        answerHandle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).start()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Log.d(TAG, "Touch started on ANSWER handle")
                        return true
                    }
                    isTouchOnHandle(event, rejectHandle) -> {
                        isDraggingReject = true
                        isDraggingAnswer = false
                        dragStartX = event.rawX
                        handleStartX = rejectHandle.translationX
                        maxDragDistance = (width - handleSize - handleMargin * 2).toFloat()
                        rejectHandle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).start()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Log.d(TAG, "Touch started on REJECT handle")
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragStartX

                if (isDraggingAnswer) {
                    // Answer handle moves RIGHT (positive translationX)
                    val clampedDelta = max(0f, min(maxDragDistance, deltaX))
                    answerHandle.translationX = handleStartX + clampedDelta
                    val progress = clampedDelta / maxDragDistance
                    guidanceText.alpha = max(0f, 1f - progress * 2f)
                    return true
                }

                if (isDraggingReject) {
                    // Reject handle moves LEFT (negative translationX)
                    val clampedDelta = min(0f, max(-maxDragDistance, deltaX))
                    rejectHandle.translationX = handleStartX + clampedDelta
                    val progress = abs(clampedDelta) / maxDragDistance
                    guidanceText.alpha = max(0f, 1f - progress * 2f)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingAnswer) {
                    val progress = abs(answerHandle.translationX - handleStartX) / maxDragDistance
                    Log.i(TAG, "Answer swipe released at progress=${String.format("%.2f", progress)}")

                    if (progress >= activationThreshold && event.action == MotionEvent.ACTION_UP) {
                        // ── Threshold reached → ANSWER ──
                        isActivated = true
                        vibrateConfirm()
                        Log.i(TAG, "🟢 Swipe-to-Answer ACTIVATED (progress=${"%.2f".format(progress)})")
                        animateHandleToCenter(answerHandle) {
                            onAnswerListener?.invoke()
                        }
                    } else {
                        // ── Spring back ──
                        springBack(answerHandle)
                        guidanceText.animate().alpha(1f).setDuration(300).start()
                    }
                    answerHandle.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    isDraggingAnswer = false
                    return true
                }

                if (isDraggingReject) {
                    val progress = abs(rejectHandle.translationX - handleStartX) / maxDragDistance
                    Log.i(TAG, "Reject swipe released at progress=${String.format("%.2f", progress)}")

                    if (progress >= activationThreshold && event.action == MotionEvent.ACTION_UP) {
                        // ── Threshold reached → REJECT ──
                        isActivated = true
                        vibrateConfirm()
                        Log.i(TAG, "🔴 Swipe-to-Reject ACTIVATED (progress=${"%.2f".format(progress)})")
                        animateHandleToCenter(rejectHandle) {
                            onRejectListener?.invoke()
                        }
                    } else {
                        // ── Spring back ──
                        springBack(rejectHandle)
                        guidanceText.animate().alpha(1f).setDuration(300).start()
                    }
                    rejectHandle.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    isDraggingReject = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private fun isTouchOnHandle(event: MotionEvent, handle: View): Boolean {
        val touchSlop = dpToPx(12)
        val hx = handle.x + handle.translationX
        val hy = handle.y + handle.translationY
        return event.x >= hx - touchSlop &&
               event.x <= hx + handleSize + touchSlop &&
               event.y >= hy - touchSlop &&
               event.y <= hy + handleSize + touchSlop
    }

    private fun springBack(handle: View) {
        ValueAnimator.ofFloat(handle.translationX, 0f).apply {
            duration = 400
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener { handle.translationX = it.animatedValue as Float }
            start()
        }
    }

    private fun animateHandleToCenter(handle: View, onComplete: () -> Unit) {
        val targetX = (width / 2f) - (handle.x + handleSize / 2f)
        ValueAnimator.ofFloat(handle.translationX, targetX).apply {
            duration = 250
            addUpdateListener { handle.translationX = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onComplete()
                }
            })
            start()
        }
        // Fade out other views
        guidanceText.animate().alpha(0f).setDuration(200).start()
        if (handle == answerHandle) {
            rejectHandle.animate().alpha(0f).setDuration(200).start()
        } else {
            answerHandle.animate().alpha(0f).setDuration(200).start()
        }
    }

    private fun vibrateConfirm() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    /**
     * Reset the view to initial state (used when call is cancelled/timeout)
     */
    fun reset() {
        isActivated = false
        answerHandle.translationX = 0f
        rejectHandle.translationX = 0f
        answerHandle.alpha = 1f
        rejectHandle.alpha = 1f
        answerHandle.scaleX = 1f
        answerHandle.scaleY = 1f
        rejectHandle.scaleX = 1f
        rejectHandle.scaleY = 1f
        guidanceText.alpha = 1f
        if (!pulseAnimator.isRunning) {
            pulseAnimator.start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
