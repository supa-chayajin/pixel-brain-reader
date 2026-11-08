package cloud.wafflecommons.pixelbrainreader.data.device

import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thrown when a read-only device is asked to change the vault. An [IOException], so the code that
 * already handles a failed write handles this one too.
 */
class ReadOnlyDeviceException(
    message: String = "This device is read-only: it never changes the vault."
) : IOException(message)

/**
 * The one switch that every vault writer checks: files ([check]), git (JGitProvider) and the
 * background writers (sync cycle, push scheduler, foreground refresh, workers, widgets).
 *
 * On a read-only device ([DeviceRole], chosen on the login screen) nothing may change the vault.
 * The switch is set from the saved role first thing in `PixelBrainApplication.onCreate`, before
 * any component can write, and again at login.
 */
object VaultWriteGate {

    @Volatile
    var readOnly: Boolean = false

    private val _refusals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A user action was refused: the screens show "read-only device" once per emission. */
    val refusals: SharedFlow<Unit> = _refusals.asSharedFlow()

    /** What the screens say when a write is refused. */
    const val REFUSAL_MESSAGE = "Read-only device: nothing was changed."

    /**
     * For writers that report failures by exception: throws [ReadOnlyDeviceException] on a
     * read-only device (and tells the screens). The last line, behind [refuses].
     */
    fun check() {
        if (readOnly) {
            _refusals.tryEmit(Unit)
            throw ReadOnlyDeviceException()
        }
    }

    /**
     * For a user action that would write: true on a read-only device, which then tells the
     * screens to say so. The caller returns without touching anything, Room included.
     */
    fun refuses(): Boolean {
        if (readOnly) _refusals.tryEmit(Unit)
        return readOnly
    }

    /** True when [throwable] (or one of its causes) is a refused write. */
    fun isRefusal(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }.take(MAX_CAUSES).any { it is ReadOnlyDeviceException }

    private const val MAX_CAUSES = 16
}
