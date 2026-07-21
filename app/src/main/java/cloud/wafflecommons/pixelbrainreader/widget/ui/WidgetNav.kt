package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import cloud.wafflecommons.pixelbrainreader.MainActivity

/**
 * Single source of truth for how home-screen widgets and launcher shortcuts navigate into the app.
 *
 * Every entry point (a widget tap, a quick-action button, a static launcher shortcut) funnels
 * through a `pixelbrain://open?screen=<key>` (or `pixelbrain://capture`) URI that
 * [MainActivity.handleIntent] validates against [screenToRoute] BEFORE it touches the NavHost — so
 * an untrusted caller can only ever reach a whitelisted top-level route, never an arbitrary one.
 *
 * Public "screen keys" (e.g. [SCREEN_MOOD]) are intentionally decoupled from the internal NavHost
 * route strings (e.g. `"home_os"`): the keys are the stable widget/shortcut contract, the routes
 * are an implementation detail of [cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen].
 */
object WidgetNav {

    const val SCHEME = "pixelbrain"
    const val HOST_OPEN = "open"
    const val HOST_CAPTURE = "capture"
    const val QUERY_SCREEN = "screen"

    // Stable, public screen keys used inside deep links + static shortcuts.
    const val SCREEN_DAILY = "daily"
    const val SCREEN_MOOD = "mood"
    const val SCREEN_HABITS = "habits"
    const val SCREEN_CHORES = "chores"
    const val SCREEN_CHAT = "chat"
    const val SCREEN_STATS = "stats"
    const val SCREEN_JOURNAL = "journal"
    const val SCREEN_SETTINGS = "settings"

    /** Maps a public screen key to the internal NavHost route, or null if unknown (→ reject). */
    fun screenToRoute(screen: String?): String? = when (screen?.lowercase()?.trim()) {
        SCREEN_DAILY -> "daily_note"
        SCREEN_MOOD -> "mood"
        SCREEN_HABITS -> "habits"
        SCREEN_CHORES -> "home_os"
        SCREEN_CHAT -> "chat"
        SCREEN_STATS -> "stats"
        SCREEN_JOURNAL -> "home"
        SCREEN_SETTINGS -> "settings"
        else -> null
    }

    /**
     * An EXPLICIT (component-targeted, therefore non-browsable) intent that opens [screen] in the
     * app. Explicit targeting means only in-app callers (widgets/shortcuts) can fire it — it is not
     * exposed to the browser, so no BROWSABLE manifest filter is added for these hosts.
     */
    fun openIntent(context: Context, screen: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "$SCHEME://$HOST_OPEN?$QUERY_SCREEN=$screen".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /** An explicit intent that opens the quick-capture (new note) flow. */
    fun captureIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "$SCHEME://$HOST_CAPTURE".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
