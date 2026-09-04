# V5 Native 持续增长：已复现的空域多边形资源泄漏

后续修复和实测结果见 `NATIVE_MEMORY_FIX_2026-08-31.md`。以下保留定位阶段原始证据。

日期：2026-08-31。只操作 `emulator-5554`，未连接户外手机。

## 核心结论

定位到百度地图 Android SDK 8.2.0 默认 Overlay 2.0 路径中，多边形删除后的 Native 子对象没有完整释放。
这不是历史轨迹点数增多导致的 CPU 负担，也不是仅凭总内存上涨推测。
通过对象计数、创建/释放对照和健康的 heapprofd 原生分配栈三种证据确认。

放大链路：

1. `MapWidgetModel.locationPoller` 每 500 ms 把返航点再次送入 `homeLocationDataProcessor`，没有值去重。
2. `MapWidget.reactToModelChanges` 把空域列表和返航点 `combineLatest`，再调用 `onFlyZoneListUpdate`，没有对相同列表去重。
3. `FlyZoneMapHelper.onFlyZoneListUpdate` 无条件先删除、再重建全部空域覆盖物。
4. 百度 `Polygon.toDrawItem()` 创建 `BmPolygon`、`BmGeoElement`、`BmLineStyle`、`BmSurfaceStyle`。
5. `Overlay.remove()` 关闭外层 `BmDrawItem`；`BmDrawItem.close()` 只调用父类关闭外层 Native 实例，未遍历 `Polygon` 持有的几何/样式子包装对象。
6. `BmObject` 是需要 `close()` 的 `AutoCloseable`；检查 SDK 字节码，没有自动 finalizer/Cleaner 注册替这些丢失的子包装对象释放原生实例。Java 对象被 GC 不等于这些 Native 分配释放。

因此相同空域被重复重建时，Java 堆可以保持平稳，Native 原生实例却累积。飞控执行、视频接收并不是触发此漏洞的必要条件。

## 叠加的第二个泄漏

`FlyZoneCategory` 已包含 `UTMISS_REGULATION`、`UTMISS_LAW_ALLOW`，但 `FlyZoneMapHelper.addPolygonToMap()` / `addCircleToMap()` 只登记四类旧类别。
这两类会绘制，却掉进 `default` 而不登记；下次 `removeFlyZonesOffMap()` 无法找到旧覆盖物。
它们不仅漏 Native 子对象，连外层百度地图覆盖物也继续被地图内部列表持有。
UNKNOWN 和同一刷新内重复 ID 也需要在后续生命周期修复中覆盖，不能只补两个枚举名字。

## 实验：固定 10 个多边形，重复 100 次相同刷新

文件：`FlyZoneNativeMemoryInstrumentedTest.kt`。真实百度地图、真实 `FlyZoneMapHelper`，只输入合成空域显示数据，不查询/解锁/修改任何飞机空域。

| 条件 | helper 清理后仍被百度持有的多边形 | Native 增量 MiB |
|---|---:|---:|
| RESTRICTED，原始 remove 路径 | 0 | +14.85 |
| UTMISS_REGULATION，原始路径 | 1000 | +34.42 |
| UTMISS_LAW_ALLOW，原始路径 | 1000 | +34.12 |
| UTMISS，相同数据只绘制一次的去重对照 | 10 | +0.35 |
| RESTRICTED，删除后显式关闭 Native 子对象的诊断对照 | 0 | +0.01 |

最后执行 map.clear、等待 3 秒、GC 和 finalization，之前泄漏的 Native 仍没有被补收回；并非只是在等待普通 Java GC。
去重对照仍留下 10 个未登记外层对象，说明“只去重”只能止住高频增长，不是完整生命周期修复。

## 原生栈证据

`flyzone-native.pftrace`：80 秒，256 KiB 采样间隔、64 MiB 共享缓冲。
没有 `heapprofd_buffer_overran` / `heapprofd_client_error`。

采样中 `FlyZoneMapHelper.onFlyZoneListUpdate → drawSubFlyZones` 路径保留分配约 34,603,008 字节；下游主要位于：

- `BmLineStyle.a`
- `BmGeoElement.<init>` / `BmGeoElement.a`
- `BmSurfaceStyle.a`

这是同一实验中分配/释放后的净值，不是把累计分配量说成泄漏；调用栈统计为包含式，不能重复相加。
此前发现的 2026-08-27 真机 trace 虽因丢样不能独立作定量结论，但同样出现 `FlyZoneMapHelper.drawSubFlyZones → Polygon.toDrawItem → BmLineStyle/BmSurfaceStyle/BmGeoElement`，与新复现吻合。

## DJI 视频路径的隔离结果

另新增 `DjiNativeVideoMemoryInstrumentedTest.kt`，直接喂入 Annex-B H264 到真正的 DJI `StreamDecoder → GLFrameDispatcher → ImageReader`，不是 MediaPlayer 替代。
完成 1049 个解码输出、116 个 RGBA 回调和 26 次监听创建/释放，Native 未呈现同样的持续上升；释放 decoder 后还下降约 5 MiB。
它不排除特定手机 GPU 驱动、实际 DJI SEI/无线链路的额外问题，但已无需用“视频可能泄漏”来代替这个已定位的空域 Native 泄漏。

## 历史真机增长的边界

2026-08-30 日志显示 Native 292→1286 MiB，Java 未同步线性增长。已定位漏洞的触发方式、增长特征和旧真机栈与之吻合。
当时没有记录每次空域类别/面片数量，也没有完整覆盖那 65 分钟的原生采样，不能声称已逐字节归因全部约 1 GiB。
但这是当前工程中确定可重复、并通过资源释放对照消除的 Native 泄漏，不再只是卡顿猜测。

## 后续修复方向（本轮未改生产实现）

- 对未改变的返航点/空域几何去重，避免 500 ms 重建一次。
- 所有类别、所有实例进入统一资源回收管理；分类筛选和资源所有权分离。
- 对百度 8.2.0 的多边形 Native 生命周期单独修复或规避。官方公开 `OverlayUtil.setOverlayUpgrade(false)` 可选择旧覆盖物渲染路径，应先做相同回归再决定是否采用；不要未经兼容验证把诊断用反射 close 直接放入生产。
- 不关闭飞控地理围栏、不隐藏空域、不降低图传或飞控频率来掩盖问题。

证据目录：`output/diagnostics/native-growth-root-20260831/`，包含样本、instrumentation 结果、健康检查、分配栈和 trace。
