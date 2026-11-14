package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserManager;
import android.service.notification.NotificationListenerService;

/**
 * Starts this launcher's process after the firmware has killed it.
 *
 * A notification listener keeps its approval across a force stop, and the system binds it again
 * once the process is gone. That rebind is what brings Home back on TCL and Xiaomi. No notification
 * is read. Enable with {@code cmd notification allow_listener com.gathin.tvhome/.GuardRevival}.
 */
public class GuardRevival extends NotificationListenerService {
    private BroadcastReceiver unlocked;

    @Override
    public void onListenerConnected() {
        // Android 6 has no direct-boot lock, and isUserUnlocked() does not exist there at all
        // (NoSuchMethodError, which would kill this listener before it can revive the helper).
        if (Build.VERSION.SDK_INT >= 24) {
            UserManager users = getSystemService(UserManager.class);
            if (users != null && !users.isUserUnlocked()) {
                if (unlocked != null) return;
                unlocked = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        stopWaiting();
                        GuardKeep.ensureHomeKey(GuardRevival.this);
                    }
                };
                registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                if (users.isUserUnlocked()) {
                    stopWaiting();
                    GuardKeep.ensureHomeKey(this);
                }
                return;
            }
        }
        GuardKeep.ensureHomeKey(this);
    }

    private void stopWaiting() {
        if (unlocked == null) return;
        try {
            unregisterReceiver(unlocked);
        } catch (RuntimeException ignored) {
        }
        unlocked = null;
    }

    @Override
    public void onDestroy() {
        stopWaiting();
        super.onDestroy();
    }
}
