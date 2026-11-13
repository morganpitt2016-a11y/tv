package com.morgan.minimallauncher

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One shared set of background workers for the screens' loading jobs (instead of a new thread for
 * each). They run below the screen's priority, so loading never fights with scrolling or animations.
 */
object Bg {
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "bg").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    fun run(block: () -> Unit) { pool.execute { try { block() } catch (e: Throwable) {} } }
}
