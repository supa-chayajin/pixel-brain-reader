package cloud.wafflecommons.pixelbrainreader.data.modes

import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository

/**
 * Per-mode tabs (E1, 12.21): each mode hides the tabs chosen for it in Modes settings
 * ([ModesConfig.hiddenTabs], in `context_modes.json`), and Daily's chip to the tools hub as
 * [TOOLKIT]. Daily itself always shows. The keys are the navigation bar's, persisted: never rename
 * one. Pure.
 */
object ModeTabs {

    const val TOOLKIT = "toolkit"

    /** Every key a mode can hide, in the bar's default order, then the toolkit chip. */
    val KEYS: List<String> = UserPreferencesRepository.DEFAULT_NAVBAR_ORDER + TOOLKIT

    /** [config] with [key] hidden in [mode] or shown again. */
    fun toggled(config: ModesConfig, mode: ContextMode, key: String): ModesConfig {
        val hidden = config.hiddenTabs[mode].orEmpty()
        val next = if (key in hidden) hidden - key else (hidden + key).distinct()
        val ordered = KEYS.filter { it in next } + next.filterNot { it in KEYS }
        return config.copy(hiddenTabs = if (ordered.isEmpty()) config.hiddenTabs - mode else config.hiddenTabs + (mode to ordered))
    }

    fun hiddenIn(config: ModesConfig, mode: ContextMode): Set<String> = config.hiddenTabs[mode].orEmpty().toSet()
}
