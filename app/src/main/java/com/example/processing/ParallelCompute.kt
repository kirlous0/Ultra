package com.example.processing

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Multi-core row-stripe parallel executor designed for Snapdragon 680 (8 Kryo 265 cores).
 * In Focus Turbo (100% power) mode, dispatches across all available CPU cores simultaneously.
 */
object ParallelCompute {

    private val cpuCores: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(cpuCores) { r ->
            Thread(r, "UltraCam-Compute").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    }

    fun parallelRows(
        totalHeight: Int,
        fullPower: Boolean,
        block: (startY: Int, endY: Int) -> Unit
    ) {
        if (totalHeight <= 0) return
        val workerCount = if (fullPower) cpuCores else minOf(4, cpuCores)
        if (workerCount <= 1 || totalHeight < workerCount * 16) {
            block(0, totalHeight)
            return
        }

        val chunk = (totalHeight + workerCount - 1) / workerCount
        val tasks = ArrayList<Callable<Unit>>(workerCount)
        var y = 0
        while (y < totalHeight) {
            val start = y
            val end = (y + chunk).coerceAtMost(totalHeight)
            tasks.add(Callable { block(start, end) })
            y = end
        }

        try {
            val futures = pool.invokeAll(tasks)
            for (f in futures) {
                f.get()
            }
        } catch (_: Throwable) {
            // Fallback deterministically on current thread if pool interrupted
            block(0, totalHeight)
        }
    }
}
