# Existing cloud session: point clouds and routes

## 中文操作：连接工作站、上传、点云与补拍

### 部署边界与需要拿到的配置

**工作站安装、GPU / 模型配置、服务启动、HTTPS / VPN 部署，由总入口仓库的工作站文档统一说明。**
这里只说明 App 端；不要求用户在手机上 SSH，也不在本仓库提供未经验证的服务启动命令。
请向工作站部署者取得：

| 配置 | App 怎么填 |
| --- | --- |
| 服务根地址 | 例如 `https://reconstruction.example.com`，替换为手机可访问的真实地址；不带 `/api` 或会话路径 |
| Bearer 访问码 | 只填访问码本身，App 添加 `Authorization: Bearer ...`；不放到 URL、截图或日志 |
| 已有会话 ID | 只读查看已有结果时需要；必须是目标服务上的 ID，不是任务名称或完整 URL |
| 航线兼容性 | Android V4 / V5 / iOS 可读 schema 1–14。默认输出 schema 13，连续补拍显式使用 14 |
| 起飞点绝对海拔 | 本机新建采集会话时确认真实 ASL 和来源，并与服务器的高度基准一致 |

手机的 `localhost` / `127.0.0.1` 是手机自身，不是远程电脑。即使服务在电脑监听 `55000`，
也要用手机可达的主机地址 / HTTPS 入口；VPN、热点与 DNS 须从手机侧验证。
V4 Release 禁止明文 HTTP，Debug 可用于受控局域网 HTTP 测试；V5 当前允许 HTTP 但仍建议
HTTPS，明文会暴露照片与访问码。不要用关闭证书校验解决 HTTPS 报错。

### 路径 A：本机实时采集与上传

1. 先完成飞机连接、相机配置和小范围航线规划，打开“云端重建 → 本机采集与上传重建”。
2. 保存根地址与访问码；核对任务名、相机水平 FOV、相机型号、真实起飞点 ASL 和补拍任务预算，
   创建会话并记下 ID。不要把飞机显示的相对起飞高度填成绝对海拔。
3. 通过预检后按正常流程执行航线。App 在有效拍照触发后保存新图传帧与关联元数据，并排队上传；
   **不是持续上传整个视频，也不是自动下载 SD 卡上的全分辨率原片**。
4. 观察“已拍 / 已传 / 待传 / 未入队”。图传、GPS 时效、ASL 或落盘不合格时可能拍了照但没入队，
   必须处理原因；没有网络时保留队列，恢复后重试。同一照片序号重试不能在服务器重复计数。
5. 完成采集且“待传 = 0”后再点“结束采集 / 结束上传”。这会 finalize 会话；任务跑完不代表
   所有图片已经上传。处理中刷新状态，按需显式重试，不反复新建会话来掩盖失败。
6. 点云就绪后点“查看 PLY / 下载并查看点云”。支持旋转、缩放和适配视图；预览是重建结果，
   不是实时避障地图。渐进预览是否出现取决于工作站，不保证每传一张图就刷新点云。

**先试历史照片**：可在本机上传流程中用系统文件夹选择器选已有 JPG/JPEG，授权只读目录。
App 会检查照片 EXIF 并压缩上传；它不是给缺失 GPS / 海拔的任意图片补造位置。
V5 的 `relative_height_test` 是测试分支，允许的输出边界与正式 ASL 会话不同，**不能导入为实飞补拍**。

### 路径 B：只读查看已有结果

1. “云端重建 → 打开已有云端会话”，填写服务根地址、访问码和会话 ID。
2. 点“连接 / 刷新结果”，读取 `GET /api/sessions/{id}/result`，等待 `point_cloud` / 航线产物就绪。
3. “下载并查看点云”只读取点云；未批准的航线打开独立只读路线图，已批准的航线进入导入确认。
4. 该入口**不会上传照片、创建 / finalize / retry 服务任务、接管本机上传队列或执行飞机**。
   不能在这里补传一份来自另一台手机的采集队列；候选相机 / 风险叠加也不能视为该入口必备功能。

### 补拍航线从下载到执行

1. 未批准的航线允许下载并查看只读路线图，不进入本地执行任务。导入仍要求结果和航线审核
   字段允许；`test_only` / `relative_height_test` 保持仅点云。V4 的 `execution_review`
   校验不变，不删除或重写审核字段。
2. 导入后只进入本地规划器。核对任务 ID、schema、补拍组 / 拍照点数量、真实起飞点和
   WGS84 / ASL 转换；换起飞点后尤其不能盲用旧的相对高度。
3. 检查目标点之间的整条飞行路径、返航高度、净空、相机 / 云台视角及估计时长；
   点云稀疏、漏建或过时不能作为通行证。运行 / 暂停任务有锁时先处理原任务。
4. 先用 HIL / 仿真和少量补拍点验证，完成本地安全预检，再按所选执行后端显式准备 / 执行。
   V5 DJI KMZ 需完成对应生成 / 上传 / 准备；V4 Mini 2 保持 App 前台与控制链路。
5. 默认 schema 13 是停车稳定后拍照。V5“飞行 → 连续补拍（实验）”只针对主动补拍、需确认，
   仅 DJI KMZ 路径支持；更改后任务 ID / 旧断点 / 已生成上传的 KMZ 失效，要重新准备。
   Android V4 的新建云端会话也有“连续补拍（实验）”选项（默认关闭），通过 App 侧
   Virtual Stick 执行符合条件的中间点，不使用 KMZ；需保持前台和连接。iOS 的新版构建也使用 App 侧控制支持此模式。
   不要把“连续”理解为所有拐弯、升降和边界点都不停。
6. 任务结束后核对实际照片与漏拍 / 未确认记录；“到达终点”不等于“所有照片有效”。
   补拍照片如何纳入下一轮分析遵循总入口的工作站流程，不假定 finalized 会话还能直接追加。

### 常见问题

| 现象 | 检查 |
| --- | --- |
| 连接超时 / 拒绝 | 手机是否可达真实主机、服务是否启动、根地址 / 端口 / HTTPS、VPN / 防火墙 |
| HTTP 401 / 403 | 访问码与权限，不是规划器故障；更换服务后重新填写对应凭据 |
| HTTP 404 / 会话不匹配 | 是否填了另一服务的会话，或错误地把完整 API 路径填进根地址 |
| 已拍增长，已传不增长 | 待传队列、未入队原因、GPS / ASL、图传新帧、网络和磁盘空间 |
| 无点云 / 无航线 | 查看 `phase`、`error`、`mission_error`，产物可能尚未生成或测试模式禁止航线 |
| 点云下载被拒 | 是否超限 / 格式不兼容 / 跨域链接；当前只读入口要求同源且拒绝重定向 |
| 航线无法导入 / 执行 | schema、审核状态、坐标 / 高度、任务锁和本地预检；不是靠重启或解除保护解决 |

下文保留开发者协议与下载上限说明；两种入口的缓存 / 点云上限不同，不混用。


This route-related workflow is included in both private and public clients. It does not need
MNN, VLN inference, or model distribution. Open the cloud/reconstruction entry and choose
**Open existing cloud session**, rather than **Capture and upload from this device**.

Enter the service root HTTP/HTTPS URL, bearer access code and existing session ID. This is not
an SSH connection. Connect/refresh reads `GET /api/sessions/{id}/result`, verifies the session ID,
then enables point-cloud viewing and mission preview when artifacts are available.

## Independent, read-only browser

- Uses only GET requests; never creates/finalizes/retries/cancels server tasks or uploads photos.
- Does not attach the remote session to the local capture/upload queue or change its connection.
- PLY is downloaded to one bounded internal cache file and opened in the existing native viewer.
  It is a reconstruction result, not a live obstacle map. This entry does not fetch candidate overlays.
- Mission download opens a confirmation showing the mission name. Import only loads the mission
  into the planner; it does not request control or execute. Check coordinates, altitude and preflight.
- Execution/paused-task locks are rechecked when the download finishes and when import is confirmed.
  Existing local-upload mission imports also recheck their asynchronous execution locks.

## Compatibility and safeguards

When creating a cloud session, the continuous-recapture checkbox explicitly requests schema 14
and `CONTINUOUS_EXPERIMENTAL`. Unchecked requests retain schema 13 stop-and-capture. Relative-height
tests disable this option. Execution of continuous missions still requires the DJI KMZ backend.

Unapproved missions open a separate read-only, north-up route diagram with waypoint count and
height range. This preview never activates a mission or obtains flight control. Import still requires
`openfly_v5_mission.safe_to_execute=true` and compatible payload review flags; the platform
decoder/validator and preflight checks remain in place. Relative-height/test-only sessions remain
point-cloud-only. Review metadata is never rewritten or removed.

Android V4 accepts schema 1–14/WGS84 and retains its strict `execution_review` approval check.
Its schema 14 continuous recapture uses app-side Virtual Stick, not DJI KMZ; the cloud-session
checkbox explicitly requests this experimental mode and is off by default.
Android V5 also accepts schema 14 and retains its existing local-upload review workflow.
Unsupported schemas are not silently downgraded. The updated iOS client supports schema 1–14 with experimental app-side continuous recapture and has a
separate warning-based preview import workflow; the clients do not claim identical approval policies.

This V5 route-only build disables terrain following. A mission with `terrainPlan` is rejected
at import/activation, execution (including resume), and DJI KMZ conversion even if its terrain
verification flag is true. Ordinary fixed-height routes and non-terrain recapture remain supported.

Results are capped at 1 MiB, mission files at 8 MiB, PLY at 64 MiB, including unknown-length
responses. Failed/truncated downloads preserve the previous valid file and remove partial files.
Closing the browser cancels requests; obsolete results cannot update a new session.

Only same-protocol/host/port artifact links are accepted; redirects, URL credentials, invalid IDs
and access-code control characters are rejected. The access code uses a separate encrypted Android
Keystore-backed preference namespace, not the upload session token. Changing the service address
clears the displayed token. Saved connection settings restore only with the saved service address.

Prefer HTTPS. HTTP sends bearer credentials in plaintext; the UI warns about this. Public builds
use their configurable `V86_DEFAULT_ENDPOINT`; no private server credentials are included.

## Verification

`V86RemoteSessionClientTest` covers read-only authenticated GET, matching session IDs, HTTP failure,
redirect rejection, cross-origin rejection, approval/test-only handling, unchanged mission metadata,
streaming/declaration byte limits, truncated PLY/cache cleanup and cancellation. Use normal Gradle
unit tests and `assembleDebug`; this does not command an aircraft.

`V86LiveServiceTest` is an opt-in check against a configured workstation: it creates and cancels
empty sessions and reads a completed reference mission for preview. Set `OPENFLYSCAN_LIVE_ENDPOINT`,
`OPENFLYSCAN_LIVE_TOKEN` and `OPENFLYSCAN_LIVE_SESSION` privately to run it. It uploads no images and
issues no aircraft commands. Without those variables it is explicitly skipped.
