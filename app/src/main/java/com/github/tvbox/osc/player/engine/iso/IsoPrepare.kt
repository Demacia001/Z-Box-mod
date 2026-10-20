package com.github.tvbox.osc.player.engine.iso

import com.github.tvbox.osc.util.LOG
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object IsoPrepare {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iso-prepare").apply { isDaemon = true }
    }

    private val scheduled = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var token = 0

    fun cancel() {
        token++
        scheduled.clear()
    }

    fun schedule(
        key: String,
        locator: IsoLocator,
        selection: IsoSelection,
        snapshot: ByteArray?,
    ) {
        if (scheduled.putIfAbsent(key, true) != null) return
        val mine = token
        val clips = selection.segments.map { it.name }.distinct()
        executor.execute {
            if (!awaitTurn(mine)) return@execute
            if (snapshot != null) {
                val bytes = IsoDiskCache.saveBytes(key, snapshot)
                if (bytes > 0) LOG.i("echo-iso-cache-save:$bytes")
            }
            val started = System.currentTimeMillis()
            var ready = 0
            for (name in clips) {
                if (mine != token) return@execute
                val clipId = name.substringBeforeLast('.').lowercase(Locale.ROOT)
                val index = runCatching { locator.accessPoints(clipId) }.getOrNull()
                if (index != null) ready++
            }
            LOG.i("echo-iso-clpi-prefetch:$ready/${clips.size}:${System.currentTimeMillis() - started}")
        }
    }

    private fun awaitTurn(mine: Int): Boolean {
        var waited = 0L
        while (waited < DELAY_MS) {
            if (mine != token) return false
            try {
                Thread.sleep(SLICE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            waited += SLICE_MS
        }
        return mine == token
    }

    private const val DELAY_MS = 6_000L
    private const val SLICE_MS = 250L
}
