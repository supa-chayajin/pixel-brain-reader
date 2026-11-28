package cloud.wafflecommons.pixelbrainreader.ui.settings

/**
 * How a permission is granted, so the Permissions page (13.4, S11) knows what to check and what to
 * open: a [RUNTIME] dialog, the Live Updates switch ([PROMOTED], from API 36.1), the Do Not Disturb
 * access page ([NOTIFICATION_POLICY]), Health Connect's own sheet ([HEALTH]), or nothing to grant
 * ([ALWAYS]).
 */
enum class PermissionKind { RUNTIME, PROMOTED, NOTIFICATION_POLICY, HEALTH, ALWAYS }

/**
 * A permission the app uses, as the Permissions page shows it: [key] (stable, the search's anchor),
 * its name, what it is for, and the manifest permissions it stands for.
 */
data class AppPermission(
    val key: String,
    val title: String,
    val purpose: String,
    val manifest: List<String>,
    val kind: PermissionKind
)

/**
 * Every permission the app declares in its manifest, one row each (S11). The page shows and opens
 * them: it adds none. Pure (plain strings: the constants of the newer ones would need a newer
 * Android at compile time), checked against the manifest by `AppPermissionsTest`.
 */
object AppPermissions {

    const val HEALTH_PREFIX = "android.permission.health."

    /** The Health Connect permissions the manifest declares: Daily, Life Stats, habits and recovery read them. */
    private val HEALTH = listOf(
        "READ_STEPS",
        "READ_SLEEP",
        "READ_HEART_RATE",
        "READ_RESTING_HEART_RATE",
        "READ_HEART_RATE_VARIABILITY",
        "READ_EXERCISE",
        "READ_ACTIVE_CALORIES_BURNED",
        "READ_HYDRATION",
        "READ_NUTRITION",
        "READ_WEIGHT",
        "READ_MINDFULNESS",
        "READ_DISTANCE",
        "READ_TOTAL_CALORIES_BURNED"
    ).map { HEALTH_PREFIX + it }

    val ALL: List<AppPermission> = listOf(
        AppPermission(
            key = "notifications",
            title = "Notifications",
            purpose = "Reminders, the evening check-in, focus and game sessions",
            manifest = listOf("android.permission.POST_NOTIFICATIONS"),
            kind = PermissionKind.RUNTIME
        ),
        AppPermission(
            key = "live_updates",
            title = "Live updates",
            purpose = "Focus and game sessions on the lock screen and the status bar",
            manifest = listOf("android.permission.POST_PROMOTED_NOTIFICATIONS"),
            kind = PermissionKind.PROMOTED
        ),
        AppPermission(
            key = "dnd",
            title = "Do Not Disturb access",
            purpose = "Game night turns on the app's own mode among Android's Modes",
            manifest = listOf("android.permission.ACCESS_NOTIFICATION_POLICY"),
            kind = PermissionKind.NOTIFICATION_POLICY
        ),
        AppPermission(
            key = "location",
            title = "Location",
            purpose = "The weather on Daily, from an approximate location",
            manifest = listOf("android.permission.ACCESS_COARSE_LOCATION"),
            kind = PermissionKind.RUNTIME
        ),
        AppPermission(
            key = "microphone",
            title = "Microphone",
            purpose = "Dictation, on this phone",
            manifest = listOf("android.permission.RECORD_AUDIO"),
            kind = PermissionKind.RUNTIME
        ),
        AppPermission(
            key = "health",
            title = "Health Connect",
            purpose = "Sleep, steps and heart figures for Daily, Life Stats and habits",
            manifest = HEALTH,
            kind = PermissionKind.HEALTH
        ),
        AppPermission(
            key = "internet",
            title = "Internet",
            purpose = "Sync, imports, feeds and the consoles; every call is in the privacy ledger",
            manifest = listOf("android.permission.INTERNET"),
            kind = PermissionKind.ALWAYS
        )
    )
}
