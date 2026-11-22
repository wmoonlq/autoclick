package com.outoclick.app

import android.content.Context
import java.io.File

class ScriptStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "scripts").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("outoclick", Context.MODE_PRIVATE)

    fun list(): List<Script> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { Script.fromJson(org.json.JSONObject(file.readText())) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Script? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { Script.fromJson(org.json.JSONObject(file.readText())) }.getOrNull()
    }

    fun save(script: Script) {
        if (script.baseWidth <= 0 || script.baseHeight <= 0) {
            val size = Screen.size(app)
            script.baseWidth = size.x
            script.baseHeight = size.y
        }
        script.updatedAt = System.currentTimeMillis()
        File(dir, "${script.id}.json").writeText(script.toJson().toString(2))
    }

    fun create(name: String): Script {
        val script = Script(name = name.ifBlank { "未命名脚本" })
        save(script)
        setActive(script.id)
        return script
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        if (activeId() == id) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun duplicate(script: Script): Script {
        val copy = Script.fromJson(script.toJson()) ?: script
        copy.id = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        copy.name = "${script.name} 副本"
        copy.updatedAt = System.currentTimeMillis()
        save(copy)
        return copy
    }

    fun importFrom(text: String): Script? {
        val parsed = Script.decode(text) ?: return null
        parsed.id = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        parsed.name = "${parsed.name} (导入)"
        save(parsed)
        return parsed
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).putBoolean(KEY_UNLOADED, false).apply()
    }

    fun active(): Script? {
        if (isUnloaded()) return null
        val id = activeId() ?: list().firstOrNull()?.id ?: return null
        return load(id) ?: list().firstOrNull()
    }

    /** 悬浮窗使用：退出方案后返回 null，不再回退到第一个脚本 */
    fun loaded(): Script? = active()

    fun isUnloaded(): Boolean = prefs.getBoolean(KEY_UNLOADED, false)

    fun unload() {
        prefs.edit().putBoolean(KEY_UNLOADED, true).apply()
    }

    fun ensureSeed(): Script {
        prefs.edit().putBoolean(KEY_UNLOADED, false).apply()
        active()?.let { return it }
        val demos = list()
        if (demos.isNotEmpty()) return demos.first()
        val demo = Script.demo()
        save(demo)
        setActive(demo.id)
        return demo
    }

    fun showPoints(): Boolean = prefs.getBoolean(KEY_SHOW_POINTS, true)

    fun setShowPoints(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_POINTS, enabled).apply()
    }

    fun overlayX(): Int = prefs.getInt(KEY_X, -1)
    fun overlayY(): Int = prefs.getInt(KEY_Y, -1)
    fun setOverlay(x: Int, y: Int) = prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()

    fun ballVisible(): Boolean = prefs.getBoolean(KEY_BALL_VISIBLE, true)
    fun setBallVisible(visible: Boolean) =
        prefs.edit().putBoolean(KEY_BALL_VISIBLE, visible).apply()

    companion object {
        private const val KEY_ACTIVE = "active_script"
        private const val KEY_UNLOADED = "active_unloaded"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val KEY_BALL_VISIBLE = "ball_visible"
        private const val KEY_SHOW_POINTS = "show_points"
    }
}
