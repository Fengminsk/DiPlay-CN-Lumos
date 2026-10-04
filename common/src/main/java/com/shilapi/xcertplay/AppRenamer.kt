package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Renames the launcher entry by switching between pre-declared activity-alias components —
 * Android reads the app label from the APK, so free text is impossible, but each alias carries
 * its own label. Samples and other packages without the aliases simply see no feature here.
 */
object AppRenamer {
    private val ALIASES = listOf(
        "LauncherAliasCn",
        "LauncherAliasDiplay",
        "LauncherAliasCarplay",
        "LauncherAliasAppleCarplay",
        "LauncherAliasNavi",
        "LauncherAliasMap",
        "LauncherAliasLink",
        "LauncherAliasMedia",
    )

    private fun components(context: Context): List<ComponentName> = ALIASES
        .map { ComponentName(context.packageName, "com.shilapi.xcertplay.$it") }
        .filter { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess }

    fun available(context: Context): Boolean = components(context).isNotEmpty()

    fun labels(context: Context): List<String> {
        val manager = context.packageManager
        return components(context).map { manager.getActivityInfo(it, 0).loadLabel(manager).toString() }
    }

    /** The chosen index lives beside the component states so both stay in sync. */
    fun current(context: Context): Int {
        val prefs = context.getSharedPreferences(RENAME_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NAME_INDEX, 0).coerceIn(0, (components(context).size - 1).coerceAtLeast(0))
    }

    fun apply(context: Context, index: Int) {
        val app = context.applicationContext
        val list = components(app)
        if (list.isEmpty()) return
        val chosen = index.coerceIn(0, list.lastIndex)
        list.forEachIndexed { i, component ->
            app.packageManager.setComponentEnabledSetting(
                component,
                if (i == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        app.getSharedPreferences(RENAME_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAME_INDEX, chosen).apply()
    }

    private const val RENAME_PREFS = "diplay_rename"
    private const val KEY_NAME_INDEX = "name_index"
}
