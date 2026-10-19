package com.github.tvbox.osc.player.engine.iso

internal class IsoBandwidthVerdict(
    val measuredBytesPerSecond: Long,
    val requiredBytesPerSecond: Long,
)

internal fun isoBandwidthVerdict(
    measuredBytesPerSecond: Long,
    targetLength: Long,
    durationMs: Long,
): IsoBandwidthVerdict? {
    if (measuredBytesPerSecond <= 0L) return null
    if (targetLength <= 0L || durationMs <= 0L) return null
    val logicalLength = targetLength * M2TS_TS_SIZE / M2TS_PACKET_SIZE
    val required = logicalLength * MILLIS_PER_SECOND / durationMs
    if (required <= 0L) return null
    if (measuredBytesPerSecond * PERCENT >= required * THRESHOLD_PERCENT) return null
    return IsoBandwidthVerdict(measuredBytesPerSecond, required)
}

internal fun stopsIsoBandwidthRetry(
    isoSession: Boolean,
    switchTried: Boolean,
): Boolean = isoSession && switchTried

internal object IsoBandwidthGuard {

    fun evaluate(durationMs: Long): IsoBandwidthVerdict? =
        isoBandwidthVerdict(IsoThroughput.bytesPerSecond(), IsoThroughput.targetLength(), durationMs)
}

private const val THRESHOLD_PERCENT = 115L
private const val PERCENT = 100L
private const val MILLIS_PER_SECOND = 1000L
