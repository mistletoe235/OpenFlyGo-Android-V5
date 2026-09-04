# V86 Android 流式重建客户端

更新：2026-08-31
Android 工程：`dji-vln-android-v5-next`
默认服务：`http://127.0.0.1:55000`。可在未跟踪的 `local.properties` 中通过
`V86_DEFAULT_ENDPOINT` 设置自建服务地址。

## 产品入口与操作顺序

入口在“航线规划”页底部的“云端重建”按钮。

1. 输入服务地址和 Bearer 访问码，保存连接；访问码由 Android Keystore 加密，既不写日志也不进入 Git。
2. 核对任务名、当前相机水平 FOV、真实起飞点绝对海拔 ASL 和最大补拍区域数，新建任务。
3. 正常执行航线。每次 DJI 拍照动作触发后，App 保存触发后的第一张新图传帧，并把同一帧时刻的飞机 GPS/ASL 送入 V86 上传队列。
4. 飞行中状态卡固定显示“已拍 / 已传 / 待传”。图传超时、保存失败、GPS/ASL 不合格会计入“未入队”，不能再表现成假正常。
5. 上传失败时图片保留在 App 私有队列，并以 2 秒起、最长 60 秒的退避自动续传；“重试上传”只用于立即重试，不是恢复上传的必要步骤。
6. 待队列归零后点击“结束采集”。该操作会冻结服务端照片前缀并启动完整 SfM、Scal3R、V50 和 V78。
7. PLY 按钮按当前阶段显示“待处理 / Scal3R 窗口进度 / 检查 PLY / 查看 PLY”。点云就绪后先流式下载到 App 文件缓存，再打开独立横屏查看页；红色为 V50、黄色为 V78、蓝色为建议补拍相机位置。
8. 点击“导入补拍任务”读取 schema 13。导入只进入本地预演与安全预检，不会自动上传飞机或执行。

## 飞行页面状态与操作

主航线页只保留一个流式状态卡和一个 PLY 按钮；服务地址、访问码、FOV、起飞 ASL 等一次性字段在活动任务期间隐藏。详细窗口的动作按状态显示，不可用动作不会占位置。

| 状态 | 飞行员看到的内容 | 可用动作 |
|---|---|---|
| 未建任务 | 连接是否已配置 | 新建流式任务 |
| 采集中 | 已拍、已传、待传、未入队次数 | 结束采集（仅待传为 0 时）、刷新 |
| 上传暂缓 | 本机保留张数；瞬时网络错误显示自动重试倒计时，确定性 4xx 显示修正提示 | 立即重试上传或修正连接配置 |
| 云端处理中 | Fast SfM 图像进度或 Scal3R 窗口进度 | 刷新、失败时重试处理 |
| PLY 已就绪 | 文件下载百分比或“查看 PLY” | 查看独立点云页 |
| 结果完成 | V50/V78 计数、补拍任务是否生成 | 查看 PLY、导入补拍任务 |

## 历史真机照片回放

这部分仅用于开发回归，入口在云端重建详情底部“回放手机照片”。可以选择 `DJI-VLN/sfm_test/` 或 `DJI-VLN/survey-trigger-frames/`。先填写文件名筛选（例如任务 ID 前缀）和最多张数（默认 40，0 才表示全部），再选文件夹。App 使用系统文件夹选择器取得持久只读授权，不申请全盘文件权限。Android 10+ 会先请求 `ACCESS_MEDIA_LOCATION`，并使用 `MediaStore.setRequireOriginal` 读取原始 EXIF；拒绝权限时停止回放，不用手机当前位置补齐 GPS。

- 按文件名排序，只接收 JPG/JPEG。
- 上传前先完整预检筛选后的图片；普通模式任何一张缺少 EXIF GPS 或 `GPSAltitude` 时整批不开始上传。仅相对高度测试模式要求 GPS 和 OpenFly `UserComment.relative_altitude_m`，不伪造 ASL。
- 大于 600 KiB 的图片在手机端缩放/调整 JPEG 质量，目标为 600 KiB、硬上限 640 KiB；原本不超过目标的图片保持原始字节，不做无意义重编码。
- DJI M30/M30T 原图可能是 MPO，原始 EXIF 指针会依赖 MPF/APP7 布局，不能把 EXIF APP1 直接移植到重编码 JPEG。App 会逐字节保留自包含的 DJI XMP（含 RTK、云台和飞行姿态），并重新写入有效的标准 EXIF GPS、绝对海拔、拍摄时间和 Make/Model；不会写成手机位置。
- 离线回放不发送 `X-Latitude`、`X-Longitude` 或 `X-Altitude`，服务端从图片 EXIF 读取并记录 `gps_source=image_exif`。`X-Timestamp` 只携带同一原图 EXIF/XMP 中的拍摄时间，避免服务端只检查顶层 IFD 时漏掉 `DateTimeOriginal`。
- 普通 DJI 文件的 `X-Capture-View` 按文件名中的 `forward/front/backward/back/left/right` 推断，无法识别时使用 `NADIR`。OpenFly 相对高度回放则按任务 ID / pass 在本机历史 `trigger_frame_saved` 日志中查真实拍摄方向；日志不足时预检失败，不静默猜测 NADIR。
- 图片逐张压缩并加入持久上传队列，最多领先网络 12 张，单张间隔 200 ms；断网后仍遵循原有幂等重试逻辑。
- “滚动快速预览”默认关闭。只在几十张的小样本实时采集时开启；上千张离线回放若开启，会在上传阶段反复触发越来越大的快速 SfM，拖慢 HTTP 服务。完整处理始终由“结束上传”触发，不依赖该开关。

### 仅相对高度的重建测试

ASL 未知的历史图传照片可在新建任务时显式勾选“仅相对高度重建测试”。请求必须含 `altitude_mode=relative_height_test`、`acknowledge_no_flight=true`，起飞 ASL 为 null。图像保留 GPS、拍摄时间和 OpenFly 位姿 UserComment；不发送 `X-Altitude`。压缩重写 EXIF 时保留云台/机身角度、相对高度、亚秒时间、时区和已有速度/方向字段。

该模式仅运行 SfM + Scal3R，跳过 V50/V78 检测和补拍规划；结果 `test_only=true`、`flight_export_allowed=false`，Android 不接受其中的 mission URL 或 `safe_to_execute=true`。UI 明确标注不能飞行使用，隐藏风险/补拍结果。它不是正常绝对海拔流程的降级替代。

“打开已结束采集的任务”可以读取已封存的云端历史任务，但本地待传队列必须为空；只切换本地查看对象，不修改服务器任务，也不会自动导入或执行航线。

## 图像与位姿语义

- 上传图像是 DJI 拍照触发后的新图传帧，不冒充飞机 SD 卡中的原始照片。
- JPEG 同时写入 EXIF；上传请求还显式携带 `X-Latitude`、`X-Longitude`、`X-Altitude`、`X-Timestamp` 和 `X-Capture-View`。
- 经纬度来自飞机遥测，不使用手机或遥控器位置。
- `X-Altitude` 必须是飞机绝对海拔 ASL；只有相对高度时拒绝上传。
- 飞机 GPS 最大允许年龄为 2 秒。没有新鲜飞机 GPS 或 ASL 时，图片仍保存在公开下载目录，但不会进入云端队列。
- schema 13 的 `LOCAL_OBLIQUE` 在上传合同中映射为 `FORWARD_OBLIQUE`，因为相机在该补拍点面向目标并使用前向光轴。

## App 调用的 HTTP 接口

除健康检查外，所有请求都使用 `Authorization: Bearer <access-code>`，访问码不会放进 URL。

| 操作 | 方法与路径 | Android 行为 |
|---|---|---|
| 新建任务 | `POST /api/sessions` | 发送 FOV、起飞 ASL、相机型号、Scal3R/预览开关和任务预算 |
| 查询状态 | `GET /api/sessions/{id}` | 详情窗口 2 秒、云端处理 5 秒、采集中后台 15 秒、完成后 60 秒刷新；也可手动刷新 |
| 上传照片 | `PUT /api/sessions/{id}/images/{sequence}` | 实时帧使用原始 JPEG/PNG；离线回放使用保留 EXIF 的约 600 KiB JPEG；序号与本机队列持久化，重启后幂等重试 |
| 结束上传 | `POST /api/sessions/{id}/finalize` | 本机队列不为空时拒绝；成功后任务 sealed |
| 重试处理 | `POST /api/sessions/{id}/retry` | 只显式重试，不把中断任务自动标成成功 |
| 查询统一结果 | `GET /api/sessions/{id}/result` | 读取 PLY、viewer、schema 13 URL 和 V50/V78 计数 |
| 下载产物 | result 返回的相对 URL | 仍携带 Bearer；PLY 使用 `.part` 流式落盘和原子替换，进度回传 UI；不接受非 HTTP/HTTPS 地址 |

## 本机恢复与失败策略

- 队列文件位于 App 私有 `files/v86-streaming/pending/`，文件管理器不可见，避免用户误删未上传照片；公开备份仍在 `Download/DJI-VLN/survey-trigger-frames/`。
- 队列状态使用临时文件原子替换。App 在上传成功但删除本机队列前退出时，重启会用相同 sequence 和相同图片重新 PUT，服务端按 SHA-256 幂等返回。
- 所有失败都保留队首图片，不会跳过失败帧继续制造序号空洞。网络断开、超时、408、429 和 5xx 会自动退避重试，重启后也会恢复；401/403 等确定性 4xx 等待用户修正访问码或数据，避免无意义重试风暴，保存新连接后会立即续传。
- 拍照成功但没有取得触发后的新图传帧、公开备份写入失败、飞机 GPS 超过 2 秒或缺少绝对海拔时，不进入上传队列并在状态卡累计“未入队”次数。
- finalize 前必须满足本机 `pendingCount == 0`。
- 服务返回的 `safe_to_execute=false` 始终保留。V50 A、V50 B 与 V78 计数分开显示，不把人工复核项或 RGB 风险描述成确定几何孔洞。

## 点云合同

原生查看器只接受 V86 当前固定合同：

```text
format binary_little_endian 1.0
property float x
property float y
property float z
property uchar red
property uchar green
property uchar blue
```

为保证移动端交互，查看器从文件随机访问并在全点范围内均匀采样，最多显示 40,000 点；不会先把整个 PLY 读入 `ByteArray`，下载的 PLY 文件本身也不被改写。查看页提供适配、俯视、前视、风险标记开关、拖动旋转、双指缩放和双击适配。坐标仍是服务声明的 `local_metric_xyz_aligned_to_uploaded_gps`。

手机缓存位于 App 私有 `files/v86-point-cloud/`。预览缓存键包含照片数和 Scal3R 窗口进度，最终结果使用独立的 `final` 缓存，不能把早期预览误当最终点云。同一版本再次打开时直接使用完整缓存；下载中断会校验 `Content-Length` 并删除 `.part`，不会覆盖原有有效文件或把半个文件当成有效 PLY。

## 当前安全边界

默认公网入口仍是明文 HTTP，Bearer 访问码和照片在传输层没有加密。App 会明确警告，仅应在受控测试网络使用。正式外场/多用户部署应先为入口配置 HTTPS，再分配可撤销、按任务隔离的访问令牌。

## 2026-08-31 有线手机小样验收

- 设备：REDMI K90 Ultra，V5 full debug；没有使用 Mac 代替手机上传，也没有连接飞机执行任务。
- 手机新建 `s20260831-031407-0d8d27`，选择第二组 `2a18da56` 的前 40 张。通过 SAF 读取、手机 JPEG 压缩、持久队列逐张上传，服务收到 40/40，本机待传 0、上传错误 0。
- 服务收到图片平均 589,644 字节（最小 464,877、最大 611,711），仍为 1440×1080。40 张服务器解析的 GPS、相对高度、机身 yaw、云台 pitch 与原图逐一对比通过；没有填造 ASL。
- 手机点击结束后任务完成，SfM 注册 40/40，Scal3R 7/7 窗口。该运行包含缓存复用，不能用于推断新场景的冷启动耗时。
- 手机通过公网下载并原生打开该任务的 802.3 KiB PLY，缓存 SHA-256 与服务器一致：`8bef0c0b5cdb373dc8a5f90103b46bf49e66e57e266b510908336ccfae459b54`。另验证了历史 590 张任务的 10.3 MiB PLY 下载/显示。
- 修正原生查看器俯视/前视预设：本地 ENU 中俯视投影 XY、前视投影 XZ；测试完成不再显示等待补拍任务。已在手机测试视角切换、拖动、适配和双击复位。
- `*V86*`、`*Jpeg*` 30 项单测通过。Android instrumentation APK 被系统安装策略阻止，未将它计为通过；以上压缩/EXIF/预览证据来自实际应用操作。
- 本次仅证明旧照片的手机上传与点云预览链路。不代表真实飞行采集触发、移动数据/VPN 切换、坐标净空检查或自动补拍闭环已经验收。相对高度模式没有补拍航线是预期安全行为。
