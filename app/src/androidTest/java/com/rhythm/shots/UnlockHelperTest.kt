package com.rhythm.shots

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 解锁辅助：vivo 平板锁屏要求"双指上滑"，普通 input swipe 只能模拟单指。
 * 这里用 UiAutomation.injectInputEvent 直接注入多点触控 MotionEvent（真双指）。
 */
@RunWith(AndroidJUnit4::class)
class UnlockHelperTest {

    private fun motion(action: Int, points: List<Pair<Float, Float>>, downTime: Long, eventTime: Long): MotionEvent {
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(points.size)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(points.size)
        points.forEachIndexed { i, (x, y) ->
            properties[i] = MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coords[i] = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
            }
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    @Test
    fun doubleSwipeUp() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dm = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        println("screen=${w}x$h")

        // 双指：屏幕左右两侧，从 y=90% 处上滑到 y=15% 处，约 400ms
        val x1 = w * 0.25f
        val x2 = w * 0.75f
        val yStart = h * 0.9f
        val yEnd = h * 0.15f
        val downTime = SystemClock.uptimeMillis()

        // 1) 第一指按下
        uiAutomation.injectInputEvent(motion(MotionEvent.ACTION_DOWN, listOf(x1 to yStart), downTime, downTime), true)
        // 2) 第二指按下（ACTION_POINTER_DOWN，pointerIndex=1）
        uiAutomation.injectInputEvent(
            motion(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x1 to yStart, x2 to yStart), downTime, downTime + 20),
            true
        )
        // 3) 两指同步上滑（插值 20 步）
        val steps = 20
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val y = yStart + (yEnd - yStart) * f
            uiAutomation.injectInputEvent(
                motion(MotionEvent.ACTION_MOVE, listOf(x1 to y, x2 to y), downTime, downTime + 20 + i * 20),
                true
            )
        }
        // 4) 第二指抬起
        uiAutomation.injectInputEvent(
            motion(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x1 to yEnd, x2 to yEnd), downTime, downTime + 20 + (steps + 1) * 20),
            true
        )
        // 5) 第一指抬起
        uiAutomation.injectInputEvent(
            motion(MotionEvent.ACTION_UP, listOf(x1 to yEnd), downTime, downTime + 20 + (steps + 2) * 20),
            true
        )

        Thread.sleep(2500)
    }
}
