package cloud.wafflecommons.pixelbrainreader.data.guard

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The confidentiality guard (fail-closed): before every commit, the staged additions are checked
 * against the on-device deny-list and the secret patterns. Anything found holds the commit —
 * nothing is recorded, so nothing can be pushed — until the user edits the note, moves it to the
 * private vault, or allows the finding.
 *
 * [held] is the last check's result; a hold also posts a notification that names no content.
 */
@Singleton
class ConfidentialityGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: GuardStore
) : CommitGuard {

    private val lock = Any()

    @Volatile
    private var loaded = false

    private val _settings = MutableStateFlow(GuardSettings())
    val settings: StateFlow<GuardSettings> = _settings.asStateFlow()

    private val _held = MutableStateFlow<List<GuardFinding>>(emptyList())
    val held: StateFlow<List<GuardFinding>> = _held.asStateFlow()

    /** When the guard last checked pending changes (a commit or a manual check); null since start. */
    private val _lastCheckMillis = MutableStateFlow<Long?>(null)
    val lastCheckMillis: StateFlow<Long?> = _lastCheckMillis.asStateFlow()

    private var notifiedSignature: Int? = null
    private var notificationCleared = false

    val settingsWereReset: Boolean get() = store.wasReset

    /** The settings, loaded on first use. Keystore-backed: call off the main thread. */
    fun current(): GuardSettings = synchronized(lock) {
        if (!loaded) {
            _settings.value = store.load()
            loaded = true
        }
        _settings.value
    }

    override fun review(changes: List<StagedText>): List<GuardFinding> {
        val findings = ConfidentialityScanner.review(changes, current())
        publish(findings)
        return findings
    }

    override fun mentionsProtectedTerm(message: String): Boolean =
        ConfidentialityScanner.mentionsAny(message, current().terms)

    /** Applies [transform] to the stored settings; findings it clears stop holding right away. */
    fun update(transform: (GuardSettings) -> GuardSettings) {
        val next = synchronized(lock) {
            val updated = transform(current())
            store.save(updated)
            _settings.value = updated
            updated
        }
        publish(ConfidentialityScanner.stillHeld(_held.value, next), checked = false)
    }

    private fun publish(findings: List<GuardFinding>, checked: Boolean = true) {
        _held.value = findings
        if (checked) _lastCheckMillis.value = System.currentTimeMillis()
        synchronized(lock) {
            if (findings.isEmpty()) {
                if (!notificationCleared) NotificationHelper.cancelGuardHold(context)
                notificationCleared = true
                notifiedSignature = null
                return
            }
            notificationCleared = false
            val signature = findings.map { it.path to it.ruleKey }.toSet().hashCode()
            if (signature != notifiedSignature) {
                NotificationHelper.postGuardHold(context, findings.size, findings.map { it.path }.distinct().size)
                notifiedSignature = signature
            }
        }
    }
}
