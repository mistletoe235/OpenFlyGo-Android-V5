# Native 空域覆盖物泄漏修复（2026-08-31）

本修复针对 `NATIVE_MEMORY_ROOT_CAUSE_2026-08-31.md` 中已复现的泄漏；不是用轨迹降采样或降低飞控/图传频率代替资源修复。

## 生产改动

1. `BaiduProvider` 在创建地图前调用百度公开的 `OverlayUtil.setOverlayUpgrade(false)`，规避 8.2.0 Overlay 2.0 的 Polygon 子 Native 对象释放问题。没有把诊断用的反射 `close()` 注入生产。
2. `FlyZoneMapHelper` 使用按实例身份管理的统一所有权表回收所有空域圆/多边形，独立于类别和 ID。UTMISS、UNKNOWN、新类别及重复 ID 都不会落在清理之外。
3. 新增 `FlyZoneRenderSnapshot`，复制与显示有关的不可变值，包括 ID、类别、坐标、半径、限高及子多边形顶点。相同内容不重建；SDK 原地修改同一对象时仍能识别变化。
4. 返航点在进入空域 `combineLatest` 前复制并值去重，避免 500 ms 轮询反复触发同一空域重绘。
5. 地图销毁前主动清理常规/自定义空域、标记及所有权表。

没有关闭空域显示，没有修改飞机围栏/解禁规则，没有修改航线控制或视频取帧数据。
经典地图标记、航线、区域多边形仍由百度 8.2.0 绘制，只切换覆盖物实现路径。

百度公开接口说明：[Android SDK 更新日志，V7.5.9 起公开 OverlayUtil.setOverlayUpgrade](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Ftheupdatelog)。今后升级地图 SDK 或采用仅 Overlay 2.0 支持的新覆盖物类型时，必须重新验证该兼容策略。

## 模拟器回归

仅 `emulator-5554`，没有安装或操作户外手机。

### 相同数据 100 次刷新

每种类别固定 10 个多边形，覆盖 SDK 的全部 7 类。断言同一个多边形实例持续复用，清理后地图内部计数为 0。

| 类别 | 修复后 Native 增量 MiB | 清理后多边形 |
|---|---:|---:|
| WARNING | +0.658 | 0 |
| AUTHORIZATION | +0.086 | 0 |
| RESTRICTED | +0.154 | 0 |
| ENHANCED_WARNING | +0.022 | 0 |
| UTMISS_REGULATION | +0.015 | 0 |
| UTMISS_LAW_ALLOW | +0.026 | 0 |
| UNKNOWN | +0.021 | 0 |

原始实现同样测试：RESTRICTED +14.85 MiB；两类 UTMISS 各约 +34 MiB 且残留 1000 个多边形。

### 原地修改坐标 + 重复 ID

同一 SDK 坐标对象每次原地修改，且 10 个子多边形使用相同 ID，继续每类刷新 100 次。
所有类别保持 10 个当前对象，清理后为 0；Native 增量 -0.129～+0.408 MiB，未再出现线性增长。
测试另断言变化后替换了旧多边形实例，防止去重误吞真实几何变化。

### 证据与运行

最终构建通过，65 项相关 JVM 单测 0 失败。最终合并 instrumentation 运行约 200 秒，两项测试通过：所有类别的变化几何/重复 ID 回收与实例替换断言，以及地图/航线界面 + H264 回放。
其中地图/视频采样约 59.7 秒，实际 TextureView 更新 243 次，Native 138.48→139.54 MiB；这是模拟器测试，不把输入文件 30 fps 当作实际渲染频率，也不外推为真机长航程验收。

- `FlyZoneNativeMemoryInstrumentedTest`：`verify_fixed=true`；变化测试另加 `mutate_geometry=true duplicate_ids=true`。
- `SurveyVideoSoakInstrumentedTest`：继续用于真实航线 UI/地图 + 合成移动 GPS + H264 回放并行检查，不代表真实无线链路或飞行验收。
- 数据：`output/diagnostics/native-memory-fix-20260831/`。
- APK：`app/build/outputs/apk/full/debug/app-full-debug.apk`。

修复后的模拟器证据只证明已定位的泄漏路径得到修复；真实手机跨电池、多任务长时间飞行仍需回归。已有进程中丢失引用的 Native 对象不能被新逻辑追溯释放，应在安全落地、结束任务后更新并重新启动 App。
