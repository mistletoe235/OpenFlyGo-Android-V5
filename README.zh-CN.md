# OpenFly Go for Android — MSDK V5

**手机图片存储更新：** 普通拍照默认不再额外往手机保存图传 JPEG / JSON。飞机 SD 卡拍照不变；
主动开启云端采集仍保留必要的待传缓存。详见 [默认行为与例外](docs/PHONE_IMAGE_STORAGE_2026-09-25.md)。

> [!WARNING]
> **实飞前必读 / Flight safety — read before flying**
>
> 本项目为研究与开发工具，不提供飞行安全保证。**仿真通过不等于实飞安全；自动航线与避障功能不能替代现场检查和飞手监督。**
>
> - **先仿真，再实飞。** 每次实飞前，必须在 App 内置仿真器中完整演练计划航线、拍照、暂停／恢复及任务结束流程。设备不支持仿真或仿真启动失败时，不得直接以实飞代替验证。
> - **条件允许时增加 HIL 验证。** 将真实飞控接入 UE 场景，检查仿真中的状态回传、航向、高度和控制响应。台架测试须先拆桨、固定飞机，并确认 DJI Simulator 已激活；仅显示 HIL 已连接不代表仿真已启动。详见 [HIL 操作指南](docs/HIL_QUICKSTART.md)。
> - **检查高度与信号。** 核对建筑、树木、电线以及转场、返航路径的净空；相对起飞点的航高不等于离地或离楼顶高度。检查定位、遥控和图传信号，并确认失联处置与返航设置适合现场。
> - **启用可用避障，禁止 Sport／S 档。** 消费级无人机并非都具备全向避障；支持避障的机型应启用并确认其生效，核对当前模式下的探测方向与限制。使用机型支持的正常定位飞行模式，不以避障代替安全间距。
> - **实飞前重新预检，全程可接管。** 确认仿真已退出、图像源恢复为真实相机，重新核对起飞点、返航点、航线、电量及天气。飞手须全程监督并随时准备暂停或手动接管，遵守当地飞行规定。
>
> This is a research and development tool, not a flight-safety guarantee. **Passing simulation does not establish real-flight safety.**
>
> - **Simulate before every flight:** rehearse the planned route, capture, pause/resume and completion in the App's built-in simulator. If simulation is unavailable or fails to start, do not substitute a real flight for validation.
> - **Use HIL where supported:** connect the real flight controller to UE to inspect simulated telemetry, heading, altitude and control responses. Remove propellers, secure the aircraft and verify that DJI Simulator is active; an HIL connection alone is not sufficient. See the [HIL guide](docs/HIL_QUICKSTART.md).
> - **Check clearance and signals:** inspect buildings, trees, wires, transit and return paths. Height relative to takeoff is not clearance above terrain or rooftops. Check positioning, control/video links, failsafe behavior and return-to-home settings.
> - **Enable available obstacle avoidance; do not use Sport/S mode.** Not all consumer aircraft have omnidirectional sensing. Verify active sensing directions and limitations, use the supported normal positioning mode and maintain safe clearance.
> - **Recheck before real flight:** exit simulation, restore the real camera and verify takeoff/home positions, route, battery and weather. Maintain pilot supervision and readiness to pause or take over; follow local flight rules.

> 中文快速导航：[版本与机型](#版本选择与机型支持) · [HIL 操作](docs/HIL_QUICKSTART.md) ·
> [工作站连接 / 点云 / 补拍](docs/CLOUD_ROUTE_WORKFLOW.md)。基本航线操作见下文“基本使用”。


Cloud point-cloud viewing and existing-session route import are included; see
`docs/CLOUD_ROUTE_WORKFLOW.md`. No model inference runtime is required.

公共控制 / 遥测同步范围与回归记录：[2026-09-21 补齐检查](docs/PUBLIC_PARITY_2026-09-21.md)。

OpenFly Go is an open-source mobile ground application for low-cost DJI aircraft. This repository
contains the Android client based on DJI Mobile SDK V5, with live camera operation, map-based survey
planning, WPMZ/KMZ mission support, simulator/HIL integration and reconstruction-service handoff.

The current public release intentionally excludes VLN, on-device model inference, USB/LAN inference
transports, model distribution and native inference runtimes. DJI RC2 compatibility experiments and
non-public DJI simulator hooks are also excluded.

仿地功能关闭：不提供仿地入口，拒绝载入带 `terrainPlan` 的任务，也不能执行或导出为 DJI KMZ。
维护者可用此开源代码配合私有 Key / 正式签名制作航线版安装包；这不是包含模型的私有开发完整版。

## 版本选择与机型支持

核对日期：2026-09-21。**V4 / V5 是 DJI SDK 两代产品线，不是同一 App 的“旧版 / 新版”；
不能为了功能更多而给 Mini 2 换装 V5。** 本表适用于三个开源客户端；安装版仍要核对具体构建。

| 客户端 | 当前依赖 | 项目已实机验证的机型 | 地图 | 航线文件 | 云端采集上传 | 已有云端点云 / 航线 |
| --- | --- | --- | --- | --- | --- | --- |
| Android V4 | MSDK 4.16.4 | **DJI Mini 2** | 百度地图 | schema 1–14 | 支持实时触发帧、历史照片上传 | 支持 |
| Android V5 | MSDK 5.18.0 | **DJI Mini 4 Pro** | 百度地图 | schema 1–14 | 支持实时触发帧、历史照片上传 | 支持 |
| iOS | MSDK 4.16.2 | **DJI Mini 2** | MapKit | schema 1–14 | 支持实时触发帧、历史照片上传（需新版构建） | 支持 |

“项目已实机验证”指项目已有硬件使用记录，**不是每次开源打包都重做了所有飞行验收**。
官方 SDK 支持某机型，也不代表本项目已验证该机型的相机、云台、控制权、航线和仿真能力。
相机参数目录里出现一个机型名称，不能作为连接支持的证据。

### DJI 官方支持列表

- [DJI MSDK 官方产品页：Supported Products / Supported Platform](https://developer.dji.com/mobile-sdk/)
- [DJI MSDK V5 官方仓库：Supported Product](https://github.com/dji-sdk/Mobile-SDK-Android-V5#what-is-dji-mobile-sdk-v5)
- [DJI MSDK V4 官方产品支持表](https://developer.dji.com/mobile-sdk/documentation/introduction/product_introduction.html#supported-products)
- [Android V4 4.16.4 官方版本](https://github.com/dji-sdk/Mobile-SDK-Android/tree/V4.16.4)
- [iOS V4 4.16.2 官方版本](https://github.com/dji-sdk/Mobile-SDK-iOS/tree/v4.16.2)

官方网页会更新，V4 旧产品介绍页也可能没有列全后来新增的机型；应同时核对**本客户端锁定的
SDK 版本、Android/iOS 平台、飞机固件和遥控器**，不要只看网页上的最新 SDK。

| 机型 / 产品线 | 如何选择 | 本项目承诺范围 |
| --- | --- | --- |
| Mini 2 | Android V4 或 iOS | 项目已实机验证；仍需按当前固件做预检 |
| Mini 4 Pro | Android V5 | 项目已实机验证；本项目 iOS 不支持 |
| Mini 3 / Mini 3 Pro | DJI 官方列在 V5；选 Android V5 做兼容验收 | 尚未按本项目完整流程实机验收 |
| Mavic 3 Enterprise、Mavic 3TA、Matrice 30 / 300 RTK / 350 RTK / 400、Matrice 4 / 4D Enterprise 系列 | 查 V5 官方清单和固件要求 | 不承诺企业负载、多相机和全部航线功能已适配；Mavic 3 Enterprise 不等于消费版 Mavic 3 |
| Mavic Pro / Mavic Air、Mavic 2 Pro / Zoom / Enterprise、Spark、Phantom、Inspire、较早 Matrice 产品 | 查 V4 表中的**具体型号**和平台限制，不能按整个系列推断 | 仅 SDK 候选机型，本项目未逐一验收 |
| Mavic Mini、Mini SE、Mavic Air 2、Air 2S 等其他 V4 产品 | 查对应 Android/iOS SDK 版本说明，不能由 Android 支持推断 iOS 支持 | 当前不列为本项目已验收机型 |
| Avata / Avata 2、Neo / Neo 2 等未适配产品 | **不属于本项目支持范围** | 不提供破解接入；刷 App、切换 V4/V5 或有图传都不等于可控 |

优先使用能通过 USB 数据线连接手机、且被对应 SDK 支持的遥控器。开源 V5 **不包含 RC2
破解 / 视频兼容实验**；不要把遥控器能装 APK 等同于可运行本项目。SDK 列表中的云台 / 负载
（例如 H30）也不是独立的飞机型号。

### 功能与构建差异

- 三端都有区域航线规划、预览、预检、暂停 / 恢复及 HIL 客户端；具体硬件 API 受机型限制。
- Android V5 有 DJI WPMZ/KMZ 执行路径；V4 / iOS 的 Mini 2 航线使用 App 侧控制，
  **不要当作上传后可以关掉 App 的离线机载任务**。保持连接和前台运行。
- 默认补拍是**到点稳定后拍照**（schema 13）。Android V4 / V5 / iOS 都支持 schema 14
  “连续补拍（实验）”：V4 / iOS 使用 App 侧 Virtual Stick，V5 使用 DJI KMZ；仅符合条件的
  中间拍照点连续通过，边界和转弯仍可停拍。默认仍使用 schema 13 停点拍照。
  V4 / iOS 需要 2026-09-22 或之后包含此适配的构建；并非仅放宽版本号，也不代表新增实飞验收。
- 开源版不含 MNN / VLN / 模型下载及私有推理运行时；云端航线与点云功能不依赖这些模块。
- 正式安装包不提供仿地：Android V4/V5 与 iOS 均不显示入口，也不激活带 `terrainPlan` 的任务。Android V4 Debug 保留实验实现。
- Debug 供开发，Release 是构建配置而不是“全部机型已验收”。自行编译需自己的 Key / 签名；
  安装版由维护者在私有环境签名，功能以该包说明为准。签名不同不能直接覆盖，勿为换包盲目清数据。

## Features

- DJI connection, account state, telemetry, battery, signal, camera and gimbal status;
- live camera preview, photo/video controls and aircraft-media browser;
- Baidu map integration with aircraft, remote-controller/device and home-point presentation;
- polygon survey planning, route ordering, camera-aware spacing and time estimation;
- custom survey execution plus DJI WPMZ/KMZ generation where supported;
- checkpoints, safe pause/resume, external-intervention handling and DJI RTH handoff;
- UE/AirSim HIL transport and the public DJI Simulator API;
- trigger-aligned frame/pose metadata and V86 image-stream/reconstruction client;
- optional DSM/building-height planning retained as experimental functionality.

Terrain following is **off by default**. It requires explicit user activation, valid surface data and
a new safety review. Surface data can be incomplete or stale and never replaces obstacle sensing or
site inspection.

## 基本使用：连接 → 航线 → 云端 → 补拍

1. **首次连接**：用数据线连接手机与遥控器，完成 USB 授权、地图隐私及必要权限提示。
   关闭会争用 USB 的其他 DJI App，确认本 App 注册成功，并看到正确的飞机 / 相机型号、
   新鲜遥测与图传；黑屏或注册失败时先排查线缆、包名、Key、签名和遥控器兼容性。
2. **创建航线**：从地图上的“航线”进入“区域航线”。在“区域”页按顺序点至少三个边界点，
   拖动调整；确认相机配置与实际镜头 / 照片比例一致，选择正射或倾斜采集方向。
3. **设置参数**：在“飞行 / 影像”中配置高度或 GSD、速度、前向 / 旁向重叠、云台角度、
   起点、返航 / 完成动作。先做小范围短航线；高度必须弄清是相对起飞点、目标面还是 ASL，
   不能把图传上的相对高度直接当海拔。路线坐标存 WGS84，不手工叠加地图偏移。
4. **预览与执行**：保存任务，查看全线、照片数、预计时间和进出场路径；运行“安全预检”，
   修复明确报出的 GPS / Home / 相机 / 控制权 / 高度等问题，再按当前后端的准备与执行流程操作。
   生成或导入路线不会自动起飞；先在 [HIL](docs/HIL_QUICKSTART.md) 验证，再由飞手现场确认实飞。
5. **暂停与续飞**：人工接管、断连或门禁触发后先查原因；恢复是显式操作，可能先回断点。
   不在执行或暂停待续飞时替换任务。返航是安全动作，不是云端处理结束后的自动许可。
6. **需要在线重建**：在采集开始前进入“云端重建 → 本机采集与上传重建”，配置工作站地址、
   访问码和真实起飞点 ASL，并创建会话；保持网络，查看“已拍 / 已传 / 待传 / 未入队”。
   拍照触发帧上传不是把飞机 SD 卡原片自动全量拉回；也不是连续视频流上传。
7. **查看与补拍**：队列清空后结束采集，等待结果；打开 PLY 查看点云，下载补拍航线，
   核对起飞基准、坐标、高度、相机、审核状态和路径净空，再预检并显式执行。
   也可用“打开已有云端会话”只读查看其他设备创建的结果，不会接管其上传队列。

详细步骤见 [HIL 操作与排障](docs/HIL_QUICKSTART.md) 和
[工作站连接、实时上传、点云查看与补拍](docs/CLOUD_ROUTE_WORKFLOW.md)。
**工作站安装 / GPU / 模型 / 服务启动由项目总入口仓库的工作站文档说明**；本仓库只说明 App
侧操作和接口边界，不附服务器安装命令。总入口地址尚未在本仓库配置，使用发布说明提供的入口，
不要把本机目录路径当作公开仓库链接。

## Requirements

- Android Studio with the Android SDK/NDK versions declared by the project;
- JDK 17 or newer supported by the included Gradle toolchain;
- an Android device with `arm64-v8a`;
- a DJI Developer account and an MSDK V5 App Key;
- a Baidu Maps Android key for the same application ID and signing-certificate SHA1.

## Configuration

```bash
cp local.properties.example local.properties
```

Set the following values only in the untracked `local.properties` file:

```properties
sdk.dir=/path/to/Android/sdk
OPENFLY_APPLICATION_ID_V5=com.example.openflygo.v5
AIRCRAFT_API_KEY_V5=your_dji_app_key
BAIDU_MAP_AK_V5=your_baidu_map_key
V86_DEFAULT_ENDPOINT=https://reconstruction.example.com
```

The DJI App Key must match `OPENFLY_APPLICATION_ID_V5`. The Baidu key must match both that package
and the certificate used to sign the APK. Never commit real keys, tokens, keystores or passwords.

服务地址须替换为手机实际可访问的工作站 HTTPS 根地址。`127.0.0.1` 在手机上指手机自身，
不是你的电脑；不要填写 SSH 地址或把 `/api/sessions/...` 粘到根地址栏。

## Build and test

See `docs/BASIC_REGRESSION_2026-09-21.md` for the latest local verification scope, startup fixes,
and the distinction between offline checks and real-aircraft acceptance.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. For release builds, copy
`release-signing.properties.example` to `release-signing.properties` and use your own keystore.
Official OpenFly Go APKs are signed by the maintainers in a private build environment.

```bash
cp release-signing.properties.example release-signing.properties
```

填写自己的 keystore 配置后，再运行：

```bash
./gradlew testReleaseUnitTest assembleRelease
```

维护者可通过 `OPENFLY_RELEASE_SIGNING_FILE` 指向仓库外的签名配置文件，不必复制密钥。
版本号可用 `-POPENFLY_VERSION_CODE=2 -POPENFLY_VERSION_NAME=0.1.0-v5` 覆盖；
需要与上次分发版本比较后递增。DJI / 地图授权和服务地址仍使用自己的私有配置。

配置正式签名后产物通常为 `app/build/outputs/apk/release/app-release.apk`；未配置签名时可能只
生成 `app-release-unsigned.apk`，不能直接安装。确认正式包名 / 签名的 DJI 和地图授权，妥善备份
自己的密钥；Debug 能用不代表换成另一张证书的 Release 也已授权。应用商店准入另按目标平台核验。


## Architecture

```text
DJI MSDK V5 ── telemetry / camera / gimbal / control / WPMZ
       │
       ├── flight HUD and map
       ├── survey planner and safety policies
       ├── public Simulator / UE HIL bridge
       └── capture metadata ── V86 reconstruction service
```

The `uxsdk/` module is derived from DJI's public MSDK V5 Sample Code and retains its original
copyright/license headers.

## Safety

This is research software, not a replacement for the remote pilot, DJI flight-safety systems,
airspace authorization, site inspection or legal compliance. Keep visual line of sight, maintain a
manual takeover path and validate changes in simulation before any real flight. Unsupported or stale
telemetry must fail closed.

Useful documentation:

- [Survey execution interface](docs/SURVEY_EXECUTION_INTERFACE.md)
- [V4/V5 HIL parity and protocol](docs/V4_V5_HIL_PARITY.md)
- [V86 reconstruction client](docs/V86_ANDROID_STREAMING_CLIENT.md)
- [WPMZ survey policy](docs/WPMZ_SURVEY_POLICY_2026-08-23.md)

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before submitting flight-control
changes. OpenFly Go is licensed under the [Apache License 2.0](LICENSE). Third-party components retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 相机参数匹配

换机、切镜头或改拍照模式前，请阅读 [航线相机参数匹配与限制](docs/CAMERA_PROFILE_COMPATIBILITY.md)。
官方支持连接不等于本项目已验证该相机；未确认的几何参数仅提示覆盖率/GSD误差，不再阻止执行；飞行与相机可用性检查仍保留。

## License and third-party software

Original OpenFly Go code uses [Apache-2.0](LICENSE). DJI SDK binaries, map
services and other dependencies retain their own terms. See
[third-party notices](THIRD_PARTY_NOTICES.md) and the retained files in `LICENSES/`;
include the applicable notices when distributing an installation package.
