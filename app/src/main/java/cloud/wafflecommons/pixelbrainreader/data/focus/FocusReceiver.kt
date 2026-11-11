package cloud.wafflecommons.pixelbrainreader.data.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The focus notifications' actions (D9): Stop on the running session, "Log it" on the done one.
 * Not exported: only the app's own explicit, immutable PendingIntents reach it.
 */
@AndroidEntryPoint
class FocusReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: FocusRepository

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    FocusPlatform.ACTION_STOP -> repository.stop()
                    FocusPlatform.ACTION_LOG -> repository.log(
                        minutes = intent.getIntExtra(FocusPlatform.EXTRA_MINUTES, 0),
                        notes = intent.getStringArrayExtra(FocusPlatform.EXTRA_NOTES)?.toList().orEmpty()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Focus action failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FocusReceiver"
    }
}
