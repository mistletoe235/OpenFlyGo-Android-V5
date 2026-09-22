# SfM + Scal3R 主动补拍任务 Android 接入说明

日期：2026-08-22

## 1. 当前结论

V5 已迁移主动补拍混合任务，沿用 `SurveyMission` schema 11：

- R1-R7 的精确局部视点使用 `CAPTURE_POINT + CAPTURE_ON_REACH`，到位、稳定并拍照成功后才推进；
- R8/R9 高楼扫描和 reconstruction bridge 使用连续距离触发 pass；
- `LOCAL_OBLIQUE` 表示不属于标准五向分组的局部精确视角；
- `active_mapping` 保存区域、pass 角色和来源等解释元数据；
- schema 1-10 仍可导入，解码后 `activeMapping = null`；
- 主动补拍任务的采集组固定，App 会阻止五向筛选和普通航线重新生成覆盖任务。

研究侧的 `captureEvents` 中间 JSON 仍不能直接导入 App，必须先运行转换器。

## 2. 文件位置

研究输入和已编译任务：

```text
handoff/active_recapture/two_buildings/
  research_full_nine_region_mission.json
  recapture_regions.json
  openfly-active-recapture-two-buildings-v1.json
```

转换器：

```text
tools/compile_active_recapture_to_survey_mission.py
```

Android 主要实现：

```text
app/src/main/java/edu/playground/djivln/survey/SurveyMission.kt
app/src/main/java/edu/playground/djivln/survey/SurveyMissionJson.kt
app/src/main/java/edu/playground/djivln/survey/SurveyCaptureSchedule.kt
app/src/main/java/edu/playground/djivln/survey/ActiveRecaptureMissionValidator.kt
app/src/main/java/edu/playground/djivln/survey/SurveyCustomExecutionEngine.kt
app/src/main/java/edu/playground/djivln/adapter/dji/SurveyWpmzConverter.kt
app/src/main/java/edu/playground/djivln/ui/SurveyFeatureController.kt
app/src/main/java/edu/playground/djivln/ui/FlightFeatureController.kt
```

## 3. schema 11 执行语义

### 3.1 精确单点拍摄

单点执行单元只含一个 waypoint：

```json
{
  "kind": "CAPTURE_POINT",
  "capture_action": "CAPTURE_ON_REACH",
  "capture_view": "LOCAL_OBLIQUE",
  "capture_interval_m": null
}
```

执行器到达该点后先归零，等待相机拍照成功，再进入下一执行段。拍照失败会暂停并保留恢复能力。进程在“拍照成功但 checkpoint 尚未推进”之间异常退出时，恢复后可能保守地重复拍一张，不会默认跳过而造成漏拍。

### 3.2 连续拍摄 pass

连续 pass 保持原有协议：

- 首点：`PASS_START + START_DISTANCE_INTERVAL`；
- 中间控制点：`TRANSIT + NONE`；
- 末点：`PASS_END + STOP_DISTANCE_INTERVAL`；
- 同一 pass 的 `pass_index` 和 `capture_view` 一致；
- heading、gimbal pitch 和高度沿控制点插值，不再只使用 pass 首点姿态。

bridge 必须是正式连续拍摄 pass，不能依赖两个 pass 之间默认不拍照的 transit。

### 3.3 `active_mapping`

元数据包含：

- 原始、正式补拍和 bridge 照片数量；
- 区域优先级、风险类型、目标位置和对应 pass；
- 每个 pass 的区域、角色、采集角色、来源以及是否为重建所需 bridge。

飞行执行仍以 `waypoints` 为唯一控制真值；`active_mapping` 用于校验、摘要和地图表达。

## 4. two_buildings 当前编译结果

使用默认 `2.8 m/s` 编译，原因是最短连续事件间距约 `5.82 m`，需要满足 Mini 2 约 `2 s` 的拍照间隔。

当前结果：

- schema：11；
- waypoint / 计划照片：339；
- 正式补拍：113；
- bridge：226；
- 执行单元：60；
- 精确单点：33；
- 连续 pass：27；
- 编译后水平路径：约 5851.31 m；
- 动态估计时间：约 2632.97 s；
- 高度范围：48.47-89.45 m；
- 最大相邻距离：24.47 m；
- 最大 yaw 步进：45°；
- 最大 pitch 步进：11.25°。

转换命令：

```bash
python3 tools/compile_active_recapture_to_survey_mission.py \
  handoff/active_recapture/two_buildings/research_full_nine_region_mission.json \
  handoff/active_recapture/two_buildings/recapture_regions.json \
  handoff/active_recapture/two_buildings/openfly-active-recapture-two-buildings-v1.json \
  --wgs84-fit handoff/active_recapture/two_buildings/wgs84_fit_report.json \
  --speed-mps 2.8
```

转换器每次会更新任务 `id` 和 `created_at_epoch_ms`，其余任务内容应保持一致。
`wgs84_fit_report.json` 将研究侧 world XYZ 转为 DJI/WGS-84；不能对输出坐标再次执行 GCJ-02 反算。

## 5. 导入、预览与 UI

导入 `openfly-active-recapture-two-buildings-v1.json` 后：

- App 自动执行主动补拍专项校验；
- 任务摘要显示正式补拍数和 bridge 数；
- 精确补拍点显示橙色编号 marker；
- R1-R9 显示被补拍目标 marker，精确补拍点以虚线连接其目标；
- 高楼和普通连续采集按各自 capture view 着色；
- reconstruction bridge 按 pass 单独显示为灰色虚线；
- 不会把同一 capture view 的不同 pass 跨空白区误连；
- 五向选择按钮不会删除、重排或覆盖主动补拍任务；
- 如需规划普通任务，先明确清空当前主动补拍任务。

纯单点主动补拍任务也能计算预览 coverage，不再要求至少存在一条有长度的航带。

## 6. 专项校验

`ActiveRecaptureMissionValidator` 当前检查：

- 计划照片数等于源事件数；
- 正式照片数 + bridge 数等于总数；
- pass 元数据与实际执行 pass 一一对应；
- 区域引用的 pass 存在；
- waypoint 高度位于 5-120 m；
- 相邻 waypoint 水平距离不超过 25 m；
- yaw 步进不超过 45°；
- gimbal pitch 步进不超过 15°；
- R8/R9 具有 nadir 和四方向 oblique；
- 精确点统一使用 `LOCAL_OBLIQUE`。

核心验证流程：

```kotlin
val mission = SurveyMissionJson.decode(raw)
val report = ActiveRecaptureMissionValidator.validate(mission)
val schedule = SurveyCaptureSchedule.build(mission)
check(schedule.size == mission.estimatedPhotoCount)
SurveySimulatorGate.evaluate(...)
```

## 7. 当前验证状态

2026-08-23 已完成：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`。

V5 自动化回归覆盖：

- 导入及冷启动恢复都会重新执行主动补拍专项校验；
- 只读预演可持续推进且明确显示 `NO CONTROL`；
- V5 KMZ 将精确单点导出为 `REACH_POINT + TAKE_PHOTO`，连续 pass 保持距离/时间触发；
- V5 自定义 Virtual Stick / UE HIL 执行器在精确点拍照成功前不会推进；
- 五向分组按钮和重新生成按钮不会改写主动补拍任务；
- 修复统计页因重复距离取整显示 358 张的问题；
- 修复预演 marker 未设置图标导致百度地图崩溃的问题。

测试覆盖：

- schema 11 编解码和旧 schema 兼容；
- 339 张任务数量和专项阈值；
- 精确单点 schedule；
- 单点相机触发不误开启连续拍摄；
- 主动补拍任务不可被五向过滤器重写；
- 纯单点 coverage；
- 连续 pass 姿态插值；
- 既有普通和五向航测回归测试。

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 8. 真机前必须确认

当前研究文件中的 `altitudeM` 被直接写入 App 的“相对起飞点高度”。在明确其高度基准前，只允许做 UI、任务预演和仿真验证，不应直接真机执行完整任务。

真机前必须确认：

1. `altitudeM` 是否确实相对本次起飞点；
2. 若它是绝对海拔、普通 GPS 高程或其他世界坐标高程，先统一转换；
3. 先只执行一个 R1-R7 精确点和 R8/R9 的一个短 pass；
4. 核对到点容差、云台限位、相机成功回调和恢复 checkpoint；
5. 确认返航高度、地理围栏、电量和架次切分后再扩大范围。

## 9. 明确不做的事

- 不在 Android 端重新运行 SfM、Scal3R 或 GS；
- 不让 Android 根据风险点重新发明轨迹；
- 不让 GS/PSNR 参与在线飞控；
- 不把点云直接当碰撞地图；
- 不把 R8/R9 的连续扫描拆成大量停车拍照点；
- 不允许把 V4 的 aircraft/flight-controller API 直接搬入 V5；执行适配必须走 V5 `WaylineManager`、`FlightControlPort` 和相机控制层。
