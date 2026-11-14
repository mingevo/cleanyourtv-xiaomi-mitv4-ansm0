# cleanyourtv，为小米电视 4C（Android 6.0.1）保留可用

这是 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) 的一个分支。
用途只有一个：让这套桌面（`com.gathin.tvhome`）在小米电视 4C（`MiTV4-ANSM0` / `xmen` / `gxbaby`，
Android 6.0.1 / API 23）上装得住、Home 键接管得住，并且还能继续跟着上游主线更新。

设备上的具体行为、踩过的死路和验证方法记在
[docs/devices/xiaomi-mitv4c-ansm0.md](docs/devices/xiaomi-mitv4c-ansm0.md)。

## 这份历史是怎么接起来的

```text
c965ef4b  (上游 Gathin23/cleanyourtv 的某个点)
   │
   ├─ ee356051  launcher: Android 6 snapshot（沿用 qqwersde/my-tv-signal-android6 的 API 23 适配）
   │
   ├─ 6a3496df  Xiaomi Mi TV 4C：本机实测后补的改动（Home 键 / 返回键 / 开机接管 / HDMI 入口）
   │
   ├─ 8e9de93c  docs: 本文件与设备笔记
   │
   └─ 4d022c0f  Merge upstream/main @294d3d59（+147,361/−3,324，2334 个文件）
```

`qqwersde/my-tv-signal-android6` 自己的仓库只有三个提交、没有上游历史，所以这里把它对上游的
适配重新落到 `c965ef4b` 上，让本分支和上游有一个真正共同的祖先 —— 以后才能用 `git merge` 跟主线，
而不是每次都比对文件。适配内容本身没改：32 个文件、+1001/−344，主要是 `minSdk` 做成
`-PlauncherMinSdk=23`、`java.time` 的 desugaring、`values-v26` 样式，以及 API 23 上的几处崩溃修复。

## 跟上游更新

```bash
git fetch upstream
git merge upstream/main          # 上游主线
```

冲突集中在下面这些文件（这是按上游当前 `294d3d59` 试算出来的，不是猜的）：

| 文件 | 上游这两个月的提交数 |
| --- | --- |
| `launcher/.../MainActivity.java` | 94 |
| `launcher/.../Config.java` | 38 |
| `launcher/.../HomeKeyService.java` | 14 |
| `launcher/.../AppAdapter.java` | 14 |
| `launcher/.../AppLibraryDialog.java` | 4 |
| `launcher/app/src/main/AndroidManifest.xml` | — |
| `launcher/app/src/main/res/values/config.xml` | — |
| `launcher/app/src/main/res/layout/item_input.xml`、`layout-iw/item_input.xml` | — |
| `README.zh-CN.md`、`.github/workflows/*.yml` | — |

前五个是硬骨头：上游对 `MainActivity` 加了 869 行，对 `HomeKeyService` 也动了不少，
合并时不能整块取上游，要把本机这套「只认 `homekey` 广播」的逻辑在新代码上重新落一遍。
`AndroidManifest.xml`、`values/config.xml` 和两个 `item_input.xml` 是适配与上游同时改到的地方，
取上游的结构、把我们那几项（`input_action`、`input_sources`、`library_inputs`、`dock_apps`、
`ic_hdmi`）补回去。

## 第一次跟主线：合并 `294d3d59`（2026-11）

`4d022c0f` 这一次合并的结果，可以当作以后合并的模板。冲突正好落在上表那 13 个文件里，
共 34 块（另外 3 个 `.github/workflows/*.yml` 是「上游改、这边删」，保持删除）。

解决原则只有一条：**行为归我们，结构归上游**。

| 文件 | 怎么合的 |
| --- | --- |
| `.github/workflows/{addons,ci,maintainer-bot}.yml` | 保持删除（`git rm`） |
| `README.md` | **取这边的重写版**。首页已经改成 4C 55 单机文档：上游的 How it works、Quick start、What you get、Supported TVs、Doing it by hand、The microphone、The launcher、Restoring、Firmware updates、When something goes wrong、Questions、The story、Contributing、Contact、Legal 全部删掉，只留 banner、设备节、Build、What changed、Known limits、Following upstream、Licence。上游以后改 README 一律不跟，冲突就整份取我们的 |
| `README.zh-CN.md` | 取上游；本机说明只放本文件 |
| `AndroidManifest.xml` | 上游结构 + `<intent-filter android:priority="999">` |
| `values/config.xml` | `input_action` 用小米的 `EXTSRC_PLAY`；`library_button=true`、`show_library_pill=false` |
| `layout/item_input.xml`、`layout-iw/item_input.xml` | 用 `style="@style/FontRegular"`（API 23 不认 `android:fontFamily` 的字符串资源） |
| `Config.java` | 上游全部新字段 + 我们三个 list（`libraryCatalog`、`libraryExclude`、`libraryExcludedApps`），构造器与 `load()` 按上游顺序插入 |
| `AppAdapter.java` | 两行都留：`stockHomePackage` 与 `iconTiles` |
| `AppLibraryDialog.java` | 上游新签名（`okOpens`、catalog 两个 list、`stockHomePackage`、`excludedApps`）＋ 我们的 catalog 过滤与「按 OK 进清单 / 否则改主屏」的分支 |
| `HomeKeyService.java` | 见下 |
| `MainActivity.java` | 见下 |

`HomeKeyService` 是这次最费劲的地方，上游把它重构过了：

* 留住我们的接管主线：`homekey` 广播 → 800ms → `openHome()`；`bootTakeover()` 开机窗口；
  `stockHomePassthrough` 期间不抢 Home；`onAccessibilityEvent` 里**不复活**「PatchWall 回到前台就 openHome」。
* 接住上游的新功能：`power_on_home` / `power_on_home_always`（`armPowerOnHome`、`powerOnRedirect`）、
  `guard_off_in_standby`（`armStandbyExit`、`leaveForStandby`）、`app_keys` 的 `front:` 项（`frontAppKeys`）。
* 丢掉上游的 `probeAtBoot()`：它用 `Settings.Global.BOOT_COUNT` 判断是不是开机，这台机返回 `null`，
  永远不触发 —— 所以这次直接删掉调用，开机接管仍旧由 `bootTakeover()` 负责。`redirectHomeKey()`
  那条 keyfilter 路线也一样不适用（这台固件不把按键送进无障碍过滤器），`onKeyEvent` 因此用我们的版本。

`MainActivity` 里冲突的几处，取上游的实现，但把我们为了 API 23 加的封装重新套上去：

* `skipOffscreen()` / `inputHidden()` / `customLabel()` 三个 API 24 封装保留，上游新增的
  `ambient.forceHasOverlappingRendering(false)`、`info.loadCustomLabel()` 全部改成走这三个；
  上游的 `InputNames.tidy()` 叠在里面用（`InputNames.tidy(customLabel(info))`）。
* 上游新增的磁贴长按菜单（`holdOk`、`tileMenuEnabled`、`focusedTilePosition`）、玻璃框按 rect 判等、
  输入源「未连接的折叠成 More inputs」，都留下了；我们的 `addXiaomiSourceRows()`（HDMI 1/2/3 行）放在
  它们前面，仍排在最上。
* `Weather` 的日志与 `Weather.show()`、`onResume` 里的 `HomeKeyService.leaveStockHome()`、
  以及 `resumed = true` 都要，所以合在一起。
* 应用库有两个入口协议：上游的 `EXTRA_OPEN_LIBRARY`（`openLibraryIfAsked`）和 qqwersde 的
  `ACTION_APP_LIBRARY`。两者方法名撞车，把前者的方法改名 `openLibraryByAction()`，`onNewIntent`
  里两个都试。

编译验证（本机，`assembleRelease -PlauncherMinSdk=23`）：

```text
BUILD SUCCESSFUL
launcher/app/build/outputs/apk/release/app-release.apk  826,086 bytes
```

上一版（只含 Android 6 适配 + 本机改动）是 740,454 bytes。这次涨到 826 KB 是上游两个月的功能带来的，
不是编译配置问题。

## 第二次跟主线：合并 `3b6b3a15`（2026-11）

用 rebase 而不是 merge：自有提交只有一条，重放到 `3b6b3a15` 之上仍是线性历史，与「仓库只留一条
自有修改记录」的约定一致。

rebase 会重写 hash：本节标题里的上一版自有提交是 `4d022c0f`（合并 `294d3d59` 后），本次 rebase 后
变成 `2cd3f888`。文中旧的 hash 只在本地对象库里查得到，远端历史里没有，读的时候按「第几次合并」认，
不要按 hash 认。

```bash
git fetch upstream --tags
git stash push -u          # 先收起未提交的 HDMI 改动
git rebase upstream/main
git stash pop
```

`15b3e5ac..3b6b3a15` 共 137 个提交，冲突只有两处：

| 文件 | 怎么合的 |
| --- | --- |
| `README.md` | 同上一次的规矩：整份取我们的 |
| `HomeKeyService.java` | 三块。`running` 字段取上游的包可见版本（`InputKeyService` 要访问），我们的 `live`、轮询字段与 `goToStockHome()` 保留；`onDestroy` 两边各加的清理都留；`onKeyEvent` 里上游新增的 `home_long_press_home` 分支放到最前，我们的 Home 短按归位 / 长按任务切换接在后面，并删掉我们那侧重复的 `RemoteKeys.handle` 调用（上游已把它提到 `settingsKey` 判断之前统一处理） |

`config.xml`、`AppAdapter.java`、`AppLibraryDialog.java`、`MainActivity.java` 都是自动合并，
但 `config.xml` 的 `input_sources` 仍需人工核对：见下节。

验证（本机，`assembleRelease -PlauncherMinSdk=23`）：

```text
BUILD SUCCESSFUL
launcher/app/build/outputs/apk/release/app-release.apk  828,383 bytes
crash buffer 空 · 进程在 · Home 由 PatchWall 回到本桌面（约 2–3 秒，固件直派 HOME 的既有代价）
```

## 第三次跟主线：合并 `deba47ca`（2026-11）

同样用 rebase。这次工作区里还压着 HDMI、dock 排序和三份文档的改动没提交，做法和上一次不同：
先把它们做成一个提交，再 rebase —— 冲突落在提交里比落在 stash 里好定位，工作区也不会在中途变空。

```bash
git fetch upstream --tags
git add -A
git commit -m "HDMI: tiles name their port, carry the connected dot, library rows match; dock order survives a reload"
git rebase upstream/main
```

`3b6b3a15..deba47ca` 共 110 个提交，但只动了 launcher 的三个文件，且都不在我们改过的范围里：

| 文件 | 上游改了什么 |
| --- | --- |
| `GamepadPassthrough.java` | 关闭按键过滤时补一句 `VolumeKeys.stop()`，让正按住的音量键不再重复 |
| `InputKeyService.java` | `startActivity` 的 catch 加上 `SecurityException` |
| `RemoteKeys.java` | 同上，两处 |

三处都不需要在 API 23 上改写：`VolumeKeys.stop()` 只用 `AudioManager`、`Handler`、`Looper`。

冲突只有两份 README，都整份取我们的版本。本 fork 的 README 是重写版，没有语言行、品牌表、
Press 卡那几段，上游这次加的 `README.fr.md`、Press 计数、品牌表增补在这些结构里没有落点；
`README.fr.md`、`themes/ridgelines/`、`tools/` 的一批脚本与若干 examples、docs 都正常合入。

验证（本机，`assembleRelease -PlauncherMinSdk=23`）：

```text
BUILD SUCCESSFUL
launcher/app/build/outputs/apk/release/app-release.apk  828,460 bytes
```

## 第四次跟主线：合并 `d11bd68c`（2026-11）

上游发 v3.5.0：Launcher 3.5、versionCode 14、八个新主题、472 个机型、Home 更轻。同样 rebase，动之前先把工作区提交干净。

`deba47ca..d11bd68c` 有 199 个提交、160 个文件，但 launcher 只有 13 个文件，且没有新增类：

| 文件 | 上游改了什么 |
| --- | --- |
| `Config.java` | 新开关 `volume_pauses_filter`：字段、构造参数、`config.xml` 读入、主题键 |
| `GamepadPassthrough.java` | 从「监听手柄」扩成「手柄 + 音量暂停」：`start(service, config)` 工厂、`BroadcastReceiver` 收 `android.media.VOLUME_CHANGED_ACTION`、`PAUSE_MS = 4000` |
| `HomeKeyService.java`、`InputKeyService.java` | 改用上面的工厂 |
| `MainActivity.java` | 玻璃焦点的监听器提成字段并在 `onDestroy` 摘掉；`ConnectivityManager` 改从 application 取；顶栏按钮的 `input:` 目标改走 `openInput()` |
| `OpenOnStart.java` | 同上，`ConnectivityManager` 从 application 取 |
| `ClockDream.java` | `attachBaseContext` 补 `LauncherLocale.wrap`，与 `ClockActivity` 一致 |
| `RemoteKeys.java` | 注释订正：`getevent -t` 那串十六进制按十进制填进列表 |
| `layout/activity_main.xml`、`layout-iw/activity_main.xml` | 时钟加 `fontFeatureSettings="tnum"`，换成等宽数字 |
| `values-da/strings.xml`、`config.xml`、`RemoteKeysTest.java` | 文案、新开关的注释与默认值、测试 |

API 23 逐条核过，全部可用：`Context.getSystemService(Class)` 是 API 23（正好等于本分支的 `minSdk`），`Locale.forLanguageTag` 21，`createConfigurationContext` 17，两参 `registerReceiver` 一直有，`fontFeatureSettings` 21。`volume_pauses_filter` 默认 `false`，本机不开 —— 它解决的是「按键过滤期间音量键每次只走一格」（Xiaomi MOEU0、Google TV 14），这台 4C 没有这个毛病，开了反而让音量键暂停过滤 4 秒。

冲突只有 `README.md` 和 `README.zh-CN.md`，三处和一处，都整份取我们的版本。上游 v3.5 改的是语言行、Press 计数和数字统计，重写版 README 没有这些段落可接。

```text
BUILD SUCCESSFUL
launcher/app/build/outputs/apk/release/app-release.apk  828,890 bytes
```

两次 `fetch` 都断在 `RPC failed; curl 56 ... early EOF`，`http.postBuffer` 加到 1 GB 也没用；只拉分支、不拉 tag 就过了（`git fetch upstream main`）。另外 `git rebase --continue` 会停在提交信息的编辑器里，非交互环境要带 `GIT_EDITOR=true`。

## HDMI 端口编号：固件是反的

本机 `dumpsys tv_input` 在「设备接 HDMI 3」时只注册 `Hdmi1InputService/HW5`，说明固件的
`Hdmi1InputService` 是面板上标着 HDMI 3 的那个插口，`Hdmi3InputService` 才是 HDMI 1；而 tvplayer 的
`input` 号仍按面板（实测 `input=23` 打开的是空着的 HDMI 1，`input=25` 锁定到 HDMI 3 上的设备）。
按 MStar 惯例填 `HW5=23` 会让 HDMI 1 与 HDMI 3 互换显示。

```xml
<string-array name="input_sources" translatable="false">
    <item>HW7=23</item>
    <item>HW6=24</item>
    <item>HW5=25</item>
</string-array>
```

`library_inputs`（`HDMI 1:23 / HDMI 2:24 / HDMI 3:25`）不用改，它本来就是面板编号。
`MainActivity.addXiaomiSourceRows()` 里读状态的 service 也要跟着反：HDMI 1 行读
`Hdmi3InputService`，HDMI 3 行读 `Hdmi1InputService`。

## dock 磁贴排序不持久：排序作用不到后加的行

菜单键 →「移动」重排 dock、退出移动模式后当场生效，但按主页键或回原生桌面再回来就恢复默认顺序。
保存一侧是正常的：`endReorder()` 里 `Order.save` 写出 `tvhome-order 1` / `dock <dock_apps>` /
可选 `docked <n>` / 每行一个 `ComponentName.flattenToShortString()`，`MainActivity` 启动时
`Order.load` 之后立刻 `adapter.setSavedOrder`，文件本身可读可写。

问题在 `AppAdapter.reload()`：`Collections.sort(fresh, Order.compare(savedRanks, …))` 只作用于从
`found` 构建出来的应用条目，而 HDMI 行（`LibraryApps.INPUT_PREFIX` + `R.array.library_inputs` 的源号）
和 `Entry.HOME_SETTINGS`（「桌面」磁贴）是在排序**之后**才 `fresh.add(…)` 追加的。保存的 rank 永远轮
不到它们，每次 reload 都落回追加时的固定位置；移动时看到的顺序变化只是 `items` 在内存里被 `move()` 改过，
所以按 Home、回原生桌面、重启进程都会复位。

修法是在追加完这两类行之后、计算 `docked` 之前，对整张 `fresh` 再排一次：

```java
if (!savedRanks.isEmpty()) {
    Collections.sort(fresh, (a, b) -> Order.compare(savedRanks,
            a.component.flattenToShortString(), b.component.flattenToShortString()));
}
docked = 0;
```

`Order.compare` 对两个都没有 rank 的项返回 0，而 `Collections.sort` 是稳定排序，所以新装应用保持原有
相对顺序、排在已有 rank 的项之后；没有 `order.txt` 时 `savedRanks` 为空，整段不执行，默认顺序不变。

验证（APK 828,407 B）：把 HDMI 1 右移一位 → 退出移动模式 → 进 PatchWall 再回 `com.gathin.tvhome`，
顺序仍是移动后的结果；`am force-stop` 重启进程后同样保持。

## 构建

```bash
cd launcher && ./gradlew assembleRelease -PlauncherMinSdk=23
adb install -r --user 0 launcher/app/build/outputs/apk/release/app-release.apk
```

不带 `-PlauncherMinSdk=23` 时 `minSdk` 还是 26（这是上游的行为），安卓 6 装不上。
重装后无障碍服务要重绑：`install -r` 会丢掉原有绑定，`am force-stop com.gathin.tvhome` 同样会把它解绑
（`pm clear` 更彻底，连 `/data/data` 里的设置一起清），
而系统记的是 `scheduleServiceRestartLocked not allow service … auto restart`，不会自己回来。服务没绑上时
按主页键就是纯固件行为——直接落到 PatchWall，不会再被拉回 `com.gathin.tvhome`。重绑顺序：删
`enabled_accessibility_services` → `accessibility_enabled` 置 0 → 等 3 秒 → 写回
`com.gathin.tvhome/.HomeKeyService` → 置 1 → 等 10 秒，再用
`dumpsys accessibility | grep label=` 确认出现 `Service[label=主页键, capabilities=8]`；一次 `put`
没生效时重试一次通常就上去了。

## 和上游的差异

本分支相对 `qqwersde` 快照多出的改动，一共 8 个文件（5 个 Java、2 个 `values`、1 个 PNG）：

```text
HomeKeyService.java   +208/−…   只按 homekey 广播接管，删掉三处「PatchWall 回前台」兜底，加开机接管窗口
AppAdapter.java       +70 余    应用库支持 input: 虚拟行，桌面条目走 stockHome 分支，reload 末尾按 order 重排整张列表
AppLibraryDialog.java +72       HDMI 虚拟行、图标、动作菜单
LibraryApps.java      +16       input:<源号> 作为合法条目持久化
MainActivity.java     +4        onResume 复位放行开关；应用库传 stockHome 包名
res/values/config.xml +27       dock、input_action、input_sources、library_inputs
res/values/strings.xml +2       app_name → 「主屏幕」
res/drawable-nodpi/ic_hdmi.png  系统自带的 HDMI 插口图标
```

`local.properties`、`build/`、`.gradle/` 由上游的 `.gitignore` 挡在外面，不进仓库。

## 合并之后，安卓 6 上还要守住这一条：API 24/26 的 framework 方法

合并 `294d3d59` 时装出来的包第一次启动就崩，栈是这样的：

```text
NoSuchMethodError: No virtual method isUserUnlocked()Z in class Landroid/os/UserManager;
  at GuardKeep$$ExternalSyntheticApiModelOutline0.m
  at GuardKeep.offInStandby
  at HomeKeyService.armStandbyExit
  at HomeKeyService.loadConfig
  at HomeKeyService.onServiceConnected
```

不是 null 返回，是整个无障碍服务在绑定那一步就死掉，之后 Home 键全归固件。

原因：desugaring 只重写**库**里的 API，不动 framework 的方法。上游在 `Build.VERSION.SDK_INT >= 24`
守卫里写了 `UserManager.isUserUnlocked()`，守卫在源码层面是对的，但 AGP 仍为它生成了
`ExternalSyntheticApiModelOutline` 合成类，运行到 API 23 上就是缺方法。

同一轮排查出来的还有 `String.join`（API 26），出现在 `GuardKeep.ensureHomeKey` 和 `Order.dockKey`，
平时不触发，一触发就是同样的崩溃。四处都按老版本能跑的方式重写：

| 文件 | 原来 | 现在 |
| --- | --- | --- |
| `GuardKeep.offInStandby` | 直接调 `isUserUnlocked()` | 整段包进 `SDK_INT >= 24` |
| `GuardKeep.ensureHomeKey` | `String.join(":", entries)` | 手写 `StringBuilder` 拼 `:` |
| `GuardRevival.onListenerConnected` | 直接调 `isUserUnlocked()` | 整段包进 `SDK_INT >= 24` |
| `Order.dockKey` | `String.join(",", dockApps)` | 手写 `StringBuilder` 拼 `,` |

跟着主线升级时按这个模式自查：凡是 API 24 以上的 framework 调用（`UserManager.isUserUnlocked`、
`PackageManager`/`ResolveInfo` 的 `isHidden`、`loadCustomLabel`、`View.forceHasOverlappingRendering`、
`String.join`、`List.of`/`Map.of`），要么有 `SDK_INT` 守卫，要么本地改写。
`MainActivity` 里那三个封装（`inputHidden`、`skipOffscreen`、`customLabel`）本来就是这么写的，
它们不受影响；受影响的是合并才进来的那几处。

## 以后跟主线

```bash
git fetch upstream
git log --oneline HEAD..upstream/main      # 这次要跟哪些提交
git merge upstream/main                    # 冲突按上面两张取舍表处理
```

合并完这三件事缺一件都别上真机：

1. 冲突取舍：行为归我们，结构归上游。`HomeKeyService`、`MainActivity`、`AppLibraryDialog`
   是老战场，逐块过一遍；
2. 扫 API 24/26 的 framework 调用，见上一节。合并本身不报错，装上去才炸；
3. `assembleRelease -PlauncherMinSdk=23` 编译，装机后把七条行为走一遍：
   主屏渲染 / 「桌面」进 PatchWall / 二级页返回键 / PatchWall 里按 Home / 长按 Home /
   HDMI 入口 / 重启后首屏。

推送与对齐检查：

```bash
git push origin main
git rev-list --left-right --count upstream/main...HEAD   # 左边是 0，说明上游内容都在
```

## 第二次跟主线：`15b3e5ac`（2026-11）

上游在 `294d3d59` 之后又进了 5 条提交，只动 8 个文件：

```text
docs/TROUBLESHOOTING.md
examples/tcl-43c645/debloat.txt
launcher/app/src/main/java/com/gathin/tvhome/ServiceRepair.java
launcher/build-and-install.ps1
tests/test_accessibility_flag_note.py
tests/test_build_install_ps1_order.py
tests/test_flamechart.py
themes/README.md
```

本分支改动的 40 个文件与这 8 个没有任何重叠，`git merge` 无冲突直接通过。合并结果随后压成一条提交（父提交为 `15b3e5ac`），对外发布的分支上自有提交只有这一条。

## 「高级动态效果」开了又自己关

设置里把「高级动态效果」打开，回一次桌面再进去又显示关闭，看着像这台电视不支持。实际是设置被清掉了。

`Config.syncThemeChoices()`（`Config.java:778`）给当前主题算一个签名：`config.json` 的 `长度@lastModified` 加 `theme_revision` 的内容，存进 `THEME_SIGNATURE_KEY`。签名一变，它就把电视端手工设置整批 remove —— `columns`、`animations`、`advanced_effects`、`rows_below_dock`、`wallpaper_choice`、`dock_opacity`、`clock_format` 七项（`:787-794`）。这是给「推主题／重置主题」准备的：主题换了，电视上的选择就该回到新主题的默认。

问题出在 `ThemeImport.bringIn()`（`ThemeImport.java:22`）：只要 `/data/local/tmp/cyt-config.json` 和 `cyt-list.txt` 都还能读，它**每次启动**都调 `copy()` 重写内部 `files/theme/config.json`。`copy()` 没有「内容没变就不写」的判断，于是 `lastModified` 每次启动都是新的，签名每次启动都变，设置每次启动都被清。开关不是没生效，是打开之后刚回到桌面就被删了。

推主题用的是 `cyt-*` 通道，推完没收拾临时的两个文件，就留下这个坑。清掉之后内部 `config.json` 的 mtime 不再变化，签名稳定，设置就能留住。

用 `cyt-*` 通道推主题的纪律：**推完必须 `rm -f /data/local/tmp/cyt-config.json /data/local/tmp/cyt-list.txt`**，只留内部那份副本。

另一个容易误判的地方是效果本身的表现形式。主屏只有 dock 一行时，`AppAdapter` 里高级动态效果的卡片上浮带 `!inDock` 条件（`:587`、`:1102`）：dock 里的卡片不上浮，这是上游 `#2135` 为避免 dock 带内偏位做的取舍。所以开关开与不开，静态截图和像素差都是零——真正变的是焦点移过去的那段过渡：弹簧刚度 `520 / 440`、`320 / 260` 与阻尼 `0.76 / 0.58`（`MainActivity:854-857`），动画时长 `220 / 170` 对 `260 / 180` 与 `OvershootInterpolator` `0.7` 对 `1.6`（`AppAdapter:1116-1118`），缩放 `1.10 → 1.12` 和 `translationZ 8dp` 的阴影也在这条路里。判断开没开要看遥控器移动光标的手感，不要截屏比对。有第二行磁贴、或在应用库里，才有那 4dp 的上浮。