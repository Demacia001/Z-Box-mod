package com.github.tvbox.osc.ui.player

import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.controller.VodControlListener
import com.github.tvbox.osc.player.state.DanmuSettingSheetState
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.LOG
import org.greenrobot.eventbus.EventBus
import java.util.HashMap

class PlayContainerControlListener(private val container: PlayContainer) : VodControlListener {

    override fun showDanmuSetting() {
        if (!container.isAttached()) return
        container.mController.getUiState().danmuSettingSheet =
            DanmuSettingSheetState(
                onOpenSearch = { container.openDanmuSearchSheet() },
                onReset = {
                    DanmuHelper.reset()
                    container.applyDanmuSettings(false)
                },
            )
    }

    override fun toggleDanmu(): Boolean {
        val danmu = container.danmuLoadController ?: return false
        return danmu.toggle()
    }

    override fun showEpisodes() {
        container.mPageHost?.showEpisodeSheet()
    }

    override fun searchDanmuUi(longClick: Boolean) {
        val vod = container.scheduler.vod()
        val series = if (vod == null) {
            null
        } else {
            container.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        }
        ApiConfig.get().searchDanmuUi(
            vod?.name ?: "",
            series?.name ?: "",
            longClick,
        )
    }

    override fun playNext(rmProgress: Boolean) {
        val preProgressKey = container.scheduler.progressKey()
        val preOwner = container.scheduler.progressOwner()
        container.playNext(rmProgress)
        if (rmProgress && preProgressKey != null) {
            WatchProgressStore.clear(preOwner ?: "", preProgressKey, discard = true)
        }
    }

    override fun playPre() {
        container.playPrevious()
    }

    override fun changeParse(pb: ParseBean) {
        container.scheduler.resetAutoRetryState()
        container.scheduler.clearTriedLines()
        container.scheduler.doParse(pb)
    }

    override fun updatePlayerCfg() {
        val persistCfg = container.scheduler.playerCfgForPersist() ?: return
        container.scheduler.vod()?.playerCfg = persistCfg.toString()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, persistCfg))
    }

    override fun replay(replay: Boolean) {
        container.reviveEngineIfReleased()
        container.scheduler.resetAutoRetryState()
        container.scheduler.clearTriedLines()
        container.scheduler.setPlaybackStarted(false)
        if (replay) {
            container.playViaScheduler(true)
        } else {
            container.replayCurrentAddress()
        }
    }

    override fun errReplay() {
        container.scheduler.rememberErrorHttpStatus()
        container.errorWithRetry(container.context.getString(R.string.player_error_play), false, true)
    }

    override fun closeSubtitles() {
        container.closeSubtitles()
    }

    override fun selectSubtitle() {
        try {
            container.selectMySubtitle()
        } catch (e: Exception) {
            LOG.e("PlayContainer", e)
        }
    }

    override fun selectAudioTrack() {
        container.selectMyAudioTrack()
    }

    override fun selectVideoTrack() {
        container.selectMyVideoTrack()
    }

    override fun prepared() {
        container.initSubtitleView()
        container.mVideoView?.prepared()
        container.startDanmuIfReady()
    }

    override fun startPlayUrl(url: String, headers: HashMap<String, String>?) {
        if (!TextUtils.isEmpty(container.scheduler.m3u8SourceUrl()) &&
            !container.scheduler.isM3u8ProxyUrl(url)
        ) {
            container.scheduler.clearM3u8ProxyUrl()
        }
        container.scheduler.goPlayUrl(url, headers)
    }

    override fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String) {
        container.scheduler.setM3u8Urls(proxyUrl, sourceUrl)
    }

    override fun clickCast() {
        container.showCastDialog()
    }

    override fun setAllowSwitchPlayer(isAllow: Boolean) {
        container.scheduler.setAllowSwitchPlayer(isAllow)
    }

    override fun setAllowDecodeFallback(isAllow: Boolean) {
        container.scheduler.setAllowDecodeFallback(isAllow)
    }
}
