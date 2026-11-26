package com.outoclick.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class ResolvedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)

/**
 * 包名 -> 展示名/图标。Android 11+ 依赖 QUERY_ALL_PACKAGES（侧载场景）。
 */
class TargetAppResolver(private val context: Context) {

    fun resolve(packageName: String): ResolvedApp {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return ResolvedApp(packageName, packageName)
        val label = runCatching { pm.getApplicationLabel(info).toString() }
            .getOrDefault(packageName)
        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
        return ResolvedApp(packageName, label.ifBlank { packageName }, icon)
    }

    fun isBindable(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName) return false
        if (Guards.isBlockedPackage(context, packageName)) return false
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        if (!info.enabled) return false
        // 云手机/预装游戏可能带 FLAG_SYSTEM；不按 system 一刀切，
        // 只排除桌面、设置、安装器、权限控制器等硬阻止名单（Guards）。
        return pm.getLaunchIntentForPackage(packageName) != null
    }

    fun launchableApps(): List<ResolvedApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        return activities
            .mapNotNull { resolveActivity ->
                val pkg = resolveActivity.activityInfo?.packageName ?: return@mapNotNull null
                if (!isBindable(pkg)) return@mapNotNull null
                resolve(pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
