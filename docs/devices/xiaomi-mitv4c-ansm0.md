# 小米电视 4C（MiTV4-ANSM0 / `xmen` / `gxbaby`），Android 6.0.1

型号字符串 `MiTV4-ANSM0`，`product xmen`，`board gxbaby`，`ro.build.version.sdk=23`，
1920×1080，中国 PatchWall 固件。shell 用户是 `uid=2000(shell)`，`run-as` 不可用。

这份笔记只讲这台机上的 Home 键行为、我们的改法、以及踩过的死路。同型号的
[`xiaomi-mitv4-ansm0.md`](xiaomi-mitv4-ansm0.md) 记的是 4S 75 寸那台的装机与禁用清单，两者可以对照。

## 固件怎么处理 Home 键

- `MitvPhoneWindowManager: prepare dispatch key 3` —— HOME 被窗口管理器**直接派给前台窗口**，
  不经过无障碍的按键过滤器。`dumpsys accessibility` 里本服务只有 `capabilities=8`、
  `eventTypes=TYPE_WINDOW_STATE_CHANGED`，拿不到 `KEYCODE_BACK`，也拿不到 `KEYCODE_HOME`。
- 短按 Home 会广播 `ACTION_CLOSE_SYSTEM_DIALOGS`，`reason=homekey`；
  长按走同一个广播，`reason=recentapps`（日志里是 `StatusBar: toggleRecentApps` → `RecentsPanelView`）。
  这两条是唯一可靠的「Home 被按了」信号。
- 广播的 `from uid` 能区分来源：`uid 1000` 是系统发送，落回 PatchWall；我们自己发的落回新桌面。
- **PatchWall 回到前台不等于按了 Home。** 只要把「前台是 PatchWall」当成 Home 的兜底，
  PatchWall 里任何二级页（详情页、相册 `com.mitv.gallery/.activity.HomePageActivity`、
  电视管家 `com.xiaomi.mitv.tvmanager`）按返回键回到 PatchWall 时都会被误判，直接把人弹回新桌面。
  这就是「返回键被吃掉」的真正原因，`onKeyEvent` 里从来没有 `KEYCODE_BACK` 分支。

## 这个 fork 的改法

1. `HomeKeyService` 只认广播：`homekey` → 等 800 ms（`HOMEKEY_SETTLE_MS`）后无条件 `openHome()`；
   `recentapps` 什么都不做，交给系统自己的任务切换器。
2. 删掉三处「PatchWall 回前台 = Home」的兜底：`foregroundCheck` 轮询末尾的重定向、
   `onAccessibilityEvent` 里的 `stockHome.contains(name)`、以及靠 UsageStats 猜的 `stockHomeReopened()`。
3. `stockHomePassthrough` 开关：进 PatchWall 时置位，`openHome()` 在置位期间直接返回，
   这样在 PatchWall 里操作不会被自己顶出去。
4. 开机首屏：无障碍服务绑定成功就是「可以接管了」的信号，开一个 90 秒窗口（`BOOT_WATCH_MS`），
   1 秒轮询里看到前台是 PatchWall 的任何一页就 `stockHomePassthrough=false` + `openHome()`；
   前台是 HDMI 或电视自己恢复的应用则不动。窗口过期后不再干预。
   不能用 `Settings.Global.BOOT_COUNT` 去重 —— 这台机 `settings get global boot_count` 返回 `null`。
5. 应用库里的 `桌面` 条目：`app_name` 改成「主屏幕」，让它在 PatchWall 的「我的应用」里
   点一下就能回到 `com.gathin.tvhome`。
6. 应用库里的 HDMI 入口：`library_inputs` 生成三条虚拟行（`input:23` / `input:24` / `input:25`），
   图标用系统自带的插口图（`res/drawable-nodpi/ic_hdmi.png`，取自
   `/system/vendor/app/MiTVTVPlayer/MiTVTVPlayer.apk` 的 `res/drawable-xhdpi-v4/hdmi.png`，
   220×150、透明底、白色），可以「添加到主屏幕」。

## HDMI

```text
action     com.xiaomi.mitv.tvplayer.EXTSRC_PLAY
component  com.xiaomi.mitv.tvplayer/.ExternalSourceActivity
extra      input (int)  HDMI1=23  HDMI2=24  HDMI3=25  AV=2
flags      0x10008000  (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)
```

`flags` 必须带上，否则 `ExternalSourceActivity` 的 `singleTask` 会复用旧实例，切到 HDMI 2 时画面全黑。
输入服务是 `com.droidlogic.tvinput/.services.Hdmi1InputService/HW5`（2 号 `HW6`、3 号 `HW7`）。
标准的 `content://android.media.tv/passthrough/...` 在这台机上没有 handler。

## 已知局限

- 在新桌面**自己身上**按 Home，会先闪一下 PatchWall，约 3 秒才回来。
  固件把 HOME 直派给前台窗口，服务只能事后补救，改不了。
- 系统桌面就是 PatchWall 本身（`mHomeProcess: com.mitv.tvhome/1000`，HOME intent 指向
  `com.mitv.tvhome/.MainActivityUserMode`），受 `com.mitv.tvhome.permission.HOME_STATE` 保护，
  换不掉、也禁不掉；但开机接管能让新桌面先显示。

## 不要重试的死路

- `am start -n com.mitv.tvhome/.MainActivityUserMode` → `SecurityException: requires com.mitv.tvhome.permission.HOME_STATE`
- `am start -a android.intent.action.MAIN -c android.intent.category.HOME` → 同样被拒
- `pm disable-user` / `pm hide` 系统桌面 → 需要 `MANAGE_USERS`，shell 没有
- `am start --activity-new-task` → 这个 `am` 不识别该参数
- `ACTION_VIEW` + `content://android.media.tv/passthrough/...` → 三个端口都 unable to resolve
- **`uiautomator dump`**：会让 `system_server` 抛 `SecurityException: getIntentSender() ... need uid=1000`，
  并把无障碍服务挤下线。要看界面就 `screencap -p /data/local/tmp/x.png` 再 `adb pull`。
- 这个桌面**不响应** `input tap`，只认方向键和 OK/Home/Back 的 keyevent。

## 构建与安装

```bash
cd launcher && ./gradlew assembleRelease -PlauncherMinSdk=23
adb install -r --user 0 launcher/app/build/outputs/apk/release/app-release.apk
```

不带 `-PlauncherMinSdk=23` 时 `minSdk` 还是 26，安卓 6 装不上；带上之后为 `java.time` 打开
core library desugaring。重装 APK 或 `pm clear` 之后无障碍绑定会掉，要按下面重绑：

```bash
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
sleep 3
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

只反复切 `0/1` 绑不回来，必须先 `delete` 再写。
