> **Xiaomi Mi TV 4C 55-inch · Android 6.0.1 (API 23).** A fork of
> [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) that runs on one 2016 Xiaomi set.
> Fork-specific detail, merge rules and the upgrade path live in
> [ADAPTATION.md](ADAPTATION.md) and [docs/devices/xiaomi-mitv4c-ansm0.md](docs/devices/xiaomi-mitv4c-ansm0.md).

<p align="center">
  <img src="docs/xiaomi-tv4c-ansm0-home.png" alt="This launcher on a Xiaomi Mi TV 4C 55-inch: clock and weather, the input pill, the app library button, the settings button, and the Desktop, HDMI 1, HDMI 2 and HDMI 3 tiles in the dock." width="800">
</p>

<p align="center">
  English · <a href="README.zh-CN.md">中文</a>
</p>

# CleanYourTV on a Xiaomi Mi TV 4C 55-inch

| | |
| --- | --- |
| Model | Xiaomi Mi TV 4C 55-inch, `MiTV4-ANSM0` |
| Product / board | `xmen` / `gxbaby` |
| System | Android 6.0.1, API 23 |
| ABI | `armeabi-v7a` |
| Panel | 1920 × 1080 |

This branch continues [qqwersde/my-tv-signal-android6](https://github.com/qqwersde/my-tv-signal-android6),
which adapted the upstream launcher to Android 6, and carries upstream main through
[`d11bd68c`](https://github.com/Gathin23/cleanyourtv/commit/d11bd68c) (Launcher 3.5, versionCode 14). Upstream is followed by rebase,
so this fork keeps a single commit of its own on top of upstream's history and no merge commit. The Home key work, the
boot behaviour, the HDMI rows and the API 23 guards below were done on this set. Nothing upstream
added was dropped. The launcher is MIT, copyright Gathin; Outfit ships under the SIL Open Font
License. Unofficial fork.


## Build and install

JDK 17 or 21, Android SDK platform 34, build-tools 34.0.0. From `launcher/`:

```bash
./gradlew assembleRelease -PlauncherMinSdk=23
```

Without `-PlauncherMinSdk=23` the default `minSdk` stays 26 and the APK will not install on API 23.
The artefact is `launcher/app/build/outputs/apk/release/app-release.apk`.

```bash
adb install -r --user 0 launcher/app/build/outputs/apk/release/app-release.apk
```

A re-install drops the accessibility binding every time. On this set the reliable way back is to
remove the entry first and then add it — flipping `accessibility_enabled` 0/1 alone does not rebind:

```bash
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

Wait a few seconds after the last write; the service binds when the system re-reads the list.

## What changed

### Home key: one broadcast is the only hook

A short press arrives as the system's `ACTION_CLOSE_SYSTEM_DIALOGS` broadcast with `reason=homekey`
(`from uid 1000` is the firmware, `from uid 10004` is this launcher), and `openHome()` runs 800 ms
later. A long press arrives as the same broadcast with `reason=recentapps` and is deliberately left
alone, so Xiaomi's task switcher opens and nothing else comes with it.

The manifest also declares the HOME filter at `android:priority="999"`, above the stock home's 100,
which makes this launcher a candidate for the HOME intent. The key itself still goes through the
firmware's own dispatch — the broadcast is what does the actual work.

### The three fallbacks that broke Back are gone

`foregroundCheck`'s trailing redirect, `onAccessibilityEvent`'s `stockHome.contains(name)` check and
`stockHomeReopened()` all read "PatchWall is in front again" as "Home was pressed". That is why Back
inside a second-level PatchWall page, and a long Home press, both jumped to this launcher. All three
are removed. `onKeyEvent` has no `KEYCODE_BACK` branch and needs none, because the firmware does not
route Back into the accessibility filter either. Back now belongs to the app in front.

### Boot first screen

Upstream's `probeAtBoot()` never ran here: it reads `Settings.Global.BOOT_COUNT`, which returns
`null` on this set. `bootTakeover()` treats the accessibility binding as the boot signal instead and
polls once a second for 90 s (`BOOT_WATCH_MS`). The window exists because the firmware starts
PatchWall first and this launcher second; the last frame of that window is the new home.

### PatchWall stays where you put it

The old 60 s release window is gone. `stockHomePassthrough` is permanent while this launcher is the
default, so opening PatchWall from the app library leaves it there instead of handing back on a timer.

Coming back: **My Apps → Home Screen**. `strings.xml` names this app after the Home Screen entry
PatchWall's list offers, and that tap lands here. The direct route is blocked on this firmware:

```text
SecurityException: requires com.mitv.tvhome.permission.HOME_STATE
```

### HDMI 1–3: connection state, drawn the way the set draws it

Three virtual rows, stored by `LibraryApps` as `input:<port>`, all opening
`com.xiaomi.mitv.tvplayer/.ExternalSourceActivity`:

| Row | `input` extra | Panel jack | Firmware input |
| --- | --- | --- | --- |
| HDMI 1 | 23 | HDMI 1 | `Hdmi3InputService/HW7` |
| HDMI 2 | 24 | HDMI 2 | `Hdmi2InputService/HW6` |
| HDMI 3 | 25 | HDMI 3 | `Hdmi1InputService/HW5` |

Action `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY`, int extra `input`, flags `0x10008000`
(`NEW_TASK | CLEAR_TASK`). `CLEAR_TASK` is not optional: without it the activity reuses itself and
HDMI 2 and HDMI 3 come up black.

**The firmware numbers the ports backwards.** Its `Hdmi1InputService` is the jack labelled HDMI 3 and
`Hdmi3InputService` the one labelled HDMI 1, while tvplayer's `input` numbers follow the panel. So
`input_sources` reads `HW7=23, HW6=24, HW5=25` rather than MStar's `HW5=23`. Read forward, a device
on HDMI 3 lit the HDMI 1 tile; the mapping was then checked against `dumpsys tv_input` with a set on
HDMI 3, and again with one on HDMI 1.

**An icon per state.** A port with a device shows the white `ic_hdmi.png`; an empty one, the grey
`ic_hdmi_dim.png`. Two bitmaps on purpose: `ic_hdmi.png` is this set's own file from
`MiTVTVPlayer.apk` (same SHA-256), and drawables loaded from one resource share their constant state,
so a `ColorFilter` put on one port's copy greyed out all three.

**Judging a port** rests on one firmware trait: DroidLogic registers an HDMI input with
`TvInputManager` only while a device answers on it. With a set on HDMI 2 the input list holds
`Hdmi2InputService/HW6` alone; unplug it and no HDMI input is registered at all, while the three
services keep running. A configured port that no registered input names is therefore empty. Where
nothing can be judged — no `TvInputManager`, an empty list, no mapping for the port — the port stays
lit instead of greying out one that works.

**Hot plug needs no polling.** `onInputStateChanged`, `onInputAdded` and `onInputRemoved` call
`reloadInputIcons()`, which reloads the tiles and the app library, so the change shows at the press
itself.

**The green dot** marks a connected port on the Home tile, the way the set's own source panel marks
it. `HdmiPorts.tileName()` appends a `ReplacementSpan` that draws a circle of `0.6 × textSize`, after
a `0.30 × textSize` gap, centred on the line box: a `"●"` character sits on the baseline and reads
low, and a compound drawable pins itself to the view's right edge, far from centred text.

### Library rows behave like app rows

Short OK pins the row to Home and it flips to "already on Home"; long OK opens the row menu (add to
Home / remove from Home / App info). `AppLibraryDialog` gained the catalog filter and these actions
for `input:` rows, and `AppAdapter` draws them. The stock-home entry takes a separate branch because
it is not an installed package.

### What the merge kept

From upstream, with this set's settings: `power_on_home` and `power_on_home_always`,
`guard_off_in_standby`, the `app_keys` front rows, the tile long-press menu (`holdOk`), the glass
focus rect, collapsing unconnected input sources, and the weather line. The first merge brought
these in from `15b3e5ac`; the rebases onto `3b6b3a15`, `deba47ca` and `d11bd68c` kept every one of them.

Two of them had to be redone for API 23. `UserManager.isUserUnlocked()` (Android 7) and `String.join`
(Android 8.1) appear in upstream code behind `SDK_INT` guards, but desugaring rewrites library APIs
only — the first bind threw `NoSuchMethodError` and took the whole accessibility service down. Both
are now either outside the call on API 23 or hand-joined. Detail in [ADAPTATION.md](ADAPTATION.md).

## Known limits

Two properties of this firmware account for most of the entries below.

**The Home key never reaches an app.** `MitvPhoneWindowManager` dispatches `KEYCODE_HOME` straight to
the foreground window, and `dumpsys accessibility` on this set shows a service receives only
`TYPE_WINDOW_STATE_CHANGED` — no key events at all. The key cannot be swallowed, only reacted to.

**PatchWall is the system home.** `com.mitv.tvhome` is an updated system app with HOME priority 100,
and `pm disable-user` / `pm hide` are refused here (`MANAGE_USERS`). Nothing is uninstalled: unused
apps are hidden by the launcher config, and the stock home is always one tap away in the app library.
| You will notice | Because | What it means |
| --- | --- | --- |
| A Home press takes about five seconds | The firmware routes it to PatchWall first, then this launcher cold-starts; the merged build also draws tiles and scans apps | Correct, but not instant |
| Back cannot be intercepted | No key events reach accessibility on this firmware | Do not add a `KEYCODE_BACK` branch |
| Long Home opens Xiaomi's task switcher | The firmware sends `reason=recentapps`, which is ignored on purpose | It keeps the screenshot and clear-all this set ships with; this launcher has no recents screen of its own |
| App info may not open | The settings app does not handle the standard application-details intent | — |
| `pm disable-user` and `pm hide` are refused | `MANAGE_USERS` | `tools/disable.sh` and a full `tools/restore.sh` do not apply to this TV |

## Following upstream

```bash
git fetch upstream
git log --oneline HEAD..upstream/main       # what is new
git rebase upstream/main                    # conflicts: behaviour from here, structure from upstream
```

Then rebuild with `-PlauncherMinSdk=23`, install, and walk these before calling it done:

- [ ] The home screen renders
- [ ] The Desktop tile opens PatchWall
- [ ] Back inside a second-level page stays put
- [ ] Home from PatchWall returns here
- [ ] Long Home opens Xiaomi recents only
- [ ] An HDMI entry opens `ExternalSourceActivity`
- [ ] The first screen after a reboot is this launcher

After every merge, re-scan for API 24+ framework calls: the merge compiles fine and only fails on the
TV.

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font License, see
`launcher/FONT-LICENSE-OFL.txt`.
