# WPMZ 航测生成策略与验证（2026-08-23）

## 目的

固定 V5 航测 KMZ 中三个容易造成真机差异的字段：载荷位置与镜头、航点转弯模式、仿地高度表达。策略位于 `SurveyWpmzConverter`、`DjiWpmzPayloadLens`，由 `SurveyWpmzConverterTest` 自动验证。

## 相机与镜头

任务生成时同时冻结 `payloadPositionIndex` 和当前图传源对应的 `payloadLensIndex`。任一项变化后，已有 KMZ 自动失效并要求重新生成。

| DJI 图传源 | WPMZ 镜头 |
|---|---|
| `DEFAULT_CAMERA`、`WIDE_CAMERA` | `WIDE` |
| `ZOOM_CAMERA` | `ZOOM` |
| `INFRARED_CAMERA` | `IR` |
| `RGB_CAMERA` | `VISABLE` |
| `MS_G_CAMERA`、`MS_R_CAMERA`、`MS_RE_CAMERA`、`MS_NIR_CAMERA` | `NARROW_BAND` |
| `NDVI_CAMERA`、`VISION_CAMERA`、`POINT_CLOUD_CAMERA`、`UNKNOWN`、空值 | 禁止生成 |

不再将所有载荷静默写成 `WIDE`。无法确定物理拍照镜头时 fail-closed，避免多传感器飞机从错误镜头拍照。

## 转弯模式

- `PASS_START`、`PASS_END` 使用 `TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE`，damping 为 `0`。
- 仿地插值产生的 `TRANSIT` 点使用 `TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE`，damping 为 `1 m`。

这样拍照航带边界必须真实到点，不会因连续曲率切角提前进入或离开测区；航带内部的地形控制点仍可连续通过，避免每个高程采样点都停车。

## 仿地高度

当前规划器已经把 DSM/DEM 高程差换算成每个航点的起飞点相对高度，并逐点写入 `executeHeight`。因此生成的 Wayline 固定：

```text
realTimeFollowSurfaceIncreaseHeight = false
```

不能同时再启用飞控侧实时增高，否则存在对同一地形变化重复补偿的风险。当前实现属于“应用离线计算逐点高度”，不是“飞控实时仿地”。

## 自动验证

`SurveyWpmzConverterTest` 覆盖：

- DJI 相机位置到 `payloadPositionIndex`；
- 图传源到 `payloadLensIndex`；
- 每个拍照 action 使用选定镜头；
- 拍照航带边界必须到点停止；
- 预计算仿地任务不得启用二次实时增高；
- 航点坐标、高度、航向、云台角、速度和距离/时间触发。

运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'edu.playground.djivln.adapter.dji.SurveyWpmzConverterTest'
```

## 仍需硬件验收

自动测试可以证明传给 WPMZ SDK 的对象字段正确，不能证明每个机型/载荷固件对字段的最终解释一致。首次在新机型上执行时仍需：

1. 生成普通定高小任务并核对 Pilot 2/飞控预览。
2. 分别选择广角、变焦、红外或多光谱源，确认实际照片来自指定镜头。
3. 用小矩形检查航带首尾照片位置，确认没有曲线切角造成漏拍。
4. 仿地任务先在低风险开阔区域验证逐点相对高度，不得据此文档直接解除现有 `realFlightVerified` 安全锁。
