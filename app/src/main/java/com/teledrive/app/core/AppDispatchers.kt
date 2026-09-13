package com.teledrive.app.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clean architectural separation between Frontend, Image Loading, and Backend workloads.
 *
 * Thread priority hierarchy:
 * - UI Thread: Managed by Android (nice = -10, highest priority for 144Hz VSYNC).
 * - ImageLoader: Dedicated pool (4 threads, nice = 10 / THREAD_PRIORITY_BACKGROUND) so
 *   decoding never contends with UI rendering.
 * - Backend: Dedicated pool (3 threads, nice = 19 / THREAD_PRIORITY_LOWEST) for TDLib,
 *   Room DB writes, transfers, and ML face scanning.
 */
object AppDispatchers {

    val ImageLoader: CoroutineDispatcher = Executors.newFixedThreadPool(
        4,
        PriorityThreadFactory("TeleDrive-ImgLoader", Process.THREAD_PRIORITY_BACKGROUND)
    ).asCoroutineDispatcher()

    val Backend: CoroutineDispatcher = Executors.newFixedThreadPool(
        3,
        PriorityThreadFactory("TeleDrive-Backend", Process.THREAD_PRIORITY_LOWEST)
    ).asCoroutineDispatcher()

    private class PriorityThreadFactory(
        private val prefix: String,
        private val priority: Int
    ) : ThreadFactory {
        private val count = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread {
            return Thread({
                try {
                    Process.setThreadPriority(priority)
                } catch (_: Exception) {}
                runnable.run()
            }, "$prefix-${count.getAndIncrement()}")
        }
    }
}

/**
 * Lightweight coordinator that tracks user interaction (scrolling, gestures).
 * When user interaction is active, heavy background workers (face scanning,
 * non-urgent sync, preloading) yield to guarantee 100% CPU and IPC throughput
 * for 144Hz buttery smooth UI fluidity.
 */
object InteractionCoordinator {
    private val _isUserInteracting = MutableStateFlow(false)
    val isUserInteracting: StateFlow<Boolean> = _isUserInteracting.asStateFlow()

    @Volatile
    var isInteracting: Boolean = false
        private set

    fun setInteracting(interacting: Boolean) {
        if (isInteracting != interacting) {
            isInteracting = interacting
            _isUserInteracting.value = interacting
        }
    }
}
