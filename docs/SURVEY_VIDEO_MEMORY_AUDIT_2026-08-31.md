# V5 航线 + 视频长期运行诊断（2026-08-31）

后续进展：已通过空域多边形创建/释放对照定位 Native 泄漏，见 `NATIVE_MEMORY_ROOT_CAUSE_2026-08-31.md`。本文保留此前地图/视频初筛记录，“尚未定位”仅代表该初筛阶段。

## 范围与结论边界

本轮只操作 `emulator-5554`，没有连接、安装、重启或控制户外手机/飞机。
生产控制逻辑没有在本轮修改；新增的是 emulator-only instrumentation 诊断。

两件事必须分开：

1. **实际飞行的 Native 内存持续增长已经由历史日志确认，但分配来源尚未定位。**
2. **地图历史轨迹无限累积、每次全量转换/提交导致耗时随点数增长，已经通过模拟器对照复现。**

不能把第 2 条当作第 1 条已经解释完，也不能把模拟器 Native 内存稳定当作真实 DJI 链路没有泄漏。

## 历史真机日志证据

使用之前已拉取到 Mac 的 2026-08-30 `previous.jsonl`、`current.jsonl`，没有本轮读取户外手机。
共 130 个 `process_memory` 样本：

| 时刻 | Native allocated MiB | Java used MiB | 总 PSS MiB |
|---|---:|---:|---:|
| 17:00:15 | 292.4 | 63.1 | 599.6 |
| 17:16:32 | 477.8 | 93.4 | 820.5 |
| 17:33:12 | 681.2 | 94.9 | 1018.6 |
| 17:49:14 | 958.1 | 43.1 | 1169.2 |
| 18:05:20 | 1286.1 | 98.8 | 1409.5 |

- Java 堆有回收，并没有与 Native 相同的单调增长。
- 18:03:48 后未再记录照片保存，18:04:18 的返航、18:04:48 的降落阶段，Native 仍分别增加约 14.4、12.6 MiB。
- 落地/断连时增长明显降低。故不能只归因于照片 JPEG 编码；需要真机 Native 分配栈进一步区分 SDK 解码、地图原生渲染等来源。
- 这些历史日志对应当时安装的版本；本轮模拟器使用当前工程构建，不把两者视作完全相同的运行环境。

## 模拟器方法

测试：`app/src/androidTest/java/edu/playground/djivln/ui/SurveyVideoSoakInstrumentedTest.kt`。

- 强制 `Build.HARDWARE` 为 ranchu/goldfish，且真实 DJI 连接必须为 false。
- 真实 V5 Activity、航线页、百度地图、MapWidget 历史轨迹和执行覆盖层。
- 合成移动 GPS 与 `EXECUTING` 展示状态，不调用起飞、航线上传/启动/继续、Virtual Stick 或拍照接口。
- 1440×1080、30 fps 编码的 H264 测试视频循环播放到生产 TextureView；记录真实 `onSurfaceTextureUpdated` 次数。30 fps 是输入文件帧率，不是实测渲染帧率；模拟器明显掉帧。
- 此替代视频并未经过 DJI 无线链路/原生解码器，也不覆盖真实 RGBA 回调、照片采集与上传链路。
- 5 个阶段，每阶段 60 秒：短轨迹实时覆盖层 → 2 万点长轨迹实时覆盖层 → 长轨迹冻结覆盖层 → 短轨迹冻结覆盖层 → 短轨迹实时覆盖层复测。
- 2 万点是预置长航程压力样本，不代表用户实际任务有 2 万点，也不是实际飞了同等时间。
- 合成 GPS 驱动目标为 20 Hz，但 `runOnMainSync` 会等待主线程，实际频率随负载下降；这不是独立异步 DJI 回调源，不能排除真机消息积压。视频播放独立于该驱动，但 TextureView 实际更新也严重掉帧。
- 阶段边界显式 GC，测 Native allocated、Java used、PSS、轨迹点数、marker 数、主线程地图调用耗时等。
- 第一阶段前部出现登录提示框，随后已关闭；比较以最后一次短轨迹复测和长轨迹为主。测试代码随后修正了按 Activity 语言识别并关闭可选登录提示的逻辑。

## 五分钟结果

实际采样跨度约 314.6 秒，instrumentation 完整运行约 324.7 秒。

| 阶段 | 结束轨迹点数 | 地图更新 P95 ms | UI 更新 P95 ms | GC 后 Native MiB | GC 后总 PSS MiB |
|---|---:|---:|---:|---:|---:|
| short_live | 159 | 190.7 | 475.0 | 123.4 | 320.1 |
| long_live | 20039 | 1811.2 | 426.1 | 124.7 | 324.7 |
| long_static_overlay | 20040 | 1689.7 | 246.7 | 124.6 | 324.5 |
| short_static_overlay | 220 | 171.8 | 328.9 | 122.9 | 320.5 |
| short_live_repeat | 193 | 188.2 | 401.5 | 122.8 | 320.6 |

模拟器绝对耗时不能直接换算到手机。可确认的是：长轨迹地图调用 P95 约为短轨迹复测的 9.6 倍；冻结覆盖层不能消除长轨迹瓶颈；清空轨迹后恢复。
markerMap 数量保持 10，Native 在这组实验未出现真机那种约 1 GiB 的持续增长。

代码链：`MapWidget.updateFlightPath()` 持续追加位置 → `refreshFlightPath()` 每次对完整列表调用 `setPoints()` → `BaiduPolyline.setPoints()` 每次重新分配整个坐标列表并逐点 WGS84/GCJ 转换 → 原生地图重新接收整条折线。无点数/航程上限，也没有历史段冻结或简化。
另有 `FlightFeatureController.renderExecutionOverlay()` 每次删除并重建三条线、目标 marker/bitmap 的额外开销；本轮只确认其成本，不称之为已证实泄漏。

## 原生采样限制

首次 16 KiB 采样间隔的 heapprofd 发生 `heapprofd_buffer_overran`，样本提前截断；该 trace 不能用于完整泄漏归因。
随后降低采样密度到 1 MiB，并扩大共享缓冲区复核。没有真实 DJI 解码输入，即使采样健康，也不能替代户外手机的原生栈。

低密度复核运行 85.3 秒通过，110 秒 trace 没有 client error/buffer overrun。采样净存活量在首次约 109 MB 的初始化分配后只小幅变化，未见持续线性增长。调用栈可见百度地图、DJI FlySafe、ART 等分配活动，但分配总量不等于泄漏；本轮没有证据把真实飞机上的约 1 GiB 增长归到其中某一个库。低密度原生栈用于分配检查，不使用其受采样影响的绝对帧耗时作为手机性能结论。

## 建议的修复顺序（本轮尚未修改生产实现）

1. 历史轨迹采用受控点数、历史段简化/冻结；实时尾段增量更新。只简化地图显示，完整原始 GPS 继续写日志。
2. 当前航带线、目标图标等复用；位置变化只移动 marker，航带/状态变化才更新几何或样式。
3. UI 展示刷新限频并合并待处理更新，不能降低实际飞控、日志或图传接收频率。
4. 真机结束飞行后，针对 Native 增长采集完整的分配/释放栈；分别对照 DJI 视频、取图和地图。未完成前不要声明内存泄漏已修复。

## 产物

`output/diagnostics/survey-video-soak-20260831/`：

- `samples.jsonl`：五分钟数值记录。
- `instrumentation.log`：运行结果。
- `emulator-video-map.png`：模拟器实际地图 + 视频同屏。
- `native-heap.pftrace`：首次截断的原生采样，仅保留排查记录。
- `native-heap-low.pftrace`、`native-heap-low-health.txt`：低密度健康采样及状态。
- `low-sampling-samples.jsonl`、`low-sampling-instrumentation.log`：低密度复核。
- `native-allocation-libraries.txt`：包含式分配统计，不是泄漏排行（同一分配可能计入多个调用栈库）。
