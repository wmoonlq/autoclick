package com.outoclick.probe

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 测试探针：
 * - 3×3 全屏格子，记录每次 MotionEvent 的 DOWN/UP/CANCEL、坐标、时间戳、时长。
 * - 可切换竖屏/横屏/传感器，显示当前 rotation/尺寸。
 * - 底部输入框用于测试输入法弹出时悬浮球是否还在。
 * - CSV 路径：/sdcard/Android/data/com.outoclick.probe/files/events.csv
 */
class ProbeActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var countView: TextView
    private lateinit var eventsFile: File
    private var eventCount = 0
    private var downReceivedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        eventsFile = File(getExternalFilesDir(null) ?: filesDir, "events.csv")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(status)

        countView = TextView(this).apply {
            setTextColor(0xFFFFD43B.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(countView)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (row in 0 until 3) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0 until 3) {
                val label = "${('A' + row)}${col + 1}"
                val cell = TextView(this).apply {
                    text = label
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setBackgroundColor(0xFF2E2E2E.toInt())
                    isClickable = true
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downReceivedAt = SystemClock.elapsedRealtime()
                                view.isPressed = true
                                record(event, label)
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                view.isPressed = false
                                record(event, label)
                                view.performClick()
                                true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                view.isPressed = false
                                record(event, label)
                                true
                            }

                            else -> false
                        }
                    }
                }
                rowView.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            grid.addView(rowView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val controls = ScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlRow.addView(control("清空") { clear() })
        controlRow.addView(control("刷新") { refreshStatus() })
        controlRow.addView(control("竖屏") { setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) })
        controlRow.addView(control("横屏") { setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) })
        controlRow.addView(control("传感器") { setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR) })
        controlRow.addView(control("CSV 路径") {
            Toast.makeText(this, eventsFile.absolutePath, Toast.LENGTH_LONG).show()
        })
        controls.addView(controlRow)
        root.addView(controls)

        val input = EditText(this).apply {
            hint = "输入法测试：点这里弹键盘"
            setHintTextColor(0xFF888888.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(0xFF2E2E2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.postDelayed({ refreshStatus() }, 250)
    }

    private fun control(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 11f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply { rightMargin = dp(4) }
        }

    private fun setOrientation(orientation: Int) {
        requestedOrientation = orientation
        window.decorView.postDelayed({ refreshStatus() }, 350)
    }

    private fun clear() {
        eventsFile.delete()
        eventCount = 0
        downReceivedAt = 0L
        refreshStatus()
    }

    private fun record(event: MotionEvent, cell: String) {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return
        }
        val receivedAt = SystemClock.elapsedRealtime()
        if (action == "DOWN") downReceivedAt = receivedAt
        // 用探针收到事件的墙钟时间算实际按住时长；注入事件的 eventTime 可能相同。
        val duration = if (action == "UP" || action == "CANCEL") {
            receivedAt - downReceivedAt
        } else {
            0L
        }
        val display = windowManager.defaultDisplay
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        @Suppress("DEPRECATION")
        val rotation = display.rotation
        val line = buildString {
            append(receivedAt).append(',')
            append(event.eventTime).append(',')
            append(action).append(',')
            append(cell).append(',')
            append(event.rawX).append(',')
            append(event.rawY).append(',')
            append(event.downTime).append(',')
            append(duration).append(',')
            append(rotation).append(',')
            append(point.x).append(',')
            append(point.y).append('\n')
        }
        runCatching {
            if (!eventsFile.exists() || eventsFile.length() == 0L) {
                eventsFile.appendText("elapsed_ms,event_time,action,cell,x,y,down_time,duration_ms,rotation,width,height\n")
            }
            eventsFile.appendText(line)
        }
        eventCount += 1
        countView.text = "events: $eventCount"
        refreshStatus()
    }

    private fun refreshStatus() {
        val display = windowManager.defaultDisplay
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        @Suppress("DEPRECATION")
        val rotation = display.rotation
        val orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }
        status.text = buildString {
            append("rotation=").append(rotation)
            append("  config=").append(orientation)
            append("  size=").append(point.x).append('x').append(point.y)
            append('\n')
            append("CSV: ").append(eventsFile.absolutePath)
        }
        countView.text = "events: $eventCount"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
