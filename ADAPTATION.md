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

## 构建

```bash
cd launcher && ./gradlew assembleRelease -PlauncherMinSdk=23
adb install -r --user 0 launcher/app/build/outputs/apk/release/app-release.apk
```

不带 `-PlauncherMinSdk=23` 时 `minSdk` 还是 26（这是上游的行为），安卓 6 装不上。
重装后无障碍服务要重绑，见设备笔记末尾。

## 和上游的差异

本分支相对 `qqwersde` 快照多出的改动，一共 8 个文件（5 个 Java、2 个 `values`、1 个 PNG）：

```text
HomeKeyService.java   +208/−…   只按 homekey 广播接管，删掉三处「PatchWall 回前台」兜底，加开机接管窗口
AppAdapter.java       +70       应用库支持 input: 虚拟行，桌面条目走 stockHome 分支
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