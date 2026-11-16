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
 * The game session notifications' Stop (12.17) and the "Still playing?" question's Keep playing
 * (13.1). Not exported: only the app's own explicit, immutable PendingIntents reach it.
 */
@AndroidEntryPoint
class GameReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: GameSessionController

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != GamePlatform.ACTION_STOP && action != GamePlatform.ACTION_KEEP) return
        val pending = goAsync()
        scope.launch {
            try {
                if (action == GamePlatform.ACTION_KEEP) controller.keepPlaying() else controller.stop()
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
