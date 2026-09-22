# V5 公共控制与遥测补齐 — 2026-09-21

## 范围

本次只修改 V5 开源客户端。航线主体、相机参数、云端上传 / 已有会话读取保持现有行为，
不复制私有 MNN/VLN、雷达规划、RC2 实验、底层 OSD 采样和诊断录制模块。

核对时，55 个航线核心文件、5 个相机采集文件及 `SurveyFeatureController` 与私有版一致。
云端客户端的公共服务地址仍由 `BuildConfig.V86_DEFAULT_ENDPOINT` 配置，不复制私有默认地址。

## 补齐内容

1. **控制权获取**：发出启用高级模式请求不再直接算成功；必须在当前获取过程中收到
   高级模式已开启的 SDK 状态回调，同时确认控制租约、App 控制权和启用状态。
   原有超时回滚、遥控接管、释放流程保持，不绕过保护。
2. **指令发送**：复用与私有版一致的 `VirtualStickSendPolicy`，发送前校验租约、App
   控制权、高级模式以及四轴数值有限性。释放前的零速度指令也经过相同检查；
   无法发送零指令时仍继续请求释放，不因检查失败跳过释放。
3. **重绑定清理**：清空旧速度、全部云台姿态及其时间戳，重新收集不支持字段；
   不把上次监听周期的值保留为当前有效姿态，不重置无关业务状态。
4. **速度新鲜度**：SDK 缓存查询仅在尚无速度回调时提供显示值，不更新时间戳，
   不覆盖已收到的回调值（包括回调明确失效的空值）。速度回调用同一个接收时间
   更新速度和飞行状态时间戳。没有新增速度积分或雷达模块。

遥测逻辑提取为可独立测试的 `DjiTelemetrySnapshotPolicy`，并接回真实 DJI 数据源；
并非只新增未使用的测试辅助代码。控制策略和其 6 项测试直接对齐私有版。

## 回归测试

- 定向测试：26 项全部通过，包括 6 项控制发送策略、7 项遥测快照策略、
  9 项控制生命周期与 4 项租约测试。
- Debug 全量：537 项，536 通过、1 项跳过、0 失败；包含上述定向测试，不重复累计。
- Release 全量：同为 537 项，536 通过、1 项跳过、0 失败。
- Debug APK、经过 R8 优化的 Release APK 及 Release `lintVital` 均通过。
  新 Release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，仍未签名。
- 两份 APK 保留 DJI 原生库，未发现 MNN / 室内规划原生库或 MNN、ONNX、GGUF、TFLite
  模型文件；本次未加入私有模块依赖。该文件检查不是完整供应链安全审计。
- 跳过项依赖用户提供的手机航线 fixture，不是把失败用例改成跳过。
- 新增覆盖：高级模式仅配置但未实报、实报后失效、控制权丢失、非有限四轴、
  小速度 / 零速度不被额外抬高、重绑定清理、缓存不能刷新时间或覆盖回调、空值失效。

复现（使用已有 JDK / Android SDK 配置）：

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease \
  -Pkotlin.incremental=false -Pkapt.incremental.apt=false
```

禁用本次构建的 Kotlin/KAPT 增量缓存是为避免既有 Java/Kotlin stub 缓存问题；
不改变仓库默认构建策略，不跳过编译或测试。

本机日志为 `/tmp/v5-public-parity-targeted-20260921.log` 和
`/tmp/v5-public-parity-full-20260921.log`。JUnit XML 位于
`app/build/test-results/testDebugUnitTest/` 和 `app/build/test-results/testReleaseUnitTest/`。
既有 SDK 的 R8 stack-map、资源和 Gradle/JDK 弃用警告仍在，未用屏蔽警告替代修复。

## 发布与实机边界

这里的回归是 JVM 逻辑测试，不代表已验收实际 DJI 回调时序或飞行行为。本次不连接飞机、
不执行航线，不用开源模板包覆盖手机上的私有版。实际设备注册、控制权获取 / 释放、
重连遥测与航线仍需硬件验收。未新增 Git 提交或推送，远程公开仓库地址仍待配置。
正式分发还需与包名匹配的 DJI / 地图 Key 和签名，不能将源码同步当作已发布安装包。
