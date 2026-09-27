# V5 前台常亮修复（2026-09-27）

## 原因

用户报告执行航线时手机自动锁屏。公开 V5 和当前私有 V5 的 `NextMainActivity`
及其根布局没有主动保持屏幕常亮。12S 读取到 `screen_off_timeout=600000`（10 分钟），
`stay_on_while_plugged_in=0`。私有工程的 `AirBrainOfflineActivity` 有常亮设置，
但不能据此认为主飞行／地图页也有常亮；未确认用户记忆中旧版本的具体差异。

`NextMainActivity.onStop` 会暂停航线并停止遥测，因此自动熄屏不仅影响观看，
还可能触发后台暂停。不能通过删除后台保护来解决。

## 修改

- 在 `activity_next_main.xml` 根布局增加 `android:keepScreenOn="true"`，覆盖主页面内的飞行、地图、航线和设置视图。
- 公开／私有 V5 同步；不修改系统熄屏时间、不加后台唤醒锁，不删除后台暂停保护。
- 不阻止用户主动按电源键锁屏或退出应用；常亮不保证系统不会因过热等原因限制应用。
- 新增根布局回归测试，遍历所有同名布局变体，防止后续只在部分页面常亮。
- 正式包版本 `0.1.11-v5`（code 13）。

## 验证边界

私有版常亮布局测试通过。公开版全量 Release 测试和构建记录在
`OpenFly-Go/artifacts/releases/20260927-v5-screen-awake/`。
安装前检查手机仍为 VS `RUNNING`、速度约 2.9 m/s，因此不覆盖安装打断控制。
待用户停控后安装，再核对窗口 `KEEP_SCREEN_ON`／屏幕保持状态；目前不能宣称真机超时验证通过。
