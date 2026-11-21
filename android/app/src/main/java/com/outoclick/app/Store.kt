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
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun active(): Script? {
        val id = activeId() ?: list().firstOrNull()?.id ?: return null
        return load(id) ?: list().firstOrNull()
    }

    fun ensureSeed(): Script {
        active()?.let { return it }
        val demos = list()
        if (demos.isNotEmpty()) return demos.first()
        val demo = Script.demo()
        save(demo)
        setActive(demo.id)
        return demo
    }

    fun overlayX(): Int = prefs.getInt(KEY_X, -1)
    fun overlayY(): Int = prefs.getInt(KEY_Y, -1)
    fun setOverlay(x: Int, y: Int) = prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()

    companion object {
        private const val KEY_ACTIVE = "active_script"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
    }
}
