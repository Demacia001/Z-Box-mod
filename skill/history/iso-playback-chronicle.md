# AVBox ISO 播放:缺陷档案与实测记录(只增不改)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件上两级)。
> 配套活规范:`skill/avbox-iso-playback-spec.md`(结论、约束、判据都在那边);索引入口:`skill/SKILL.md` 的「文档地图」。
> 性质:**过程与取证**存档,不写"应该怎么做",只写"当时看到了什么、怎么定位的"。默认**不通读**,按关键词检索。
> 编号约定:下列章节**保留原始编号**(案例 §4.x、缺陷 §14.x、实验 §11.x、实测 §12.9/§13.x),活规范引用时写「档案 §14.13」这种形式,便于与历史日志、旧文档对账。
> 迁入来源:2026-10-20 由 `文档/iso-playback-spec-20261019.md`(§11/§12.9/§13/§14)与已删除的 `文档/iso-playback-handoff-20261019.md`(§4)原文迁入。

---

## 甲、案例档案(8 例,原 handoff §4)

### 4.1 案例 A:「我存在的时间」`/book/21014.html` —— 我们的 UDF bug(已修)

**现象**:07:39:51 起播,`format=video/x-iso`,UDF 链走完到 `fsd:0:2` 后报 `echo-iso-error:BDMV directory missing`,三次重试同样失败。

**取证**:

- 该 URL(`/fishplay/go/quark/128t/e0f4c8da`)实测是真 ISO:总长 **24,295,178,240**,偏移 32768 处 `BEA01`、34816 处 `NSR02`,**根目录里有 `BDMV`**。
- 盘是 **UDF 1.02 + 普通 File Entry(tag 261)**;`NSR03` 的盘用扩展 File Entry(tag 266),所以我们之前的测试全绿也没暴露。
- 根因:`UdfFormat.kt` 把"扩展属性长度 / 分配描述符长度"读在 176/180、数据从 184 起,而 ECMA-167 是 **168/172,数据从 176 起**。于是 `allocationLength` 读到 0 → 根目录数据 0 字节 → 目录为空 → `BDMV` 找不到。

**修复**(已装机):

```diff
- FILE_ENTRY_LENGTH_OFFSET = 176   → 168
- FILE_ENTRY_DATA_OFFSET   = 184   → 176
```

同时 `app/src/test/.../iso/UdfImageBuilder.kt` L170-172 的测试镜像也按同样错误偏移写过,一并改成 168/172/176(否则测试会继续"自洽地错")。

**验证**:

- 离线(JVM 探针跑真 URL):根目录列出 `BDMV` / `CERTIFICATE` / 一个 `.url` 文件;`IsoLocator` 得到 `IsoPlayTarget(m2tsOffset=712218624, m2tsLength=8048861184, name=00003.m2ts)`。
- 真机 07:53:53:`echo-iso-udf-ok:712218624:8048861184` + `echo-iso-target:00003.m2ts`,与离线完全一致。
- 单测 1121 全绿,闸门 PASS。

**结论**:该盘解析问题已彻底解决;后续卡点与它无关(见 4.3)。

---

### 4.2 案例 B:「佐贺偶像是传奇」`/book/20915.html` —— 裸 192 字节 BDAV(**已支持并真机验证**)

**现象**:07:40:47 起播,先 `UnrecognizedInputFormatException`(所有 extractor 都不认),再被当 HLS 重试报 `Input does not start with the #EXTM3U header`,换内核重试仍失败。

**取证**(主机经 `adb forward` 直接抓):

```
HTTP/1.1 206 Partial Content
Content-Length: 289916928
Content-Type: application/octet-stream
首字节:24 0e 28 3c | 47 40 00 10 00 00 b0 11 ...
0x47 出现在 4 / 196 / 388 / 580 / 772 / 964 …  间距 192
```

**结论**:这是**未封装的 192 字节 BDAV M2TS 流**(4B ATS + 188B TS),不是 ISO。我们只在 ISO 通道里做 192→188 剥离,普通 HTTP 通道直接喂 `TsExtractor`(按 188 找同步),必然失败。

**已实现方案(2026-10-19 11:00,代码 + 单测;真机验证见本节末 11:20)**

- `player/engine/iso/BdavDataSource.kt`:普通通道上的探测 + 剥离。探测 = 打开上游取总长,读 768B,断言 offset `4/196/388/580` 均为 `0x47`;命中则按 `IsoPlayTarget(0, total)` 复用现成的 `M2tsStream`(同一套 192→188 映射),不命中原样透传。探测结果按 URI 缓存(LRU 8),同一次播放只多打一次请求。
- 触发点:`PlayerEngine.retryAsBdav`,只在 `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 且 `IsoSettings.enabled()` 时触发一次,插在 `retryAsHls` 之前;**不靠 URL 后缀识别**。
- **会话语义(关键)**:命中后 `isoSession = true` + `IsoSessionSignal.markEscalated()`,于是 seek 禁用、续播归零、403 只刷新解析、带宽守卫全部继承;`PlaybackRetryDelegate` 用 `isoSession || escalated()` 判定。20s 超时首次触发时把窗口重算成 45s(`consumeWindowExtension()`),否则重试只能吃首轮失败后的残余预算。
- 单测:`BdavDataSourceTest`(剥离/跨包/定位读/透传/探测只跑一次/stub 门槛)+ `BdavSyncTest`(4 条对齐判据)+ `IsoSessionSignalTest`(5 条);全量 1139 通过 / 0 失败 / 2 跳过。
- **stub 门槛(2026-10-19 11:20,真机暴露后补)**:`/book/20849.html` 夸克原画#0101 的 URL 只吐 **6144 字节**(`Content-Range: bytes */6144`,内容 = 一个 PAT + 全 `0xFF` 空包)。这种流 192 对齐、`TsExtractor` sniff 能过,但**产不出任何轨道** ⇒ `prepare` 永不完成 ⇒ 播放器恒 `PREPARING`,`bps=0`,20s 超时→45s 续期→重放→再来一遍,表现为"永远转圈"。修法:探测时 **总长 < 1MB 直接判 stub**(`echo-iso-bdav-probe:small:<total>`)。命中 192 才 `markEscalated()`,避免空壳把会话升级成 ISO。
- **11:22 复测发现第二层问题**:第一版对"不命中"做的是**透传**,但透传仍用 `IsoExtractorsFactory` → 又是 `TsExtractor` → 零轨道 → 仍然卡到 20s 超时。改为**不命中直接抛 `IOException`**(`echo-iso-bdav-skip`),media3 报 `ERROR_CODE_IO_UNSPECIFIED`(属 `isParsingError`),`retryAsHls` 与既有梯子照常推进。
- **11:27 复测发现第三层问题**:抛普通 `IOException` 会被 `DefaultLoadErrorHandlingPolicy` **重试 3 次**(1s/2s 退避),同一次尝试出现 4 条 `bdav-skip`,三轮梯子白烧 9s,总耗时 20.5s。改为 `BdavSkipException` + `BdavLoadErrorPolicy`(该异常返回 `C.TIME_UNSET` = 不重试);探测本身失败仍走普通 `IOException` 保留重试。
- **11:35 实测(最终)**:play → 报错 **13.4s**(改前 20.5s / 更早是无限转圈);每次 bdav 尝试只 **1 条** `bdav-skip`,`BdavSkipException` 11ms 内返回;同一 URL 重放时探测结果命中缓存,连 `probe:` 都不再打。剩余耗时来自 `retryAsHls` 的 2.2s/轮(既有逻辑,未动)与梯子的 4 轮。
- **11:35 判据升级(防误判)**:对齐检查只能证明"是 192 对齐的 TS",证明不了"有内容"。探测窗口从 768B 扩到 **64KB**,新增结构判据:**PMT(`table_id=0x02`)或 PES 起始(`00 00 01`)**。用真实盘 `00011.m2ts` 头 4MB 验证:对齐通过、PMT 在 packet 1(PID 256)、首个 PES 在 packet 4(PID 0x1011),前 5 个包内命中;空壳(PAT + 空包)两条都不满足 ⇒ 判 `echo-iso-bdav-probe:empty:<total>`。1MB 门槛降级为兜底。
- **真机验证(2026-10-19 11:20,本案例原源)**:
  ```
  11:20:03.919 CONTAINER_UNSUPPORTED(首轮,预期)
  11:20:03.927 echo-iso-bdav-probe:192:289916928
  11:20:04.028 echo-iso-bdav-play
  11:20:04.657 echo-iso-seek:283764192:112800      尾部时长探测
  11:20:05.737 echo-iso-lpcm-format:audio/raw sr=48000 ch=2 src=24bit out=16bit
  11:20:05.926 dumpsys audio: new AudioTrack piid:111 48000/2ch
  11:20:14~26  state=PLAYING pos 8s→20s dur=62099ms 00018.m2ts [276.49MB]
  ```
  全程无 `echo-iso-error` / `StuckPlayerException` / 带宽误报;拖拽被 `echo-iso-seek-blocked` 挡掉(设计内)。**A1-9 验收通过。**

---

### 4.3 案例 C:「BanG Dream! Ave Mujica S01 D01」`/book/21084.html` —— **已解决(2026-10-19 10:15):LPCM 轨 `sampleMetadata` 未带 `BUFFER_FLAG_KEY_FRAME`**

> **一句话**:`LpcmReader` 给 `sampleMetadata` 传的 flags 是 `0`,media3 的 `SampleQueue` 在 `init()/reset()` 置 `upstreamKeyframeRequired=true`,首条非关键帧样本被**静默丢弃**且永不清标志 ⇒ 音频队列恒空 ⇒ `MediaCodecAudioRenderer.isReady()` 恒 false ⇒ 播放器永远 `BUFFERING`、`pos=0`、视频冻在首帧 ⇒ 8.5s 后 `StuckPlayerException` ⇒ 被带宽守卫误报成「网盘限速」。
> **修复**:`sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)`,一行。
> **验证**:`dumpsys audio` 的 avbox 条目从「无」变成 `AudioTrack state:started`;播放位置稳定推进;无 `StuckPlayerException`。
> 完整定位过程、被排除的 5 个假设、以及 harness 为什么没抓到,见 spec §4.7 与「A1-6 补记」。

以下为定位前的原始记录,保留作证据。

URL 里带 `filename=BanG.Dream.Ave.Mujica.S01.D01.2025.1080p.JPN.Blu-ray.AVC.LPCM.2.0-Nekomoe.kissaten.iso`,`thread=32`、`sp=100`。

**时间线(09:25,APK 为 07:48 构建)**:

```
09:25:24.010  p2 setData 指南|https://4kzn.cc/book/21084.html|夸克无限#1
09:25:24.643  echo-iso-play: format=video/x-iso
09:25:24.665  echo-iso-start-zero: drop resume 37552
09:25:34.656  echo-iso-udf-ok:425691136:6917855232      ← 解析 10.0s
09:25:34.656  echo-iso-target:00011.m2ts:425691136:6917855232
09:25:35.195  echo-iso-seek:6773620448:112800           ← 时长探测,读尾部
09:25:36.320  echo-iso-lpcm-format: audio/raw 48k 2ch src=24bit out=16bit
09:25:36.451  echo-exo-codec-init: OMX.qcom.video.decoder.avc (HARDWARE)
09:25:44.656  echo-switchLinePlay timeout state: PREPARING, started: false
09:25:44.657  echo-iso-bandwidth-probe: dur=1872989 bps=17,907,517 target=6917855232
09:25:44.658  echo-autoRetry replay same url before decode fallback   ← 第 2 次
09:25:53.878  echo-iso-udf-ok:425691136:6917855232      ← 又解析 9.2s
09:26:04.666  echo-switchLinePlay timeout state: PREPARING, started: true
09:26:06.253  StuckPlayerException: Player stuck buffering and not loading for 4000 ms
09:26:06.258  echo-iso-bandwidth-mid:0:3616536           ← 最近 4s 读 0 字节
09:26:06.260  echo-iso-bandwidth-exhausted               ← 弹「网盘限速，带宽不足」并停
```

**已确认的事实**

1. **VIP 生效**:实测 **17.9 MB/s**,而本片需要 `6917855232×188/192÷1872.989s = 3,616,536 B/s ≈ 3.62MB/s`,**5 倍余量**。
2. **提示是误报**:`StuckPlayerException` 先发生(播放器 4 秒没有加载),我们的守卫把"窗口内 0 读取 + 停滞"判成带宽不足(`IsoBandwidthGuard` 里 `stalled=true` 时 `measured=0` 仍参与判定)。0 读取是"加载器停了"的**结果**。
3. **数据其实在读**:第 1 次 20 秒内 `bps≈17.9MB/s`(约 150MB+,≈27 秒素材),却始终 `PREPARING` / 无首帧。
4. **解码器建好但没帧**:logcat 只有 `09:25:37.138 [OMX.qcom.video.decoder.avc] setting dataspace on output surface`,之后到超时**没有任何解码/渲染活动**。
5. **码流本身正常**(主机抓 m2ts 起点 4MB 分析):PAT ✓、PMT PID 256 ✓、PCR PID 4097 ✓(21344 包里 9 个 PCR)、视频 ES 0x1B/0x1011(约 45Mbps)、音频 ES 0x80/0x1100 LPCM 24bit;首个 PES 的 **PTS=54,000,000(600s)**,蓝光常见的 10 分钟起点。

**当前假设(未验证,按可能性排序)**

- H1:**样本没送进渲染器**(抽取器产出 format 但没产出可用样本 / 或首帧前就停),所以 `PREPARING` 卡死。
- H2:**加载被提前判 EOF**:`M2tsStream` 里 `upstreamRemaining` 用 `min(opened, span.length)` 初始化,若代理对超大 Range 返回的长度偏小,读空后返回 `END_OF_INPUT` → Loader 停 → 4s 后正是 `StuckPlayerException: not loading`。
- H3:24bit LPCM 音频轨引入后新增的等待(05:10 那次 A1-5 走查**无音轨**,视频能出画面;现在有音轨)。
- H4:首包 PTS=600s + 时长探测的组合问题(media3 有 TimestampAdjuster,可能性最低)。

**反证/待澄清**:spec A1-5 记录 05:10 在线整盘**出过画面**(首帧约 14s)。所以这不是"从来不能播",而是**05:10 之后引入的回归,或与音频轨/守卫改动相关**。这是接手后最该先查的一条线。

**当时的下一步(已全部执行,结论见上)**

```powershell
# 用已抓好的 4MB m2ts 头(192B 包)离线喂 IsoExtractorsFactory,看能否产出样本
$env:ISO_SLICE = "C:\Users\Administrator\AppData\Local\Temp\iso_head.bin"
cd D:\Project\TVBoxOS-main
.\gradlew.bat :app:testDebugUnitTest --tests "com.github.tvbox.osc.player.engine.iso.IsoSliceHarnessTest" --no-daemon --console=plain -q
# 报告落在 (<java.io.tmpdir>\iso-harness-report.txt)
```

- `iso_head.bin`:4,098,048 字节,取自 `00011.m2ts` 起点(`Range: 425691136-429789183`),已确认 192 对齐、PAT/PMT/PCR 正常。
- 结果:H1(抽取器不产样本)**排除** —— 视频 16 样本、音频 140 样本、时间戳全对。**但 harness 的 `RecordingTrack` 不模拟 `SampleQueue` 的 `upstreamKeyframeRequired` 过滤,所以「样本数正常」不等于「样本能进播放器」**;真因藏在 flags 里(报告中的 `keyFrames=0` 就是线索)。
- 之后依次排除了:数据源/线程阻塞(线程栈 RUNNABLE)、seek 往返污染(harness 二次 seek 后样本照常)、AudioTrack 打不开(`dumpsys audio` 根本没条目)。最后靠 EventLogger 的 `rendererReady` 只有 video、`audioInputFormat` 已下发这组对比锁定。

### 4.4 案例 D:「冰雪奇缘 4K DIY」`/book/16204.html` —— UDF 目录 FID 跨块 + 快照版本(2026-10-20 06:35/06:43 两修)

**这台盘的事实**(Quark 直链 + Range 独立走 UDF 得到,不依赖应用日志):`BDMV/STREAM` **87 个 m2ts**、`BDMV/PLAYLIST` **68 个 mpls**,主片 `00071.m2ts` = 29,941,518,336 B(28 个 extent),`00020.mpls` 有 **302 段**、总时长 15,509,409ms。

**症状链**:`echo-iso-m2ts:count=33` → `echo-iso-playlist-skip:00020.mpls:reason=segment-missing` → 退回"可见的最大 m2ts" = 6.5 MB 片头 → 4K HEVC 解码失败。**播放器全程没在放正片**,而错误信息只指向解码,很容易误判成"盘解码能力不足"。

**根因 1(06:35 修)**:`UdfVolume.parseDirectory` 按「FID 不得跨逻辑块」强制对齐。本盘 `STREAM` 目录 = 40B(父项)+ 87×60B = 5260B,第 34 个 FID 起于 2020、长 59,**跨过 2048 边界**,于是断在第 33 条。直读该目录顺序解析可得 88 条(父项 + 87),`00040.m2ts` 在列。
修法:顺序读;**仅当该处不是 FID 标签**时才跳到下一块头并确认那里是 FID。回归 `UdfVolumeTest.list_keepsFidsThatCrossBlockBoundaries`。

**根因 2(06:43 修)**:装完新 APK 复测仍是 33,因为 `echo-iso-cache-hit:5:1` 命中了旧代码建的目录快照。修法:`IsoVolumeSnapshot.VERSION` 1→2,旧快照 `decode` 失败即删重建(`echo-iso-cache-decode-fail`)。回归 `decode_rejectsStaleSnapshotVersion`。

**修复后**:`echo-iso-m2ts:count=87:chosen=00071.m2ts:29941518336` / `echo-iso-playlist:00020.mpls:segments=302:duration=15509409:first=00040.m2ts` / `echo-iso-playlist-append:301`。解码仍失败,那是模拟器只支持 `ProfileMain` 的真限制(见 spec §14.8)。

**教训**:遇到"应用日志与直读结果不一致",先怀疑自己的解析器,再怀疑盘;改了解析器一定要同时检查**派生缓存**是否跟着失效。

### 4.5 案例 E:4K「杜比视界双层」盘 —— 把增强层当视频轨(2026-10-20 06:48 发现,当日修)

**盘的事实**(离线直读,应用日志只作对照):49.19 GiB、10 个 m2ts、8 个 mpls;`00006.mpls` 1 段 4,853,390ms;STN 1 条音轨(`0x1100`,`0x86` DTS-HD MA,eng)+ 5 条 PG(`0x12a0` eng、`0x12a1..a4` zho)—— `echo-iso-streams` 与之完全一致,解析层没问题。

**PMT 里有两条 HEVC**:

| PID | SPS | 身份 |
|---|---|---|
| `0x1011` | Main10 / L5.1 / 3840×2160 | 正片基础层(BL) |
| `0x1015` | Main10 / L5.1 / 1920×1088 | **Dolby Vision 增强层(EL)** |

盘自己的 STN 写得很明确:`video count=1 → 0x1011`,`secVideo=0`。

**症状**:`echo-exo-player-error … Format(1/4117, …, [1920, 1080, 10bit])` —— 解码的是 `0x1015`。报错里的 1080p 不是片子不是 4K,而是我们拿错了 PID。

**根因**:media3 只看 PMT(两路都是 `0x24`),本机 4K 主层被能力过滤,选择器退到 1080p 的 EL。DV 的 EL 是残差层,单独解出来是灰块 —— 真机上「只支持 1080p Main10」的设备会踩到。

**修法**:STN 的 primary video PID → `IsoPlaylist.videoPid` → `IsoPlaylistState` → 新增 `PrimaryVideoExtractor`(包 `TsExtractor`,在 `ExtractorOutput.track` 丢弃非主视频轨,返回只排空的 `TrackOutput`,避免"没有 Format 的队列"卡死 prepare)。安全闸:只在主 PID 已声明过时才丢,顺序相反或 STN 缺失则退化为旧行为。

**边界**:我们解 BL 当 HDR10 播,EL 丢弃;media3 不解 DV 的 RPU,**真杜比视界效果不在这条链路能力内**。日志 `echo-iso-video-skip:<pid>:primary=<pid>`。**2026-10-20 07:00 设备复测通过**:`echo-iso-video-skip:4117:primary=4113`,随后报错里的 Format 从 `1/4117 (1920×1080)` 变成 `1/4113 (3840×2160)`;解码仍失败属模拟器 Main-profile 限制,符合预期。见 spec §14.10。

### 4.6 案例 F:4K《猎凶风河》—— 播放列表诱饵(2026-10-20 07:10 修)

**盘的事实**(从设备快照 `cache/iso-volume/*.snap` + 两次 `Range` 直读得到,73.67 GiB / 12 m2ts / 10 mpls):正片 `00000.m2ts` = 77,659,410,432 B(73 个 `long_ad`,首尾相接 ⇒ 合并成 1 个 run);`00001.mpls` 1 段 6,428,630ms = 107:08,STN 是 1 条视频 `0x1011` + 2 条 `0x83` TrueHD(eng)+ 7 条 PG(`0x12a0/a1` eng、`0x12a2` spa、`0x12a3..a6` zho)。

**诱饵**:`00100.mpls` / `00101.mpls` 各 253 段、声明 231:27,但 `00101` 是 253 段**同一条** `00002.m2ts`、in/out 完全相同;`00100` 是首段 `00007.m2ts` + 252 段同样重复。真实内容只有 54.9s ×(1~2)。

**症状**:`echo-iso-mpls:…:chosen=00100.mpls:items=253:duration=13886703` / `echo-iso-playlist:…:segments=253` / `echo-iso-streams` 打 253 个 `4352:eng`,**没有一条 `echo-iso-pgs-format`、没有 `echo-iso-dts-hd`**。进度条 3:51:27、每 55 秒跳回开头。

**根因 1**:`pickLongestPlaylist` = `maxByOrNull { durationMs }`,重复段被重复计数,109.8s 的诱饵压过 107min 的正片。这张是 BD-J 盘(有 `JAR/`+`BDJO/`),真实选片在 Java 菜单里,**静态拿不到**;"最长 = 正片"这个启发式一旦遇到诱饵就必错。

**根因 2**:`readStnStreams` 把每段的 STN 累加进同一个 `streams` 列表,重复段 ⇒ 同一音轨 253 条。

**修法**:`uniqueDurationMs()`(同一 `(clipId,inTimeUs,outTimeUs)` 只计一次)参与选片;`dedupeStreams()` 按 `(pid, language)` 去重;日志加 `unique=`。`IsoPlaylist.durationMs` 维持原值——它进的是时间轴,不能砍。

**修复后(07:09:55)**:

```
echo-iso-m2ts:count=12:chosen=00000.m2ts:77659410432
echo-iso-mpls:…:chosen=00001.mpls:items=1:duration=6428630:unique=6428630:…
echo-iso-playlist:00001.mpls:segments=1:duration=6428630:unique=6428630:total=77659410432:first=00000.m2ts
echo-iso-streams:4352:eng,4353:eng,4768:eng,4769:eng,4770:spa,4771:zho,4772:zho,4773:zho,4774:zho
echo-iso-truehd-core: lang=null
echo-iso-pgs-format        ×7
echo-iso-video-skip:4117:primary=4113
echo-iso-bps:740041:0:11828570        ← 需求 11.83MB/s,四台盘最高
```

**教训**:mpls 的 `duration` 是**盘上写的数字**,不是内容量。任何"按时长挑"的地方都要先问一句:这个时长里有没有重复计数的段。同类风险:`echo-iso-playlist` 的 `total` 也是各段长度之和(含重复引用),不能当"要读多少字节"。

**残留**:盘 B(`00020.mpls` 302 段)没做过诱饵审计(它的元数据分区脚本没跑通,`unique` 未核对),重播时看一眼 `duration` 与 `unique` 是否相等即可定性。

**同类第二例(2026-10-20 07:08-07:13,《潜伏3》4K)**:诱饵换了一副面孔,但机制同源 ——

- 盘:30.55 GiB、**179 个 m2ts / 189 个 mpls**,正片 `00001.mpls` 1 段 5,841,836ms(`00001.m2ts` 21.6 GB,AVC 1080p + 2 音轨 + 11 条 PGS);
- 诱饵:`00095.mpls`(100 段同一段 60.06s)、`00096.mpls`、`00099.mpls`(100 段全同一段),声明 100.2 min、去重后只有 61~63 s;
- **诱饵里的素材是 MPEG-2**:PMT 只有 `0x1011 / stream_type=0x02`,而且**一条音轨都没有**。Android 侧没有 MPEG-2 解码器,于是 `echo-exo-selector: mime=video/mpeg2 … count=0 first=none`,一帧都不出;
- 用户看到的就是「**能加载、有进度条、能拖,但全黑且没声**」:时长来自 mpls,和能不能解完全无关;media3 对"没有 renderer 支持的 mime"**不报错**,只是停在那儿,所以日志里一条 error 都没有;
- 07:09 装的新包按去重时长选到 `00001.mpls`,复测正常:`mime=video/avc count=2`、`echo-exo-codec-init … h264 … SOFTWARE`、11 条 `pgs-format` / 9 条 `pgs:first`、`AT::add … 0000003F 48000`(5.1 起了)。

**沉淀**:「有进度条 + 全黑 + 无声」这个组合,先查两件事 —— ①`echo-iso-mpls` 的 `duration` 与 `unique` 是否相等(诱饵);②`echo-exo-selector` 的 mime 是不是 `video/mpeg2` / `count=0`(没有解码器)。两者都是"页面看起来在播、其实没内容"。

### 4.7 案例 G:《最佳拍档》1982 4K DIY —— ISO9660 + UDF 1.02 混合盘,VRS 不在 sector 16(2026-10-20 07:18 发现,07:21 修)

**盘的事实**:`58,636,980,224 B`(54.61 GiB)、**3 个 m2ts / 3 个 mpls**;正片 `00003.mpls` = `00005.m2ts`(58.34 GB,93.8 min),3 音轨(zho/eng/zho)+ 3 条 PGS(eng/zho/zho);另有一条诱饵 `00001.mpls`(100 段同一段 55.1s)。

**症状**:

```
07:18:18.641  echo-iso-play: format=video/x-iso
07:18:19.683  echo-iso-error:volume recognition missing
07:18:19.696  echo-exo-player-error: code=ERROR_CODE_IO_UNSPECIFIED,
              cause[1]=IsoFormatException: volume recognition missing
```

连 `echo-iso-udf-vrs` 都没有 —— 不是"选错片",是**盘没打开**。autoRetry 三次都是同一句。

**根因(直读 sector 16..20 才看清)**:

| 扇区 | 内容 |
|---|---|
| 16 | `\x01CD001` ISO9660 主卷描述符 |
| 17 | `\xffCD001` ISO9660 卷描述符集结束符 |
| 18 | `\x00BEA01` |
| 19 | `\x00NSR02`(UDF 1.02) |
| 20 | `\x00TEA01` |

这是 mkisofs 一类工具做的**桥盘**:ISO9660 与 UDF 两套目录树并存,ISO9660 的描述符占住 16/17,把 UDF 的 VRS 挤到 18 起。而 `readVolumeRecognition()` 一遇到不是 `BEA01/NSR0x/TEA01` 的描述符、且还没拿到版本号,就**立刻**抛 `volume recognition missing` —— 它只认"VRS 从 16 开始"的纯 UDF 盘,而前四张盘恰好全是这种。

**修法**:按 ECMA-167 的 VRS 语义扫**描述符序列**而不是死盯 16 —— `CD001` 跳过、`BEA01` 空操作、`NSR02`/`NSR03` 记版本、`TEA01` 或全零扇区结束、扫完 16 个扇区仍无版本才报错;顺带新增 `isBlankSector()` 做结束判据。

**为什么 UDF 1.02 不影响后续**:这张盘只有一个 physical 分区(起于 sector 257、28,630,931 块),没有 metadata 分区(`buildPartitions` 本来就能处理);`00005.m2ts` 用 `short_ad`(55 个 extent 拼 58.3 GB),`UdfParser` 两种 AD 都支持,且 55 段首尾相接 ⇒ 合并成 1 个 run。

**修复后(07:25:05)**:

```
echo-iso-udf-vrs:NSR02        echo-iso-udf-avdp:32:32768      echo-iso-udf-lvd:1
echo-iso-m2ts:count=3:chosen=00005.m2ts:58336057344
echo-iso-mpls:count=3:chosen=00003.mpls:items=1:duration=5627330:unique=5627330
echo-iso-streams:4352:zho,4353:eng,4354:zho,4768:eng,4769:zho,4770:zho
echo-iso-pgs-format ×3        echo-iso-bps:1739588:0:10150590
```

位置 0→100 秒正常推进,直到 `07:25:08.894` 报 4K Main10 解不动 —— 那是 §4.5 同一条解码边界,与解析无关。

**教训**:UDF 的"卷识别序列"是**一整个扇区序列**,不是"第 16 扇区的某个 magic"。所有"固定偏移找标记"的写法都会在混合盘上翻车,而且错误信息(`volume recognition missing`)会把人往"文件不是 ISO"上带。取证顺序也因此固定下来:**解析失败时先直接 `Range` 读 sector 16 起的一小段**,再谈盘坏没坏。

单测:`UdfVolumeTest.open_acceptsIso9660HybridRecognition`(+ `UdfImageBuilder(hybridRecognition = true)`)。

### 4.8 案例 H:《十面埋伏》4K DIY —— prepare 等不到音轨 Format,4 秒被掐死(2026-10-20 07:45 发现,08:13 修)

**盘的事实**:47.54 GiB、23 个 m2ts / 29 个 mpls;选中 `00024.mpls`(1 段、7,187,054ms)= `00027.m2ts`(39,139,885,056 B);需求 5,332,431 B/s。`echo-iso-streams:4352:zho,4353:zho,4354:zho,4355:zho,4608:eng,4609:zho,4610:zho` ⇒ **4 音轨 + 3 PGS + 1 视频 = 8 条轨**。

**症状**:

```
07:45:30.334  echo-exo-player-error: code=ERROR_CODE_FAILED_RUNTIME_CHECK,
              cause[0]=StuckPlayerException: Player stuck buffering and not loading for 4000 ms
```

此前只有 `echo-iso-pgs-format ×3`、`echo-iso-seek`(尾读)、`echo-iso-bps`,**没有一条 `echo-exo-selector`** —— 不是解码问题,是 prepare 没完成,而且是被 media3 内部计时器掐的。

**三个根因(逐条都有源码位置)**:

| # | 事实 | 位置 |
|---|---|---|
| 1 | `TsExtractor` 在 PMT 解析完就 `output.endTracks()`,声明窗口关闭 | `TsExtractor.java:838` |
| 2 | `maybeFinishPrepare()` 要求**每条已声明轨都有 Format**,任一为 null 就 return | `ProgressiveMediaPeriod.java:979` |
| 3 | 本盘 4353/4354/4355 的真实 Format 要到 **48 MB 之后(≈3.7 s)** | 实测 |
| 4 | 掐断的是 `PLAYBACK_STUCK_AFTER_MS = 4000`,**private static final,硬编码** | `ExoPlayerImplInternal` |

**先踩过的坑**:`ExoPlayer.Builder.setStuckBufferingDetectionTimeoutMs(20000)` 改的是另一条路径(默认 **600000**),既拦不住 4000 ms、又把 10 分钟收紧成 20 秒;改完日志完全一样,已回退。**两个计时器不是一回事,别改错。**

**为什么不能"纯懒声明"**:`prepareTrackOutput()` 在 `endTracks()` 之后遇到新 TrackId 直接返回 `DiscardingTrackOutput`(并打 `Extractor added new track ... after finishing tracks.`)⇒ 8 条轨全丢、prepare 以 0 轨完成 ⇒ 必然黑屏。

**最终方案(三件套)**:

1. `PrimaryVideoExtractor` → **延迟声明门**:首次 `init` 扣住 `endTracks()`,轨只有拿到 Format(或写出 sample data)才 `output.track()`;放行 = 全部落地 / EOF / **有音视频 Format 且等满 1.5 s**;视频没 Format 不放行;第二次 `init` 退回立即声明。
2. `DeferredTrackOutput.sampleData` 一旦被调用就**立即落地**(不再丢字节)。
3. `ProvisionalFormatReader` + 工厂按 stream type 发**临时 Format**(LPCM→`audio/raw` 48k 2ch;TrueHD/AC3→`audio/ac3` 48k 6ch;EAC3→`audio/eac3`;DTS→`audio/vnd.dts`),真实 Format 到达覆盖。

**中途自己引入又修掉的两个 bug(这是本轮最值钱的部分)**:

- **`off=-165, len=1442 out of bounds (size=65536)`**:第一版延迟声明把视频在拿到 Format **之前**的 165 字节丢了,而 `H264Reader.consume()` 是"先 `output.sampleData(data, data.bytesLeft())`、后发 Format"(`H264Reader.java:139`),它自己的 `totalBytesWritten` 照算 ⇒ `SampleQueue.sampleMetadata` 的 `absoluteOffset = totalBytesWritten - size - offset`(`SampleQueue.java:712`)变成负值 ⇒ `SampleDataQueue.readData` 里 `ByteBuffer.put` 越界。**结论:包装 TrackOutput 可以延迟声明,但绝不能扣住数据。**
- **主音轨被丢、全片无声**:08:08 那轮 1.5 s 到点把 4352 也丢了(`track-drop:4352` 之后 80 ms 就来了 `track-late:4352`),`audio_flinger` 这轮 0 次 `AT::add`。原因就是"时间窗"判不了"晚到但真实"—— 这正是改用临时 Format 的直接原因。

**修复后(08:13)**:

```
echo-iso-track-defer:declared=8            ← 全程没有 track-drop
echo-exo-selector: mime=video/avc count=2  echo-exo-codec-init: OMX.google.h264.decoder
echo-iso-lpcm-format: audio/raw sr=48000 ch=6     ← 真实 Format 覆盖临时 Format
AT::add  sr=48000  mask=0x3F(5.1)  session=257    ← 出声
```

8 条轨全保留(与 STN 的 `zho×4` 一致)、约 1.1 s 起播、无 stuck / 无越界、音频 5.1/48kHz。

**边界与残留**:临时 Format 是猜的(48k / 5.1 或 2ch),真实 Format 到达会重配一次;某轨全程无数据时列表里会多一条静音轨(不再丢轨,但选中没声);"起播未完成就 seek"这种边角仍走旧的立即声明;`ISO_SLICE` 门控的 `IsoSliceHarnessTest` 报告里 `endTracks` 行可能不出现(Robolectric 时钟不推进),它只诊断不断言。

**新增日志**:`echo-iso-track-defer:declared=N`、`echo-iso-track-drop:<pid>:<type>`、`echo-iso-track-end:declared=N:dropped=N`、`echo-iso-track-late:<pid>:<type>`(前缀都在 `echo-iso` 白名单内)。

单测:`PrimaryVideoExtractorTest` 5 → 17、`BdTsPayloadReaderFactoryTest` 8 → 13;全量 **1281 / 0 失败 / 3 跳过**。

---


---

## 乙、缺陷档案(2026-10-20 五台实盘 + 4K 大盘,原 spec §14)

## 14. 超大盘与 4K 原盘:真实缺陷全记录(2026-10-20 06:00-08:15)

### 14.0 五台实盘与取证方法

| | 盘 A(40.19 GB,港版) | 盘 B(64.4 GB,4K) |
|---|---|---|
| 文件 | `[北京遇上西雅图之不二情书 Book of Love 2016][港版原盘 中英文字幕][TTG][40.19GB].iso` | `[SGNB-126][冰雪奇缘 Frozen 2013][4K DIY …].iso` |
| 真实大小 | 43,152,703,488 B | `STREAM` 合计 64,433,473,536 B(60.01 GiB) |
| m2ts | 13 个 | **87 个** |
| mpls | 9 个 | **68 个** |
| 选中 | `00001.mpls`,1 段,7,795,666ms | `00020.mpls`,**302 段**,15,509,409ms |
| 主片 | `00003.m2ts` = 41,750,980,608 B | `00071.m2ts` = 29,941,518,336 B(28 个 extent) |
| 需求带宽 | 5,244,089 B/s | 5.89MB/s(按总长折算) |

随后又到手两台 4K 盘(§14.10、§14.11 的主角):

| | 盘 C(4K 杜比视界双层,49.19 GiB) | 盘 D(4K DIY《猎凶风河》,73.67 GiB) |
|---|---|---|
| 文件 | `[SGNB-126]…[4K DIY]…iso` | `[猎凶风河 2017][4K DIY 简繁双语特效字幕][Dolby Vision HDR10 Atmos 7.1][BHYS@OurBits].iso` |
| 真实大小 | 52,813,692,928 B | 79,103,066,112 B |
| m2ts / mpls | 10 / 8 | 12 / 10 |
| 选中 | `00006.mpls`,1 段,4,853,390ms | `00001.mpls`,1 段,6,428,630ms(修前选中 `00100.mpls`,253 段) |
| 主片 | `00009.m2ts` = 49,880,795,136 B | `00000.m2ts` = 77,659,410,432 B(73 个 `long_ad` 且首尾相接 ⇒ 合并成 1 个 run) |
| 需求带宽 | ≈10.28MB/s | **≈11.83MB/s** |

第五台(§14.12 的主角)与前四台的最大差别不在 4K,而在**UDF 是老版本、且是 ISO9660 混合盘**:

| | 盘 E(4K DIY《最佳拍档》1982,54.61 GiB) |
|---|---|
| 文件 | `[最佳拍档.1982][4K.美Shout.Factory版原盘.DIY.国配简繁中字][Dolby.Vision_HDR10.DTS-HDMA2.0][BHYS@OurBits][54.61G].iso` |
| 真实大小 | 58,636,980,224 B |
| m2ts / mpls | 3 / 3(单分区,起于 sector 257,28,630,931 块) |
| UDF | **NSR02(UDF 1.02)** + ISO9660 混合布局,无 metadata 分区 |
| 选中 | `00003.mpls`,1 段,5,627,330ms(93.8 min) |
| 主片 | `00005.m2ts` = 58,336,057,344 B(**short_ad**,55 个 extent,物理连续 ⇒ 1 个 run) |
| 需求带宽 | ≈10.15MB/s |

取证方式:**Quark 直链 + HTTP `Range` 独立走一遍 UDF**(`.iso_forensics/udfprobe5.py`、`mplprobe1.py`、`mplprobe2.py`),再与应用内 `echo-iso-*` 双向对照。本节记录的缺陷里有三个(碎片 run、FID 跨块、快照版本)是"应用日志与直读结果不一致"才暴露的 —— 单看应用日志会误判成"盘有问题"。

盘 C / 盘 D 改用了一条更省的办法:**直接拉设备上的目录快照**,不再整趟走 UDF。

```
adb exec-out run-as com.github.avbox.osc cat cache/iso-volume/<总长>_<前4KB哈希>.snap
```

> 快照自 2026-10-20 22:40 起改存 `files/iso-volume/`(§13.6),上面是当时的路径;`cache/iso-volume` 会被首次访问清掉。另注意:BD 盘前 4KB 常为全零,`<前4KB哈希>` 恒为 `ad7facb2…`(= `sha256(4096B 0)`),真正区分盘的是 `<总长>`。

按 `IsoVolumeSnapshot` 的格式(`MAGIC/VERSION/目录数` → 每个条目 `name/directory/length/extents/inline`)解开,就得到每个 m2ts/mpls/clpi 的 `extents` **以及内联内容**(≤64KB 的文件整体存在快照里,多数 mpls、clpi 都在其中),只有少数大 mpls 需要再发一次 `Range`。脚本:`.iso_forensics/snapprobe.py`(看结构)、`snapdump.py`(导出内联文件)、`rankcheck.py`(逐条播放列表算"声明时长 vs 去重时长")、`discD_mpls.py`(按快照给的 offset 拉大 mpls 并解析 play item/STN)。

盘 E 没有快照可用(解析在建立快照之前就失败了),只能回到最原始的办法:直接 `Range` 读 sector 16 起的 21 个扇区看出布局,再用 `.iso_forensics/discG_probe.py` 把 UDF 走一遍列目录与播放列表 —— **根因就是靠这两步定死的**,只看应用日志只会得到一句 `volume recognition missing`。

### 14.1 碎片 m2ts:一个文件多个物理 run(盘 A)

`00003.m2ts` 在 UDF 里是 **40 个 `long_ad`**:39 处首尾相接,1 处 448 KiB 间隙 ⇒ 合并成 **2 个 run**:

```
476905472 + 24473567232      (00003.m2ts 前半,约 22.8 GiB)
24950931456 + 17277413376    (后半,约 16.1 GiB)
和 = 41750980608 = 文件声明长度(精确相等)
```

旧实现要求"extents 必须物理连续",直接抛 `m2ts has no extents: 00003.m2ts` —— 40 GB 的盘根本起不来。

改法:

- `IsoLocator.coalesceRuns(extents)`:相邻(`off+len == next.off`)或重叠即合并,丢弃零长/空 extent;`IsoPlayTarget.runs` 持有 run 表,`m2tsOffset` 退化为 `runs.first().offset`(兼容旧用法);
- `IsoDataSource.isoReadWindows(position, length)` 在 run 边界切窗,一次 `read()` 可跨多个 run;状态机 `windows/windowIndex/windowRemaining/windowDelivered`,跨窗短读即判流结束(`bytesRemaining = 0`);
- 校验:run 总长 ≥ 文件声明长度、`runs.size <= MAX_TARGET_RUNS (256)`;
- 日志:`echo-iso-target-runs:<name>:<n>:<off>+<len>,…`、`echo-iso-target-skip:<name>:reason=…`。

实测(06:30:14 窗口,1x 播放 25 次进度采样无回退):

```
echo-iso-target-runs:00003.m2ts:2:476905472+24473567232,24950931456+17277413376
echo-iso-bps:20897625:16919936:5244089          ← 窗口 20.9MB/s、累计 16.9MB/s、需求 5.24MB/s
```

### 14.2 BD `stream_type` 与 MPEG-TS 的编号碰撞(盘 A)

蓝光的 `stream_type` 是**自己的编号**,和 MPEG-TS 注册表撞了三个:

| BD `stream_type` | BD 语义 | media3 的对应值 | 默认映射的实际后果 |
|---|---|---|---|
| `0x82` | DTS | `130`(`HDMV_DTS`) | 需 `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS`,默认不出声 |
| `0x84` | E-AC-3 / DD+ | `135`(`E_AC3`) | `DefaultTsPayloadReaderFactory` 返回 **null** ⇒ 该轨静默消失 |
| `0x85` | DTS-HD HRA | `136`(`DTS_HD`) | 同 0x84 |
| `0x86` | DTS-HD **MA** | ⚠️ `134` = `TS_STREAM_TYPE_SPLICE_INFO`(SCTE-35) | 喂给 `SpliceInfoDecoder` ⇒ **崩抽取器** |

`0x86` 的症状是 `IllegalStateException: null`(栈顶在 `SpliceInfoDecoder.decode`),从 media3 的错误里完全看不出和音频有关 —— 这也是本轮最难定位的一条。

改法:`BdTsPayloadReaderFactory` 改成**显式表**,不再依赖默认映射:

| `stream_type` | reader |
|---|---|
| `0x80` | `LpcmReader`(受 `player_iso_lpcm` 控制) |
| `0x81` / `0x84` | `Ac3Reader(language, roleFlags, MimeTypes.VIDEO_MP2T)` |
| `0x82` | `DtsReader`(受 `hdmvDtsEnabled` 控制) |
| `0x83` | `Ac3CoreReader`(TrueHD 取 core,见 §5-A1-10) |
| `0x85` / `0x86` | `DtsReader(… EXTSS_HEADER_SIZE_MAX = 4096 …)`,`echo-iso-dts-hd:type=…` |
| `0x90` | `PgsReader` |

这一条同时**关闭 §6.1 缺口 13**(当时只知道 0x86 被当 SCTE-35,判"需第二样本复核"):第二样本就是本盘,确认 BD 用 0x86 承载 DTS-HD MA。

### 14.3 语言在 mpls 的 STN 表,不在 PMT

BD 的 PMT **通常不带 ISO_639 描述符**,语言只存在于 `PLAYLIST/*.mpls` 的 `STN_table` 里,所以原实现只能显示「字幕1 APPLICATION/PGS」「音轨1 DTS」。

布局(以盘 A 实盘对齐;其余盘未单独核对语言):

- `STN_table` 头 **14 字节** = `u16 length` + 2 reserved + **7 个计数**(video / audio / PG_textST / IG / secAudio / secVideo / pipPG)+ 5 reserved;
- 每条流 = `stream_entry`(`u8 len` + `u8 type` + `u16 pid` + pad)+ `stream_attributes`(`u8 len` + `u8 coding` + …);
- **语言的偏移因流类型而异**:音频 = `attrStart + 2`(coding + format + lang),PG = `attrStart + 1`(coding + lang)。
  踩坑:按同一偏移读音频,`jpn` 会被读成 `0jp`(`MplPlaylistTest` 的 `expected:<jpn> but was:<0jp>` 就是它)。

管道:`MplPlaylist.readStnStreams` → `IsoPlaylistState.publishStreams / languageForPid(pid)` → `BdTsPayloadReaderFactory(languageForPid = …)` → `LanguageOverrideReader`。

`LanguageOverrideReader` 的关键实现约束:

- 覆盖必须发生在 `ExtractorOutput.track(id, type)` 里(此时 `id` 就是 PID),**不能**在 `createTracks` 之前读 `TrackIdGenerator.getTrackId()`;
- `TrackIdGenerator` 是 `final class`,且 `getTrackId()` 在 `generateNewId()` 之前会抛 `IllegalStateException: generateNewId() must be called before retrieving ids.` —— 第一版包装器就崩在这里,表面症状是 `Source error`,和语言毫无关系。已补回归测试(构造真实 `TrackIdGenerator`、不调 `generateNewId()`);
- media3 的 `Format.Builder.setLanguage()` 会把 ISO 639-2 归一化成 2 字母(`jpn→ja`、`eng→en`),测试断言按归一化后的值写。

实盘(盘 A):`echo-iso-streams:4352:zho,4353:zho,4608:zho,4609:eng` ⇒ 菜单 `[音轨1 - 国语 AC3 / 6 声道, 音轨2 - 国语 VND.DTS.HD / 8 声道]`。

**已知限制**:BD 普遍把国语/粤语都标 `zho`,两条音轨会同时显示"国语";`Format.language` 最终是 2 字母。

### 14.4 字幕轨名字:`application/x-media3-cues` 的 codecs 才是源格式

media3 的官方字幕转码会把 PGS 包成 `mime = application/x-media3-cues`,**源 mime 放在 `codecs` 字段**;旧实现把 `codecs`(`application/pgs`)原样大写,于是菜单出现「字幕1 APPLICATION/PGS」。

改法:新增 `SubtitleFormatName.subtitleFormatName(mime)` → `PGS` / `VobSub` / `DVB` / `ASS` / `SRT` / `WebVTT` / `TTML` / `CEA-608` / `CEA-708` / `SAMI`;`application/x-media3-cues` 返回空串,由调用方改用 `codecs`;仍取不到就退回 `label`。`EngineTrackSelection.getSubtitleName()` 负责去重。测试 `SubtitleFormatNameTest` 4 条。

### 14.5 字幕层必须贴视频矩形(位图 cue 会被拉伸)

media3 摆放**位图** cue 用的是"视图尺寸的比例",所以字幕视图比视频矩形宽时,PGS 会被横向拉伸 —— 实测 `1039/832 = 1.249 = 2800/2240`(窗口宽 2800,视频矩形 2240),同时被多放大 1.458x 而不是 1.167x,于是又糊又扁。

改法:

- `PlayerUiState.videoRect`(`VideoRect(left, top, width, height)`);
- `PlayerSurfaceHost` 用 `Modifier.videoRectLayout(rect)` 把字幕视图钉在视频矩形上(`Constraints.fixed(w, h)` + 外层 `layout(hostW, hostH) { placeable.place(rect.left, rect.top) }`);`lyricView` 保持 `fillMaxSize()`;
- 矩形来源:`AppPlayerView.addDisplay()` 给 render view 挂 `addOnLayoutChangeListener`,上报后打 `echo-render-rect:<l>,<t>:<w>x<h>`。

效果:字形宽高比 **1.23–1.35 → 0.92–1.08**;锯齿梯度 p99 **87 → 107**(FongMi 参考值 97)。

两个注意点:

1. **文本** cue(外挂/内封 SRT、ASS)由 TTF 重新栅格化,本不受影响;但两套字幕视图(`exoSubtitleView` 走 PGS/位图、`subtitleView` 走文本)都钉到了同一矩形上,避免两套字幕位置不一致;
2. `LOG.fileLog` 是**前缀白名单**(`FILE_LOG_PREFIXES`):第一版用了 `echo-video-rect`,前缀不匹配 ⇒ 日志被静默丢弃,表现成"改了没生效"。改名 `echo-render-rect`(前缀 `echo-render`)后才可见。**新增日志前缀必须先查白名单**。

### 14.6 UDF 目录的 FID 可以跨逻辑块(盘 B,2026-10-20 06:35 修)

症状:`echo-iso-m2ts:count=33`,而直读是 **87** 个;`PLAYLIST` 同样 33 vs 68。于是 302 段的 `00020.mpls` 里第一个 clip `00040.m2ts` 不在名单里 ⇒ `echo-iso-playlist-skip:…:reason=segment-missing` ⇒ 退回"可见的最大 m2ts" = **6.5 MB 的片头**,播放器从头到尾没在放正片。

根因:`UdfVolume.parseDirectory` 按 ECMA-167「FID 不得跨逻辑块」做了强制对齐。本盘 `STREAM` 目录 5260 字节 = 父项 40B + 87×60B,第 34 个 FID **起于 2020、长 59,跨过 2048 边界**:

```
data[2000:2100] = 30 00 30 00 30 00 33 00 32 00 2e 00 6d 00 32 00 74 00 73 00
                  01 01 03 00 48 00 …            ← 2020 处直接是下一个 FID 标签(0x0101)
```

顺序读 88 条全在;按块对齐就断在 33。真实播放器(libudf / libbluray / FFmpeg)**都不做这个假设**。

改法:顺序读;**仅当该处不是 FID 标签时**,才跳到下一个块头并确认那里是 FID(这样"块尾留白"的正常盘照样能跳过去,不会把填充字节当 FID)。回归测试 `UdfVolumeTest.list_keepsFidsThatCrossBlockBoundaries`(测试镜像构造跨块目录 50 条;修前 39 条)。

修复后(06:43:55):

```
echo-iso-m2ts:count=87:chosen=00071.m2ts:29941518336
              top=00071:29941518336,00085:13443121152,00061:5528887296,00072:4964653056,…
echo-iso-playlist:00020.mpls:segments=302:duration=15509409:total=91336390656:first=00040.m2ts
echo-iso-target:00040.m2ts:143243264:324624384
echo-iso-playlist-append:301:total=302:duration=15509409
```

`total=91336390656` 比整盘 STREAM(64.4 GB)还大,因为 302 段里有大量 clip 被重复引用 —— 这是**各段长度之和**,不是要去重读的字节数。

### 14.7 派生缓存必须带解析器版本(2026-10-20 06:43)

装完修好的 APK 复测仍是 `count=33`,日志给了答案:

```
06:41:27.058  echo-iso-cache-hit:5:1        ← 命中了 06:31 用旧解析器建的快照
```

`IsoVolumeSnapshot` 把整棵目录树(目录 → 条目清单)序列化到 `cacheDir/iso-volume/*.snap`,键是"总长度 + 前 4KB 指纹" —— **文件没变、键就不变**,旧解析结果被原样回放,新代码根本没跑。

改法:`IsoVolumeSnapshot.VERSION` **1 → 2**。旧文件 `decode` 失败(版本不符)⇒ `echo-iso-cache-decode-fail:<size>` ⇒ 删文件 ⇒ 走真实 UDF 重建。回归测试 `decode_rejectsStaleSnapshotVersion`。

教训(通用):**目录快照是派生数据,解析语义一改就必须整体失效**。否则用户机器上会长期呈现"改了没用",而代码、测试、APK 全都对 —— 本轮在这上面白跑了一轮。

### 14.8 4K《冰雪奇缘》的解码错误:不是误报

修复后同一张盘的最后一条:

```
echo-exo-player-error: code=ERROR_CODE_DECODING_FAILED
  format=… video/hevc, hvc1.2.4.H153.B0,
          [3840, 2160, … ColorInfo(BT2020, Limited range, ST2084 PQ, false, 10bit Luma, 10bit Chroma)]
  format_supported=NO_EXCEEDS_CAPABILITIES
  cause[0]=MediaCodecVideoDecoderException: Decoder failed: OMX.google.hevc.decoder
```

判定依据是模拟器自己的声明能力(`/system/etc/media_codecs_google_video.xml`):

```xml
<MediaCodec name="OMX.google.hevc.decoder" type="video/hevc">
    <!-- profiles and levels:  ProfileMain : MainTierLevel51 -->
    <Limit name="size" min="2x2" max="4096x4096" />
```

只到 **ProfileMain(8bit)**;片子是 **Main10** + 4K + HDR10(`ST2084 PQ`)。`NO_EXCEEDS_CAPABILITIES` 是 media3 拿 `MediaCodecInfo` 的 profileLevel 卡出来的,**结论正确,不是误报**;这行提示该保留。

两个必须写清的边界:

1. 这是**模拟器软件解码器**的上限,真机硬解(`c2.qti.hevc.decoder` 等)通常吃 4K Main10 —— 真机未验;
2. 本次 `preferSoft=true`(播放配置里的"软解码")**强制软解**,真机开着它一样会撞同一条。是否改成"软解超能力时这一次自动回退硬解"→ **待拍板**(未实现)。

### 14.9 本轮验收与未覆盖

- 单测 **1264 通过 / 0 失败 / 3 跳过**(本轮起点基线 1251);`null_assert_gate` PASS;i18n 剩余 44(与基线一致);`assembleDebug` 通过。
  新增的 13 条:跨块 FID、旧版快照拒绝(§14.6/§14.7),`PrimaryVideoExtractorTest` 5 条 + STN 主视频 PID 2 条 + `IsoSelection` 发布/清空 1 条(§14.10),去重时长选片 1 条 + 跨段流去重 1 条(§14.11),ISO9660/UDF 混合盘 VRS 1 条(§14.12)。另**修改**了 `readsStreamLanguagesAcrossPlayItems` 的期望值(去重后 4→3),这是行为修正,不是放宽断言。
- 已验证:盘 A 完整播放(LPCM 之外的两条音轨 + 两条字幕语言正确);盘 B 选片正确(87/68/302 段/first=00040);盘 C 的 DV 增强层被丢弃(07:00);盘 D 选片 + 轨道表正确(07:09,见 §14.11);盘 E 解析打通、选片/轨道表正确(07:25,见 §14.12)。
- **未验证 / 未覆盖**:
  1. **302 段的运行时切段**没证到 —— 解码先挂,`playlist-append:301` 只说明段表建好;8 段的《BanG Dream》已真机跑通,机制同一套;
  2. 4K Main10 的**硬解**只能真机验(盘 C/D/E 三张 4K 全部卡在这一条);
  3. 4K 盘的 PGS 跨段(片头 30s 内无字幕);
  4. 稳态吞吐(盘 A 5.89MB/s、盘 D 11.83MB/s、盘 E 10.15MB/s 都只是折算值,未连续跑 2-3 分钟);
  5. ~~杜比视界双层盘的设备端验证~~ —— **已通过(07:00)**,见 §14.10;仅剩「真机硬解 BL」未验;
  6. **盘 B 没做过诱饵审计**:它的 `00020.mpls` 是 302 段、15,509,409ms,但这台盘当时脚本没跑通(元数据分区解析失败),`unique` 未核对。§14.11 的修法对"段全不重复"的播放列表不改变其排名,但如果盘 B 本身也含重复段,它的 `duration` 会大于 `unique` —— 需重播一次看这两个数是否相等才能定性;
  7. **只支持 physical / metadata 两种分区映射**:虚拟分区(2.00 的 VAT)、sparable 分区(可擦写盘)没实现,`buildPartitions` 遇到会抛 `unsupported partition map`。五台盘都是只读母盘,暂未遇到。


### 14.10 杜比视界双层盘:增强层不能当视频轨(2026-10-20 06:48 发现,当日修)

盘:`[SGNB-126][冰雪奇缘…]` 之外的第二张 4K 盘,49.19 GiB、10 个 m2ts、8 个 mpls,选中 `00006.mpls`(1 段、4,853,390ms),需求 ≈10.28MB/s(目前最高)。

**事实(离线直读 PMT + SPS + STN,三条互相印证)**:

| PID | PMT | SPS 实测 | STN |
|---|---|---|---|
| `0x1011`(4113) | `stream_type=0x24`,reg `HDMV ff 24 81 3f` | Main10 / L5.1 / **3840×2160** / 4:2:0 | **video count=1,就是它** |
| `0x1015`(4117) | `stream_type=0x24`,reg `HDMV ff 24 61 3f` | Main10 / L5.1 / **1920×1088** | 不在 STN 里(`secVideo=0`) |

`0x1015` 是 **Dolby Vision 增强层(EL)**:1080p 残差 + DV 元数据,**不是一张完整画面**。任何播放器都不会单独解它 —— 单独解出来是灰块/噪点。

**症状**:`echo-exo-player-error … format=Format(1/4117, …, [1920, 1080, 10bit Luma, 10bit Chroma])` —— 送去解码的是 `0x1015`(EL),不是正片。

**为什么**:media3 只看 PMT,两路都是 `0x24`(HEVC) ⇒ 注册成两条候选视频轨。本机 `OMX.google.hevc.decoder` 只到 ProfileMain,4K 主层被能力过滤掉,选择器退而求其次拿了 1080p 那条。真机上 4K 主层能被选中时通常不会错,但「支持 1080p Main10、不支持 4K Main10」的设备会静默播 EL。

**修法(按盘上的规格,不猜)**:

1. `MplPlaylist` 读 `STN_table` 时顺便取出 **primary video PID**(`videoCount` 区段第一条 in-mux 记录),放进 `IsoPlaylist.videoPid`;
2. `IsoLocator` 选中播放列表后 `IsoPlaylistState.publishVideoPid(...)`(fallback 分支发布 null;`reset()` 清空);
3. 新增 `PrimaryVideoExtractor`:包住 `TsExtractor`,在 `ExtractorOutput.track(id, type)` 处丢弃 **非主视频** 轨(返回一个只排空、不发 Format 的 `TrackOutput`,因此不会有一条永远没有 Format 的队列把 prepare 卡死 —— 见 §6.1-20);
4. 安全闸:只有**主 PID 已经被声明过**才丢弃其它视频轨。PMT 顺序通常是主层在前(本盘 0x1011 在前);万一 STN 与 PMT 不一致或顺序相反,退化为旧行为,绝不会出现"一个视频轨都没有";
5. 日志:`echo-iso-video-skip:<pid>:primary=<pid>`。

**边界(必须写清)**:我们这条链路**拿不到真正的杜比视界效果** —— media3 不解 DV 的 RPU。修完后是「解 BL,当 HDR10 播」,与 Kodi/FongMi 对非 DV 设备的处理一致;EL 全程丢弃。

**验证(2026-10-20 07:00 设备复测通过)**:

```
07:00:12.041  echo-iso-video-skip:4117:primary=4113          ← EL 被丢弃,只丢一次
07:00:13.429  echo-exo-player-error … Format(1/4113, …, [3840, 2160, … 10bit …])
                                            NO_EXCEEDS_CAPABILITIES
```

即:送解码的已从 `0x1015 / 1920×1080` 变成 `0x1011 / 3840×2160`;解码本身仍失败(模拟器 `OMX.google.hevc.decoder` 只到 ProfileMain),**符合预期**。同一窗口另有 5 条 `echo-iso-pgs-format` + 5 条 `echo-iso-pgs:first`、`echo-iso-dts-hd:type=134`,`echo-iso-streams` 与 STN 逐项一致。

单测:`PrimaryVideoExtractorTest` 5 条、`MplPlaylistTest.readsPrimaryVideoPidFromStn`/`keepsVideoPidNullWithoutStn`、`IsoSelectionTest.playlistStatePublishesPrimaryVideoPidAndClearsOnReset`。

### 14.11 播放列表诱饵:253 段其实是同一段(盘 D,2026-10-20 07:10 修)

盘:`[猎凶风河 2017][4K DIY 简繁双语特效字幕][Dolby Vision HDR10 Atmos 7.1][BHYS@OurBits].iso`,79,103,066,112 B(73.67 GiB)、12 个 m2ts、10 个 mpls。正片 `00000.m2ts` = 77,659,410,432 B,73 个 `long_ad` 且段段首尾相接 ⇒ 合并成 **1 个 run**。

**盘的三种播放列表(直读 ISO,不经应用)**:

| mpls | 段数 | 声明时长 | 去重时长 | 实际内容 |
|---|---|---|---|---|
| `00001.mpls` | 1 | 6,428,630ms | 6,428,630ms | `00000.m2ts`,正片(107:08) |
| `00100.mpls` | 253 | 13,886,703ms | **109,776ms** | 首段 `00007.m2ts`,其余 252 段全是同一段 `00002.m2ts`、in/out 完全相同(`27,000,000 → 29,469,967` = 54.888s) |
| `00101.mpls` | 253 | 13,886,703ms | **54,888ms** | 253 段全同一段 |

**症状**:选中 `00100.mpls` ⇒ 时长 3:51:27、每 55 秒跳回开头、**没有字幕、没有 TrueHD**;`echo-iso-streams` 打出 253 个 `4352:eng`;`echo-iso-pgs-format` 一条都没有。

**根因 1(选片)**:`pickLongestPlaylist` 是 `maxByOrNull { durationMs }`。诱饵靠"重复计数"把 109.8s 吹成 231min,压过真正片的 107min。这张是 BD-J 盘(有 `JAR/`、`BDJO/`),真正的选片写在 Java 菜单里,静态读不到;`MOVIE_OBJECT` 的 `PlayPL` 也用不上 ⇒ **只能靠启发式,而启发式手里只有一个数字时,一个重复段就能骗过它**。

**根因 2(轨道表)**:`readStnStreams` 按 play item 逐段累加到同一个 `streams` 列表。重复段就把同一条音轨加 253 次,`IsoPlaylistState.publishStreams` 拿到的是脏表(按语言标注轨道的逻辑会看到重复项)。

**修法**:

1. 新增 `uniqueDurationMs(playlist)`:同一 `(clipId, inTimeUs, outTimeUs)` 只计一次时长;`pickLongestPlaylist` 改按它排序。
2. `dedupeStreams()`:`parseMplPlaylist` 返回前按 `(pid, language)` 去重。
3. 日志加 `unique=`: `echo-iso-mpls` 的 `chosen` 与 `top=` 每项、`echo-iso-playlist` 都带上,以后一眼能看出诱饵。

**为什么不把 `IsoPlaylist.durationMs` 也砍成去重值**:这个值进的是播放器时间轴。段真的重复(极少见)时,时间轴按盘上实际段长才对;选片是"挑哪条",时间轴是"这条多长",两件事不能共用一个数。所以只有选片用去重值。

**对另外三张盘的影响(逐盘核过)**:盘 A、盘 B 的段互不重复,`unique == duration`,排名不变;盘 C 本来就有一条 10 段重复的诱饵 `00001.mpls`(声明 600s / 去重 60s),只是它短于 `00006.mpls`(4853s)而没被选中 —— 修完依然选 `00006.mpls`。

**残留风险(必须写清)**:这条规则防的是"完全重复的段"。如果诱饵改成每段 in/out 各偏 1 tick,或改用 253 段互不相同的短片,去重时长就不再有效。**根治要靠让用户能选播放列表**(按 ISO 身份记住选择),本次**没做**(用户 2026-10-20 拍板"暂时不做保底")。

**验证(2026-10-20 07:09 设备复测通过)**:

```
07:09:54.858  echo-iso-m2ts:count=12:chosen=00000.m2ts:77659410432
07:09:55.422  echo-iso-mpls:…:chosen=00001.mpls:items=1:duration=6428630:unique=6428630:…
                     top=00001.mpls=1:6428630:6428630;00100.mpls=253:13886703:109776;
                         00300.mpls=1:102102:102102;00101.mpls=253:13886703:54888;…
07:09:55.424  echo-iso-playlist:00001.mpls:segments=1:duration=6428630:unique=6428630:
                     total=77659410432:first=00000.m2ts
07:09:55.425  echo-iso-streams:4352:eng,4353:eng,4768:eng,4769:eng,4770:spa,
                     4771:zho,4772:zho,4773:zho,4774:zho
07:09:55.652  echo-iso-truehd-core: lang=null
07:09:55.654  echo-iso-video-skip:4117:primary=4113
07:09:55.661  echo-iso-pgs-format            ← 共 7 条
07:09:57.175  echo-iso-bps:740041:0:11828570
```

即:诱饵仍在 `top=` 里,但 `unique=109776 / 54888` 让它排到正片之后;播放对象从"490MB 花絮 + 250 段循环"变成 `00000.m2ts` 正片;轨道表 9 条无重复、7 条 PGS、2 条 TrueHD;需求带宽 11.83MB/s(四张盘里最高)。解码仍在 `07:09:57.614` 报 `NO_EXCEEDS_CAPABILITIES`,Format 为 `1/4113 … 3840×2160 10bit BT2020 PQ` —— 模拟器 `OMX.google.hevc.decoder` 只到 `ProfileMain`,与 §14.8 同因,与选片无关。

另一处顺手确认:`07:09:55.766 echo-iso-seek:76040378048` 恰好等于「该段逻辑长度 76,041,506,048 − 1,128,000」,与盘 C 的 `48,840,483,904`(= 48,841,611,904 − 1,128,000)同一规律 ⇒ 这是 media3 打开后自己发的一次**尾读**,不是我们的 seek 路径,也不是缺陷。

单测:`MplPlaylistTest.picksPlaylistByUniqueDurationNotRawDuration`、`dedupesStreamsRepeatedAcrossPlayItems`。

### 14.12 ISO9660 + UDF 混合盘:VRS 不在 sector 16(盘 E,2026-10-20 07:18 发现,07:21 修)

盘:`[最佳拍档.1982][4K.美Shout.Factory版原盘.DIY.国配简繁中字][Dolby.Vision_HDR10.DTS-HDMA2.0][BHYS@OurBits][54.61G].iso`,58,636,980,224 B(54.61 GiB)、**3 个 m2ts / 3 个 mpls**:正片 `00005.m2ts`(58.34 GB);另有 193 MB 的 `00001.m2ts`(55.1s,正是诱饵 `00001.mpls` 重复 100 次的那段)和 106 MB 的 `00003.m2ts`(33.5s,即 `00002.mpls`)。

**症状**:点播 4 秒后

```
07:18:18.641  echo-iso-play: format=video/x-iso
07:18:19.683  echo-iso-error:volume recognition missing
07:18:19.696  echo-exo-player-error: code=ERROR_CODE_IO_UNSPECIFIED,
              cause[0]=UnexpectedLoaderException: Unexpected IsoFormatException: volume recognition missing
              cause[1]=IsoFormatException: volume recognition missing
```

连 `echo-iso-udf-vrs` 都没有,更没有 m2ts/playlist —— **UDF 根本没打开**。autoRetry 连试三次,每次都是同一句。

**盘的实际布局(直读 sector 16..20)**:

| 扇区 | 偏移 | 内容 |
|---|---|---|
| 16 | 32768 | `\x01CD001` ISO9660 主卷描述符 |
| 17 | 34816 | `\xffCD001` ISO9660 卷描述符集结束符 |
| 18 | 36864 | `\x00BEA01` |
| 19 | 38912 | `\x00NSR02`(UDF 1.02) |
| 20 | 40960 | `\x00TEA01` |

卷标 `ACES_GO_PLACES_1982_UHD_BLURAY_2`。这是 mkisofs 一类工具做的**桥盘**:ISO9660 与 UDF 两套目录树并存,ISO9660 的描述符占住了 16、17,把 UDF 的 VRS 挤到 18 起。

**根因**:`readVolumeRecognition()` 从 sector 16 开始扫,但一遇到不是 `BEA01`/`NSR0x`/`TEA01` 的描述符、且还没拿到版本号,就**立刻**抛 `volume recognition missing`。也就是说它只认"VRS 恰好从 16 开始"的纯 UDF 盘 —— 前十次测试的四张盘恰好全是这种,所以一直没暴露。

**修法**:按 ECMA-167 的 VRS 语义扫**描述符序列**,而不是死盯 16:

- `CD001`(ISO9660 主/补充/结束符)→ 合法成员,**跳过**;
- `BEA01` → 空操作(只声明"后面有 UDF");
- `NSR02` / `NSR03` → 记版本(顺带把 UDF 1.02 的 `NSR02` 也接上);
- `TEA01` 或**全零扇区** → VRS 结束;
- 扫完 16 个扇区仍没拿到版本号,才报错。

新增 `isBlankSector()` 做结束判据,避免把后面的目录数据误读成描述符。

**为什么 UDF 1.02 不影响后续**:这张盘只有 **一个 physical 分区**(起于 sector 257、28,630,931 块),没有 metadata 分区(那是 UDF 2.50 才有的),`buildPartitions` 本来就能处理;`00005.m2ts` 用的是 **`short_ad`**(55 个 extent 拼出 58.3 GB),`UdfParser` 两种 AD 都支持,而且这 55 段首尾相接 ⇒ 合并成 1 个 run(所以没有 `echo-iso-target-runs`)。

**验证(2026-10-20 07:25 设备复测通过)**:

```
07:25:05.365  echo-iso-udf-vrs:NSR02
07:25:05.578  echo-iso-udf-avdp:32:32768
07:25:05.785  echo-iso-udf-lvd:1
07:25:05.992  echo-iso-m2ts:count=3:chosen=00005.m2ts:58336057344
07:25:06.614  echo-iso-mpls:count=3:chosen=00003.mpls:items=1:duration=5627330:unique=5627330
07:25:06.615  echo-iso-streams:4352:zho,4353:eng,4354:zho,4768:eng,4769:zho,4770:zho
07:25:07.277  echo-iso-pgs-format              ← 共 3 条
07:25:07.378  echo-iso-seek:57119594816:1128000   ← 尾读(57,120,722,816 − 1,128,000)
07:25:08.780  echo-iso-bps:1739588:0:10150590     ← 需求 10.15MB/s
```

位置 0→100 秒正常推进、`state=PLAYING`,直到 `07:25:08.894` 才报 4K Main10 解不动(Format `1/4113 … 3840×2160 10bit BT2020 ST2084 PQ`,与 §14.8 同因,与解析无关)。另外这张盘的 PMT 只有一路视频(全程没有 `echo-iso-video-skip`),所以文件名虽标 Dolby Vision,实际是**单层**,没有增强层可丢。

**残留**:见 §14.9 未覆盖 7 —— 只实现了 physical / metadata 两种分区映射,虚拟分区(VAT)与 sparable 分区没做。

**教训(通用)**:UDF 的"卷识别序列"是**一整个扇区序列**,不是"第 16 扇区的某个标记"。任何"在固定偏移找一个 magic"的写法,都会在混合盘/桥盘上翻车;而且它抛出的 `volume recognition missing` 会把人往"文件不是 ISO"上带,实际盘好得很。

单测:`UdfVolumeTest.open_acceptsIso9660HybridRecognition` —— 用 `UdfImageBuilder(hybridRecognition = true)` 造出 `CD001/CD001/BEA01/NSR02/TEA01` 的布局,断言日志里出现 `vrs:NSR02` 且目录可列出。

### 14.13 prepare 等不到音轨 Format:4 秒卡死(盘 J,2026-10-20 07:45 发现,08:13 修)

盘:`[十面埋伏 2004][德版原盘 满屏版 全景声国语 DIY 港版国语 TrueHD 7.1][47.54G]`,正片 `00027.m2ts` 39,139,885,056 B、23 个 m2ts / 29 个 mpls,选中 `00024.mpls`(1 段、7,187,054ms),需求 5,332,431 B/s。mpls 的 STN 表声明 **4 条音轨(4352–4355,全 `zho`)+ 3 条 PGS(4608 `eng`、4609/4610 `zho`)+ 1 路视频 = 8 条轨**。

**症状**:点播约 6 秒后

```
07:45:30.334  echo-exo-player-error: code=ERROR_CODE_FAILED_RUNTIME_CHECK,
              cause[0]=StuckPlayerException: Player stuck buffering and not loading for 4000 ms
```

此前只有 `echo-iso-pgs-format ×3`、`echo-iso-seek`(media3 的尾读)、`echo-iso-bps`,**视频轨连 `echo-exo-selector` 都没到过** —— 不是解码失败,是 prepare 没完成。

**根因(media3 1.11.1 源码 + 字节码双向核实)**:

1. `TsExtractor` 在 PMT 解析完立刻 `output.endTracks()`(`TsExtractor.java:838`,MODE_MULTI_PMT);
2. `ProgressiveMediaPeriod.maybeFinishPrepare()` 要求**每一条已声明的轨都有 Format**(`sampleQueue.getUpstreamFormat() != null`),任何一条没有就 return;
3. 这张盘的 4353/4354/4355 三条音轨,真实 Format 要到**约 48 MB 之后**(≈3.7 s)才出现 —— DIY 盘可以把"已声明的轨"的数据放到片头之后很久;
4. 真正掐死这次播放的是 `ExoPlayerImplInternal.PLAYBACK_STUCK_AFTER_MS = 4000`(**private static final,硬编码,无 API**)。

**先踩过的坑(必须记下来)**:`ExoPlayer.Builder.setStuckBufferingDetectionTimeoutMs(...)` 管的是**另一条**路径(`DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS = 600000`);把它设成 20 s 既拦不住这次,又把 10 分钟收紧成 20 秒 —— 改完日志一模一样(07:45:30 仍是 4000 ms),已回退。**这两个计时器不是一回事。**

**为什么"字面懒声明"不可行**:`ProgressiveMediaPeriod.prepareTrackOutput()` 在 `endTracks()` 之后遇到新 TrackId 会

```java
Log.w(TAG, "Extractor added new track (id=" + id.id + ") after finishing tracks.");
return new DiscardingTrackOutput();
```

而 `endTracks()` 在 PMT 解析完就到了 ⇒ 纯懒声明的结果是 **8 条轨全被丢掉(连视频),prepare 以 0 轨完成**,必然黑屏。这不是"风险",是确定性行为。

**最终落地的三件事**:

1. **延迟声明门**:`PrimaryVideoExtractor` 重写为 `ExtractorOutput` 包装 + `DeferredTrackOutput`。首次 `init` **扣住 `endTracks()`**,轨只有真的拿到 Format(或真的写出 sample data)时才 `output.track()`;放行条件三条 —— 全部落地 / 读到文件尾 / **有音视频 Format 且已等满 1500ms**(此时把始终没有任何动静的轨丢掉,记 `echo-iso-track-drop`)。视频没 Format 就**不**放行(宁可等,不丢主轨);第二次 `init`(同一 MediaSource 内 re-prepare)直接退回旧的"立即声明",避免 seek 后丢轨。
2. **有数据即落地**:`DeferredTrackOutput.sampleData` 一旦被调用就立刻落地到真身 TrackOutput,**一个字节都不丢**。这条是下面 bug A 的教训。
3. **临时 Format**:`ProvisionalFormatReader`(新文件)+ `BdTsPayloadReaderFactory` 按 stream type 在**声明时**先发一条猜的 Format(临时 Format 表见下),真实 Format 到了自动覆盖 ⇒ prepare 不再依赖音频真实 Format 的时间。

| stream type | 临时 Format |
|---|---|
| `0x80` LPCM | `audio/raw` 48kHz 2ch 16bit |
| `0x83` TrueHD | `audio/ac3` 48kHz 6ch(只解 core) |
| `0x81` AC3 | `audio/ac3` 48kHz 6ch |
| `0x84` E-AC3 | `audio/eac3` 48kHz 6ch |
| `0x82`/`0x85`/`0x86` DTS | `audio/vnd.dts` 48kHz 6ch |
| `0x90` PGS | 不加(PgsReader 声明时就出 Format) |

**修复过程中自己引入又修掉的两个 bug(同一个坑不要再踩)**:

**A. 丢掉 Format 之前的数据 → `Buffer.put` 越界**

```
08:04:25.246  echo-exo-player-error: IndexOutOfBoundsException:
              off=-165, len=1442 out of bounds (size=65536)
    java.nio.Buffer.checkBounds
    java.nio.DirectByteBuffer.put
    SampleDataQueue.readData(SampleDataQueue.java:411)
    SampleQueue.read(SampleQueue.java:489)          ← MediaCodecVideoRenderer
```

账目很清楚:`SampleQueue.sampleMetadata()` 用 `absoluteOffset = totalBytesWritten - size - offset`(`SampleQueue.java:712`)记样本绝对位置;而 `H264Reader.consume()` **在发 Format 之前就已无条件追加整段 PES 数据**:

```java
// H264Reader.java:139
totalBytesWritten += data.bytesLeft();
output.sampleData(data, data.bytesLeft());
```

第一版延迟声明把 SPS/PPS 之前那 165 字节丢了,`H264Reader` 自己的 `totalBytesWritten` 却照算 ⇒ 第一个 AU 的 `absoluteOffset` 变成 **-165** ⇒ `SampleDataQueue.readData` 里 `target.put(allocation.data, -165, 1442)` 抛异常。

**通用结论:任何"按轨丢弃字节"的包装层,都会破坏 reader 内部的位置记账。可以延迟"声明",不能丢弃"数据"。**

**B. 只按时间丢轨 → 把主音轨也丢了(全片无声)**

08:08 那轮(已修 A、尚未加临时 Format):

```
08:08:29.824  echo-iso-track-drop:4352:1     ← 主音轨
08:08:29.904  echo-iso-track-late:4352:1     ← 80 ms 后它的真实 Format 就到了
08:08:29.826  echo-iso-track-end:declared=8:dropped=4
```

`audio_flinger` 里最后一次 `AT::add` 还是 07:17,这轮 0 次 ⇒ 无声。原因是 1.5 s 时间窗只能判"等多久",判不了"晚到但真实":4352 的真实 AC-3 帧在 1.25 s 到、4353/4354/4355 在 3.7 s 到。**这就是最后改用临时 Format 的直接原因** —— 用时间窗丢轨,在这种盘上必然丢错。

**验证(2026-10-20 08:13 设备复测通过)**:

```
08:13:26.384  echo-iso-truehd-core: lang=null
08:13:26.394  echo-iso-pgs-format            ×3
08:13:26.396  echo-iso-track-defer:declared=8        ← 8 条挂起,全程没有任何 track-drop
08:13:27.477  echo-exo-selector: mime=video/avc count=2 first=OMX.google.h264.decoder
08:13:27.579  echo-exo-codec-init: OMX.google.h264.decoder
08:13:27.586  echo-iso-lpcm-format: audio/raw sr=48000 ch=6   ← 真实 Format 覆盖临时 Format
08:13:28.384  AT::add  sr=48000  mask=0x3F(5.1)  session=257  ← 出声
```

即:8 条轨全部保留(与 mpls 的 `zho×4` 一致)、prepare 在首个视频帧后完成、`StuckPlayerException` 与 `IndexOutOfBoundsException` 都不再出现、音频起 5.1/48kHz;位置从 RAP 续播(187 s)稳定推进。

**边界与残留(必须写清)**:

1. 临时 Format 是**猜**的(48kHz、5.1/2ch)。真实 Format 到达会触发一次重配(毫秒级);若某轨全程没有数据,列表里会多一条静音轨(比"丢轨"更接近 mpls 的声明,但选中它没有声音)。
2. 1.5 s 时间窗现在只在"既没有临时 Format、也没有任何数据"的怪流上生效,不再参与这张盘。若某盘唯一音轨整段无数据,现在不会丢轨而是留一条静音轨,**可能拖慢起播**。
3. 首次 prepare 期间若发生 media3 内部重 init(不是 `rebuildIsoMediaSource`,而是同一 MediaSource 内 seek),第二次走旧的立即声明 —— "起播未完成就 seek"这种边角仍可能卡住。
4. `ISO_SLICE` 门控的 `IsoSliceHarnessTest` 报告里 `endTracks` 行可能在有"永不出格式"的轨时不出现(Robolectric 时钟不推进),它只做诊断、不断言。

**新增日志**:`echo-iso-track-defer:declared=N`、`echo-iso-track-drop:<pid>:<type>`、`echo-iso-track-end:declared=N:dropped=N`、`echo-iso-track-late:<pid>:<type>`;前缀都在既有的 `echo-iso` 白名单内,无需改 `LOG.kt`。

**单测与基线**:`PrimaryVideoExtractorTest` 5 → 17(延迟声明三条件、丢弃后迟到的 Format 被忽略、有数据先落地、`165+1277` 字节全保留、seek 重 init 立即声明、flush 后 Format 变更仍透传);`BdTsPayloadReaderFactoryTest` 8 → 13(各 stream type 的临时 Format、PGS 只发自己那条)。全量 **1281 通过 / 0 失败 / 3 跳过**;`null_assert_gate` PASS;i18n 剩余 44(与基线一致)。

**教训**:

1. media3 有**两个**"卡住"计时器,`setStuckBufferingDetectionTimeoutMs` 不是掐断 4 秒卡死的那个;
2. 在 media3 里"延迟声明"等于"丢轨",因为 `endTracks()` 的语义是**声明窗口关闭**;
3. 包装 `TrackOutput` 时**数据必须原样放行**,否则 reader 的位置记账会错位;
4. PMT 里"声明过的音轨"不等于"片头有数据" —— DIY 盘能把三条音轨的真实数据放到 48 MB 之后;
5. 用时间窗做"放弃"判定一定会误伤"晚到但真实"的轨;能用内容(临时 Format)解决,就不要用时间去猜。

---

## 丙、seek 与倍速实验记录(原 spec §11.5–§11.10;含已判负的分支与被推翻的中间结论)

### 11.5 S0 实测结果(2026-10-20,**已执行**)

做法:debug 临时放开 `PlayerEngine.seekTo` 的 ISO 闸门(`ISO_SEEK_EXPERIMENT`,验完已删),实盘 `00011.m2ts` 从 8s 拖到 46.8s。

**真机现象**:拖动后花屏、不恢复、画面像停住,**声音与进度正常**。

| 观测 | 数据 | 判读 |
|---|---|---|
| 落点 open | `echo-iso-seek:160873405:6612859843` 只有**一次** | `TsBinarySearchSeeker` 走通,无多次探针 |
| 落点精度 | 物理偏移折算 ≈43.6s,请求 46.8s,偏差 ≈3s(**落在目标前**) | 属 PCR 插值的正常前偏,不是缺陷 |
| 解码器是否重建 | seek 后**无新的 `echo-exo-codec-init`** | 只 flush,未重建实例,csd 不会重下发 |
| 时间线 | 46.8s → 83s 稳定推进,`state=PLAYING`,无 `echo-iso-error` / `StuckPlayer` / `autoRetry` | 解复用与音频链路完全正常 |
| 是否还在出帧 | SurfaceFlinger 视频层 `frame=` 3s 内 +100(≈33fps) | **不是"没有帧"**,解码器在持续输出,只是内容错 |
| 音频 | LPCM 48k/2ch 正常 | 与视频问题无关 |

**离线复核**(取落点 32MB,实得 8.98MB 切片,`IsoSliceHarnessTest`):

```
track#4113 video/avc samples=75 keyFrames=3 firstKeyPts=867533(= firstPts)
track#4352 audio/raw 48kHz 2ch samples=634 keyFrames=634
```

- `keyFrames=3` 且**首个样本就是关键帧** ⇒ 落点附近不缺随机访问点,"按关键帧对齐"这件事码流层已经满足;
- 能抽出 `video/avc` 的 Format 且产 75 个样本 ⇒ 切片内**存在 SPS/PPS**(否则 `hasOutputFormat` 永远不置真、样本数为 0);
- 结论:**码流层面既不缺 I 帧也不缺参数集**。唯一断点是 §11.1 那条:`H264Reader.hasOutputFormat` 不会重置 ⇒ in-place seek 后不再下发 Format ⇒ `MediaCodec.flush()` 之后解码器拿不到 csd ⇒ 持续花屏。

**修复方向(修订路 B)**:

| 方案 | 做法 | 代价 | 风险 |
|---|---|---|---|
| **B2c renderer 级 `releaseCodec()`(推荐)** | 在 `ReplayableCacheVideoRenderer` 覆盖 `onPositionReset(positionUs, joining, mayRender)`(`MediaCodecVideoRenderer` 声明为 protected、可覆盖),ISO 会话时**先 `releaseCodec()` 再 `super`** → 下一个输入用 `currentFormat`(含 SPS/PPS 的 `initializationData`)重建解码器 | 每次 seek 多一次解码器 init(约 100-300ms);MediaPeriod 不重建,音频/进度/弹幕不动 | 0.5-1 天 | 低-中 |
| **B2a 重开源到落点** | ISO 的 seek 不再用 `seekTo`,改为重建 MediaSource + `setMediaSource(target)` → 新 MediaPeriod → 新解码器 + csd 重下发 | 每次 seek 约 2-3s 重建(sniff + 尾部时长探测 + 落点 open)、音频重启、进度/UI 需处理;约 1 天 | 低(全走公开行为),但 UX 差,**作兜底** |
| ~~B2b reader 级伪造 Format~~ | **作废**:`MediaCodecVideoRenderer.shouldReleaseCodecInsteadOfFlushing()` 是 `final`,无法覆盖;真正该用的是 `releaseCodec()`。靠改 `Format.id` 之类的差异也不保证触发 `canReuseCodec` 失败 | — | — |

字节码依据(2026-10-20):`MediaCodecRenderer.releaseCodec()` 为 `protected`,只置 `codec=null`/`mediaCrypto=null` 并累加 `decoderReleaseCount`,**不清 `currentFormat`**;`onPositionReset` → `flushOrReinitializeCodec()` → 依据 `shouldReleaseCodecInsteadOfFlushing()` 决定 flush 还是 release,而视频侧该方法被声明为 `final`(默认 false)。

建议顺序:**先做 B2c 最小验证**(只验"一次拖动后能否恢复"),失败再退 B2a。判据:拖动后 `echo-iso-seek` 紧跟一条新的 `echo-exo-codec-init`,且 1s 内画面正确。具体实施见 §11.6。

### 11.6 B2c 实施方案(2026-10-20 定稿,**待实施**)

思路:ISO 会话里每次 position reset 之后**主动丢弃解码器实例**,让 media3 用当前 `Format`(含 SPS/PPS 的 `initializationData`)重新 `configure` 一个解码器。不改 MediaSource、不换 reader、不动音频。

#### 11.6.1 改动清单(4 处 + 1 组单测)

| # | 文件 | 改动 |
|---|---|---|
| 1 | `player/engine/iso/IsoSeekRecycle.kt`(新增) | 纯决策函数 `fun shouldRecycleCodecOnSeek(isoLike: Boolean, hasCodec: Boolean): Boolean = isoLike && hasCodec`,便于单测 |
| 2 | `player/effect/ReplayableCacheVideoRenderer.kt` | 构造参数追加 `isoLike: () -> Boolean`;新增覆盖 `onPositionReset(positionUs: Long, joining: Boolean, mayRenderFirstFrame: Boolean)`:`super.onPositionReset(...)` **之后**,`if (shouldRecycleCodecOnSeek(isoLike(), getCodec() != null)) { LOG.i("echo-iso-codec-recycle:$positionUs"); releaseCodec() }` |
| 3 | `player/engine/EngineRenderersFactory.kt` | 构造参数追加 `isoLike: () -> Boolean`,第 175 行构造 `ReplayableCacheVideoRenderer(builder, lateThreshold, isoLike)` |
| 4 | `player/engine/PlayerEngine.kt` | `createPlayer()` 里 `EngineRenderersFactory(..., isoLike = { isoSession || IsoSessionSignal.escalated() })` |
| 5 | 单测 | `IsoSeekRecycleTest`:ISO+有 codec→true / ISO+无 codec→false / 非 ISO+有 codec→false / 非 ISO+无 codec→false |

日志:`echo-iso-*` 已在 `FILE_LOG_PREFIXES`,新增 `echo-iso-codec-recycle:<positionUs>` 无需改白名单。

#### 11.6.2 为什么是这个位置(字节码依据)

- `MediaCodecVideoRenderer.onPositionReset(long, boolean, boolean)` 是 `protected`(非 final)→ 可覆盖;
- `MediaCodecRenderer.releaseCodec()` 是 `protected`,内部只置 `codec=null`、`mediaCrypto=null` 并累加 `decoderReleaseCount`,**不清 `currentFormat`** → 重建时 csd 仍在;
- `getCodec()` 是 `protected final`,可直接判空;
- `flushOrReinitializeCodec()` 内部会调 `maybeInitCodecOrBypass()`,因此 release 后下一轮 `feedInputBuffer` 会用 `currentFormat` 立刻重建解码器;
- **不能用的钩子**:`MediaCodecVideoRenderer.shouldReleaseCodecInsteadOfFlushing()` 与 `shouldFlushCodec()` 都是 `final`;`shouldReinitCodec()` 虽然可覆盖,但它被 `feedInputBuffer()` 调用(不是 seek 路径),语义不对。

#### 11.6.3 实施顺序与判据

1. 按 §11.6.1 落地 + 单测 → `assembleDebug` → 装机;
2. 实盘拖 1 次:期望 `echo-iso-seek:<off>` 之后 1s 内出现新的 `echo-exo-codec-init`,画面直接正确(允许闪 1 帧黑);
3. 连拖 3 次都要成立;音频不断、`rawPosition` 与进度条一致、无 `echo-exo-player-error` / `StuckPlayerException`;
4. 通过后放开正式逻辑:`PlayerEngine.seekTo` 去掉 ISO 拦截、`setStartPosition` 解除归零(S4,含 `ZeroStartGate` 走查)、补 §11.2 的 S1/S2(落点前偏与 UI 显示按实际位置);
5. 失败(重建后仍花屏)→ 说明解码器重建后仍拿不到可用 csd,退 §11.2 的 B2a。

#### 11.6.4 代价与边界

- 每次 seek 多一次解码器 init(约 100-300ms),以及一次 flush 前的缓冲丢弃;体感上比"直接 seek"略慢,但比 B2a 的 2-3s 重建好一个量级;
- 只在 `isoLike` 会话生效,普通 m3u8 / MP4 / 直播完全不变;**不新增 KV 开关**(必要时用 debug 常量对比);
- 与 R14(倍速花屏)**无关**:那条是丢参考帧,本方案不会改善也不会恶化;
- 走查需覆盖 HEVC 盘(本方案在 renderer 层,理应与 mime 无关,但 HEVC 无样本,只能标注"未覆盖")。

**工作量**:实现 + 单测 0.5 天,真机走查 0.5 天;若同批解禁续播再加 0.5 天。合计 **seek 恢复 1 天 / seek+续播 1.5 天**。

#### 11.6.5 与 R14(倍速花屏)的关系 —— 只能解决一半

- **3x 播放期间的宏块错乱:解决不了**。R14 的成因是迟到 >15ms 时丢**解码器输入**(丢的是参考帧);只要还在丢帧,画面必然错,`releaseCodec()` 不会让"正在丢帧"的过程变干净。要根治只能①关掉丢输入(花屏换卡顿,§5-A4 已否)或②实现"只丢非参考帧"(需要 slice 级参考性判断,media3 无此接口)。
- **"松开 3x 之后不自愈":可以顺带解决**。`MediaCodecVideoRenderer.setPlaybackSpeed(float current, float target)` 是 `public` 且非 final(2026-10-20 字节码核实),可在同一子类覆盖:当 `isoLike() && getCodec() != null && target == 1f` 时 `releaseCodec()` → 解码器重建,从下一个 I 帧起恢复干净。按 S0 的落点切片推算,本盘 I 帧间隔约 0.6-0.7s,即**松开后 <1s 自愈**。
- 代价:每次松开长按多一次解码器 init(约 0.2-0.3s),可能闪 1 帧黑;仅 ISO 会话生效。
- 可选项(收益小):进入 3x 时也 release,让 3x 从干净状态起步,但随后丢帧仍会继续破坏,不建议做。
- 若同批实现:§11.6.1 第 2 处改动追加一个 `setPlaybackSpeed` 覆盖;判定扩成「进入 3x 不 release / 回到 1x 才 release」两组;工作量 **+0.2 天**,走查需长按一次(用户操作)。

### 11.7 R14 彻底根治:只能走「跳段快进」(2026-10-20,方案,**未开工**)

#### 11.7.1 先把「根治」拆成两件事

| 目标 | 现状 | 难度 |
|---|---|---|
| 松开长按后恢复 | 永久花屏 | **低**:§11.6.5 的 `releaseCodec()`,+0.2 天 |
| 长按 3x 期间画面干净 | 花屏 | **高**:受 §11.7.2 的物理约束,不是代码问题 |

#### 11.7.2 物理约束:在线 ISO 的 3x「逐帧解码」不成立

- 本盘 1x 需 **3.69MB/s**(29.5Mbps);3x 逐帧 ⇒ **11.1MB/s ≈ 88Mbps**;
- 实测(VIP,§9 R5b):持续约 **4.1MB/s**,峰值 7.4–17.9MB/s ⇒ 供给差 2-3 倍;
- 供给不足 ⇒ media3 必然按 15ms 迟到阈值丢**解码器输入**,丢的就是参考帧 ⇒ 只要还在丢,画面不可能干净;
- 结论:任何「解码每一帧再 3 倍速输出」的方案,在网盘 ISO 上都没有解。要做到干净 3x,只能**不逐帧解码**;顺滑 3x 的前置是 ISO 磁盘缓存/预取,已在 §8 明确不做。

#### 11.7.3 三条路线

| 路线 | 做法 | 画面 | 带宽 | 代价 | 工作量 |
|---|---|---|---|---|---|
| **R14-A 跳段快进(推荐)** | ISO 会话 `setSpeed(>1)` 不下发 ExoPlayer:保持 1x 解码,由驱动器每 `STEP_MS`(建议 500ms)真实时间 `seekTo(current + STEP_MS×speed)`;落点重同步复用 B2c(重建解码器 + 关键帧) | **干净**,但呈「跳段/幻灯片」而非顺滑 | ≈1x(每次只读落点窗口) | 长按期间音频建议静音(否则跳段与变速音叠加成口吃);进度按倍速前进 | **1-1.5 天** |
| R14-B 关闭丢输入 | ISO 会话给 `experimentalSetLateThresholdToDropInputUs` 传 `C.TIME_UNSET`(`EngineRenderersFactory` 158-169 / `ReplayableCacheVideoRenderer` 26-32) | 干净 | 需 3x 带宽 | 带宽不足 ⇒ 视频落后、卡顿、音画不同步;只适用于本地或高带宽源 | 0.1 天 |
| R14-C 只丢非参考帧 | 自研 VideoRenderer / 替换 video graph,按 slice 级参考性(H.264 `nal_ref_idc==0`、HEVC `TRAIL_N` 等)过滤 | 干净且顺滑 | 需 3x 带宽 | 5-8 天,media3 无此接口,需自建整条视频渲染链路 | **不建议** |

#### 11.7.4 落地顺序

1. **先做 B2c + §11.6.5 松开自愈**(+0.2 天):把「永久花屏」降为「松开后 ≤1s 恢复」,风险最低;
2. 若确认「长按期间也要干净」,做 R14-A。入口唯一:`PlayerEngine.setSpeed`(534-538):
   - `speed > 1f && isoSessionLike()` → **不调** `setPlaybackParameters`,启动跳段驱动器;
   - `speed == 1f` → 停驱动器、清静音、恢复正常播放参数;
   - 驱动器:定时(Handler/协程)`seekTo(min(position + STEP_MS×speed, duration - 1s))`,每次 seek 由 B2c 保证重同步;
   - 边界:临近结尾停;切集/退出/后台/进入音乐页一律停;与用户手动拖动互斥(手动拖动时暂停驱动器一拍)。
3. R14-C 不做。

#### 11.7.5 判据

- 长按 3x 持续 10s:画面**无花屏**(每跳后 1 帧内干净)、进度按约 3x 前进、带宽不超过 1x 的 1.3 倍;
- 松开:≤1s 回到连续播放且画面干净;
- 拖动 / 切集 / 后台 与跳段驱动器互不干扰;
- 非 ISO 内容:`setSpeed` 行为完全不变(仍走 `PlaybackParameters`)。

**工作量**:R14-A 依赖 seek 能力(B2c),合计 **+1~1.5 天**;R14-B 仅 0.1 天,可作为「本地/高带宽源」分支另说。

**收尾**:临时开关已回退并重装,`git status` 无代码改动(仅用户自己改的 `README.md`);落点切片、含 Cookie 的 URL 与 logcat 抓取文件已删除。

### 11.8 B2c 与 B2a 实测判负(2026-10-20,已执行)

S0 之后按§11.6.1 与 §11.5 分别试了「路 B」的两种修法,**都失败**。

#### 11.8.1 B2c(renderer 级 `releaseCodec()`,已回滚)

实现 4 处 + 单测 4 组(新增 `iso/IsoSeekRecycle.kt`、`ReplayableCacheVideoRenderer.isoLike` + `onPositionReset` 覆盖、`EngineRenderersFactory` 透传、`PlayerEngine` 传 `{isoSession || IsoSessionSignal.escalated()}`,单测基线 1162 → 1166)。

真机实测(01:24:21,拖到 24.3s):

```
01:24:21.542  echo-iso-codec-recycle:1000024348000     ← recycle 触发(streamOffset 1000.0s)
01:24:21.581  MediaCodec looper is gone                  ← 旧实例确实释放
01:24:21.585  makeComponentInstance(OMX.qcom.video.decoder.avc)
01:24:21.601  echo-exo-codec-init                        ← 重建成功,59ms
01:24:21.964  setting dataspace on output surface to 0x104
01:24:22.701  SoftwareRenderer setting dataspace         ← 新解码器确实吐了 2 帧
```

**判据前半条达成**(1s 内出现新 codec-init),**后半条不达成**:SurfaceFlinger 视频层 3 秒 **0 帧**(`--latency` 只有 refresh period),而 S0 不重建时是 33fps 在出帧。时间线与音频正常、无报错。即 **B2c 把「花屏但画面在动」变成「花屏且画面彻底不动」**,比原问题更严重。已回滚全部 4 处 + 删除 `IsoSeekRecycle.kt` 与其单测。

#### 11.8.2 B2a(重建 MediaSource 做 seek,当前保留但默认关闭)

实现:新增 `iso/IsoSeekRebuild.kt` 的三态纯判定(`IsoSeekAction.ALLOW/BLOCK/REBUILD` + 单测 5 组)、`PlayerEngine.rebuildIsoMediaSource(positionMs)`(重建源 → `setMediaSource(source, positionMs)` → `prepare()` → 保持 `playWhenReady`)、`seekTo` 按判定分派、日志 `echo-iso-seek-rebuild:<pos> playing=<bool> bdav=<bool>`。开关 `ISO_SEEK_EXPERIMENT`(临时,验证用)。

真机实测(三个盘各 2~3 次拖动):

| 会话 | 盘 | 起播 codec-init | 每次重建后 codec-init | 状态 |
|---|---|---|---|---|
| 01:30 | BanG Dream S01D01(LPCM 2.0) | ✅ | ❌ ×1 | 重建后一直 BUFFERING |
| 01:35 | BanG Dream S01D02(LPCM 2.0) | ✅ | ❌ ×3 | 第二次重建后 PLAYING 但只推进位置 |
| 01:36 | 九品芝麻官(TrueHD core + PGS) | ✅ | ❌ ×3 | 位置推进到 243s |

**共性故障(与盘无关)**:`selector mime=video/avc count=2` 每次都打(轨道选择器确实在处理视频轨)、`lpcm-format` / `pgs-format` 都正常注册,**唯独没有 `echo-exo-codec-init`** ⇒ 视频解码器从未建立。音频驱动时间线推进,画面停在重建前那一帧(用户看到的是"花屏不动";重建前画面正常时则表现为"进度条动、画面不动",易被误判成可拖动)。

落点偏差:请求 539.4s,落点 `echo-iso-seek:1854853259:6561013365` 折算 ≈512.2s(按 dur=2,324,969ms 与 logical length 8,415,866,624),**前偏约 27s**;且**只有一次落点 open**,`TsBinarySearchSeeker` 的 PCR 二分过程一次未跑。对比 S0 的 in-place 落点前偏仅 2.3s。

#### 11.8.3 一条被证伪的解释(duration 探测)

曾怀疑「duration 算错导致落点按比例估偏」(另一个 app 显示同资源总时长 2:46:04,而我们是 31:12)。**已证伪**:2026-10-20 新增诊断日志 `echo-iso-m2ts:count=16:chosen=00011.m2ts:6917855232:top=00011.m2ts:6917855232,00019.m2ts:5437642752,00017.m2ts:5420802048,00018.m2ts:5420470272,00016.m2ts:5386985472` 显示:该盘是**多段盘**(16 个 m2ts,第 2~5 大均为 5.4GB 段),选片正确(chosen = 最大),而 `dur=1872989ms` 是 **`00011.m2ts` 这一段自身的真实时长**(6.44GB ÷ 1873s ≈ 29.5Mbps,自洽)。2:46:04 是多段拼接后的整片时长。

**推论**:落点前偏 27s 不能用 duration 错误解释;`TsBinarySearchSeeker` 未跑与「视频轨在落点建不起来」的关系**仍未定论**(待查项,见 §11.8.5)。另:`echo-iso-m2ts` 这条诊断日志**保留**,它同时是多段盘判定的依据。

#### 11.8.4 fongmi 对照调查(重要反证)

用户报告 fongmi 用同一源、同一 exo 硬解**可以拖动进度条**。对 `示例文件/fongmi` 的全库搜索结论:

- fongmi **没有任何 ISO / UDF / m2ts / 192→188 剥离 / 自定义 TsExtractor 代码**(`m2ts`、`BDMV`、`UdfParser`、`TsPayloadReader`、`PesReader`、`SampleQueue`、`TsBinarySearchSeeker` 全部 0 命中);
- 它对 TS 的**唯一**改动是 `示例文件/fongmi/app/src/main/java/com/fongmi/android/tv/player/exo/ExoMediaSourceFactory.java:123`:`new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10)`;
- `seekTo` 纯透传(`player/PlayerManager.java:414`),无任何 ISO 分支;
- media3 默认 `TsExtractor()` 的真实参数(字节码核实):`mode=1(MODE_MULTI_PMT)`、`extractorFlags=0`、`SubtitleParser.Factory.UNSUPPORTED`、`TimestampAdjuster(0)`、`timestampSearchBytes=112800`。

⇒ **推断**:fongmi 播的"ISO 资源"不是 UDF 容器(它无 ISO 代码),而是源侧给它的**完整单文件 / m2ts / m3u8**;两边的数据形态不同,不能直接类比我们链路的 seek 行为。唯一实质参数差异(timestampSearchBytes ×10)已对齐,见 §4.5 与 §6.1-23。

#### 11.8.5 仍未定的两个疑点(下一轮入口)

1. `TsBinarySearchSeeker` 为何**一次二分都没跑**(重建路径只有单次落点 open);若 seekMap 判定为 unseekable,落点即按平均码率粗估。
2. 从落点开始读时,`TsExtractor` 为何拿不到视频轨的 Format(音频/字幕轨都正常注册 ⇒ PMT 已解析 ⇒ 疑在视频侧需要 SPS/PPS 才下发 Format,而落点窗口内没有)。

验证手段:加诊断日志打印 seekMap 是否 seekable 与 seek point 数量(尚未做)。

### 11.9 「RAP 起播」实验记录(2026-10-20,**本节结论已被 §11.10 推翻,保留作过程)**

§11.8 判负后换了一条完全不同的路子:不再让 media3 在窗口内 seek,而是**按 CLPI 的访问点表把落点算成精确字节偏移,开一个全新的 period,从访问点那一字节起播**。整条链路实现完、单测全绿、真机跑了 4 轮,结论是**这条路对这张盘走不通**,原因不在我们的解析。

#### 11.9.1 CLPI EP_map 解码(三重验证通过)

按 libbluray `clpi_parse.c` 实现 `ClpiIndex`,真机日志三处独立对齐,证明解码**不是"看起来对"**:

| 验证项 | 值 | 交叉证据 |
|---|---|---|
| 首个访问点 PTS | `188,999,936` ticks ÷ 45000 = **4199.999s** | mpls 里 `00016` 的 IN 点 = `4,200,000,000µs` = 4200.000s，差 **1ms** |
| 访问点总数 × 包长 | `28,057,216 × 192` = **5,386,985,472** | `00016.m2ts` 的文件字节数，**完全相等** |
| 落点字节 → 实际码流 PTS | 落点 `555,026,880` → PES PTS = **4334.51s** | 期望 `4200.000 + 134.508` = **4334.508s**，差 **2ms** |

访问点密度：`00016` 426 个粗访问点，`minGap=0.296s`、`maxGap=6.633s`、均值 3.345s。

#### 11.9.2 访问点的码流结构(逐字节解开)

对落点读 128 字节（`echo-iso-rap-head`），结构完全合法：

```
ATS头(4B) | 0x47 同步头 PUSI=1 PID=0x1011 | PES(E0, PTS/DTS) |
00 00 00 01 09 (AUD) | 67 (SPS,High/4.1) | 68 (PPS) | 06 (SEI: buffering_period / recovery_point / pic_timing) |
41 (slice: nal_ref_idc=2, NAL type=1, first_mb_in_slice=0, slice_type=7 → I)
```

⇒ **参数集是在码流里自带的**，不需要注入；`recovery_point` 的 `recovery_frame_cnt=0`、`exact_match_flag=1`。§6.1-14 里「SPS/PPS 各只有 1 个、位于片头第 768 字节」的旧结论**已被这条推翻**：每个访问点都会重发 SPS/PPS。

#### 11.9.3 实现过程中查出的两个真 bug(已修，保留在树里)

1. **读取起点晚 4 字节**：`ClpiAccessPoint` 的偏移原本按「文件里 TS 同步头的位置」算 = `spn*192+4`，而 `m2tsOffset()` 内部已经加了 `M2TS_ATS_SIZE=4`，两者叠加导致实际读起点是 `spn*192+8`。症状是 `TsExtractor` 抛 `IllegalArgumentException`（栈：`ParsableByteArray.setPosition ← SectionReader.consume ← TsExtractor.read`）以及整段绿屏。修法：`startOffset = spn*192`（包起点），并加不变量测试 `startOffset + m2tsOffset(0) == syncOffset`。
2. **同一次播放建了两个源**：`setDataSource` 先建一个 `seekMs=0` 的 MediaSource，`prepare()` 又建一个带续播位置的；前者已经进 prepare 开始加载（日志里它的 duration 探针按**整段**逻辑长度 `5,274,756,608` 算，与带偏移的窗口不符），1s 后自己抛 IAE 触发 `autoRetry`。修法：ISO 的 MediaSource 只在 `prepare()` 里建一次（`isoSourceBuilt`）。

#### 11.9.4 判负结论

修掉上面两个 bug 后，日志变成"完全干净"：无 `player-error`、无 `autoRetry`、`echo-iso-seek:1715539480:1128000` 与窗口逻辑长度**精确相等**、PTS 与 CLPI 差 2ms。但**画面仍然是绿的**，且用户复述为「**画面在动，绿色/紫色/花屏交替闪烁**」。

这是「解码器不接受非 IDR 的恢复点 I 帧」的唯一表现：MediaCodec 收到 `BUFFER_FLAG_KEY_FRAME`（media3 的 `FLAG_ALLOW_NON_IDR_KEYFRAMES` 让它这么标），但 qti 的 `OMX.qcom.video.decoder.avc` 内部不把非 IDR 帧当参考帧 ⇒ DPB 为空 ⇒ 之后每个 P/B 帧都从不存在的参考里预测 ⇒ 每帧偏差不同 ⇒ 绿/紫/花屏闪烁且**永不自愈**（蓝光只在段首放真 IDR）。

为验证补了「向前反向找最近真 IDR」的兜底（扫 `00 00 01` + NAL type 5，再用 AUD 回退到访问单元边界，最后用该包 PES PTS 反算时间）：

```
echo-iso-idr-miss:00016.m2ts:229486272:66440   ← 16MB 内一个 IDR 都没有
```

- 该盘在离访问点 **16MB**（≈4s 内容，码率 3.8MB/s）以内**不存在 IDR**；
- 且 16MB 分 64 个 256KB 块串行扫要 **14.5s**（`04:17:38.9 → 04:17:53.3`），每个 data source 打开都重扫一次 ⇒ 用户看到的就是「一直在加载，刷新不出来」。

#### 11.9.5 当前状态与解除条件

- `PlayerEngine.ISO_SEEK_RAP_ENABLED = false`：ISO 的 `>1s` seek 仍 `echo-iso-seek-blocked`，`setStartPosition` 仍归零（`echo-iso-start-zero`），与 §11.8 之前一致；
- 代码**保留**：`ClpiIndex` / `IsoPlayTarget.startOffset` / `IsoSelection.baseMs` / `IsoPlaylistState.windowStartMs` / `alignSelectionToAccessPoint`（全部有单测），打开开关即可回到"RAP 起播"；带偏移的位置/时长聚合已独立生效（比原来的 timeline 求和更准）。
- **解除条件**（缺一不可）：①落点必须是真 IDR（或播放器侧能证明解码器接受恢复点）；②IDR 定位成本要可接受（现在是 14.5s / 16MB 且找不到）。目前这张盘两条都不满足。
- 若要继续，可行的下一步只有「**段级跳转**」（8 个段首，精度 23 分钟，成本~10 行、零扫描）或「**软解验证**」（证明内容本身可从恢复点起播），两者都不解决进度条精度。

### 11.10 真机复验:**RAP 起播可用**(2026-10-20 04:48-04:52,**vivo V2425A 真机**)

§11.9 的全部"判负"都是在 **MuMu 模拟器**上做的。用户指出另一款同源应用(fongmi,装了 ISO 支持的私有构建)在手机上正常、在 MuMu 上同样绿屏 —— 做了对照实验后确认:

> **MuMu 的 H.264 解码路径不能作为本问题的裁判。** 模拟器里 `OMX.qcom.video.decoder.avc` 是宿主机的假冒实现;真机是 `c2.qti.avc.decoder`。同一个 app、同一部片、同一个 media3 分支,只有模拟器坏 ⇒ 前面所有基于 MuMu 画面的"解码器不接受恢复点"结论**作废**。

真机实测(硬件解码,`c2.qti.avc.decoder`):

| 场景 | 日志证据 | 结果 |
|---|---|---|
| 续播(RAP 起播) | `echo-iso-seek-rap:749802:747121:00016.m2ts:2969587200:segments=8` → `echo-exo-codec-init: name=c2.qti.avc.decoder kind=HARDWARE` | 出画、有声、有字 |
| 段内拖动 | `echo-iso-seek-inperiod:229175:229175:1421962` + `echo-iso-seek:807646097:4467110511`(**按字节跳 807MB**)+ **无第二次 codec-init** | 画面正常 |
| 段内再拖 | `echo-iso-seek-inperiod:707455:707455:1421962` + `echo-iso-seek:2493174827:...` | 画面正常 |
| 跨段拖动 | `echo-iso-seek-rap:2590682:2588792:00017.m2ts:4405401600:segments=7` → `lpcm-format`/`pgs-format`/`selector/encoder enabled` → `pos=2588793` | 画面正常 |
| 再跨段 | `echo-iso-seek-rap:7253909:7251324:00021.m2ts:584187456:segments=3` → `pos=7251599` | 画面正常 |
| 倍速 | 用户实测 | 正常,无花屏 |

全程 `echo-exo-player-error` / `echo-autoRetry` / `StuckPlayer` 均为 0。

**所以最终架构就是原来那一套**:
1. 段内:media3 自己的**容器 seek**(`TsExtractor` 的 PCR 二分,`echo-iso-seek` 里的字节跳就是它),period 不变、解码器不重建 ⇒ 恢复点 I 帧照常解;
2. 跨段:用 **CLPI 访问点表**算精确字节偏移,重建 source 并从该字节起播;
3. 位置/时长由 `IsoPlaylistState` 的窗口表聚合(`baseMs + 窗口起点 + 窗口内位置`)。

落点会回退到最近的访问点:本盘 426 个粗访问点,平均间隔 3.345s、最小 0.296s、最大 6.633s。

**教训(写进流程)**:涉及硬件解码/花屏的判断,**模拟器只能用来验证"不崩",不能用来判定"画面对不对"**。本问题上模拟器给了 4 次错误判负,浪费了一整轮。


---

## 丁、多段盘与性能实测(原 spec §12.9 + §13)

### 12.9 P2/P3 实测结果(2026-10-20,真机,**已完成**)

#### 12.9.1 起播与整片时间轴

```
03:28:52.716  echo-iso-play: format=video/x-iso
03:28:54.587  echo-iso-udf-prefetch:655360+262144:1        ← metadata 分区一次预读(§9 R9 追加)
03:28:54.588  echo-iso-udf-fsd:1:2                         ← 0ms(命中预读)
03:28:54.591  echo-iso-m2ts:count=16:...                   ← 3ms(16 个 File Entry 全在窗口内)
03:28:54.946  echo-iso-mpls:count=5:parsed=5:skipped=0:chosen=00005.mpls:items=8:duration=9964162
03:28:54.947  echo-iso-playlist:00005.mpls:segments=8:duration=9964162:total=37856182272:first=00016.m2ts
03:28:54.947  echo-iso-target:00016.m2ts:7406712832:5386985472
03:28:54.948  echo-iso-playlist-append:7:total=8:duration=9964162   ← 1ms 后补齐 7 段
03:28:56.490  echo-exo-codec-init (HARDWARE avc)
03:28:56.5    echo-progress-tick: rawDuration=9964162 rawPosition=... PLAYING
```

| 判据 | 结果 |
|---|---|
| 选片 | `00005.mpls`,8 段,从段 0 `00016` 起播(**不是**最大的 `00011`) |
| 总时长 | `9,964,162ms = 2:46:04`,与另一播放器一致;`uiDuration` 全程稳定 |
| 追加时机 | 解析完成后 **1ms** 内完成 7 段追加,用户仍停在位置 0,无时间轴跳变 |
| 音视频/字幕 | LPCM 有声、H.264 硬解、**5 条 PGS**(该段比 `00011` 多 2 条)全部注册并出字 |
| 错误面 | 本次会话 **0** 条 `echo-iso-error` / `StuckPlayer` / `autoRetry` / 带宽误报 |

#### 12.9.2 跨段连续播放(决定性验证)

段 0 是 23:42,无法等自然播完,故用**临时段序轮转**(`IsoLocator.BOUNDARY_EXPERIMENT`,验完已删):把 10.01s 的收尾段 `00009` 排到最前,播到 10s 即自然跨到 `00016` —— **纯 MediaPeriod 边界,不经过任何 seek**,不混入 §11 的「落点缺参数集」花屏。

```
03:31:18.575  echo-iso-boundary-experiment:first=00009.m2ts
03:31:18.576  echo-iso-target:00009.m2ts:380078080:44544000     ← 44.5MB ≈ 10.0s
03:31:19.096  echo-iso-seek:42488000:1128000                     ← 段 0 尾部时长探测
03:31:20.666  echo-exo-codec-init                                ← 唯一一次
03:31:23.135  echo-iso-seek:5273628608:1128000                   ← 段 1 尾部探测(下一窗口预载)
03:31:30.951  echo-progress-tick: rawPosition=10000              ← 段 0 末尾
03:31:31.953  echo-progress-tick: rawPosition=10915              ← 跨段后继续,未回 0
03:31:43.038  echo-progress-tick: rawPosition=22002 uiDuration=9964162 PLAYING
```

| 判据 | 结果 |
|---|---|
| 是否真的跨段 | 段 0 只有 10.0s,位置越过 10000ms 后继续涨到 22002ms ⇒ **窗口确实切换了** |
| 位置连续性 | 10000 → 10915 → … → 22002,**无回 0、无跳变**(偏移 = 10010ms + 窗口内位置) |
| 解码器 | **全程只有 1 次 `codec-init`** ⇒ 段切换时解码器被 media3 **复用**,没有重建、没有黑屏 |
| 总时长 | `uiDuration` 恒为 `9964162`,跨段不抖动 |
| 错误面 | 0 条错误;`playing=true`、`state=PLAYING` 全程 |

结论:**段切换不需要重建解码器**(比 §12.6-1 预估的「一次重建」更轻),`§12.6-1` 的高风险门**不成立**,P3 通过。段首的 PAT/PMT/参数集足以让新 MediaPeriod 无缝接上。

> 验证后 `BOUNDARY_EXPERIMENT` 与其轮转代码**已删除并重装**;正常构建下 `echo-iso-boundary-experiment` 不再出现,`first=00016.m2ts`。

#### 12.9.3 尚未覆盖

- **PGS 跨段延续**:本次只验了 10s 的跨段,字幕在该窗口内已注册(`echo-iso-pgs:first` ×5),但未观察跨段后字幕是否继续跟随(需要看到跨段后 1 分钟以上的字幕变化);
- **4K/HEVC 多段盘**:无样本;
- **跨段时的带宽峰值**:段切换要为新窗口预载 + 尾部时长探测(1.128MB),真实 23 分钟间隔下无影响,但未单独测;
- **续播/历史**:仍按 §12.6-2 不解禁(整片时长已正确,但落点仍受 §11 的 seek 制约)。

## 13. 性能观测与三项优化(2026-10-20)

### 13.1 真机日志能证明什么、不能证明什么

对 2026-10-20 04:46-04:55 的 1426 行真机日志逐段求差:

| 阶段 | 从 0 起播 | 续播 749.8s |
|---|---|---|
| `iso-play` → 第一个 UDF 字节 | **1.20s** | **1.09s** |
| UDF probe + mpls 解析 | 1.59s | 1.28s |
| CLPI 首次读 | — | 0.17s |
| 首次 media 读 → 音频格式就绪 | 1.48s | 1.82s |
| `codec-init` | 43.178 | 38.829 |
| **合计起播** | **≈4.9s** | **≈4.6s** |

跨段拖动两次耗时一致:

```
41.638 seek-rap:2590682 playing=true → 43.866 renderer enabled = 2.23s
   └ 0.60s 读 00017.clpi │ 0.53s 建源 │ 0.90s 首读+音频 │ 0.02s 渲染
46.296 seek-rap:7253909 playing=true → 48.539 renderer enabled = 2.24s
   └ 0.52s 读 00021.clpi │ 0.72s 建源 │ 1.19s 首读+音频 │ 0.02s 渲染
```

- 段内拖动 `04:48:46.795 → 46.894`(**0.10s**)、`04:49:12.138 → 12.209`(0.07s),**无** `renderer: disabled`、**无**新 `codec-init`;
- 连续播放 04:52:49-04:54:49 共 25 个 5s 进度 tick,**间隔全部 5.00s**,无一次缺口;全程 0 条 `player-error`/`autoRetry`/`StuckPlayer`/`CodecException`。

**看不到的**:吞吐量与掉帧。`IsoThroughput` 一直在测量,但历史上只在「换线重试超时」路径打 `echo-iso-bandwidth`(§9),本次零错误 ⇒ 该路径未触发 ⇒ **日志里一条 bps 都没有**。能间接推断的只有:256KB 的 metadata extent 用了 0.43s(≈0.6MB/s)⇒ **每个 Range 请求有 0.25-0.4s 的固定延迟开销,请求数比字节数更值钱**。

### 13.2 三项改动(已实现,已装真机)

| # | 改动 | 位置 | 预期 |
|---|---|---|---|
| 1 | 播放期带宽打点 `echo-iso-bps:<10s窗口>:<累计平均>:<需要>`,10s 一条、单会话上限 60 条 | `IsoThroughput` + `IsoBandwidthLogger` | 下次日志有真实吞吐 |
| 2 | 播放稳定 6s 后后台预热 playlist 全部 `.clpi`(8 个 ≈80KB) | `IsoPrepare` | 跨段 seek −0.5~0.6s |
| 3 | 卷快照落盘(`cacheDir/iso-volume/<len>_<sha256>.snap`,下次起播跳过 UDF 解析;现改为 `filesDir`,见 §13.6) | `IsoVolumeSnapshot`/`CachedVolume`/`IsoDiskCache` | 重启后起播 4.6s → ~1.2-1.5s |

#### 13.2.1 首轮真机结果(2026-10-20 05:06-05:09)

| 项 | 结果 |
|---|---|
| ① | **生效**。但需求值算错(用了「剩余字节 / 全长时长」⇒ 3.36MB/s,应 3.69MB/s),且 10s 窗口值在 1.3↔13MB/s 之间跳(ExoPlayer 蓄满即停,单条无意义)⇒ 改为「完整 playlist 字节数」为分母,并在同一行加「会话累计平均」 |
| ② | **生效**。`echo-iso-clpi-prefetch:8/8:947`(8 个 CLPI 947ms);随后的重建 seek `seek-rap:707455 playing=true` 前**不再出现** `echo-iso-clpi:00016` ⇒ 0.5-0.6s 的 CLPI 读被省掉 |
| ③ | **未生效**。`cache/iso-volume` 目录已建但为空,`echo-iso-cache-save` 一条都没有 |

③ 的根因与修复:

- 旧代码 `runCatching { save() }` 把异常吞了,无任何日志 ⇒ 新增 `echo-iso-cache-fail:<异常>` / `-oversize` / `-rename-fail` / `-write-fail` / `-decode-fail` / `-skip:<n>`;
- **真实健壮性缺陷**:`snapshot()` 原用 `list(bdmv)`,BDMV 下任意一个无关条目(BACKUP、MovieObject.bdmv…)解析失败就会让整个快照抛异常(`list()` 是原子的),而 `IsoLocator` 只需要 STREAM/PLAYLIST/CLIPINF ⇒ 改为 `UdfVolume.listAvailable()` 逐条容错,坏的跳过并计数;
- `UdfParser` 的 span 缓存 8 → 16,让快照构建时目录数据仍在缓存中(去掉那 2.7s 的后台重读)。

> 三次起播耗时 4.93s / 5.33s / 6.92s,差异来自网络波动(TTFB 1.09→1.55s、首读 1.14→1.68s),不是回归。

#### 13.2.2 次轮真机结果(2026-10-20 05:15-05:16):失败原因抓到了

```
05:15:30.812  echo-iso-cache-fail:HttpDataSourceException:java.net.ProtocolException: unexpected end of stream
05:15:31.833  echo-iso-clpi-prefetch:7/8:1021        ← 同一窗口预热也掉了 1 个
```

即:6s 之后在后台**新发起**的那十几次 Range 读,被 5266 本地代理截断(`unexpected end of stream` = 响应体短于 Content-Length)。同期预热也只成功 7/8,说明那几秒代理确实在断流 —— 不是快照逻辑的错。

修复(三点):

1. **快照改为解析时同步生成**:`parse()` 结束前就地构建(此时 UDF 的 span 缓存里还留着 root/BDMV/STREAM/PLAYLIST 与 5 个 mpls,只有 CLIPINF 目录是新的 —— 而 CLIPINF 目录本来就是 `locateSeek` 紧接着要读的,净增延迟 ≈0)。后台任务只负责 6s 后**写文件**(纯 I/O),不再发起任何网络读;
2. `DataSourceRangeReader.read` 加**一次重试**(403/410 不重试,其余 IOException 重连一次),对启动解析路径同样有益;
3. `snapshot()` 改返回**可空**,且要求 STREAM/PLAYLIST/CLIPINF 三个目录的列举完整(有任一 entry 解析失败即整份作废)。这条是安全性要求:一份"缺条目的快照"会让下次起播算出**不同的选片/选段**,静默播错内容,宁可放弃缓存。

#### 13.2.3 三轮结果(2026-10-20 05:19-05:20,**③ 生效**)

```
05:19:39.029  echo-iso-target:00016.m2ts:7406712832:5386985472      ← 冷启动,解析完成
05:19:39.242  echo-iso-cache-build:9512:212                          ← 快照 9,512B / 212ms
05:19:39.454  echo-iso-clpi:00016.clpi:rap=426:...                   ← CLIPINF 目录已在缓存,只读 CLPI 文件
05:19:41.701  echo-exo-codec-init                                    ← 冷启动 codec-init(5.29s)
05:19:45.255  echo-iso-cache-save:9512                               ← 6s 后落盘(纯 I/O)
05:19:46.028  echo-iso-clpi-prefetch:8/8:771                         ← 预热 8/8

05:20:29.664  echo-iso-prepare-rap:1421408                           ← 二次起播(杀进程重开)
05:20:30.475  echo-iso-m2ts:count=16:...                             ← 0.81s:4KB probe + 快照解码 + locateSelection
05:20:30.477  echo-iso-cache-hit:5:8                                 ← 5 个目录(含 CLIPINF)/ 8 段
05:20:30.754  echo-iso-clpi:00016.clpi:...                           ← 0.28s
05:20:31.105  echo-iso-seek:...                                      ← 建源 0.35s
05:20:31.709  echo-iso-lpcm-format                                   ← 首读 0.60s
05:20:31.866  echo-exo-codec-init                                    ← 二次起播 codec-init(2.20s)
```

| 指标 | 冷启动 | 二次起播 | 变化 |
|---|---|---|---|
| prepare → codec-init | **5.29s** | **2.20s** | −3.09s(−58%) |
| 其中 UDF 解析 | 1.6-2.6s | **0**(快照命中,只剩 mpls 内存重解析) | |
| 快照构建 | 212ms(冻结在解析路径,与 CLIPINF 目录读合并) | — | |
| 快照大小 | 9,512B | — | |
| CLPI 读 | 0.21s | 0.28s(未内联,仍走网络) | |

剩余 2.20s 的构成:4KB probe 的 TTFB ≈0.5-0.8s(无法再省,是"认文件"的最小成本)+ CLPI 0.28s + 建源 0.35s + 首读 0.60s。

带宽打点同时修正后:需求值 `3,720,083` B/s(≈3.72MB/s,与 37.86GB/9964s 相符),旧值 `3,365,487` 作废。**累计平均**在缓冲填充期偏高(实测 27s 内 10.8→11.5→9.6MB/s),稳态吞吐需播 2-3 分钟后再看,不能拿前 30s 的数判倍速。


设计要点:

- 快照只存 **BDMV / STREAM / PLAYLIST / CLIPINF 四个目录的条目**(名字、extents、≤8KB 的嵌入数据),不存 m2ts 数据;本盘约 10KB/文件,上限 8 个、1MB 单个,超限按 `lastModified` 淘汰;
- 缓存键 = `总长度 + 前 4KB 的 SHA-256`(即 `isoFingerprint`),URL/凭证变化不影响,文件被换则长度或前缀变化 ⇒ 自动失效;`locateSelection` 抛错 ⇒ `echo-iso-cache-stale` + 删文件 + 回退完整解析;`decode` 失败(含**版本不符**)⇒ `echo-iso-cache-decode-fail` + 删文件 + 回退完整解析。版本号语义见 §14.7;
- `CachedVolume` 实现与 `UdfVolume` 同一接口 `IsoFileSource`,`IsoLocator` 不感知来源;CLPI 仍按需读(仅在预热未覆盖时付 0.3-0.5s);
- 后台任务单线程 + 令牌取消:新建 ISO 源即 `IsoPrepare.cancel()`;预热与快照写入都在**起播 6s 之后**,不与首缓冲抢带宽;
- `IsoLocator.accessPoints` 改为 `ConcurrentHashMap` + 每 clip 一把锁,允许后台预热与前台 seek 并发而不重复拉取。

**明确不做**:播放中预取下一段首 1MB(1x 下链路 3.8MB/s 已被 3.69MB/s 占满,预热会抢带宽);8 段拼成一条连续流(每段 PTS 基准都是 4200.000s,拼接处时间戳回跳 1421s,`TimestampAdjuster` 必然判为断流并重建解码器 ⇒ 现有「每段一个 period」才是正确形态)。

### 13.3 剩余待补的验收数字

- **跨段 seek 耗时**:CLPI 预热已证明能省掉那 0.5-0.6s(05:06:59 那次重建前不再出现 `echo-iso-clpi`),但尚未在干净样本里量到"2.23s → ?";
- **稳态吞吐**:需连续 1x 播 2-3 分钟,看 `echo-iso-bps` 的累计平均值是否收敛到 ≈3.72MB/s,再切 3x 观察是否掉缓冲;
- 跨段拖动**没有**落在预热窗口内(刚起播就拖)时的降级表现;
- 多盘/换片时快照淘汰(上限 8 个)是否合理。

### 13.4 第 6 项:去掉重复 resolve(2026-10-20)

`IsoDataSource.open` 与 `M2tsDataSource.open`(经 `factory::resolve`)各解析一次,每个 MediaSource 打开要跑两遍 `probe + resolve`、打两行 `echo-iso-seek-rap`(一次 prepare 6 行)。

改法:把「probe → resolve → 发布 selection → 记住 target」收进新的 `IsoTargetProvider`,由 `IsoDataSource.Factory` 持有一个实例,`resolve()` 用 `AtomicReference` 记忆化。`IsoDataSource` 只拿 `(Uri) -> IsoPlayTarget`。

- 效果:`echo-iso-seek-rap` 每个 MediaSource 从 2 行变 1 行;
- 语义不变:`IsoPlaylistState.update` / `IsoThroughput.setTarget` 仍在该 factory **第一次**解析后、首次读之前执行(跨段重建会新建 factory,照旧重新发布);
- 测试:`IsoTargetProviderTest` 三条,其中 `repeatedResolve_opensVolumeOnce` 断言第二次 `resolve` **不再新增 DataSource**(即零网络)。

### 13.5 第 4 项(并行读 mpls):决定不做

结论:**不做**。理由三条:

1. **收益已被 #3 吃掉**——快照命中后,5 个 mpls 从内联字节里读,零网络;并行只对"某张盘第一次解析"有效,省约 0.3s,且一辈子只省一次;
2. **并发风险是实测过的**——串行的后台突发(不是并发)就已经让代理截断响应(`unexpected end of stream`)并令预热掉到 7/8;Quark 对同一文件的并发 Range 不友好;
3. **上限不在线程而在链路**:NanoHTTPD 每请求一线程、无池上限;上游 `ItvClient` 的 `maxRequestsPerHost = 10`(与 default 客户端共享同一个 Dispatcher),并发 5 个不会立刻撞上限,但会**分走那条 3.8MB/s 的链路**,与媒体读抢带宽。

如果将来非要并行:只能"快照缺失时 + 并发上限 2 + 任一失败即整体放弃"。

### 13.6 目录快照改存 `filesDir`(2026-10-20 22:40)

**起因**:用户问「可以将快照挪 filesDir 吗」。核查后确认这不是洁癖问题,而是收益真的会被白丢:

- 快照的价值在 §13.2.3 量过 —— 命中后 prepare→codec-init **5.29s→2.20s**,省掉的正是 1.6–2.6s 的 UDF 往返;
- 而它原来住在 `cacheDir/iso-volume`,凡是清 `cacheDir` 的都会把它带走:应用自己的「清除缓存」(`SettingsPage.kt:145` → `FileUtils.clearCache()`,`FileUtils.kt:326` 遍历删 `cacheDir` 全部子项)、系统设置/应用信息里的"清除缓存"、低存储回收;
- ⇒ 越是常清缓存的用户,越要每次吃满那 3 秒。`filesDir` 只有"卸载/清除数据"才没,符合"重建代价高、体积小"的派生数据该待的地方(项目里 `JarLoader` 的 `filesDir/csp/`、`AppBootstrap` 的 `filesDir/<md5>` 是同一个判据)。

**改法**(`IsoDiskCache.kt`):

| 项 | 改前 | 改后 |
|---|---|---|
| 目录 | `File(context.cacheDir, "iso-volume")` | `File(context.filesDir, "iso-volume")` |
| 淘汰 | 靠系统清 cache + 自身 `MAX_FILES=8`/`1MB` | **只剩自身淘汰**(`lastModified`,≤8 个,总量 ≤8MB) |
| 旧位置 | — | 首次 `directory()`(或首次清缓存)整目录删并打 `echo-iso-cache-legacy-drop:<n>`(见下"为什么非要删") |
| 应用内「清除缓存」 | 隐式删(整个 `cacheDir`) | **显式删**:`SettingsViewModel.clearCache()` 调 `IsoDiskCache.clear()`,并把 `bytesOnDisk()` 计入显示的大小 |

**用户拍板的策略**:搬家要躲的是**用户控制不了的那些回收**(系统低存储、系统设置里清缓存),不是用户自己按的按钮。所以「清除缓存」继续清快照(语义、数字都诚实:显示大小含这 ≤8MB),`filesDir` 带来的持久化只用来抵住系统侧的无差别回收。副作用:`IsoDiskCache.clear()` 现在不再顺带建目录(`directory()` 会 `mkdirs`),改成直接算路径。

**为什么非要删旧目录**:旧 `.snap` 留在 `cache/iso-volume` 会让取证直接错 —— 档案上文(甲)教人用 `run-as … cat cache/iso-volume/<len>_<hash>.snap` 拉快照,目录里还剩旧文件的话,会读到"看似有效、实际已不被使用"的快照,进而把"缓存没生效"误判成"缓存生效了"。顺便也回收那几 MB。

**顺带查到的两件事**:

1. 真机上当时正躺着两份旧快照:`103527874560_ad7facb2….snap`(79,359B,22:42)与 `47073787904_ad7facb2….snap`(25,204B,22:10)。两份**指纹完全相同**,且等于 `sha256(4096 字节 0)` ⇒ **BD 盘前 4KB 是全零**,`isoFingerprint` 的身份成分实际只剩总长。同长的不同盘会互撞。已记入规范 `§9-10`(已知风险,未修);
2. 规范 §3 把 `IsoDiskCache.kt` 描述成"目录快照与 clpi 缓存",实际它**完全不碰 clpi**(clpi 预取是 `IsoPrepare` 写内存 Map),一并改正。

**验证**:

- `IsoDiskCacheTest` 5 → **9** 例(新增 `dropLegacy_removesOldCacheDirectory`、`dropLegacy_absentDirectory_returnsZero`、`bytesIn_countsSnapshotsOnly`、`bytesIn_missingDirectory_returnsZero`,走 `dropLegacyInto`/`bytesIn` 注入目录,不依赖 Context);
- 全量 **1302 通过 / 0 失败 / 3 跳过**;`null_assert_gate` PASS;`i18n_gate` TOTAL 44;`assembleDebug` 55,590,382B,已装 V2425A(`10AF1J04JX0016G`)。
- **未在设备上跑过**(要点播放/进设置才触发):预期下次 ISO 起播先完整解析一次(旧位置无 `files/iso-volume`)、日志出现一次 `echo-iso-cache-legacy-drop:2`、`cache/iso-volume` 消失、6s 后 `echo-iso-cache-save` 落在 `files/iso-volume`;点「清除缓存」后归零且 `files/iso-volume` 变空(显示口径不变:旧位置的 104KB 本来就在统计里,只是换了盘上的位置)。



### 13.7 「硬解失败」是假警报:音频解码失败被当成视频硬解失败(2026-10-20 22:55)

**现象**:《拯救大兵瑞恩》4K DV 续播(39,574ms)时弹「硬解失败,已切换软解重试」,但画面一直是硬的。

```
22:55:10.958  echo-iso-cache-hit:5:1                              ← 快照命中,续播 39,574ms(RAP 35,993)
22:55:13.959  echo-exo-player-error: code=ERROR_CODE_DECODING_FAILED,
              msg=FfmpegAudioRenderer error, index=3,
              format=Format(1/4359, …, audio/vnd.dts.hd, …, [6, -1, 48000]),
              format_supported=YES | cause[0]=FfmpegDecoderException: Error decoding
22:55:13.961  echo-autoRetry hard->soft decode: …
22:55:16.159  echo-exo-codec-init: name=c2.qti.hevc.decoder kind=HARDWARE preferSoft=true
22:55:20.186  echo-player-audio-codec: mime=audio/ac3 name=ffmpeg.ac3.decoder …       ← 重试后音轨变英文 AC-3
```

**根因(两层)**:

1. **归类错**:`PlayerEngine.classifyError` 只看错误码名(`ERROR_CODE_DECOD…` → `ERROR_KIND_DECODE`),不区分是哪类轨;`PlaybackRetryDelegate.autoRetry()` 里 `exoErrorKind != ERROR_KIND_NETWORK` 就调 `trySoftDecodeFallback()`,于是①把整台播放器切软解、②弹 `player_decode_fallback_tip`。实测**视频始终是 `c2.qti.hevc.decoder` 硬解**(`preferSoft=true` 也没换掉它),提示与行为都不成立。
2. **音频轨真的解不了**:选中的是 PID 4359(中文 DTS-HD MA 5.1),media3 的 `DtsReader` 把这条轨判成 `audio/vnd.dts.hd`,ffmpeg 的 dca 处理不了 ⇒ `FfmpegDecoderException`。同一部片 **22:43 从 0 起播时同一条轨被判成 `audio/vnd.dts`(core),正常出声**;22:57 从 424,630ms 续播也没复现 —— 推断 mime 取决于起读点是否落在带 EXTSS 的帧上(`DtsReader` 按首帧头定 mime),但**未取到 ffmpeg 原生日志**(设备 logcat 环形缓冲已把该行滚掉),所以只记"未根治"。

**修复(1+2,按用户要求一起做;提示文案定为「音轨无法解码,自动切换」)**:

| 改动 | 位置 | 说明 |
|---|---|---|
| 失败轨类型 | `PlayerEngine` | `classifyTrackKind(error/mime)` 读 `ExoPlaybackException.rendererFormat.sampleMimeType`,记 `lastErrorTrackKind` / `lastErrorFormatId`,经 `ExoPlayer` 包装层暴露;契约常量 `ERROR_TRACK_*` |
| 音频专用回退 | `PlaybackRetryDelegate` | `tryAudioTrackFallback()`:`ERROR_KIND_DECODE` + 音频轨 ⇒ 拉黑该 PID → 重建 → toast `player_audio_track_fallback_tip`;**不碰** `exo` 解码设置;音频分支**直接 return**(不再往下走"切内核/换线路"),`trySoftDecodeFallback()` 里另加"音频直接 return false"兜底 |
| 拉黑落地 | `IsoAudioFallback`(新) | 按内容(VOD URL,`Uri.parse(...).toString()` 归一)记 PID;`IsoExtractorsFactory` 把内容键传进 `PrimaryVideoExtractor`,后者对拉黑音频 `return DiscardTrackOutput` 并打 `echo-iso-audio-skip:<pid>` |
| 诊断日志 | `PlayerEngine.reportAudioFormat` | 每次选中的音轨变化打一条 `echo-iso-audio-format:<id>:<mime>:ch=`(这正是这次缺的证据) |

**验证**:`IsoAudioFallbackTest` 6 例、`PrimaryVideoExtractorTest` 17→20 例、`PlayerEngineErrorTest` 9→11 例;全量 **1313 通过 / 0 失败 / 3 跳过**;`null_assert_gate` PASS;`i18n_gate` TOTAL 44(新文案三套 values 齐);`assembleDebug` 55,939,524B,已装 V2425A。

**真机复现(23:11,修复后第一次触发)**:整条链路按设计走完,并顺手把"为什么间歇"这个问题答了:

```
23:11:48.195  echo-iso-cache-hit:5:1                       ← 快照命中,续播 634,210ms
23:11:50.911  echo-iso-audio-format:null:audio/vnd.dts:ch=6      ← 起读时是 core
23:11:51.069  echo-exo-codec-init: c2.qti.hevc.decoder kind=HARDWARE preferSoft=false
23:11:51.145  echo-exo-player-error: … FfmpegAudioRenderer error, format=…audio/vnd.dts.hd…  ← 234ms 后同一轨升级成 dts.hd
23:11:51.146  echo-autoRetry audio track fallback:4359            ← 新逻辑,不再硬切软解
23:11:52.334  echo-iso-audio-skip:4359                            ← 拉黑生效,该轨不再声明
23:11:53.481  echo-iso-audio-format:null:audio/ac3:ch=6           ← 自动落到 AC-3(TrueHD core)轨
23:11:53.524  echo-exo-codec-init: … preferSoft=false             ← 解码设置没被动
23:11:55.337  echo-progress sample: pos=633599                    ← 续播继续,此后无 error
```

**由此更正了之前的推断**:不是"续播落点"的锅,而是**同一条轨在播放中途被 `DtsReader` 升级**:起读那几十毫秒是 core 帧(`audio/vnd.dts`),遇到第一个 EXTSS 帧后 media3 重新发一条 `audio/vnd.dts.hd` 的 Format,ffmpeg 随即解不动。所以"什么时候遇到 EXTSS"决定复现与否 —— 这与"39.5s 失败、424.6s 正常"完全吻合,也解释了 `echo-iso-audio-format` 为什么先是 dts 再变 dts.hd。

**副作用(须知)**:回退后该内容的中文 DTS-HD 轨在本次会话内被一直拉黑,音轨落到英文 AC-3;这是有意的(见 D10),但意味着要根治必须做 §9-11 的"只送 core"。

**根治(同轮,方案 A)**:`0x85`/`0x86` 不再交给 media3 的 `DtsReader`,改用自研 `DtsCoreReader` —— 只认 DTS core 同步字(0x7FFE8001,含 LE/14-bit 变体),用 `DtsUtil.getDtsFrameSize` 按 core 帧长截断,**EXTSS 字节在扫描下一帧时被自然跳过**,Format 由 `DtsUtil.parseDtsFormat` 生成、mime 恒为 `audio/vnd.dts`(带上 core 的声道数/采样率/码率)。这样"送解码的字节"与 23:11 之前能正常出声的形态完全一致。`0x82`(纯 DTS)仍走 media3 `DtsReader`,不受影响。

- 新日志:`echo-iso-dts-hd:…:core=1`(工厂侧,一次/盘)、`echo-iso-dts-core:ch=<n>:rate=<n>`(reader 首次发 Format);
- 单测 `DtsCoreReaderTest` 4 例:core+EXTSS 交替时只出 core 样本、垃圾前缀可重新同步、跨 PES 的帧拼得回来、`seek()` 后仍在找同步且不重发 Format;
- 坑:media3 1.11 的 `ParsableByteArray.readUnsignedByte()/readBytes()` 会触发测试期断言(`IsRunningInTestHolder` 在本项目 JVM 单测里初始化失败),所以 reader 里改用 `data.data` + `position/limit` + `skipBytes`,与 `Ac3CoreReader` 的写法一致;
- **真机验证通过(23:22)**:同一部片、同一续播点(684,637ms),日志::

```
23:22:27.619  echo-iso-dts-hd:type=134:lang=null:core=1        ← 0x86 走 core 路径
23:22:29.666  echo-iso-dts-core:ch=6:rate=48000                 ← 三条 DTS-HD 轨各一条(4359/4360/4361)
23:22:29.676  echo-iso-audio-format:null:audio/vnd.dts:ch=6      ← 全程 core,不再变 dts.hd
23:22:29.738  echo-exo-codec-init: c2.qti.hevc.decoder HARDWARE preferSoft=false
23:22:31.668  echo-music session gate: PLAYING hasAudio=true audioPlayback=true
23:22:38.042  echo-bar-draw: 689s → 690s                         ← 进度正常推进
```

  全程**没有** `echo-exo-player-error`、`echo-autoRetry audio track fallback`、`echo-iso-audio-skip` —— 既没崩也没换轨。

**保留的兜底**:C13/D10 的"音频失败换音轨"仍在。若 `DtsCoreReader` 在某张盘上仍失败,回退会静默换轨而不是弹假提示。

### 13.8 字幕菜单只剩"字幕1/2/3":卷缓存命中时语言表没重建(2026-10-20 23:30)

**现象**:内置字幕列表全是 `字幕1 … 字幕15`,没有英语/国语/日语;同一部片有时候又正常("概率性时好时坏")。

**日志时间线(同一次启动、同一张盘)**:

```
23:30:12.473  echo-iso-lang:pid=4770:type=3:lang=zho      ← 第一次 prepare,15 条 PGS 全查到语言
23:30:16.125  echo-setTrack subtitle-list: 字幕1 - 英语 PGS … 字幕15 - 日语 PGS   ✅
23:30:20.947  echo-p2 detach page=…                        ← 退出播放页
23:30:38.063  echo-p2 attach … / onNewPlayStarted          ← 重新进同一集
23:30:38.533  echo-iso-prepare-rap:0                       ← 重建媒体源(IsoPlaylistState.reset())
23:30:39.411  echo-iso-lang:pid=4352:type=1:lang=-         ← 连音轨都变成 -
23:30:39.420  echo-iso-lang:pid=4768:type=3:lang=-         ← 字幕也 -
23:30:44.093  echo-setTrack subtitle-list: 字幕1 PGS …     ❌
```

第二次 prepare 期间**没有任何** `echo-iso-cache-hit` / `echo-iso-playlist` / `echo-iso-streams` / `echo-iso-stream-lang`。

**根因**:三件事凑到一起 ——

1. `MediaSources.createIsoMediaSource()` 每次都 `IsoPlaylistState.reset()`,把 `pid→语言`表清空;
2. 该表只在 `IsoLocator.locateSelection()` 里 `publishStreams()` 发布;
3. 第二次播放同一张盘时 `IsoResolver.resolve()` 命中 `IsoVolumeCache`(进程内 LRU 4 条 / TTL 10 分钟,`IsoResolver.kt`),**直接返回缓存里的 `IsoVolumeInfo`,根本不跑 locate**。

于是"清空"发生、"重建"没发生,所有轨(10 条音轨 + 15 条字幕)同时失去语言;字幕名只剩 `字幕N` + 从 `codecs` 推出来的 `PGS`。

**为什么看起来是概率**:首次冷进(或超过 TTL、缓存被挤出)必好;退出重进、换线路重播、10 分钟内再播必坏。23:22 那次看似正常,是因为走的是**磁盘快照**(`echo-iso-cache-hit`),那条路仍然会跑 `locateSelection()` —— 磁盘快照只缓存 UDF 目录树,不缓存选片结果。

**修复**:把语言表挂进 `IsoVolumeInfo`,在 `IsoResolver.resolve()` 里每次(含缓存命中)重新发布。

| 改动 | 位置 | 说明 |
|---|---|---|
| 记住语言表 | `IsoLocator` | `selectedStreams` / `selectedVideoPid`,在 `locateSelection()` 里设值(不再直接 publish) |
| 随缓存带语言表 | `IsoVolumeInfo` | 新增 `streams` / `videoPid` 两个字段,`loadVolume()`(磁盘快照命中)与 `parse()` 两条路径都填 |
| 每次会话重发 | `IsoResolver.resolve()` | `publishStreams(info.streams)` + `publishVideoPid(info.videoPid)`,在 seek 对齐**之前**执行 |

**判据日志**(本轮新增,常驻):

- `echo-iso-stream-lang:<n>` —— 本次发布会话发布了多少条;坏会话**没有**这一行;
- `echo-iso-lang:<pid>:<type>:<lang>` —— 每条轨建出时按 PID 查到的语言,`lang=-` 即表是空的(**诊断期临时加,同轮末尾按用户决定删除**:它是唯一随重建次数增长的日志,每次重建 25 行;判据改由 `echo-iso-stream-lang` 是否出现 + `subtitle-list` 名字覆盖);
- `echo-setTrack subtitle-list: count/selected/names` —— 字幕列表的最终名字(音轨早有 `echo-setTrack list`,字幕一直没有);
- `echo-iso-pgs-format:pid=…:lang=…` —— 原来只有一行无参数的 `echo-iso-pgs-format`,顺带补上 PID 与 PMT 描述符语言(实测恒为 `-`,BD 的 PG 语言只在 STN 里)。

**验证**:新增 `IsoResolverTest.resolve_republishesStreamLanguagesOnCacheHit`(人为把带 streams 的 `IsoVolumeInfo` 塞进 `IsoVolumeCache`,再 `IsoPlaylistState.reset()`,断言 resolve 之后 `languageForPid` / `videoPid` 都回来了);ISO 包单测通过,门禁 PASS;`assembleDebug` 55,939,524B 已装 V2425A。

**真机复验(23:37,同一张盘 10 分钟内第二次 prepare)**:

```
23:37:29.382  echo-goPlayUrl:…（重新播这张 ISO）
23:37:29.391  echo-iso-prepare-rap:56106          ← 第二次 prepare ⇒ IsoPlaylistState.reset()
23:37:29.971  echo-iso-stream-lang:count=25       ← 580ms 后语言表就重新发布了（卷缓存命中，无 udf/mpls/playlist 日志）
23:37:29.972  echo-iso-seek-rap:56106:52764:00005.m2ts:210152640
23:37:30.256  echo-iso-lang:pid=4352:type=1:lang=eng … 4361:zho   ← 10 条音轨全有语言
23:37:30.262  echo-iso-lang:pid=4768:type=3:lang=eng … 4782:jpn   ← 15 条字幕全有语言，lang=- 0 条
23:37:33.958  subtitle-list: 字幕1 - 英语 PGS … 字幕15 - 日语 PGS   ✅
```

对照修复前**同一条路径**(23:30:38.533 第二次 prepare → 23:30:39.411):25 条轨全是 `lang=-`,且没有 `echo-iso-stream-lang`;列表退化成 `字幕1 PGS …`。两边唯一差别就是这次修复。

顺带记一个**踩坑**:第一次复验时用户"退出播放页再进同一集",日志出现 `echo-p3 take over same playback`(`sameOwned=true`)—— 页面直接接管旧播放,**不重建媒体源**,既不复现旧 bug 也不验证修复。要走到 `IsoVolumeCache` 那条路,必须真正触发第二次 `prepare()`(换线路、或播别的条目再回来)。
