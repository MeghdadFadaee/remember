package meghdad.remember

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button

/** One idle callback per visible menu; never polls or intercepts the content's gestures. */
class FloatingMenuController(val button: Button) {
    private val handler = Handler(Looper.getMainLooper())
    private val accessibility = button.context.getSystemService(AccessibilityManager::class.java)
    private var active = false
    private var touching = false
    private val hide = Runnable {
        if (active && !touching && !keepVisible()) button.visibility = View.INVISIBLE
    }
    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
        if (active) {
            if (accessibility.isTouchExplorationEnabled) reveal() else scheduleHide()
        }
    }
    init {
        button.visibility = View.INVISIBLE
        button.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (active) { if (focused) reveal() else scheduleHide() }
        }
        accessibility.addTouchExplorationStateChangeListener(explorationListener, handler)
    }
    fun resume() {
        active = true; touching = false
        if (keepVisible()) reveal() else button.visibility = View.INVISIBLE
    }
    fun pause() {
        active = false; touching = false
        handler.removeCallbacks(hide)
        button.visibility = View.INVISIBLE
    }
    fun dispose() {
        pause()
        button.onFocusChangeListener = null
        accessibility.removeTouchExplorationStateChangeListener(explorationListener)
    }
    /** Called AFTER normal dispatch so a reveal cannot become a menu click in the same gesture. */
    fun onTouch(action: Int) {
        if (!active) return
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { touching = true; reveal() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; interact() }
        }
    }
    fun interact() {
        if (!active) return
        reveal(); scheduleHide()
    }
    private fun keepVisible() = accessibility.isTouchExplorationEnabled || button.hasFocus()
    private fun reveal() {
        handler.removeCallbacks(hide)
        button.visibility = View.VISIBLE
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        if (active && !touching && !keepVisible()) {
            val timeout = accessibility.getRecommendedTimeoutMillis(3000, AccessibilityManager.FLAG_CONTENT_CONTROLS)
            handler.postDelayed(hide, timeout.toLong())
        }
    }
}
