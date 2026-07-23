package app.local1st.files.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class ExternalOpenKind(val aliasName: String) {
    ARCHIVE("OpenArchiveActivity"),
    IMAGE("OpenImageActivity"),
    VIDEO("OpenVideoActivity"),
}

/** User-controlled participation in Android's global ACTION_VIEW resolver. */
object ExternalOpenRegistry {
    fun isEnabled(context: Context, kind: ExternalOpenKind): Boolean {
        val component = component(context, kind)
        return when (context.packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getActivityInfo(
                    component,
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                ).enabled
            }.getOrDefault(false)
        }
    }

    fun setEnabled(context: Context, kind: ExternalOpenKind, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context, kind),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun kindOf(componentName: ComponentName?): ExternalOpenKind? {
        val className = componentName?.className ?: return null
        return ExternalOpenKind.entries.firstOrNull { className.endsWith(".${it.aliasName}") }
    }

    private fun component(context: Context, kind: ExternalOpenKind): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.${kind.aliasName}")
}
