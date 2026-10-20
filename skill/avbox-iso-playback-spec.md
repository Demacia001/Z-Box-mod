# AVBox ISO 播放 Spec:蓝光原盘 ISO / 裸 m2ts 在 media3 上的落地(as-built)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)。
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-playback-service-spec.md`(播放器归属/挂摘,本文只讲**取流与抽取链路**)。
> 状态:**as-built 活规范(2026-10-20 重写)**。链路已落地并真机验证:UDF → mpls/m2ts → 192→188 → `TsExtractor` + 自定义 reader;多段盘拼接、RAP 起播、4K 单层/双层盘、ISO9660+UDF 混合盘、裸 192B BDAV 都跑通。**唯一硬边界是 4K Main10 的模拟器解码能力**,不是选片或解析。
> ⚠️ **改动 `engine/iso/**`、`MediaSources` 的 ISO 分支、`PlayerEngine` 的 isoSession 语义、或任何 `TrackOutput`/`ExtractorOutput` 包装层前必读**;`§4` 是不变量(违反即回归),`§5` 是已知坑,`§7` 是走查清单(**编号 1–12 与节号 §7 保持稳定**,外部文档按「§7 清单」引用)。
> 过程与取证**不写在这里**:案例、缺陷解剖、判负实验、性能实测全在 `history/iso-playback-chronicle.md`(下文引用写作「档案 §14.13」)。

## 0. 摘要与范围

**一句话**:不开 native、不 fork media3 —— 在 `DataSource` 层把 ISO 里的 `BDMV/STREAM/*.m2ts` 当作一个可随机读的流(192 字节 ATS 剥成 188),再交给 media3 的 `TsExtractor`,只替换「BD 私有 `stream_type` 的 reader」与「主视频 PID 的选择」两处。

**链路**:

```
MediaSources.createIsoMediaSource(uri, headers, seekMs)
  └── IsoDataSource(HTTP Range + UDF 只读)
        └── M2tsDataSource(逻辑 188 ↔ 物理 192,支持多 run)
              └── ProgressiveMediaSource(IsoExtractorsFactory)
                    └── PrimaryVideoExtractor(主视频 PID 过滤 + 延迟声明门)
                          └── TsExtractor(MODE_MULTI_PMT, extractorFlags=0)
                                └── BdTsPayloadReaderFactory(LPCM / TrueHD core / AC-3 / E-AC3 / DTS-HD / PGS)
                                      └── 自定义 reader:LpcmReader / Ac3CoreReader / PgsReader
```

**不变项(动它们等于动架构)**:

- 只在 **DataSource / Extractor 层**扩展,**不 fork media3、不加 native 解码器、不改内核**;
- ISO 会话由 `PlayerEngine.isoSession` 标记,起播窗口 45s、seek 走**重建 MediaSource**(不是解码器复用);
- 轨道语言来自 **mpls 的 STN 表**,不从 PMT 读;主视频轨来自 STN 的 `videoCount` 区段;
- 选片是**启发式**(去重时长最长),没有 BD-J 菜单解析、没有用户选播放列表;
- 4K Main10 解不动是**设备边界**,按失败如实上报,不做"软解兜底"。

**非目标**:杜比视界完整效果(RPU 不解,按 HDR10 播)、TrueHD 无损(只取 AC-3 core)、BD-J 菜单、虚拟分区/可擦写盘 UDF、外挂字幕。

## 1. 技术基线与边界

| 项 | 值 |
|---|---|
| media3 | **1.11.1**(`TsExtractor` 6 参构造;`DefaultSubtitleParserFactory` 官方字幕转码) |
| App | `com.github.avbox.osc`,versionName 1.2.8,minSdk 24 / targetSdk 37,APK 只带 arm64-v8a |
| 抽取模式 | `TsExtractor.MODE_MULTI_PMT` + `extractorFlags = 0` + `TimestampAdjuster(0)` + `DEFAULT_TIMESTAMP_SEARCH_BYTES × 10` |
| 起播窗口 | ISO 会话 `ISO_SWITCH_LINE_PLAY_TIMEOUT_MS = 45s`(非 ISO 仍 20s) |
| 吞吐守卫 | 门槛 = 平均码率(188 口径)× 115%;`measured <= 0` 一律不判 |
| 网盘 | Quark 直链经本地代理(日志里的 `127.0.0.1:5266`);VIP 实测 7.4–17.9MB/s,盘需求 3.6–11.8MB/s |
| KV 开关 | `player_iso_enabled`(默认 **false**)、`player_iso_lpcm` / `player_iso_truehd_core` / `player_iso_pgs`(默认 true) |

**设备解码边界(必须如实写)**:

- 模拟器只提供 `OMX.google.hevc.decoder`(**ProfileMain / 最大 4096×4096**)⇒ 4K Main10 盘必然 `NO_EXCEEDS_CAPABILITIES`,**这是真报不是误报**;
- 真机硬解(`c2.qti.hevc.decoder` 一类)通常能解 4K Main10,**未验证**;
- 播放配置里的「软解码」(`preferSoft=true`)会强制走软解,4K 同样撞这条线;「软解超能力时自动回退硬解」**未实现、待拍板**。

## 2. 配置与入口判定

| 判定点 | 位置 | 规则 |
|---|---|---|
| 走不走 ISO 通道 | `MediaSources.getMediaSource` | 请求头带 `TVBox-Iso`(`isoHeaders`)或 `contentType == TYPE_ISO` |
| 内容类型推断 | `MediaSources.inferContentType` / `PlayerEngine` | `info.format` 或 URL 后缀 `.iso`;`.m2ts` 走 BDAV 探测 |
| ISO 会话标记 | `PlayerEngine.setDataSource` | ISO 命中后跳过磁盘缓存/预载,打 `echo-iso-play-skip-cache` |
| 裸 192B BDAV | `BdavDataSource` + `retryAsBdav` | 首轮解析失败 → 64KB 结构判据(192 对齐 + PMT/PES)→ 命中即 `IsoSessionSignal.markEscalated()` |
| 音乐页拦截 | `MusicPlayerActivity.isIsoSession/bailIsoSession` | ISO/bdav 内容**禁止**进音乐页(打 `echo-iso-music-bail`,Toast 后回详情页) |
| 45s 窗口续期 | `PlaybackRetryDelegate` | 迟到 ISO 会话(`IsoSessionSignal.escalated()`)首次超时把窗口重算 45s |

## 3. 模块地图(as-built)

包 `app/src/main/java/com/github/tvbox/osc/player/engine/iso/`:

| 文件 | 职责 | 维护要点 |
|---|---|---|
| `UdfVolume.kt` | 卷识别 / 分区映射 / 目录遍历 / 读文件 | 卷识别按 ECMA-167 扫**描述符序列**;`physical`/`metadata` 两种映射 |
| `UdfParser.kt` / `UdfFormat.kt` | File Entry、`short_ad`/`long_ad`、`decodeDString` | 派生缓存带**解析器版本** |
| `IsoLocator.kt` | 定位 `BDMV/STREAM`、选 m2ts、合并相邻 run | 碎片文件多 run |
| `MplPlaylist.kt` | mpls `STN_table` + play item 解析、选片、流去重 | 选片按**去重时长**;语言在 STN |
| `IsoDataSource.kt` | ISO 层 DataSource(HTTP Range 之上)+ 探测与解析编排 | 会话开始时 `IsoThroughput.beginSession()` |
| `M2tsDataSource.kt` | 192→188 剥 ATS、逻辑↔物理映射、多 run | 属性测试覆盖(`M2tsDataSourceTest`) |
| `IsoExtractorsFactory.kt` | 组装 `TsExtractor` + reader 工厂 + `PrimaryVideoExtractor` | 6 参构造;`extractorFlags=0` |
| `BdTsPayloadReaderFactory.kt` | BD 私有 `stream_type` → reader 映射 + **临时 Format 注入** | 见 §4-C4/C5、§5 的 #1–#3 与 #5 |
| `ProvisionalFormatReader.kt` | 在 `createTracks` 时为音频轨先发一条猜测 Format | 真实 Format 到达自动覆盖 |
| `PrimaryVideoExtractor.kt` | 主视频 PID 过滤 + **延迟声明门** | 见 §4-C1..C3 |
| `LpcmReader.kt` | LPCM 4B 头解析、声道重排、24→16bit | 样本必带 `BUFFER_FLAG_KEY_FRAME` |
| `Ac3CoreReader.kt` | `0x83` TrueHD 只取 PES 起始的 AC-3 core | 不能直连 `Ac3Reader`(假同步会崩) |
| `PgsReader.kt` | PGS 按 END 拼完整 display set 再产 sample | `createTracks` 立刻发 Format |
| `LanguageOverrideReader.kt` | 按 PID 覆盖语言 | 语言来源是 STN |
| `IsoPlaylistState.kt` | 跨阶段共享:mpls 选中结果 / 片段表 / 主视频 PID / 语言 | `reset()` 必须覆盖全部字段 |
| `IsoSeekRebuild.kt` | seek 落点重建 MediaSource / RAP 起播判定 | 纯判定 `IsoSeekAction`,带单测(`IsoSeekRapTest`/`IsoSeekRebuildTest`) |
| `IsoThroughput.kt` / `IsoBandwidthGuard.kt` | 4s 窗口吞吐 / 带宽判定 | 见 §1 |
| `IsoDiskCache.kt` | 目录快照与 clpi 缓存 | 版本号参与 key |
| `ClpiIndex.kt` | `*.clpi` RAP 索引 | seek/RAP 起播用 |
| `IsoPrepare.kt` | 后台预取 clpi(6s 延迟) | 不阻塞起播 |
| `BdavDataSource.kt` | 裸 192B BDAV 探测 + 复用 `M2tsStream` | stub <1MB 快速失败 |
| `IsoSessionSignal.kt` | 引擎 → 控制器的迟到会话信号 | 一次性 |
| `IsoFileSource.kt` / `IsoResolver.kt` / `IsoSettings.kt` / `RangeBlockReader.kt` | 源解析 / 开关 / 测试接缝 | `RangeBlockReader` 是主要测试替身 |

接线点(ISO 相关):

- `player/engine/MediaSources.kt`:`createIsoMediaSource`(多段时 `ConcatenatingMediaSource` 追加)、`getBdavMediaSource`、`isIsoUri`/`isoHeaders`/`inferContentType`;
- `player/engine/PlayerEngine.kt`:`isoSession` 标记、`prepare()` 里的 RAP 起播、`seekTo` 三态(`ALLOW`/`BLOCK`/`REBUILD`)、`retryAsBdav`;
- `player/PlaybackRetryDelegate.kt`:带宽三处入口(中段/耗尽/超时探测)+ 45s 窗口续期;
- `ui/activity/DetailActivity.kt` / `MusicPlayerActivity.kt`:ISO 内容禁进音乐页(判定必须限定"引擎装的就是本页内容")。

## 4. 不变量(违反即回归)

> 这一节是本文最需要维护的部分。每条都对应一次真实故障,证据在档案里。

**C1 — 声明过的轨必须拿到 Format,否则 `prepare` 永远不完成。**
media3 `ProgressiveMediaPeriod.maybeFinishPrepare()` 要求**每条已声明轨**的 `SampleQueue.getUpstreamFormat() != null`;少一条就 return,播放停在 `BUFFERING`,最后被 `ExoPlayerImplInternal.PLAYBACK_STUCK_AFTER_MS = 4000`(**硬编码,无 API**)掐死。因此:**不得声明一条可能永远没有 Format 的轨**。手段只有两个 —— 临时 Format(`ProvisionalFormatReader`)或延迟声明门(`PrimaryVideoExtractor`)。

**C2 — 包装 `TrackOutput` 时,数据一个字节都不能扣。**
`H264Reader.consume()` 在发 Format **之前**就 `output.sampleData(data, data.bytesLeft())`,并用自己累计的 `totalBytesWritten` 计算后续 `sampleMetadata` 的绝对位置(`absoluteOffset = totalBytesWritten - size - offset`)。扣掉任何一段字节都会让位置记账错位,表现为 `IndexOutOfBoundsException: off=... out of bounds`。可以延迟**声明**,不能扣**数据**。

**C3 — `endTracks()` = 声明窗口关闭,想延迟声明就必须扣住它。**
`TsExtractor` 在 PMT 解析完就调 `output.endTracks()`;之后 `ProgressiveMediaPeriod.prepareTrackOutput()` 对新 TrackId 直接返回 `DiscardingTrackOutput`(只打一条 `Extractor added new track ... after finishing tracks.`)。所以"字面懒声明"= 全轨丢弃。

**C4 — BD 的 `stream_type` 与 MPEG-TS 撞号,必须自己映射。**

| `stream_type` | 真身 | media3 默认行为 |
|---|---|---|
| `0x80` | LPCM | 当 DC2/H.262 |
| `0x82` | DTS | 需 `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` |
| `0x83` | TrueHD | **不认**(无 reader) |
| `0x84` | E-AC-3 | 与 DTS-HD 冲突 |
| `0x85` | DTS-HD HRA | 丢轨 |
| `0x86` | DTS-HD MA | 当 SCTE-35 ⇒ **崩抽取器** |
| `0x90` | PGS | 未注册 |
| `0x24`/`0x1b`/`0x02` | HEVC / AVC / MPEG-2 | 走默认 |

**C5 — 语言只在 mpls 的 `STN_table`。**
音频语言在 `attrStart+2`、PG 在 `attrStart+1`;PMT 里没有。读错会得到 `0jp` 这种垃圾值。`IsoPlaylistState.publishStreams` 是唯一来源,`LanguageOverrideReader` 按 PID 覆盖。

**C6 — 只保留 STN 指定的主视频轨。**
DV 双层盘的 PMT 同时有主层(3840×2160)和增强层(1920×1088),`PrimaryVideoExtractor` 按 `STN_table` 的 `videoCount` 区段丢后者。安全闸:**主 PID 已声明后**才丢;拿不到主 PID 时全留(绝不出现"零视频轨")。

**C7 — 选片按「去重时长」,时间轴用「声明时长」。**
同一 `(clip, in, out)` 只计一次 ⇒ `uniqueDurationMs`;`pickLongestPlaylist` 用它排序,`IsoPlaylist.durationMs` 保持原值进时间轴。两个数不等就是重复段诱饵。

**C8 — 轨道表按 `(pid, language)` 去重。**
重复段会把同一条音轨加 N 次,脏表会污染语言标注与音轨菜单。

**C9 — 目录快照与 clpi 缓存的 key 必须含解析器版本。**
否则改完解析器仍回放旧快照(`echo-iso-cache-hit`),表现为"改了没用"。改解析器必须看到 `echo-iso-cache-decode-fail` 重建一次。

**C10 — UDF 卷识别是「扇区序列」,不是 sector 16 的 magic。**
`CD001`(ISO9660 主/补充/结束符)是合法成员 → 跳过;`BEA01` 空操作;`NSR02`/`NSR03` 记版本;`TEA01` 或全零扇区结束;扫满 16 个扇区仍无版本才报错。

**C11 — 一个 m2ts 可以有多个物理 run。**
`long_ad`/`short_ad` 拼出的 extent 首尾相接要合并成一个 run;跨 run 读取不能假设物理连续。

**C12 — 新增日志前缀必须进 `LOG.FILE_LOG_PREFIXES`。**
现有 `echo-iso` 前缀已覆盖全部 ISO 日志(`LOG.kt:20`)。代码**零注释**;说明写进本文或档案。

## 5. 已知坑与判据

> 每条:现象 → 根因 → 判据。完整解剖见档案对应节。

| # | 现象 | 根因(一句话) | 判据 / 日志 | 档案 |
|---|---|---|---|---|
| 1 | 4 秒 `StuckPlayerException` | 声明了永远没有 Format 的音轨(C1) | `echo-iso-track-defer` 后出现 `track-drop`;正常盘不该有 | §14.13 |
| 2 | `IndexOutOfBoundsException: off=-165...` | 包装层扣了 Format 之前的数据(C2) | 任何 `off=` 负值 | §14.13 |
| 3 | 全片无声、音轨菜单只剩一条 | 用时间窗丢轨,误伤"晚到但真实"的主音轨 | `track-drop:<pid>` 紧跟 `track-late:<pid>` | §14.13 |
| 4 | 起不来,`physical discontinuity` | 碎片 m2ts 多 run 未合并(C11) | `echo-iso-target-runs` 里 `n>1` | §14.1 |
| 5 | 静默丢轨 / 抽取器崩 | `stream_type` 撞号(C4) | `echo-iso-dts-hd`,或零音轨 | §14.2 |
| 6 | 音量/语言标错、字幕语言怪 | 语言读 PMT 而非 STN(C5) | `echo-iso-streams` 出现 `0jp` 类 | §14.3 |
| 7 | 字幕轨名显示 `APPLICATION/PGS` | 名字取错了字段 | `echo-setTrack ... codecs=` 才是源格式 | §14.4 |
| 8 | PGS 横向拉伸 1.25× | 字幕层没贴视频矩形 | `echo-render-rect` | §14.5 |
| 9 | 302 段正片被判 `segment-missing` | UDF 目录 FID 跨逻辑块,目录被截断 | `echo-iso-m2ts:count` 与 `echo-iso-mpls:count` 同时偏小 | §14.6 |
| 10 | 改了解析器却没效果 | 目录快照没带解析器版本(C9) | 无 `cache-decode-fail` 却有 `cache-hit` | §14.7 |
| 11 | 播到增强层(1080p 残差)、灰块 | DV EL 被当视频轨(C6) | `echo-iso-video-skip`;解码 Format 的尺寸 | §14.10 |
| 12 | 时长 3:51、每 55 秒跳开头、无字幕无 TrueHD | 重复段诱饵骗过 `maxBy(duration)`(C7) | `echo-iso-mpls` 里 `duration != unique` | §14.11 |
| 13 | 打开就报 `volume recognition missing` | 混合盘 VRS 不在 sector 16(C10) | 无 `echo-iso-udf-vrs` | §14.12 |
| 14 | 黑屏无声、进度条正常 | 诱饵指向 MPEG-2 菜单素材,设备无 MPEG-2 解码器,media3 不报错 | `echo-exo-selector: mime=video/mpeg2 count=0` | 档案 §4.6 |
| 15 | 4K 盘 `NO_EXCEEDS_CAPABILITIES` | 模拟器只到 ProfileMain,不是误报 | `echo-exo-codec-init: kind=SOFTWARE` + Format 尺寸 | §14.8 |

## 6. 运维:拉日志 / 复现

```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"

"$ADB" devices
"$ADB" -s 127.0.0.1:16384 exec-out run-as com.github.avbox.osc cat files/preload_debug.log > cur.log
"$ADB" -s 127.0.0.1:16384 logcat -d -v time > logcat.txt
"$ADB" -s 127.0.0.1:16384 install -r app/build/outputs/apk/debug/AVBox_debug.apk
"$ADB" -s 127.0.0.1:16384 forward tcp:15266 tcp:5266
```

- 应用日志是**前缀白名单**(`LOG.FILE_LOG_PREFIXES`),不在此列的一律丢弃;长 URL 用 `awk 'length($0)>200'` 截断再看。
- 日志文件名 `files/preload_debug.log`,单线程追加,不丢行;**8MB 轮转**:超过就改名成 `preload_debug.log.1`(旧的 `.1` 覆盖),要完整历史两个文件都得拉。
- 落盘只在 debug(`LOG.FILE_LOG = BuildConfig.DEBUG`);release 只走 `Log.i`,不碰磁盘。
- 频次基线(实测):整份日志平均 **4.9 行/秒**;`echo-progress-tick` / `echo-bar-draw` 各 **1/s**(后者仅在进度变化时打);ISO 相关最密的是 `echo-iso-seek`(每次 DataSource 重开一条)与每 10s 一条的 `echo-iso-bps`。**没有 per-sample / per-frame 日志**,不要往热路径加。
- 离线复现:把真实盘切片交给 `IsoSliceHarnessTest`(环境变量 `ISO_SLICE=<192B 切片文件>`),它只做诊断、不断言;`PgsParseProbeTest` 用 `PGS_SETS`。
- 取证顺序:**先 `Range` 直读盘头**(sector 16 起的卷识别序列 / PMT / STN),再与应用日志对照。应用日志单独看会把"我们的解析 bug"误判成"盘有问题"(档案 §14.6/§14.7)。

**日志速查(ISO 相关,按出现顺序)**

| 标记 | 含义 |
|---|---|
| `echo-iso-play: format=...` / `echo-iso-play-skip-cache` | 命中 ISO 分流 / 跳过磁盘缓存 |
| `echo-iso-udf-vrs:NSR02\|NSR03` → `echo-iso-udf-avdp/lvd/fsd` → `echo-iso-udf-ok` | UDF 各阶段;`NSR02` = UDF 1.02(混合盘常态,不是异常) |
| `echo-iso-cache-hit` / `-decode-fail` / `-build` | 目录快照;改过解析器必须出现 `decode-fail` |
| `echo-iso-m2ts:count/…:chosen` | 盘内 m2ts 与选中项(多段盘判据) |
| `echo-iso-mpls:…:chosen=…:duration=…:unique=…:top=…` | 播放列表盘点;`duration != unique` = 重复段 |
| `echo-iso-playlist:…:segments/unique/total` / `-append` | 选片结果与跨段追加 |
| `echo-iso-streams:<pid>:<lang>,…` | STN 读到的语言表 |
| `echo-iso-pgs-format` / `echo-iso-pgs:first=` | PGS 轨注册(必须)/ 首条 display set |
| `echo-iso-truehd-core` / `echo-iso-lpcm-format` / `echo-iso-dts-hd` | 三条自定义音频链路就绪 |
| `echo-iso-track-defer:declared=N` | 扣住 `endTracks()`,N 条轨等 Format(正常盘随后自动放行) |
| `echo-iso-track-drop:<pid>:<type>` / `-late` / `-end:` | 1.5s 仍无格式无数据被丢 / 丢弃后才来格式 / 汇总。**`drop` 表示该盘有"不说话"的轨** |
| `echo-iso-video-skip:<pid>:primary=` | 非主视频轨被丢弃(DV EL / 次视频) |
| `echo-iso-seek:<pos>:<len>` | ISO 内随机读。打开后紧跟的 `长度 − 1,128,000` 是 media3 尾读,不是我们的 seek |
| `echo-iso-seek-rap:<ms>:<…>` / `echo-iso-seek-inperiod` / `-blocked` | RAP 起播 / 段内 seek / 被闸门挡 |
| `echo-iso-bps:<window>:<avg>:<required>` | 吞吐 vs 需求(比例 <3x 才值得怀疑带宽) |
| `echo-iso-bandwidth*` / `echo-iso-bdav-*` | 带宽判定 / BDAV 探测与升级 |
| `echo-exo-selector` / `echo-exo-codec-init` | 解码器候选与实选;`kind=SOFTWARE` 播 4K Main10 必失败 |
| `echo-iso-error:<msg>` | 解析/定位失败;`volume recognition missing` ≠ 文件不是 ISO |

## 7. 走查清单(编号稳定)

> 每次动 `engine/iso/**` 或 `PrimaryVideoExtractor`/`BdTsPayloadReaderFactory` 后按此表跑一遍。设备操作(点播放)必须由用户执行。

1. **单段 ISO**:`udf-vrs` → `m2ts` → `mpls`(`duration == unique`)→ `playlist` → `pgs-format` → `track-defer` → `echo-exo-selector` → `codec-init` → `bps` → 进度 1s/s 推进,无 `echo-iso-error`。
2. **多段 ISO**:`playlist:segments=N` 且 `playlist-append` 出现;跨段无黑屏、无 `codec-init` 重建(档案 §12.9)。
3. **音轨**:菜单条数 = STN 音轨数;主音轨出声(`AT::add`,48kHz/5.1 之类);无 `track-drop` + `track-late` 组合。
4. **字幕**:三条 PGS 都能切、轨名显示 `PGS`(不是 `APPLICATION/PGS`)、位图不被拉伸。
5. **4K 单层**:送解码的 Format 是 3840×2160;失败必须是 `NO_EXCEEDS_CAPABILITIES`(设备边界),不能是"零视频轨"。
6. **4K 双层 DV**:`video-skip` 出现且只出现一次,解码对象是主层。
7. **诱饵盘**:`chosen` 是正片,`duration == unique`;诱饵仍在 `top=` 里但排后面。
8. **混合盘(ISO9660+UDF 1.02)**:`udf-vrs:NSR02` 后全链路通。
9. **碎片盘**:`target-runs` 多 run 也能播。
10. **裸 192B m2ts**:`bdav-probe:192` → 起播;空壳源 13s 级快速失败并提示"资源不完整"。
11. **音乐页**:ISO 内容被拦(`echo-iso-music-bail`),不进音乐页、不静音播放。
12. **seek / 续播**:ISO 内拖动或 RAP 起播后出现**新的** `codec-init` 且画面正确;连拖三次成立。

## 8. 决策记录

| # | 决策 | 理由 / 代价 |
|---|---|---|
| D1 | 只用 media3 扩展点(`DataSource` / `TsPayloadReader.Factory` / `ExtractorOutput`),不 fork | 跟随上游;代价是个别行为要在包装层绕(见 C1–C3) |
| D2 | 不加 native,TrueHD 只取 AC-3 core | 640kbps 有损;纯 TrueHD 无 core 的盘不产音轨(不会"有轨无声") |
| D3 | 选片用去重时长的启发式,不做 BD-J 解析 | 挡得住重复段诱饵,挡不住"每段微调 in/out";用户选播放列表**拍板暂不做** |
| D4 | 4K Main10 解不动如实上报,不做软解兜底 | 回退硬解的开关**待拍板**;模拟器上无论如何解不动 |
| D5 | seek 走"重建 MediaSource + RAP 起播",不做解码器复用 | B2c/B2a 两轮实测判负(档案 §11.8);RAP 起播真机可用(档案 §11.10) |
| D6 | 音轨"晚到"用**临时 Format**,不用时间窗丢轨 | 时间窗会误伤真实主音轨(档案 §14.13 的 bug B) |
| D7 | 延迟声明门保留,但只作保险 | 有临时 Format 后它基本不参与;留着兜住"既无格式又无数据且未覆盖的类型" |
| D8 | 多段盘用 `ConcatenatingMediaSource` 逐段追加 | 段首必带参数集,跨段解码器复用;代价是 seek 更容易触发重建 |

## 9. 未决清单

| # | 事项 | 状态 |
|---|---|---|
| 1 | 真机 4K Main10 硬解验证 | **未验证**(模拟器只到 ProfileMain) |
| 2 | 「软解超 `NO_EXCEEDS_CAPABILITIES` 时是否自动回退硬解」 | **待拍板** |
| 3 | 盘 B(`00020.mpls`,302 段)的诱饵审计:`duration` 与 `unique` 是否相等 | **未核对** |
| 4 | 用户选播放列表 + 按盘记选择 | 已评估,**用户拍板暂不做** |
| 5 | 音轨"按 PID 载荷判活"的更精确丢弃规则 | 可选,**未开工**(现状见下,别读成空白)。**已有的一半**:`DeferredTrackOutput` 在 **Format 或首个 sample 任一到达**时就 `open()`(「有数据即落地」),所以"永远不说话的轨"仍会等到 `DEFER_HOLD_MS = 1500ms` 被 `track-drop`。**缺的一半**:判死依据是**时长**而非**该 PID 的载荷**(`PrimaryVideoExtractor.kt` 的 `dropAllowed()` 只看"主视频/音频是否已落地");要做可挂在 `LanguageOverrideReader`(它已是带 pid 的包装层,`EsInfo` 不带 pid 的障碍已绕过) |
| 6 | 临时 Format 的准值:从 STN 的 `audio_format`/声道数读,而非按类型猜 | 可选,**未开工**(现状见下)。`BdTsPayloadReaderFactory.PROVISIONAL_*` 仍是硬编码 `48kHz` + `6ch`(AC-3/E-AC-3/DTS) / `2ch`(LPCM);`MplPlaylist.readStnStreams` **只读 `pid + language`**,`audio_format`/声道数字段根本没解。代价仅限"真实 Format 到达前音轨菜单显示猜值",不影响出声 |
| 7 | 4K 多段盘、PGS 跨段延续、分钟级跨段 | **未覆盖** |
| 8 | 稳态吞吐长跑(2–3 分钟连续) | **未测**;现有 5.9–11.8MB/s 都是折算值 |
| 9 | ~~seek 的两个疑点:`TsBinarySearchSeeker` 为何一次二分都没跑;落点开始读时视频轨为何拿不到 Format~~ | **已结(2026-10-20 真机复验,档案 §11.10)**。①"二分没跑"不是缺陷:重建路径按 CLPI 访问点算出**精确字节偏移**、直接开新 period,**本来就不该跑二分**;段内 seek 才是 media3 容器 seek(真机 `echo-iso-seek:807646097:4467110511`,一次跳 807MB,无二次 `codec-init`)。②"落点拿不到 Format"的前提不成立:访问点**自带参数集**(档案 §11.9.2 逐字节解开 `AUD/SPS/PPS/SEI`,每个访问点重发),真机 RAP 起播 `codec-init: name=c2.qti.avc.decoder kind=HARDWARE` 出画有声有字。真正留下的是教训:模拟器假解码器给的 4 次判负全部作废(档案 §11.10) |

## 10. 档案索引

| 想知道什么 | 去哪 |
|---|---|
| 某个现象当初怎么定位的、盘的事实、日志原文 | `history/iso-playback-chronicle.md` 甲(案例 A–H)/ 乙(缺陷 §14.x) |
| seek / 倍速为什么这么定、哪些路被证伪 | 档案 丙(`§11.5–§11.10`) |
| 多段盘与性能的实测数字 | 档案 丁(`§12.9`、`§13.x`) |
| 播放器归属 / 挂摘 / 后台与通知 | `avbox-playback-service-spec.md` |
| 通用规范、零注释、门禁、审查收敛 | `SKILL.md` |

## 11. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-10-19 | ISO 播放立项:UDF 只读解析 + m2ts 选片 + 192→188 剥头 + `TsExtractor` 接入;A0 spike 与 A1 分片落地(细节见档案与旧文档存档) |
| 2026-10-19 | LPCM 致命 bug 修复:样本未带 `BUFFER_FLAG_KEY_FRAME` ⇒ 音频渲染器永不 ready,表现为"卡住 + 带宽误报" |
| 2026-10-19 | TrueHD(`0x83`)取 AC-3 core(`Ac3CoreReader`);裸 192B BDAV 支持(`BdavDataSource` + `IsoSessionSignal`) |
| 2026-10-19 | PGS 走 media3 官方字幕转码(`PgsReader` 按 END 拼接 + 6 参 `TsExtractor`);自研 PGS 解码器删除 |
| 2026-10-20 | 多段盘拼接(`ConcatenatingMediaSource` + mpls 解析);RAP 起播经真机复验可用 |
| 2026-10-20 | 五台超大盘 + 三台 4K 暴露出 `§5` 的 #4–#13:碎片 run、`stream_type` 撞号、STN 语言、字幕命名与矩形、FID 跨块、快照版本、DV EL、诱饵播放列表、混合盘 VRS |
| 2026-10-20 | **本文重写为 as-built 活规范**,并从 `文档/` 迁入 `skill/`:结论/约束/判据留本文,案例与取证迁 `history/iso-playback-chronicle.md`;文档地图登记 |
| 2026-10-20 | 盘 J 的"4 秒卡死"修复:`PrimaryVideoExtractor` 延迟声明门 + 有数据即落地 + 按 stream type 的临时 Format(`ProvisionalFormatReader`);新增 `§4-C1..C3` 与 `§5` 的 #1–#3 |
| 2026-10-20 | 诊断日志复核:确认无 per-sample 日志、落盘仅 debug;顺带修掉 `echo-progress-tick` 暴露的**进度回调自旋**(纯判定 `ProgressTickDelay`,下限 200ms)与 debug 日志**8MB 轮转**(`LOG.shouldRotateLog`);全量 **1287 通过 / 0 失败 / 3 跳过** |
| 2026-10-20 | §9 对账(只改文档,不动代码):seek 两个疑点按真机复验(档案 §11.10)结案并删除;`#5`/`#6` 补 as-built 现状 —— 「有数据即落地」已覆盖大部分丢轨场景(缺的只是按载荷而非时长判死)、临时 Format 仍硬编码 `48k/6ch/2ch` 且 STN 只解 `pid+language`,避免被读成"完全空白" |
