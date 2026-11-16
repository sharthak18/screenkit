package com.screenkit.screenrecorder.ui.overlay

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.screenkit.screenrecorder.services.FloatingOverlayService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercises the real Android ViewGroup dispatch/removal interaction, not only geometry math. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingBubbleTouchTest {
    private lateinit var controller: ServiceController<FloatingOverlayService>
    private lateinit var service: FloatingOverlayService
    private lateinit var root: LinearLayout
    @Before fun setUp() {
        controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        service = controller.get()
        invoke("addOverlay") // Isolate the real overlay from camera, VPN and foreground start flows.
        root = field("overlayRoot") as LinearLayout
        settle()
    }
    @After fun tearDown() { controller.destroy(); settle() }
    private fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun invoke(name: String) { service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service) }
    private fun layout() {
        val p = field("overlayParams") as? WindowManager.LayoutParams ?: return
        root.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, p.width, p.height)
    }
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); if (::root.isInitialized) layout() }
    private fun event(action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun bubble(): View = (0 until root.childCount).map { root.getChildAt(it) }
        .first { it.contentDescription?.startsWith("ScreenKit") == true }
    private fun tap() {
        val b = bubble(); val x = b.left + b.width / 2f; val y = b.top + b.height / 2f
        event(MotionEvent.ACTION_DOWN, x, y); event(MotionEvent.ACTION_UP, x, y)
    }
    @Test fun frameworkRemovalDuringUpReentersTheListenerWithCancel() {
        var cancellations = 0
        bubble().setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) root.removeAllViews()
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancellations++
            true
        }
        event(MotionEvent.ACTION_DOWN, 20f, 20f)
        event(MotionEvent.ACTION_UP, 20f, 20f)
        assertEquals("Android synthesizes CANCEL while removing the current touch target", 1, cancellations)
    }
    @Test fun dragReleaseDefersHierarchyChangeAndDoesNotCloseWindow() {
        val b = bubble()
        event(MotionEvent.ACTION_DOWN, 20f, 20f)
        event(MotionEvent.ACTION_MOVE, 100f, 70f)
        event(MotionEvent.ACTION_UP, 100f, 70f)
        assertSame("Do not remove the touch target inside dispatch", b, bubble())
        settle()
        assertSame(root, field("overlayRoot"))
        assertTrue(root.isAttachedToWindow)
        assertFalse(field("expanded") as Boolean)
    }
    @Test fun repeatedExpandDragCollapseSurvivesActualViewDispatch() {
        repeat(12) {
            tap(); settle()
            assertTrue(field("expanded") as Boolean)
            val b = bubble(); val x = b.left + b.width / 2f; val y = b.top + b.height / 2f
            event(MotionEvent.ACTION_DOWN, x, y)
            event(MotionEvent.ACTION_MOVE, x + 60, y + 30)
            event(MotionEvent.ACTION_UP, x + 60, y + 30)
            settle()
            assertFalse(field("expanded") as Boolean)
            assertTrue(root.isAttachedToWindow)
        }
    }
    @Test fun cancelledTapDoesNotExpandOrRemoveBubble() {
        event(MotionEvent.ACTION_DOWN, 20f, 20f)
        event(MotionEvent.ACTION_CANCEL, 20f, 20f)
        settle()
        assertFalse(field("expanded") as Boolean)
        assertSame(root, field("overlayRoot"))
    }
    @Test fun stateRebuildIsDeferredUntilTouchDispatchCompletes() {
        val b = bubble()
        event(MotionEvent.ACTION_DOWN, 20f, 20f)
        invoke("rebuildOverlay")
        assertSame(b, bubble())
        event(MotionEvent.ACTION_CANCEL, 20f, 20f)
        assertSame(b, bubble())
        settle()
        assertNotSame(b, bubble())
        assertSame(root, field("overlayRoot"))
    }
    @Test fun queuedReleaseCannotRecreateRemovedOverlay() {
        event(MotionEvent.ACTION_DOWN, 20f, 20f)
        event(MotionEvent.ACTION_MOVE, 100f, 60f)
        event(MotionEvent.ACTION_UP, 100f, 60f)
        invoke("removeOverlay")
        settle()
        assertNull(field("overlayRoot"))
        assertFalse(root.isAttachedToWindow)
    }
}
