# V5 虚拟摇杆拍照提示与双后端审计（2026-09-27）

## 本次日志结论

来源：12S 导出的 `DJI-VLN-flight-20260927-113609.jsonl`。
仅统计本次 VS 时间段，不能把文件中历史 KMZ 记录混进结果。

- 11:34:03.877–11:36:06.236：`capture_result` 共 30 条，后端均为 `CUSTOM_VIRTUAL_STICK`，全部 `success=true`。
- 同一时间段有 30 条 `camera_photo_captured`，来源为 `shooting_edge`。
- 这证明收到了成功回调和相机拍照状态，不等价于逐张检查 SD 卡文件完整性。
- VS 的结果经 `recordSurveyEvent` 记录，但未调用 Activity 顶层拍照提示；原提示调用只在 KMZ 回调里。
  飞行页另有相机状态提示，不能替代地图页的顶层提示。这是接线遗漏，不是本次没有触发拍照。

## 已修复：统一结果提示

新增 `SurveyCaptureFeedback`，将 `capture_result` 和 `dji_app_capture_result` 的布尔结果
统一送到原有 Activity 顶层浮层；移除 KMZ 旧的单独调用，避免重复。
成功、失败用同一浮层，失败不显示为已拍摄；请求、相机状态、图传帧保存不重复触发。
UE HIL 使用其保存结果；不拿飞行进度或请求发出来冒充拍照成功。

公开／私有 V5 已同步。未改飞行控制、拍照时效、比例或暂停恢复流程。
新增 7 项测试通过；公开版全量 604 项（602 通过、2 跳过、0 失败），私有版相关 28 项通过。
`0.1.10-v5`（code 12）Release 构建、签名和对齐验证通过。

**尚未安装**：检查手机时仍为原生仿真 Joystick 飞行中、速度约 2.9 m/s，不能重装打断控制。
包：`OpenFly-Go/artifacts/releases/20260927-v5-vs-capture-feedback/OpenFlyGo-Android-V5-0.1.10.apk`。

## 另发现的差异和风险（本批未修改）

### 确认的配置缺口：照片比例

`ui/SurveyFeatureController.kt` 的 `applyMissionPhotoRatio` 仅被
`prepareUploadAndExecuteDji` 调用。VS 的 `SurveyCustomExecutionEngine.start/restore`
绑定相机并选择照片模式，没有同步任务要求的 4:3／16:9。
因此界面的规划比例不保证已经写入相机；这不是此次浮窗缺失原因，但应补上共同的相机准备流程，
覆盖新执行和恢复，并保留取消／异步回调保护，不在检查期间直接改飞机设置。

### 跨后端隔离风险：旧 KMZ 状态和断点

- `waylinePort.start` 的 SDK 状态监听仍处理原生航线状态，部分副作用没有先区分当前后端。
- `persistDjiCheckpoint` 没有当前后端判断：若收到满足条件的旧原生任务状态，可能写入或清除公共断点槽。
- `bestAvailableCheckpoint` 的兜底先尝试原生 `waylineState`，然后才尝试自定义断点。
  `exportArtifacts` 虽另导出 custom-checkpoint，但通用 checkpoint 仍有拿错后端的风险。
- 这是代码检查发现的条件性风险，**本次日志未证明已发生断点覆盖**。需要增加后端／任务身份隔离和回归测试，
  不应直接删除已有断点或禁止普通 VS 恢复。

### 有意保留的 KMZ 专用功能

- V5 的实验连续补拍（schema 14 执行模式）仍要求 KMZ；普通航带与默认停点补拍不是这个限制。
- KMZ 上传、机载航线分段续接、原生动作组错误、原生 breakpoint API 属于 KMZ 后端。
  VS 用自己的执行状态机和恢复点，不应照搬原生 API。
- `dji_recapture_missed` 汇总来自原生航点经过时的漏拍检测；VS 停点补拍由成功结果推进，失败保留／暂停，
  并非没有 VS 失败处理，但两者没有同一个“漏拍汇总”报告。

## 确认已有 VS 接线（仅代码核对，不宣称本轮全部实飞验收）

- 普通等距离／定时拍照：自定义执行器配置并恢复拍照控制器，日志已出现实际成功事件。
- 默认停点补拍：等待拍照结果后推进，失败走自定义处理。
- 云端触发帧上传：`onPhotoTriggered = ::captureDjiTriggerFrame` 与 KMZ 共用入口；仍需云端会话开启。
- 地图目标／当前航带／恢复点、剩余时间：分别有 customState 分支。
- 暂停、恢复、保存恢复点、跨重启恢复：分别调用 customExecution 对应方法。
- 外部接管、停止及任务完成返航：存在 VS 分支；完成返航先停止发送并移交 DJI。

后续优先级：先让当前 VS 测试安全停止并安装提示修复，再分别处理相机配置准备、跨后端断点隔离。
