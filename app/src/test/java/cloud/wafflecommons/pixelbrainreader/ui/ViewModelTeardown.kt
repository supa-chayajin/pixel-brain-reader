package cloud.wafflecommons.pixelbrainreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Cancels this ViewModel's scope and waits until every coroutine in it has finished. Call it
 * BEFORE `Dispatchers.resetMain()`.
 *
 * A ViewModel flow that runs on a real thread (`flowOn(Dispatchers.Default)`, a
 * `withContext(Dispatchers.IO)` block) finishes cancelling on that thread, then resumes its
 * collector on Dispatchers.Main. If Main was already reset, that resume throws, and the test
 * framework reports the exception in the NEXT test that starts, as UncaughtExceptionsBeforeTest
 * (CI, 2026-11-10: a LifeOSViewModelTest case failed on an exception its previous case left
 * behind).
 *
 * Pass the [scheduler] of a StandardTestDispatcher: the resumes wait in its queue until it runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun ViewModel.cancelAndAwait(scheduler: TestCoroutineScheduler? = null, timeoutMs: Long = 5_000) {
    val job = viewModelScope.coroutineContext.job
    job.cancel()
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (!job.isCompleted) {
        scheduler?.advanceUntilIdle()
        check(System.nanoTime() < deadline) { "ViewModel coroutines still running ${timeoutMs} ms after the cancel" }
        Thread.sleep(1)
    }
}
