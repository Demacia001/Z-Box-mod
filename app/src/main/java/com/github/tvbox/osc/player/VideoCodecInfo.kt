package com.github.tvbox.osc.player

data class VideoCodecInfo(
    val decoderName: String = "",
    val kind: PlayerDecodeKind = PlayerDecodeKind.UNKNOWN,
    val generation: Int = 0,
)

interface VideoCodecObserver {
    fun onCodecInitialized(name: String, kind: PlayerDecodeKind)

    fun onCodecReleased()

    val codecInfo: VideoCodecInfo
}
