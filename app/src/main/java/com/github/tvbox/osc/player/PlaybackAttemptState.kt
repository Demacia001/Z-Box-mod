package com.github.tvbox.osc.player

class PlaybackAttemptState {

    @JvmField
    var allowSwitchPlayer: Boolean = true

    @JvmField
    var hasAutoSwitchedPlayer: Boolean = false

    @JvmField
    var autoSwitchedPlayerType: Int = -1

    @JvmField
    var hasAutoSwitchedDecode: Boolean = false

    @JvmField
    var hasAutoSwitchedAudioTrack: Boolean = false

    @JvmField
    var autoSwitchedDecodeOld: String? = null

    @JvmField
    var autoSwitchedDecodeKey: String = "exo"

    @JvmField
    var hasRetriedAfterStart: Boolean = false

    @JvmField
    var usedPreloadedResult: Boolean = false

    @JvmField
    var hasRetriedSameUrlOnBoot: Boolean = false

    @JvmField
    var hasRefreshedOnFormatError: Boolean = false

    @JvmField
    var refreshKeepsProgress: Boolean = false

    @JvmField
    var playbackStarted: Boolean = false

    @JvmField
    var playTimeoutBasePosition: Long = 0

    @JvmField
    val triedLineFlags: MutableSet<String> = HashSet()

    @JvmField
    var lastRetryTime: Long = 0

    @JvmField
    var userPickedLine: Boolean = false

    @JvmField
    var allowAutoSwitchLine: Boolean = true

    enum class SwitchIntent { NONE, REUSE, REBUILD }

    @JvmField
    var switchIntent: SwitchIntent = SwitchIntent.NONE

    @JvmField
    var switchStopPending: Boolean = false

    @JvmField
    var pendingInheritKey: String? = null

    @JvmField
    var pendingInheritProgress: Long = 0

    @JvmField
    var switchingPlayback: Boolean = false

    @JvmField
    var audioPlayback: Boolean = false

    @JvmField
    var audioOnlyConfirmed: Boolean = false

    @JvmField
    var castPrepareOnly: Boolean = false

    @JvmField
    var isoSession: Boolean = false

    @JvmField
    var hasRefreshedOnIsoHttpError: Boolean = false

    @JvmField
    var isoBandwidthSwitchTried: Boolean = false

    @JvmField
    var localProxySession: Boolean = false

    @JvmField
    var slowOpenExtended: Boolean = false

    @JvmField
    var lastHttpStatus: Int = 0

    @JvmField
    @Volatile
    var castAborted: Boolean = false

    fun beginSession() {
        playbackStarted = false
        switchStopPending = false
        autoSwitchedDecodeOld = null
        castAborted = false
        hasRefreshedOnFormatError = false
        refreshKeepsProgress = false
        isoBandwidthSwitchTried = false
        localProxySession = false
        slowOpenExtended = false
        lastHttpStatus = 0
    }

    fun beginNewPlay() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasAutoSwitchedDecode = false
        hasAutoSwitchedAudioTrack = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
        usedPreloadedResult = false
        isoSession = false
        hasRefreshedOnIsoHttpError = false
        localProxySession = false
        slowOpenExtended = false
        lastHttpStatus = 0
    }

    fun userSelfRescue() {
        hasAutoSwitchedPlayer = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
        hasRefreshedOnFormatError = false
        hasRefreshedOnIsoHttpError = false
        isoBandwidthSwitchTried = false
    }

    fun stoppedForSourceSwitch() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        toggleReuseIntent(false, false)
        switchStopPending = true
    }

    fun resetAutoRetryLadder() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasAutoSwitchedAudioTrack = false
        hasRetriedSameUrlOnBoot = false
        hasRefreshedOnFormatError = false
        hasRefreshedOnIsoHttpError = false
        clearTriedLines()
    }

    fun onLineSwitched() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasRefreshedOnFormatError = false
        hasRefreshedOnIsoHttpError = false
        slowOpenExtended = false
        if (switchIntent != SwitchIntent.REBUILD) switchIntent = SwitchIntent.REUSE
    }

    fun consumeRefreshKeepsProgress(): Boolean {
        val keep = refreshKeepsProgress
        refreshKeepsProgress = false
        return keep
    }

    fun linesExhausted() {
        clearTriedLines()
    }

    fun clearTriedLines() {
        triedLineFlags.clear()
    }

    fun setReuseIntent(reuse: Boolean) {
        if (reuse) {
            if (switchIntent == SwitchIntent.NONE) switchIntent = SwitchIntent.REUSE
        } else if (switchIntent == SwitchIntent.REUSE) {
            switchIntent = SwitchIntent.NONE
        }
    }

    fun setReleaseIntent(release: Boolean) {
        if (release) {
            switchIntent = SwitchIntent.REBUILD
        } else if (switchIntent == SwitchIntent.REBUILD) {
            switchIntent = SwitchIntent.NONE
        }
    }

    fun toggleReuseIntent(reuse: Boolean, release: Boolean) {
        switchIntent = if (release) SwitchIntent.REBUILD else if (reuse) SwitchIntent.REUSE else SwitchIntent.NONE
    }

    fun consumeReuseIntent(): Boolean {
        val reuse = switchIntent == SwitchIntent.REUSE
        switchIntent = SwitchIntent.NONE
        return reuse
    }

    fun clearSessionFlags() {
        switchingPlayback = false
        audioPlayback = false
    }

    fun abortCastSession() {
        castPrepareOnly = false
        castAborted = true
    }

    fun clearCastAbort(): Boolean {
        if (!castAborted) return false
        castAborted = false
        return true
    }
}
