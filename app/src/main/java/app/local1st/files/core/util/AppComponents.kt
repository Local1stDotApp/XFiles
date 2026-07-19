package app.local1st.files.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.shQuote
import java.io.IOException

/** The four Android manifest component kinds, with display label and id slug. */
enum class ComponentType(val label: String, val slug: String) {
    ACTIVITY("Activities", "activity"),
    SERVICE("Services", "service"),
    RECEIVER("Receivers", "receiver"),
    PROVIDER("Providers", "provider");

    companion object {
        fun fromSlug(slug: String): ComponentType? = entries.firstOrNull { it.slug == slug }
    }
}

/** One declared component of an installed app. */
data class AppComponent(
    val type: ComponentType,
    /** Fully-qualified class name. */
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
)

/**
 * Reads an installed app's manifest components via [PackageManager]. Call off the main thread.
 *
 * Disabled-by-default components are included (MATCH_DISABLED_COMPONENTS) so the tree mirrors the
 * manifest, not just the currently-enabled set — the point of browsing components is to see them
 * all. Ids have the form `apps://<pkg>/@components/<slug>/<fully.qualified.ClassName>`.
 */
object AppComponents {

    const val COMPONENTS_SEGMENT = "@components"

    /** Per-type counts. Empty map when the package is gone. */
    fun counts(context: Context, packageName: String): Map<ComponentType, Int> {
        val pkg = packageInfo(context.packageManager, packageName, allFlags()) ?: return emptyMap()
        return mapOf(
            ComponentType.ACTIVITY to (pkg.activities?.size ?: 0),
            ComponentType.SERVICE to (pkg.services?.size ?: 0),
            ComponentType.RECEIVER to (pkg.receivers?.size ?: 0),
            ComponentType.PROVIDER to (pkg.providers?.size ?: 0),
        )
    }

    /** All components of [type], sorted by fully-qualified class name. */
    fun list(context: Context, packageName: String, type: ComponentType): List<AppComponent> {
        val pm = context.packageManager
        val pkg = packageInfo(pm, packageName, flagFor(type)) ?: return emptyList()
        val infos: Array<out ComponentInfo>? = when (type) {
            ComponentType.ACTIVITY -> pkg.activities
            ComponentType.SERVICE -> pkg.services
            ComponentType.RECEIVER -> pkg.receivers
            ComponentType.PROVIDER -> pkg.providers
        }
        return infos.orEmpty()
            .map {
                // it.isEnabled is only the manifest android:enabled value; fold in any runtime
                // pm enable/disable override so the badge reflects the effective state.
                val enabled = effectiveEnabled(pm, packageName, it.name, it.isEnabled)
                AppComponent(type, it.name, it.exported, enabled)
            }
            .sortedBy { it.className }
    }

    /** A component id split back into (package, type, class), or null if [id] isn't one. */
    data class Parsed(val packageName: String, val type: ComponentType, val className: String)

    fun parseId(id: String): Parsed? {
        val segs = id.substringAfter("://", "").split('/')
        // <pkg> / @components / <slug> / <fqcn>
        if (segs.size < 4 || segs[1] != COMPONENTS_SEGMENT) return null
        val type = ComponentType.fromSlug(segs[2]) ?: return null
        // A class name never contains '/', but rejoin defensively.
        val className = segs.subList(3, segs.size).joinToString("/")
        return Parsed(segs[0], type, className)
    }

    /**
     * Whether [setEnabled] can work for [packageName]: always for our own package (a plain
     * [PackageManager] call), otherwise only with working root. This is intentionally NOT gated by
     * read-only root mode — that mode blocks irreversible *file* changes, while enabling/disabling a
     * component is a reversible `pm enable`/`pm disable` away. May block on the first `su` probe —
     * call off the main thread.
     */
    fun canToggle(context: Context, packageName: String): Boolean =
        packageName == context.packageName || PrivilegedAccess.usable()

    /**
     * The component's effective enabled state: the runtime override when one is set, else the
     * manifest value. Null when the component (or its package) no longer exists.
     */
    fun isEnabled(context: Context, component: Parsed): Boolean? =
        list(context, component.packageName, component.type)
            .firstOrNull { it.className == component.className }
            ?.enabled

    /**
     * A component's effective enabled state: the runtime override from
     * [PackageManager.getComponentEnabledSetting] when one is set, else its [manifestEnabled] value.
     */
    private fun effectiveEnabled(
        pm: PackageManager,
        packageName: String,
        className: String,
        manifestEnabled: Boolean,
    ): Boolean = when (pm.getComponentEnabledSetting(ComponentName(packageName, className))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        else -> false // DISABLED / DISABLED_USER / DISABLED_UNTIL_USED
    }

    /**
     * Enables or disables one component (works for all four types). Our own package goes through
     * [PackageManager] directly; any other package needs root (`pm enable`/`pm disable`).
     * Throws [IOException] with a user-facing message when it can't be done.
     */
    @Throws(IOException::class)
    fun setEnabled(context: Context, component: Parsed, enabled: Boolean) {
        val name = componentName(component)
        if (component.packageName == context.packageName) {
            context.packageManager.setComponentEnabledSetting(
                name,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            return
        }
        if (!PrivilegedAccess.usable()) {
            throw IOException("Changing another app's components needs root")
        }
        val verb = if (enabled) "enable" else "disable"
        PrivilegedAccess.active?.exec("pm $verb ${shQuote(name.flattenToString())}")
            ?: throw IOException("Changing another app's components needs root")
    }

    private fun componentName(component: Parsed): ComponentName =
        ComponentName(component.packageName, component.className)

    private fun flagFor(type: ComponentType): Int {
        val base = when (type) {
            ComponentType.ACTIVITY -> PackageManager.GET_ACTIVITIES
            ComponentType.SERVICE -> PackageManager.GET_SERVICES
            ComponentType.RECEIVER -> PackageManager.GET_RECEIVERS
            ComponentType.PROVIDER -> PackageManager.GET_PROVIDERS
        }
        return base or PackageManager.MATCH_DISABLED_COMPONENTS
    }

    private fun allFlags(): Int =
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.MATCH_DISABLED_COMPONENTS

    private fun packageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
