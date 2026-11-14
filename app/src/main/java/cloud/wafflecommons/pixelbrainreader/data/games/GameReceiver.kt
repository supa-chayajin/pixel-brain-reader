package cloud.wafflecommons.pixelbrainreader.data.games

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
 * The game session notification's Stop (12.17). Not exported: only the app's own explicit,
 * immutable PendingIntent reaches it.
 */
@AndroidEntryPoint
class GameReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: GameSessionController

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GamePlatform.ACTION_STOP) return
        val pending = goAsync()
        scope.launch {
            try {
                controller.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Game stop failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GameReceiver"
    }
}
