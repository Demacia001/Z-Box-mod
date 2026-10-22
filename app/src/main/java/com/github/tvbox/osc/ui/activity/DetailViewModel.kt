package com.github.tvbox.osc.ui.activity

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.EpisodeTotals
import com.github.tvbox.osc.data.HistoryWriter
import com.github.tvbox.osc.data.VodFollow
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.net.SearchHelper
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.thunder.Thunder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DetailViewModel : ViewModel() {

    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    sealed interface PageState {
        data object Loading : PageState
        data class Empty(val msg: String? = null) : PageState
        data object Ready : PageState
    }

    data class SourceChip(val key: String, val name: String)

    val pageState = MutableStateFlow<PageState>(PageState.Loading)
    val revision = MutableStateFlow(0)
    val fullScreen = MutableStateFlow(false)
    val rotating = MutableStateFlow(false)
    val enteringFullscreen = MutableStateFlow(false)
    val exitingFullscreen = MutableStateFlow(false)
    val playSignal = MutableStateFlow(0)
    val collected = MutableStateFlow(false)
    val follow = MutableStateFlow<VodFollow?>(null)
    val qualityOptions = MutableStateFlow<List<String>>(emptyList())
    val qualitySelected = MutableStateFlow(0)
    val sourceChips = MutableStateFlow<List<SourceChip>>(emptyList())
    val sourcesSearching = MutableStateFlow(false)
    val relatedVideos = MutableStateFlow<List<Movie.Video>>(emptyList())
    val episodeSheet = MutableStateFlow(false)
    val toastEvent = MutableStateFlow<String?>(null)
    val finishEvent = MutableStateFlow(false)

    private val playbackCommandChannel = Channel<PlaybackCommand>(Channel.BUFFERED)
    val playbackCommands: Flow<PlaybackCommand> = playbackCommandChannel.receiveAsFlow()

    var vodInfo: VodInfo? = null; private set
    var previewVodInfo: VodInfo? = null; private set
    var vodId = ""; private set
    var sourceKey = ""; private set
    var firstsourceKey = ""; private set

    private var manualLineSwitchPending = false

    private var manualLineSwitchTargetIndex = -1

    private var vodName = ""
    private var vodPicture = ""
    private var fromCollect = false

    val pendingPicture: String get() = vodPicture

    private val sourceViewModel = SourceViewModel()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val navStack = DetailNavStack()
    private var searchJob: Job? = null
    private var detailBuildToken = 0

    private var detailRequestToken = 0

    private var detailRetryTarget: String? = null

    private val fallbackCandidates = ArrayList<Movie.Video>()
    private val candidateKeys = HashSet<String>()
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()
    private val semaphore = Semaphore(SOURCE_SEARCH_CONCURRENCY)
    private val pendingSearchDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val searchCaller = SourceViewModel()
    private var fallbackKeepCurrentDetail = false
    private var fallbackLoadingCandidate = false
    private var fallbackActive = false
    private var fallbackAutoSwitch = false
    private var fallbackEpisode: VodInfo.VodSeries? = null
    private var fallbackEpisodeIndex = -1
    private var detailTimeoutScheduled = false

    private class SwitchSnapshot(
        val vodInfo: VodInfo,
        val vodId: String,
        val sourceKey: String,
        val firstsourceKey: String,
        val vodName: String,
        val vodPicture: String,
        val wasPlaying: Boolean,
    )

    private var switchSnapshot: SwitchSnapshot? = null

    private var searchToken = 0
    private var searchTitle = ""

    init {
        EventBus.getDefault().register(this)
        viewModelScope.launch {
            sourceViewModel.detailResult.flow.collect { data ->
                if (DetailResponseGuard.isCurrent(detailRequestToken, data?.detailToken)) onDetailResult(data)
            }
        }
    }

    fun initFromIntent(intent: Intent?) {
        if (vodId.isNotEmpty()) return
        val target = parseTarget(intent) ?: return
        navStack.push(target)
        applyTarget(target)
    }

    fun pushTargetFromIntent(intent: Intent?) {
        val target = parseTarget(intent) ?: return
        if (!navStack.push(target)) return
        applyTarget(target)
    }

    fun backToPreviousTarget(): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        return true
    }

    private fun parseTarget(intent: Intent?): DetailNavStack.Target? {
        val bundle = intent?.extras ?: return null
        return DetailNavStack.Target(
            vodId = bundle.getString("id", "").orEmpty(),
            sourceKey = bundle.getString("sourceKey", "").orEmpty(),
            title = bundle.getString("title", "").orEmpty(),
            picture = bundle.getString("picture", "").orEmpty(),
            fromCollect = bundle.getBoolean("collect", false),
        )
    }

    private fun applyTarget(target: DetailNavStack.Target) {
        cancelInFlightContent()
        resetContentState()
        sendCommand(PlaybackCommand.StopForContentSwitch)
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
        fromCollect = target.fromCollect
        vodName = target.title
        vodPicture = target.picture
        loadDetail(target.vodId, target.sourceKey)
        LOG.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        if (vodName.isNotEmpty()) startSourceSearch()
    }

    private fun cancelInFlightContent() {
        searchJob?.cancel()
        searchJob = null
        pendingSearchDone.values.forEach { it.complete(Unit) }
        pendingSearchDone.clear()
        cancelDetailTimeout()
        searchToken = SEARCH_SEQ.incrementAndGet()
    }

    private fun resetContentState() {
        vodInfo = null
        previewVodInfo = null
        switchSnapshot = null
        firstsourceKey = ""
        searchTitle = ""
        manualLineSwitchPending = false
        collected.value = false
        follow.value = null
        relatedVideos.value = emptyList()
        sourcesSearching.value = false
        qualityOptions.value = emptyList()
        qualitySelected.value = 0
        episodeSheet.value = false
        toastEvent.value = null
        finishEvent.value = false
        pageState.value = PageState.Loading
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        usedSourceKeys.clear()
        detailRetryTarget = null
        resetEngineState(keepChips = false)
    }

    fun onFullScreenToggleRequested(requested: Boolean, facts: DetailPlaybackFacts) {
        if (requested) {
            val reason = DetailFullScreenGate.refusalReason(
                pageState.value,
                loadingText = { str(R.string.detail_content_not_ready) },
                emptyText = { str(R.string.detail_empty_source) },
            )
            if (reason != null) {
                toastEvent.value = reason
                return
            }
            enterFullscreen()
            return
        }
        val next = DetailFullScreenSlide.exit(slideState()) ?: return
        applySlideState(next)
        Thunder.cancelPendingParse()
        rotating.value = DetailPlaybackCommands.exitFullScreenState(facts).second
    }

    fun bumpRevision() {
        revision.value += 1
    }

    internal fun onPlayRequested(entry: DetailPlaybackEntry) {
        val list = vodInfo?.seriesMap?.get(vodInfo?.playFlag)
        if (list.isNullOrEmpty()) {
            toastEvent.value = str(R.string.detail_no_playable_content)
            return
        }
        applyPlaybackEntry(entry)
    }

    internal fun applyPlaybackEntry(entry: DetailPlaybackEntry, resumable: Boolean = false) {
        val plan = DetailPlaybackPolicy.plan(entry, resumable)
        if (plan.enterFullScreen) enterFullscreen()
        if (plan.startPlayback) playSignal.value += 1
    }

    private fun enterFullscreen() {
        val next = DetailFullScreenSlide.enter(slideState()) ?: return
        applySlideState(next)
    }

    fun onEntrySlideFinished() {
        val next = DetailFullScreenSlide.entryFinished(slideState()) ?: return
        applySlideState(next)
    }

    fun onExitSlideFinished() {
        applySlideState(DetailFullScreenSlide.exitFinished(slideState()))
    }

    fun cancelEntrySlide() {
        val next = DetailFullScreenSlide.cancelEntry(slideState()) ?: return
        applySlideState(next)
    }

    private fun slideState() = DetailFullScreenSlideState(
        fullScreen = fullScreen.value,
        entering = enteringFullscreen.value,
        exiting = exitingFullscreen.value,
    )

    private fun applySlideState(state: DetailFullScreenSlideState) {
        fullScreen.value = state.fullScreen
        enteringFullscreen.value = state.entering
        exitingFullscreen.value = state.exiting
    }

    private fun consumeManualLineSwitch(): Boolean {
        val pending = manualLineSwitchPending
        manualLineSwitchPending = false
        return pending
    }

    fun showEpisodeSheet() {
        episodeSheet.value = true
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(true))
    }

    fun dismissEpisodeSheet() {
        episodeSheet.value = false
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
    }

    fun clearToast() {
        toastEvent.value = null
    }

    fun consumeFinish() {
        finishEvent.value = false
    }

    private fun retryDetailOnce(reason: String): Boolean {
        val key = sourceKey
        val id = vodId
        if (id.isEmpty()) return false
        val target = key + "|" + id
        if (detailRetryTarget == target) return false
        detailRetryTarget = target
        LOG.i("echo-detail-retry key=$key id=$id reason=$reason")
        mainHandler.postDelayed({
            if (sourceKey == key && vodId == id) loadDetail(id, key)
        }, DETAIL_RETRY_DELAY_MS)
        return true
    }

    private fun loadDetail(vid: String, key: String) {
        val requestToken = nextDetailRequestToken()
        vodId = vid.orEmpty()
        sourceKey = key.orEmpty()
        firstsourceKey = sourceKey
        usedSourceKeys.add(firstsourceKey)
        refreshCollectState(sourceKey, vodId)
        if (DetailResponseGuard.isUnloadableTarget(vodId, ApiConfig.get().getSource(sourceKey) == null)) {
            onDetailUnavailable()
            return
        }
        pageState.value = PageState.Loading
        sourceViewModel.getDetail(sourceKey, vodId, false, requestToken)
    }

    private fun nextDetailRequestToken(): Int = ++detailRequestToken

    private fun refreshCollectState(key: String, vid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val isCollected = try {
                AppGraph.collectRepository.isVodCollect(key, vid)
            } catch (th: Throwable) {
                LOG.e("DetailViewModel", "collect state read failed: " + th, th)
                return@launch
            }
            val followRecord = try {
                AppGraph.followRepository.find(key, vid)
            } catch (th: Throwable) {
                LOG.e("DetailViewModel", "follow state read failed: " + th, th)
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (sourceKey != key || vodId != vid) return@withContext
                collected.value = isCollected
                follow.value = followRecord
            }
        }
    }

    fun retry() {
        if (vodId.isEmpty()) return
        detailRetryTarget = null
        loadDetail(vodId, sourceKey)
        if (searchTitle.isNotEmpty() && !sourcesSearching.value) startSourceSearch()
    }

    private fun onDetailUnavailable() {
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
            return
        }
        if (vodName.isNotEmpty() && !sourcesSearching.value) startSourceSearch()
        val staleSource = ApiConfig.get().getSource(sourceKey) == null
        if (!(staleSource && startFallbackIfNeeded(auto = true))) {
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    fun onDetailResult(absXml: AbsXml?) {
        if (fallbackActive && !fallbackLoadingCandidate) return
        if (absXml != null && !absXml.sourceKey.isNullOrEmpty() && absXml.sourceKey != sourceKey) return
        val videoList = absXml?.movie?.videoList
        val detailToken = ++detailBuildToken
        if (videoList != null && videoList.isNotEmpty()) {
            val wasFallback = fallbackLoadingCandidate
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
            }
            if (wasFallback) {
                val fallbackSource = ApiConfig.get().getSource(sourceKey)
                toastEvent.value = str(R.string.detail_switch_site, fallbackSource?.name ?: sourceKey)
            }
            if (isSourceErrorMsg(absXml.msg)) {
                if (!rollbackManualSwitch(absXml.msg)) {
                    toastEvent.value = absXml.msg
                    enterEmpty(absXml.msg)
                }
                return
            }
            val mVideo = videoList[0]
            mVideo.id = vodId
            if (mVideo.name.isNullOrEmpty()) mVideo.name = vodName
            if (mVideo.name.isNullOrEmpty()) mVideo.name = "TVBox"
            if (mVideo.pic.isNullOrEmpty() && vodPicture.isNotEmpty()) {
                mVideo.pic = vodPicture
            }
            val keep = vodInfo?.takeIf { it.sourceKey == mVideo.sourceKey }
            val keepFlag = keep?.playFlag
            val keepIndex = keep?.playIndex
            val info = VodInfo()
            info.setVideo(mVideo)
            info.sourceKey = mVideo.sourceKey
            sourceKey = mVideo.sourceKey ?: sourceKey

            val recordKey = sourceKey
            val recordId = vodId
            viewModelScope.launch {
                val record = withContext(Dispatchers.IO) {
                    if (HistoryHelper.isIncognito()) null else AppGraph.historyRepository.getVodInfo(recordKey, recordId)
                }
                if (detailToken != detailBuildToken || sourceKey != recordKey || vodId != recordId) return@launch
                if (record != null) {
                    info.playIndex = maxOf(record.playIndex, 0)
                    info.playFlag = record.playFlag
                    info.playerCfg = record.playerCfg
                    info.reverseSort = record.reverseSort
                } else {
                    info.playIndex = 0
                    info.playFlag = null
                    info.playerCfg = ""
                    info.reverseSort = false
                }
                if (info.reverseSort) info.reverse()
                if (info.playFlag == null || info.seriesMap?.containsKey(info.playFlag) != true) {
                    info.playFlag = info.seriesMap?.keys?.firstOrNull()
                }
                if (keepFlag != null && info.seriesMap?.containsKey(keepFlag) == true) {
                    info.playFlag = keepFlag
                    info.playIndex = (keepIndex ?: 0).coerceIn(0, (info.seriesMap?.get(keepFlag)?.size ?: 1) - 1)
                }
                restoreFallbackEpisode(info)
                resetEngineState(keepChips = true)
                val playingList = info.seriesMap?.get(info.playFlag)
                if (!playingList.isNullOrEmpty()) {
                    info.playIndex = info.playIndex.coerceIn(0, playingList.size - 1)
                    for (flag in info.seriesFlags.orEmpty()) {
                        flag.selected = flag.name == info.playFlag
                    }
                }
                vodInfo = info
                if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                    searchTitle = info.name.orEmpty().trim()
                    startSourceSearch()
                }
                vodName = mVideo.name ?: vodName
                if (!playingList.isNullOrEmpty()) switchSnapshot = null
                pageState.value = PageState.Ready
                bumpRevision()
                if (playingList.isNullOrEmpty()) {
                    if (retryDetailOnce("no-line")) return@launch
                    LOG.i("echo-detail-no-line key=$sourceKey id=$vodId name=${info.name}")
                    toastEvent.value = str(R.string.detail_no_playable_content)
                }
            }
        } else {
            if (fallbackLoadingCandidate) {
                if (retryDetailOnce("empty-candidate")) return
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
                loadNextFallbackCandidate()
                return
            }
            handleEmptyDetail(absXml)
        }
    }

    private fun handleEmptyDetail(data: AbsXml?) {
        val msg = data?.msg.orEmpty()
        if (isSourceErrorMsg(msg)) {
            if (rollbackManualSwitch(msg)) return
            if (fallbackToPreviousTarget(msg)) return
            LOG.i("echo-detail-finish reason=source-msg msg=$msg key=$sourceKey id=$vodId")
            resetEngineState(keepChips = false)
            toastEvent.value = msg
            finishEvent.value = true
            return
        }
        if (retryDetailOnce("empty-detail")) return
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
        } else {
            LOG.i("echo-detail-empty key=$sourceKey id=$vodId name=$vodName")
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    private fun fallbackToPreviousTarget(reason: String?): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        if (!reason.isNullOrEmpty()) toastEvent.value = reason
        return true
    }

    private fun startSourceSearch() {
        val title = searchTitle.ifEmpty { vodName.trim() }
        if (title.isEmpty()) return
        if (sourcesSearching.value && searchTitle == title) return
        searchTitle = title
        searchToken = SEARCH_SEQ.incrementAndGet()
        val myToken = searchToken
        val tokenStr = "detail_$myToken"
        val checked = SearchHelper.getSourcesForSearch()
        val home = ApiConfig.get().getHomeSourceBean()
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && it.isQuickSearch() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        sourcesSearching.value = sources.isNotEmpty()
        relatedVideos.value = emptyList()
        if (sources.isEmpty()) return
        searchJob = viewModelScope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            val done = CompletableDeferred<Unit>()
                            pendingSearchDone.put(bean.key.orEmpty(), done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                    searchCaller.getSearch(bean.key, title, tokenStr)
                                    done.await()
                                }
                            } finally {
                                pendingSearchDone.remove(bean.key)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (tokenStr == currentTokenStr()) {
                sourcesSearching.value = false
                if (fallbackActive && fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSearchResultEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            val data = event.obj as? AbsXml ?: return
            if (data.searchToken != currentTokenStr()) return
            pendingSearchDone.remove(data.sourceKey)?.complete(Unit)
            val videos = data.movie?.videoList.orEmpty()
            val fresh = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() == searchTitle
                        && !usedSourceKeys.contains(it.sourceKey)
                        && it.sourceKey != sourceKey
            }.filter { candidateKeys.add(candidateKey(it)) }
            if (fresh.isNotEmpty()) {
                synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
                publishSourceChips()
                if (fallbackActive && fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
            }
            val related = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() != searchTitle
                        && !(it.sourceKey == sourceKey && it.id == vodId)
            }
            if (related.isNotEmpty()) {
                val seen = relatedVideos.value.mapTo(HashSet()) { candidateKey(it) }
                val deduped = related.filter { seen.add(candidateKey(it)) }
                if (deduped.isNotEmpty()) relatedVideos.value = relatedVideos.value + deduped
            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(event.obj as? org.json.JSONObject)
        }
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

    private fun publishSourceChips() {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
        sourceChips.value = candidates
            .filter { !usedSourceKeys.contains(it.sourceKey) && it.sourceKey != sourceKey }
            .map { video ->
                val key = video.sourceKey.orEmpty()
                SourceChip(key, ApiConfig.get().getSource(key)?.name ?: key)
            }
            .distinctBy { it.key }
    }

    fun candidateForKey(key: String): Movie.Video? =
        synchronized(fallbackCandidates) { fallbackCandidates.firstOrNull { it.sourceKey == key } }

    fun switchSource(video: Movie.Video) {
        stopPlaybackForSwitch()
        captureFallbackEpisode()
        detailRetryTarget = null
        usedSourceKeys.add(video.sourceKey.orEmpty())
        vodName = video.name ?: vodName
        vodPicture = video.pic ?: vodPicture
        resetEngineState(keepChips = true)
        loadDetail(video.id.orEmpty(), video.sourceKey.orEmpty())
    }

    private fun stopPlaybackForSwitch() {
        val info = vodInfo ?: return
        if (switchSnapshot == null) {
            switchSnapshot = SwitchSnapshot(
                info,
                vodId,
                sourceKey,
                firstsourceKey,
                vodName,
                vodPicture,
                wasPlaying = fullScreen.value,
            )
        }
        sendCommand(PlaybackCommand.StopForSourceSwitch(str(R.string.detail_switching_source)))
    }

    private fun rollbackManualSwitch(reason: String? = null): Boolean {
        val snapshot = switchSnapshot ?: return false
        if (snapshot.vodInfo.seriesMap?.get(snapshot.vodInfo.playFlag).isNullOrEmpty()) return false
        switchSnapshot = null
        nextDetailRequestToken()
        vodInfo = snapshot.vodInfo
        vodId = snapshot.vodId
        sourceKey = snapshot.sourceKey
        firstsourceKey = snapshot.firstsourceKey
        vodName = snapshot.vodName
        vodPicture = snapshot.vodPicture
        resetEngineState(keepChips = true)
        toastEvent.value =
            if (reason.isNullOrEmpty()) {
                str(R.string.detail_switch_failed)
            } else {
                str(R.string.detail_switch_failed_reason, reason)
            }
        pageState.value = PageState.Ready
        bumpRevision()
        applyPlaybackEntry(DetailPlaybackEntry.SwitchRollback, resumable = snapshot.wasPlaying)
        return true
    }

    private fun enterEmpty(msg: String? = null) {
        sendCommand(PlaybackCommand.ClearSourceSwitchTip)
        LOG.i("echo-detail-empty-state msg=$msg key=$sourceKey id=$vodId")
        pageState.value = PageState.Empty(msg)
    }

    fun startFallbackAfterLinesExhausted(): Boolean = startFallbackIfNeeded(auto = true, fromLinesExhausted = true)

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        val currentSource = ApiConfig.get().getSource(sourceKey)
        if (currentSource != null && !currentSource.isChangeable()) return false
        if (fallbackActive) return true
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return false
        fallbackKeepCurrentDetail = fromLinesExhausted && vodInfo != null && !vodInfo?.seriesMap.isNullOrEmpty()
        captureFallbackEpisode()
        searchTitle = title
        usedSourceKeys.add(sourceKey)
        fallbackActive = true
        fallbackAutoSwitch = auto
        triedKeys.add(candidateKey(sourceKey, vodId))
        loadNextFallbackCandidate()
        return fallbackActive
    }

    private fun loadNextFallbackCandidate() {
        while (true) {
            val video = synchronized(fallbackCandidates) {
                val index = fallbackCandidates.indexOfFirst { isAutoSwitchable(it.sourceKey.orEmpty()) }
                if (index < 0) null else fallbackCandidates.removeAt(index)
            } ?: break
            val cKey = candidateKey(video)
            if (usedSourceKeys.contains(video.sourceKey) || !triedKeys.add(cKey)) continue
            fallbackLoadingCandidate = true
            fallbackActive = true
            fallbackAutoSwitch = true
            usedSourceKeys.add(video.sourceKey.orEmpty())
            vodName = video.name ?: vodName
            vodPicture = video.pic ?: vodPicture
            publishSourceChips()
            scheduleDetailTimeout()
            loadDetailInternal(video.id.orEmpty(), video.sourceKey.orEmpty(), detailRequestToken)
            return
        }
        publishSourceChips()
        if (!sourcesSearching.value) finishFallbackWithoutResult()
    }

    private fun isAutoSwitchable(key: String): Boolean {
        if (key.isEmpty()) return false
        val source = ApiConfig.get().getSource(key) ?: return false
        return source.isChangeable()
    }

    private fun loadDetailInternal(vid: String, key: String, requestToken: Int) {
        vodId = vid
        sourceKey = key
        firstsourceKey = key
        refreshCollectState(sourceKey, vodId)
        sourceViewModel.getDetail(sourceKey, vodId, true, requestToken)
    }

    private fun finishFallbackWithoutResult() {
        val keep = fallbackKeepCurrentDetail
        resetEngineState(keepChips = true)
        if (!keep && rollbackManualSwitch()) return
        if (!keep && pageState.value != PageState.Ready) {
            enterEmpty()
        }
    }

    private fun scheduleDetailTimeout() {
        if (detailTimeoutScheduled) return
        detailTimeoutScheduled = true
        mainHandler.postDelayed({
            detailTimeoutScheduled = false
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                sourceViewModel.cancelDetail()
                loadNextFallbackCandidate()
            }
        }, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
    }

    private fun cancelDetailTimeout() {
        detailTimeoutScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun captureFallbackEpisode() {
        val info = vodInfo
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        if (info?.seriesMap == null || info.playFlag.isNullOrEmpty()) return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.isEmpty()) return
        fallbackEpisodeIndex = info.playIndex.coerceIn(0, list.size - 1)
        fallbackEpisode = list[fallbackEpisodeIndex]
    }

    private fun restoreFallbackEpisode(info: VodInfo) {
        val episode = fallbackEpisode
        if (episode == null || fallbackEpisodeIndex < 0 || info.seriesMap == null) return
        val preferredFlag = info.playFlag
        val preferredList = info.seriesMap?.get(preferredFlag)
        var matched = findMatchingEpisodeIndex(episode, preferredList)
        if (matched >= 0) {
            info.playIndex = matched
            return
        }
        for (flag in info.seriesFlags.orEmpty()) {
            if (flag.name.isNullOrEmpty() || flag.name == preferredFlag) continue
            matched = findMatchingEpisodeIndex(episode, info.seriesMap?.get(flag.name))
            if (matched >= 0) {
                info.playFlag = flag.name
                info.playIndex = matched
                return
            }
        }
        if (preferredList != null && preferredList.isNotEmpty()) {
            info.playIndex = fallbackEpisodeIndex.coerceIn(0, preferredList.size - 1)
        }
    }

    private fun resetEngineState(keepChips: Boolean) {
        fallbackActive = false
        fallbackAutoSwitch = false
        fallbackKeepCurrentDetail = false
        fallbackLoadingCandidate = false
        detailTimeoutScheduled = false
        cancelDetailTimeout()
        triedKeys.clear()
        if (!keepChips) {
            synchronized(fallbackCandidates) { fallbackCandidates.clear() }
            candidateKeys.clear()
        }
        publishSourceChips()
    }

    fun destroyEngine() {
        cancelDetailTimeout()
        sourceViewModel.cancelDetail()
        searchCaller.cancelSearch()
        Thunder.cancelPendingParse()
    }

    private fun candidateKey(video: Movie.Video): String =
        (video.sourceKey ?: "") + "|" + (video.id ?: "")

    private fun candidateKey(key: String, id: String): String = "$key|$id"

    fun onEpisodeClick(position: Int) {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (position < 0 || position >= list.size) return
        if (position != manualLineSwitchTargetIndex) manualLineSwitchPending = false
        info.playIndex = position
        list.forEachIndexed { index, series -> series.selected = index == position }
        bumpRevision()
        onPlayRequested(DetailPlaybackEntry.Episode)
    }

    fun onFlagClick(flagName: String) {
        val info = vodInfo ?: return
        if (info.playFlag == flagName) return
        val oldList = info.seriesMap?.get(info.playFlag)
        val currentIndex = info.playIndex.coerceAtLeast(0)
        val currentSeries = oldList?.getOrNull(currentIndex)
        info.playFlag = flagName
        val newList = info.seriesMap?.get(flagName)
        if (newList != null && newList.isNotEmpty()) {
            info.playIndex = findSameEpisodeIndex(currentSeries, newList, currentIndex)
            newList.forEachIndexed { index, series -> series.selected = index == info.playIndex }
        }
        info.seriesFlags.orEmpty().forEach { it.selected = it.name == flagName }
        manualLineSwitchPending = true
        manualLineSwitchTargetIndex = info.playIndex
        qualityOptions.value = emptyList()
        qualitySelected.value = 0
        bumpRevision()
    }

    fun toggleReverse() {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.size <= 1) return
        info.reverseSort = !info.reverseSort
        info.reverse()
        info.playIndex = (list.size - 1) - info.playIndex
        bumpRevision()
    }

    fun toggleCollect() {
        val info = vodInfo ?: return
        val key = sourceKey
        if (collected.value) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.deleteVodCollect(key, info)
                } catch (th: Throwable) {
                    LOG.e("DetailViewModel", "collect delete failed: " + th, th)
                }
            }
            toastEvent.value = str(R.string.toast_removed_from_collect)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.insertVodCollect(key, info)
                } catch (th: Throwable) {
                    LOG.e("DetailViewModel", "collect insert failed: " + th, th)
                }
            }
            toastEvent.value = str(R.string.toast_added_to_collect)
        }
        collected.value = !collected.value
    }

    fun saveFollow(days: Set<Int>, hour: Int) {
        val info = vodInfo ?: return
        val key = sourceKey
        val vid = vodId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppGraph.followRepository.upsert(key, info, days, hour)
                val record = AppGraph.followRepository.find(key, vid)
                withContext(Dispatchers.Main) { follow.value = record }
            } catch (th: Throwable) {
                LOG.e("DetailViewModel", "follow save failed: " + th, th)
            }
        }
        toastEvent.value = str(R.string.toast_follow_saved)
    }

    private fun updateQualityOptions(result: org.json.JSONObject?) {
        val options = ArrayList<String>()
        try {
            val value = result?.opt("url")
            val urls = when (value) {
                is org.json.JSONArray -> value
                is String -> org.json.JSONArray(value)
                else -> null
            }
            if (urls != null) {
                var i = 0
                while (i + 1 < urls.length()) {
                    options.add(urls.optString(i))
                    i += 2
                }
            }
        } catch (ignored: Throwable) {
            LOG.d("DetailViewModel", "quality url list parse failed, keep empty options")
        }
        if (options == qualityOptions.value) return
        qualityOptions.value = options
        qualitySelected.value = 0
    }

    fun switchQuality(position: Int) {
        sendCommand(PlaybackCommand.SelectQuality(position))
    }

    fun onQualitySelectionAccepted(position: Int) {
        qualitySelected.value = position
    }

    private fun sendCommand(command: PlaybackCommand) {
        playbackCommandChannel.trySend(command)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_PLAYBACK_STARTED) {
            onPlaybackStarted()
            return
        }
        if (event.type != RefreshEvent.TYPE_REFRESH) return
        val info = vodInfo ?: return
        when (val obj = event.obj) {
            is VodInfo -> syncPlayingVodInfo(obj)
            is Int -> {
                val list = info.seriesMap?.get(info.playFlag) ?: return
                list.forEachIndexed { index, series -> series.selected = index == obj }
                info.playIndex = obj
                insertVod()
                bumpRevision()
            }
            is org.json.JSONObject -> {
                info.playerCfg = obj.toString()
                insertVod()
                bumpRevision()
            }
        }
    }

    private fun onPlaybackStarted() {
        val info = vodInfo ?: return
        val playing = App.getInstance()?.getVodInfo() ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        if (playing.playFlag != info.playFlag || playing.playIndex != info.playIndex) return
        val controller = PlaybackService.peek()?.controller() ?: return
        if (!controller.isSameStartedContent()) return
        LOG.i("echo-history insert on playback-started key=" + sourceKey + "|" + vodId)
        insertVod()
    }

    private fun syncPlayingVodInfo(playing: VodInfo) {
        val info = vodInfo ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        val newFlag = playing.playFlag
        if (newFlag.isNullOrEmpty() || info.seriesMap?.containsKey(newFlag) != true) return
        val newList = info.seriesMap?.get(newFlag) ?: return
        if (newList.isEmpty()) return
        val playingList = playing.seriesMap?.get(newFlag)
        val playingSeries = playingList?.getOrNull(playing.playIndex.coerceIn(0, playingList.size - 1))
        val newIndex = findSameEpisodeIndex(playingSeries, newList, playing.playIndex)
        info.playFlag = newFlag
        info.playIndex = newIndex
        if (playing.playerCfg != null) info.playerCfg = playing.playerCfg
        info.seriesFlags.orEmpty().forEach { it.selected = it.name == newFlag }
        info.seriesMap?.values?.forEach { list -> list.forEach { it.selected = false } }
        newList[newIndex].selected = true
        refreshPlayNote(info)
        bumpRevision()
        LOG.i("echo-detail sync -> $newFlag/$newIndex")
    }

    private fun insertVod() {
        val info = vodInfo ?: return
        refreshPlayNote(info)
        EpisodeTotals.putFromVod(info)
        HistoryWriter.write(firstsourceKey, info)
    }

    private fun refreshPlayNote(info: VodInfo) {
        try {
            info.playNote = info.seriesMap?.get(info.playFlag)?.get(info.playIndex)?.name ?: ""
        } catch (_: Throwable) {
            info.playNote = ""
        }
    }

    fun preparePlaySession(): PlaybackSession? {
        val info = vodInfo ?: return null
        val list = info.seriesMap?.get(info.playFlag) ?: return null
        if (list.isEmpty()) return null
        refreshPlayNote(info)
        val preview = previewVodInfo ?: VodInfo()
        preview.id = info.id
        preview.name = info.name
        preview.pic = info.pic
        preview.sourceKey = info.sourceKey
        preview.playNote = info.playNote
        preview.seriesFlags = info.seriesFlags
        preview.seriesMap = info.seriesMap
        preview.playerCfg = info.playerCfg
        preview.playFlag = info.playFlag
        preview.playIndex = info.playIndex
        previewVodInfo = preview
        App.getInstance()?.setVodInfo(preview)
        return PlaybackSession(preview, sourceKey, consumeManualLineSwitch())
    }

    private fun findSameEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>, fallback: Int): Int {
        if (target.isEmpty()) return 0
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) {
            return fallback.coerceIn(0, target.size - 1)
        }
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return if (matched >= 0) matched else fallback.coerceIn(0, target.size - 1)
    }

    private fun findMatchingEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>?): Int {
        if (target.isNullOrEmpty()) return -1
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) return -1
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return matched
    }

    private fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) return 0
        if (targetName.equals(currentName, ignoreCase = true)) return 100
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) return 80
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) return 70
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) return 60
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (name.isNullOrEmpty()) return -1
        return try {
            var text = name.replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.lowercase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
            // i18n: keep —— 从源侧片名/集名里抽集数,关键词是数据规则
        val matcher = Regex("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})").find(text)
            if (matcher != null) {
                matcher.groupValues[1].toInt()
            } else {
                val number = text.replace(Regex("\\D+"), "")
                if (number.isNotEmpty()) number.toInt() else -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
        destroyEngine()
        super.onCleared()
    }

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L
        private const val DETAIL_RETRY_DELAY_MS = 700L
        private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
        private const val SOURCE_SEARCH_CONCURRENCY = 6

        // i18n: keep —— 源侧"没有数据"的哨兵值;误翻会把空结果判成源报错,详情页提示后自动关闭
        private const val SOURCE_EMPTY_MSG = "数据列表"

        internal fun isSourceErrorMsg(msg: String?): Boolean =
            !msg.isNullOrEmpty() && msg != SOURCE_EMPTY_MSG
    }
}
