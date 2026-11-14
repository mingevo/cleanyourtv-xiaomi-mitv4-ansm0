package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    private boolean settingsClearTask;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** Package named by stock_home_redirect, so the usage poll can look it up again after it returns. */
    private String stockHomePackage = "";
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries; the package in front goes to RemoteKeys.frontPackage, for both helpers. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected or after a volume change; null unless gamepad_passthrough or volume_pauses_filter, and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    static HomeKeyService running;
    /** Android 6: PatchWall sends no window event, so usage stats are polled once a second. */
    private boolean foregroundPollStarted;
    private boolean foregroundPollStopped;
    private int foregroundTicks;
    private static final long FOREGROUND_POLL_MS = 1000;
    private static final long FOREGROUND_LOOKBACK_MS = 10_000;
    /** Uptime window power_on_home counts as "at power-on". bootTakeover() uses the bind instead: this set reports
     *  no boot counter, so an uptime-based test cannot tell a reboot from a wake. */
    private static final long BOOT_WINDOW_MS = 60_000L;
    /**
     * Set when a tile for stock_home_redirect is tapped: the stock launcher was opened on purpose, so Home stays
     * out of the way until the user leaves it. There is no time limit — the owner works inside PatchWall. It is
     * cleared as soon as any other activity comes to the front, or when this launcher is resumed again.
     */
    static volatile boolean stockHomePassthrough;
    /** When the stock launcher was opened on purpose; its own entry is ignored below this point plus a margin. */
    private static volatile long stockHomeEnteredAt;
    /** When the task switcher was put up from the long press, so its own resume is not read as a Home press. */
    private static volatile long recentsShownAt;

    static void enterStockHome() {
        stockHomePassthrough = true;
        stockHomeEnteredAt = System.currentTimeMillis();
    }

    /**
     * Ends the pass-through. Ignored while the entry is still in flight: bringing the stock launcher up resumes
     * this launcher for a moment on the way, and that must not be read as the owner coming back.
     */
    static void leaveStockHome() {
        if (System.currentTimeMillis() - stockHomeEnteredAt < ENTRY_GRACE_MS) return;
        stockHomePassthrough = false;
    }

    /** How long after the entry a resume of this launcher still counts as part of the entry itself. */
    private static final long ENTRY_GRACE_MS = 2500;

    /** The bound service instance, so a tile can ask Android to deliver HOME without starting a guarded activity. */
    private static volatile HomeKeyService live;

    /**
     * Brings the stock launcher to the front the way the remote does. performGlobalAction(HOME) is the system
     * delivering the key itself, so the firmware hands it to whichever process it treats as home (PatchWall)
     * without needing com.mitv.tvhome.permission.HOME_STATE, which only the firmware holds and which blocks any
     * explicit start of its launcher activity. Returns false when the service is not bound, so callers can fall
     * back to an explicit intent.
     */
    static boolean goToStockHome() {
        HomeKeyService service = live;
        if (service == null) return false;
        service.performGlobalAction(GLOBAL_ACTION_HOME);
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        live = this;
        watchHomeKey();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        if (userUnlocked()) {
            loadConfig();
            return;
        }
        UserManager users = getSystemService(UserManager.class);
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (userUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    /** Android 6 has no direct-boot lock. isUserUnlocked is Android 7. */
    private boolean userUnlocked() {
        if (android.os.Build.VERSION.SDK_INT < 24) return true;
        UserManager users = getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        if (live == this) live = null;
        RemoteKeys.frontPackage = "";
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        stopWatchingHomeKey();
        foregroundPollStopped = true;
        handler.removeCallbacks(foregroundCheck);
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    /**
     * The window manager closes system dialogs on every Home press with reason "homekey", and this firmware hands
     * the key itself to the front window instead of letting the accessibility filter see it. That broadcast is the
     * only signal that survives inside the stock launcher, whose own Home press does not even produce a usage
     * event because it is already the foreground activity. Guarded by the pass-through and delayed, so a long press
     * that brings up the task switcher is not mistaken for a short one.
     */
    private BroadcastReceiver watcher;
    private static final long HOMEKEY_SETTLE_MS = 800;

    private final Runnable homekeyCheck = new Runnable() {
        @Override
        public void run() {
            // The entry itself is performed with a HOME action, which raises the same broadcast: skip that one.
            if (System.currentTimeMillis() - stockHomeEnteredAt < ENTRY_GRACE_MS) return;
            // A long press raises "recentapps" and is filtered out in the watcher, but the firmware also closes
            // system dialogs with "homekey" when its own panel is up: leave the task switcher alone.
            if (System.currentTimeMillis() - recentsShownAt < 3000) return;
            // A short Home press. Nothing else produces this reason, so it means "back to this launcher" wherever
            // the key was pressed — inside PatchWall, inside an app PatchWall opened, in an input, in this launcher.
            // No front-activity test here: the usage record lags by a second or two, and testing it let a Home
            // press in another app fall through and leave the owner on PatchWall.
            stockHomePassthrough = false;
            openHome();
        }
    };

    private void watchHomeKey() {
        if (watcher != null) return;
        watcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String reason = intent.getStringExtra("reason");
                if (!"homekey".equals(reason)) return;
                // Not guarded by the pass-through: the firmware also routes a Home press made in another app
                // straight to PatchWall, and that press has to come back here too.
                handler.removeCallbacks(homekeyCheck);
                handler.postDelayed(homekeyCheck, HOMEKEY_SETTLE_MS);
            }
        };
        registerReceiver(watcher, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    private void stopWatchingHomeKey() {
        if (watcher == null) return;
        unregisterReceiver(watcher);
        watcher = null;
        handler.removeCallbacks(homekeyCheck);
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        settingsClearTask = config.settingsKeyClearTask;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        // Window changes: stock home coming back, and this TV's long-press Home, which the firmware
        // handles itself (CloudEntryActivity) before the key ever reaches this filter.
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        if (!stockHome.isEmpty()) bootTakeover();
        // MiTV4-ANSM0 (Android 6): the stock home comes up without an accessibility window event.
        // Usage stats still name the activity in front. Without the permission the poll stops itself.
        if (Build.VERSION.SDK_INT <= 23 && !foregroundPollStarted) {
            foregroundPollStarted = true;
            handler.postDelayed(foregroundCheck, FOREGROUND_POLL_MS);
        }
        // Upstream's probeAtBoot() here is dropped on purpose: it counts boots with Settings.Global BOOT_COUNT,
        // which this set answers with null, so it would never fire. bootTakeover() above counts from the bind.
        armStandbyExit();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough and volume_pauses_filter, off by default.
        if (gamepads == null) gamepads = GamepadPassthrough.start(this, config);
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        return RemoteKeys.tap(keys.get(code), front.get(code));
    }

    private boolean isSettingsScancode(KeyEvent event) {
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        if (isSettingsScancode(event)) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // A keycode app_keys entry with a tap target wins over the Settings keycodes (InputKeyService opens it too, and
        // RemoteKeys answers a press once), but not over a button named in settings_key_scancodes. A hold-only entry
        // leaves the tap to Settings and still runs its hold.
        boolean settingsKey = isSettingsKey(event);
        String tap = inFront(appKeys, frontAppKeys, event.getKeyCode());
        String hold = holdAppKeys.get(event.getKeyCode());
        if ((!settingsKey || (tap != null && !isSettingsScancode(event))) && RemoteKeys.handle(this, event, tap, hold)) {
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (settingsKey) {
            RemoteKeys.handle(this, event, null, hold);
            boolean held = hold != null && event.getEventTime() - event.getDownTime() >= RemoteKeys.HOLD_MS;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && !held) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                // settings_key_clear_task: on MediaTek boards KEYCODE_SETTINGS is also a global key that opens Display &
                // Sound in the Settings task before this helper sees it; cleared, Back from Settings goes Home, not there.
                if (settingsClearTask && !settingsPanel) open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
            return true;
        }
        int code = event.getKeyCode();
        // Consume Home. Returning it to this firmware starts 云服务同步 on a long press, because the
        // stock launcher that used to open all apps is gone. A short press opens Home; a long press
        // opens the app library (the same list as 我的应用).
        if (code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
                // An explicit press of Home always means "back to this launcher", even from the stock one.
                leaveStockHome();
                openHome();
            } else {
                // Long press: the task switcher, where the running apps can be seen and cleared.
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                RemoteKeys.frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }
        CharSequence cls = event.getClassName();
        if (cls == null) return;
        String name = cls.toString();
        // Long-press Home on this firmware opens Xiaomi cloud sync instead of a task switcher. Turn that screen
        // into the task switcher the owner asked for: it lists the running apps and can clear them all.
        if (name.endsWith("CloudEntryActivity")) {
            recentsShownAt = System.currentTimeMillis();
            // The task switcher is now the front surface, so Home takes over again once it is dismissed.
            leaveStockHome();
            performGlobalAction(GLOBAL_ACTION_RECENTS);
            return;
        }
        // stock_home_redirect: while the stock launcher was opened on purpose, leave it alone. Another app (or a
        // system panel such as the task switcher) coming to the front means the owner has moved on, so Home
        // takes over again from there.
        if (stockHomePassthrough) {
            CharSequence from = event.getPackageName();
            String pkg = from == null ? "" : from.toString();
            if (!pkg.isEmpty() && !pkg.equals(stockHomePackage) && !pkg.equals(getPackageName())
                    && !pkg.equals("android") && !pkg.startsWith("com.android.systemui")) {
                stockHomePassthrough = false;
            }
            return;
        }
        // No "stock home came to the front, so send the owner back" here. PatchWall reaches the front for reasons
        // that are not Home: Back out of a page it opened, Back out of 相册 / 电视管家, and the long press that
        // raises the task switcher. Redirecting on those threw the owner out of every one of them. Home is
        // recognised only by its own "homekey" broadcast, in homekeyCheck.
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after the bind the boot takeover keeps looking for the firmware home screen. The bind itself is
     *  the "boot finished" signal, so counting from it is steadier than counting from uptime (this TV reports no
     *  boot counter at all), and the window still covers a slow start-up. */
    private static final long BOOT_WATCH_MS = 90 * 1000L;

    private long bootWatchUntil;
    private boolean bootChecked;

    /** This TV's own home screen is the system home process (`mHomeProcess: com.mitv.tvhome`, run as uid 1000 by
     *  the firmware), so a restart lands on PatchWall with nobody having pressed Home. Nothing redirects that
     *  screen, which is why a reboot used to stay there. From the bind on, for the next minute and a half, the
     *  one-second poll below takes the first chance to bring this launcher forward — but only when what is in
     *  front really is PatchWall: an HDMI input, or an app the TV resumed by itself at boot, is left alone. */
    private void bootTakeover() {
        bootChecked = false;
        bootWatchUntil = SystemClock.elapsedRealtime() + BOOT_WATCH_MS;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** One look per second at which activity moved to the front. Stops after a missing permission. */
    private final Runnable foregroundCheck = new Runnable() {
        @Override
        public void run() {
            if (foregroundPollStopped) return;
            handler.postDelayed(this, FOREGROUND_POLL_MS);
            foregroundTicks++;
            if (stockHome.isEmpty() || foregroundTicks % 30 == 1) refreshStockHomes();
            if (stockHome.isEmpty()) return;
            long[] at = new long[1];
            String cls = newestForeground(at);
            if (!bootChecked && SystemClock.elapsedRealtime() < bootWatchUntil) {
                // Boot takeover, see bootTakeover. Any other screen in front means the TV was left on an input or
                // on an app, so the owner is where they meant to be.
                if (cls != null && isStockLauncher(cls)) {
                    bootChecked = true;
                    android.util.Log.i("TvHomeBoot", "takeover front=" + cls);
                    stockHomePassthrough = false;
                    openHome();
                }
                return;
            }
            if (stockHomePassthrough) {
                // Stay as long as the owner is anywhere inside PatchWall: its home screen, or any page opened from
                // there (details, 我的应用, …). Those pages are the same package under another class name, so the
                // test has to be by package.
                if (cls == null || isStockLauncher(cls)) return;
                stockHomePassthrough = false;
            }
            // Nothing else here opens this launcher. PatchWall coming back to the front is not a Home press: it is
            // what Back from a page it opened (a detail screen, 相册, 电视管家) does, and what the long press that
            // shows the task switcher does. Treating it as Home bounced the owner out of both. The Home key itself
            // is recognised by its own broadcast, in homekeyCheck, on a short press only.
        }
    };

    private void refreshStockHomes() {
        String pkg = stockHomePackage;
        if (pkg == null || pkg.isEmpty()) return;
        stockHome.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            stockHome.add(ri.activityInfo.name);
        }
    }

    /**
     * True when this activity class belongs to the stock launcher: its Home activity itself, or any page it opens
     * from there (details, 我的应用, …). Those pages are the same package under other class names, so the package
     * prefix is what keeps a page change from reading as "the owner left PatchWall". Matched against the package
     * seen in the usage event, never against a hard-coded class list, which would go stale on the next firmware.
     */
    private boolean isStockLauncher(String cls) {
        if (cls == null || cls.isEmpty()) return false;
        if (stockHome.contains(cls)) return true;
        return !stockHomePackage.isEmpty() && cls.startsWith(stockHomePackage + ".");
    }

    /** Class name of the newest move to the front in the last few seconds, or null. */
    private String newestForeground(long[] atOut) {
        UsageStatsManager stats = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (stats == null) return null;
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = stats.queryEvents(now - FOREGROUND_LOOKBACK_MS, now);
            if (events == null) return null;
            UsageEvents.Event event = new UsageEvents.Event();
            String cls = null;
            long at = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && event.getClassName() != null) {
                    cls = event.getClassName();
                    at = event.getTimeStamp();
                }
            }
            atOut[0] = at;
            return cls;
        } catch (SecurityException denied) {
            foregroundPollStopped = true;
            return null;
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        if (stockHomePassthrough) return;
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        startActivity(open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /** Long-press Home: the full app list, including apps that are not on the desktop. */
    private void openLibrary() {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one). A Home intent would then put this launcher in the home task and make it the home process, and from
     *  Android 10 the stock launcher, no longer exempt from the limits on background activity starts, can't open
     *  apps for the remote's YouTube and Netflix buttons (#1274). openHome starts it like an app instead:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
