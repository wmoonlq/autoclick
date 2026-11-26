package com.outoclick.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.outoclick.app.model.AppSettings
import com.outoclick.app.model.AppState
import com.outoclick.app.model.Button
import com.outoclick.app.model.JitterProfile
import com.outoclick.app.model.PauseReason
import com.outoclick.app.model.Plan
import com.outoclick.app.model.PlanStatus
import com.outoclick.app.model.PlanStep
import com.outoclick.app.model.RunSession
import com.outoclick.app.model.SessionStatus
import com.outoclick.app.model.StopReason
import com.outoclick.app.model.TargetApp

/**
 * SQLite 结构。表与《数据实体建模 v4》对齐：
 * apps / buttons / plans / plan_steps / sessions / settings
 *
 * 说明：
 * - v1 数据量小，结构变更（方案/按钮/步骤）用事务整体重写结构表；
 *   运行进度只 UPDATE 单条 session，避免每次点击重写全库。
 * - sessions 保留最近 50 条。
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "outoclick.db"
        const val DB_VERSION = 1

        private const val CREATE_APPS = """
            CREATE TABLE apps (
                id TEXT PRIMARY KEY NOT NULL,
                package_name TEXT NOT NULL UNIQUE,
                app_label TEXT NOT NULL,
                bound_rotation INTEGER NOT NULL DEFAULT 0,
                bound_display_w INTEGER NOT NULL DEFAULT 0,
                bound_display_h INTEGER NOT NULL DEFAULT 0,
                first_bound_at INTEGER NOT NULL DEFAULT 0,
                last_run_at INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_BUTTONS = """
            CREATE TABLE buttons (
                id TEXT PRIMARY KEY NOT NULL,
                target_app_id TEXT NOT NULL DEFAULT '',
                label TEXT NOT NULL DEFAULT '',
                anchor_x REAL NOT NULL DEFAULT 0.5,
                anchor_y REAL NOT NULL DEFAULT 0.5,
                width REAL NOT NULL DEFAULT 0.12,
                height REAL NOT NULL DEFAULT 0.05,
                click_count INTEGER NOT NULL DEFAULT 1,
                click_gap_ms INTEGER NOT NULL DEFAULT 120,
                press_ms INTEGER NOT NULL DEFAULT 75
            )
        """

        private const val CREATE_PLANS = """
            CREATE TABLE plans (
                id TEXT PRIMARY KEY NOT NULL,
                target_app_id TEXT,
                name TEXT NOT NULL,
                status TEXT NOT NULL,
                loop_count INTEGER NOT NULL DEFAULT 1,
                anti_detect_enabled INTEGER NOT NULL DEFAULT 1,
                jitter_delay_ms INTEGER NOT NULL DEFAULT 5,
                jitter_gap_ms INTEGER NOT NULL DEFAULT 5,
                jitter_press_ms INTEGER NOT NULL DEFAULT 5,
                jitter_pos_px INTEGER NOT NULL DEFAULT 3,
                version INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                last_run_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_PLAN_STEPS = """
            CREATE TABLE plan_steps (
                id TEXT PRIMARY KEY NOT NULL,
                plan_id TEXT NOT NULL,
                button_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                point_gap_ms INTEGER NOT NULL DEFAULT 1000,
                FOREIGN KEY (plan_id) REFERENCES plans(id) ON DELETE CASCADE,
                FOREIGN KEY (button_id) REFERENCES buttons(id) ON DELETE CASCADE
            )
        """

        private const val CREATE_SESSIONS = """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                plan_id TEXT NOT NULL,
                plan_version INTEGER NOT NULL DEFAULT 0,
                target_app_id TEXT,
                target_package TEXT NOT NULL DEFAULT '',
                snapshot_json TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                paused_reason TEXT,
                stop_reason TEXT,
                current_step_seq INTEGER NOT NULL DEFAULT 0,
                loop_index INTEGER NOT NULL DEFAULT 0,
                last_progress_at INTEGER NOT NULL DEFAULT 0,
                foreground_package TEXT NOT NULL DEFAULT '',
                display_w INTEGER NOT NULL DEFAULT 0,
                display_h INTEGER NOT NULL DEFAULT 0,
                rotation INTEGER NOT NULL DEFAULT 0,
                error_code TEXT,
                ok_count INTEGER NOT NULL DEFAULT 0,
                fail_count INTEGER NOT NULL DEFAULT 0,
                skip_count INTEGER NOT NULL DEFAULT 0,
                started_at INTEGER NOT NULL DEFAULT 0,
                ended_at INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_SETTINGS = """
            CREATE TABLE settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                active_plan_id TEXT,
                schema_version INTEGER NOT NULL DEFAULT 1
            )
        """
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_APPS)
        db.execSQL(CREATE_BUTTONS)
        db.execSQL(CREATE_PLANS)
        db.execSQL(CREATE_PLAN_STEPS)
        db.execSQL(CREATE_SESSIONS)
        db.execSQL(CREATE_SETTINGS)
        db.execSQL("CREATE INDEX idx_buttons_app ON buttons(target_app_id)")
        db.execSQL("CREATE INDEX idx_steps_plan ON plan_steps(plan_id, seq)")
        db.execSQL("CREATE INDEX idx_sessions_status ON sessions(status)")
        db.execSQL("INSERT INTO settings (id, active_plan_id, schema_version) VALUES (1, NULL, 1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 只有初始版本；后续迁移在这里按版本号逐步实现，禁止 drop 用户数据。
    }

    fun loadState(): AppState {
        val state = AppState()
        val db = readableDatabase

        db.query("apps", null, null, null, null, null, "first_bound_at ASC").use { c ->
            while (c.moveToNext()) {
                state.apps.add(
                    TargetApp(
                        id = c.str("id"),
                        packageName = c.str("package_name"),
                        label = c.str("app_label"),
                        boundRotation = c.int("bound_rotation"),
                        boundDisplayW = c.int("bound_display_w"),
                        boundDisplayH = c.int("bound_display_h"),
                        firstBoundAt = c.long("first_bound_at"),
                        lastRunAt = c.long("last_run_at"),
                    )
                )
            }
        }

        db.query("buttons", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                state.buttons.add(
                    Button(
                        id = c.str("id"),
                        targetAppId = c.str("target_app_id"),
                        label = c.str("label"),
                        anchorX = c.double("anchor_x"),
                        anchorY = c.double("anchor_y"),
                        width = c.double("width"),
                        height = c.double("height"),
                        clickCount = c.int("click_count"),
                        clickGapMs = c.int("click_gap_ms"),
                        pressMs = c.int("press_ms"),
                    )
                )
            }
        }

        val planIndex = mutableMapOf<String, Plan>()
        db.query("plans", null, null, null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) {
                val plan = Plan(
                    id = c.str("id"),
                    targetAppId = c.strOrNull("target_app_id"),
                    name = c.str("name"),
                    status = enumOrNull<PlanStatus>(c.str("status")) ?: PlanStatus.DRAFT,
                    loopCount = c.int("loop_count"),
                    jitter = JitterProfile(
                        enabled = c.bool("anti_detect_enabled"),
                        delayMs = c.int("jitter_delay_ms"),
                        clickGapMs = c.int("jitter_gap_ms"),
                        pressMs = c.int("jitter_press_ms"),
                        positionPx = c.int("jitter_pos_px"),
                    ),
                    version = c.int("version"),
                    createdAt = c.long("created_at"),
                    updatedAt = c.long("updated_at"),
                    lastRunAt = c.long("last_run_at"),
                    deleted = c.bool("deleted"),
                )
                state.plans.add(plan)
                planIndex[plan.id] = plan
            }
        }

        db.query("plan_steps", null, null, null, null, null, "seq ASC").use { c ->
            while (c.moveToNext()) {
                val plan = planIndex[c.str("plan_id")] ?: continue
                plan.steps.add(
                    PlanStep(
                        id = c.str("id"),
                        buttonId = c.str("button_id"),
                        seq = c.int("seq"),
                        enabled = c.bool("enabled"),
                        pointGapMs = c.int("point_gap_ms"),
                    )
                )
            }
        }
        state.plans.forEach { plan ->
            plan.steps.sortBy { it.seq }
            plan.resequence()
        }

        db.query("sessions", null, null, null, null, null, "started_at ASC").use { c ->
            while (c.moveToNext()) {
                state.sessions.add(
                    RunSession(
                        id = c.str("id"),
                        planId = c.str("plan_id"),
                        planVersion = c.int("plan_version"),
                        targetAppId = c.strOrNull("target_app_id"),
                        targetPackage = c.str("target_package"),
                        snapshotJson = c.str("snapshot_json"),
                        status = enumOrNull<SessionStatus>(c.str("status")) ?: SessionStatus.STOPPED,
                        pausedReason = enumOrNull<PauseReason>(c.strOrNull("paused_reason")),
                        stopReason = enumOrNull<StopReason>(c.strOrNull("stop_reason")),
                        currentStepSeq = c.int("current_step_seq"),
                        loopIndex = c.int("loop_index"),
                        lastProgressAt = c.long("last_progress_at"),
                        foregroundPackage = c.str("foreground_package"),
                        displayW = c.int("display_w"),
                        displayH = c.int("display_h"),
                        rotation = c.int("rotation"),
                        errorCode = c.strOrNull("error_code"),
                        okCount = c.int("ok_count"),
                        failCount = c.int("fail_count"),
                        skipCount = c.int("skip_count"),
                        startedAt = c.long("started_at"),
                        endedAt = c.long("ended_at"),
                    )
                )
            }
        }

        db.query("settings", null, "id = 1", null, null, null, null).use { c ->
            if (c.moveToFirst()) {
                state.settings = AppSettings(
                    activePlanId = c.strOrNull("active_plan_id"),
                    schemaVersion = c.int("schema_version"),
                )
            }
        }
        if (state.plan(state.settings.activePlanId) == null) {
            state.settings.activePlanId = null
        }
        return state
    }

    /**
     * 结构表（apps/buttons/plans/plan_steps/settings）整体重写。
     * sessions 不动，避免运行历史丢失。
     */
    fun saveState(state: AppState) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("plan_steps", null, null)
            db.delete("plans", null, null)
            db.delete("buttons", null, null)
            db.delete("apps", null, null)
            db.delete("settings", null, null)

            state.apps.forEach { app -> db.insertOrThrow("apps", null, app.toValues()) }
            state.buttons.forEach { button -> db.insertOrThrow("buttons", null, button.toValues()) }
            state.plans.forEach { plan ->
                db.insertOrThrow("plans", null, plan.toValues())
                plan.steps.forEach { step ->
                    db.insertOrThrow("plan_steps", null, step.toValues(plan.id))
                }
            }

            val settings = ContentValues().apply {
                put("id", 1)
                put("active_plan_id", state.settings.activePlanId)
                put("schema_version", state.settings.schemaVersion)
            }
            db.insertOrThrow("settings", null, settings)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertSession(session: RunSession) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "sessions",
                null,
                session.toValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.execSQL(
                "DELETE FROM sessions WHERE id NOT IN " +
                    "(SELECT id FROM sessions ORDER BY started_at DESC LIMIT 50)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun reconcileSessions() {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", SessionStatus.STOPPED.name)
            put("stop_reason", StopReason.KILLED.name)
            putNull("paused_reason")
            put("error_code", "PROCESS_KILLED")
            put("ended_at", now)
        }
        writableDatabase.update(
            "sessions",
            values,
            "status IN (?, ?, ?)",
            arrayOf(
                SessionStatus.PENDING.name,
                SessionStatus.RUNNING.name,
                SessionStatus.PAUSED.name,
            ),
        )
    }

    // ------------------------------------------------------------------
    // ContentValues
    // ------------------------------------------------------------------

    private fun TargetApp.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("package_name", packageName)
        put("app_label", label)
        put("bound_rotation", boundRotation)
        put("bound_display_w", boundDisplayW)
        put("bound_display_h", boundDisplayH)
        put("first_bound_at", firstBoundAt)
        put("last_run_at", lastRunAt)
    }

    private fun Button.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("target_app_id", targetAppId)
        put("label", label)
        put("anchor_x", anchorX)
        put("anchor_y", anchorY)
        put("width", width)
        put("height", height)
        put("click_count", clickCount)
        put("click_gap_ms", clickGapMs)
        put("press_ms", pressMs)
    }

    private fun Plan.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("target_app_id", targetAppId)
        put("name", name)
        put("status", status.name)
        put("loop_count", loopCount)
        put("anti_detect_enabled", if (jitter.enabled) 1 else 0)
        put("jitter_delay_ms", jitter.delayMs)
        put("jitter_gap_ms", jitter.clickGapMs)
        put("jitter_press_ms", jitter.pressMs)
        put("jitter_pos_px", jitter.positionPx)
        put("version", version)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("last_run_at", lastRunAt)
        put("deleted", if (deleted) 1 else 0)
    }

    private fun PlanStep.toValues(planId: String): ContentValues = ContentValues().apply {
        put("id", id)
        put("plan_id", planId)
        put("button_id", buttonId)
        put("seq", seq)
        put("enabled", if (enabled) 1 else 0)
        put("point_gap_ms", pointGapMs)
    }

    private fun RunSession.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("plan_id", planId)
        put("plan_version", planVersion)
        put("target_app_id", targetAppId)
        put("target_package", targetPackage)
        put("snapshot_json", snapshotJson)
        put("status", status.name)
        put("paused_reason", pausedReason?.name)
        put("stop_reason", stopReason?.name)
        put("current_step_seq", currentStepSeq)
        put("loop_index", loopIndex)
        put("last_progress_at", lastProgressAt)
        put("foreground_package", foregroundPackage)
        put("display_w", displayW)
        put("display_h", displayH)
        put("rotation", rotation)
        put("error_code", errorCode)
        put("ok_count", okCount)
        put("fail_count", failCount)
        put("skip_count", skipCount)
        put("started_at", startedAt)
        put("ended_at", endedAt)
    }

    // ------------------------------------------------------------------
    // Cursor helpers
    // ------------------------------------------------------------------

    private fun Cursor.str(name: String): String =
        getString(getColumnIndexOrThrow(name)) ?: ""

    private fun Cursor.strOrNull(name: String): String? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

    private fun Cursor.double(name: String): Double = getDouble(getColumnIndexOrThrow(name))

    private fun Cursor.bool(name: String): Boolean = int(name) != 0

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
