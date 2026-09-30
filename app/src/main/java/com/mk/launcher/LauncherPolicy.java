package com.mk.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;

import com.mk.kiosk.Kiosk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the device into a small, fixed set of apps.
 *
 * <p>This is the multi-app side of lock task, and the reason it exists: an app
 * like AppSheet cannot be made a device owner, and does not need to be. Only
 * the launcher holds device owner. It names the allowed packages, and Android
 * lets those run inside the lock whoever wrote or signed them. The allowed apps
 * need no cooperation, no SDK, and no awareness that any of this is happening.
 *
 * <p>Home returns here rather than being blocked, which is what makes a set of
 * apps usable instead of a single kiosk.
 */
final class LauncherPolicy {

    static final String TAG = "KioskLauncher";

    private LauncherPolicy() { }

    /**
     * Packages never hidden, whatever the allow-list says.
     *
     * <p>Hiding a package makes it vanish from the system entirely, and some of
     * them cannot be lost without taking the device with them. Settings stays
     * visible because hiding it would break the Wi-Fi repair route while
     * leaving no way back; it is kept off the lock-task allow-list instead, so
     * a user still cannot open it.
     */
    private static final Set<String> NEVER_HIDE = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.android.settings",
            "com.android.settings.intelligence",
            "android",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
    ));

    /**
     * Packages allowed to run inside the lock alongside the chosen apps.
     *
     * <p>An app is never just its own package. Tapping "Sign in with Google"
     * hands off to Play Services; a photo button hands off to the camera or the
     * document picker. Lock task refuses to start anything outside the list and
     * says nothing about it, so the button simply does nothing and the app looks
     * broken. These are the helpers that make ordinary apps work.
     *
     * <p>Settings is deliberately absent: it is a way out, and it is opened
     * briefly and on purpose from the settings screen instead.
     */
    private static final List<String> HELPERS = Arrays.asList(
            "com.google.android.gms",            // sign-in, account picker, consent
            "com.google.android.gsf",
            "com.android.vending",               // Play, for auth and updates
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",  // runtime permission dialogs
            "com.android.documentsui",           // file and photo pickers
            "com.android.webview",
            "com.google.android.webview"
    );

    /**
     * The lock-task list, with no repeats.
     *
     * <p>setLockTaskPackages throws IllegalArgumentException on a duplicate and
     * rejects the whole call, so a chosen app that is also a helper - Play Store
     * is both - silently left the previous list in force and every hand-off
     * looked like a blocked app. A set keeps that from ever recurring.
     */
    private static String[] lockTaskList(Context ctx, String... extra) {
        Set<String> out = new LinkedHashSet<>(new AppRepo(ctx).allowed());
        out.add(ctx.getPackageName());
        out.addAll(HELPERS);
        if (new AppRepo(ctx).allowBrowser()) out.addAll(browserPackages(ctx));
        if (extra != null) out.addAll(Arrays.asList(extra));
        out.remove(null);
        return out.toArray(new String[0]);
    }

    static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static ComponentName admin(Context ctx) {
        // the admin receiver comes from the kiosk library, so the same QR
        // provisioning tooling works for this app unchanged
        return new ComponentName(ctx, com.mk.kiosk.KioskAdminReceiver.class);
    }

    static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager d = dpm(ctx);
        return d != null && d.isDeviceOwnerApp(ctx.getPackageName());
    }

    /**
     * Applies the whole policy. Safe to call repeatedly - every piece is
     * attempted on its own so an OEM refusing one does not lose the rest.
     */
    /** Minutes a maintenance unlock survives if nobody comes back. */
    private static final long MAINTENANCE_MS = 15 * 60 * 1000L;

    private static long suspendedUntil = 0L;
    private static boolean wentAwayWhileUnlocked = false;

    static boolean isSuspended() {
        return System.currentTimeMillis() < suspendedUntil;
    }

    /**
     * True when returning to the launcher should re-lock it.
     *
     * Leaving lock task itself causes a resume, so "always re-lock on resume"
     * would make the maintenance unlock impossible - the lock would snap back
     * before anyone could leave. Only a real trip away from the app counts.
     */
    static boolean shouldReapply(Context ctx) {
        if (!new AppRepo(ctx).enabled()) return false;
        if (isSuspended() && !wentAwayWhileUnlocked) return false;
        wentAwayWhileUnlocked = false;
        return true;
    }

    /** Called when the launcher genuinely goes to the background. */
    static void noteLeft() {
        if (isSuspended()) wentAwayWhileUnlocked = true;
    }

    static void apply(Activity activity) {
        suspendedUntil = 0L;
        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);
        AppRepo repo = new AppRepo(ctx);

        if (isDeviceOwner(ctx) && d != null) {
            ComponentName admin = admin(ctx);

            // 1. who may run inside the lock: us, plus whatever was chosen
            String[] allowed = lockTaskList(ctx);
            try {
                d.setLockTaskPackages(admin, allowed);
                android.util.Log.i(TAG, "lock task packages: " + Arrays.toString(allowed));
            } catch (Exception e) {
                // Never silent again. This failing left the old list in force and
                // made every allow-listed hand-off look like a blocked app.
                android.util.Log.w(TAG, "setLockTaskPackages FAILED: "
                        + Arrays.toString(allowed), e);
            }

            // 2. Home must work, or there is no way back from an allowed app.
            //    This is the difference between a launcher and a single-app kiosk.
            try {
                // Recents is deliberately left out. It showed a card that closed
                // straight back to the launcher, which is noise, and it is a
                // second way into an app the grid already offers.
                // NOTIFICATIONS is what lets the shade be pulled down, so it stays
                // off: a dropdown full of other apps is a way out of the kiosk.
                // SYSTEM_INFO keeps the clock and signal readable without it.
                // GLOBAL_ACTIONS stays, or the device cannot be powered off.
                int features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                        | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
                d.setLockTaskFeatures(admin, features);
            } catch (Exception ignored) { }

            // 3. be Home, so the button and a reboot both land here
            try {
                IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addCategory(Intent.CATEGORY_DEFAULT);
                d.addPersistentPreferredActivity(admin, home,
                        new ComponentName(ctx, LauncherActivity.class));
            } catch (Exception ignored) { }

            try {
                d.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        String.valueOf(BatteryManager.BATTERY_PLUGGED_AC
                                | BatteryManager.BATTERY_PLUGGED_USB
                                | BatteryManager.BATTERY_PLUGGED_WIRELESS));
                d.setKeyguardDisabled(admin, true);
                // stops the shade being dragged down even where an OEM allows it
                d.setStatusBarDisabled(admin, true);
            } catch (Exception ignored) { }

            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    d.setUserControlDisabledPackages(admin,
                            Collections.singletonList(ctx.getPackageName()));
                } catch (Exception ignored) { }
            }

            // Scanning for networks needs location permission. A device owner can
            // grant it to itself, which keeps a permission dialog out of a kiosk
            // where there may be nobody able to answer it.
            if (Build.VERSION.SDK_INT >= 23) {
                for (String p : new String[]{
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION"}) {
                    try {
                        d.setPermissionGrantState(admin, ctx.getPackageName(), p,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    } catch (Exception ignored) { }
                }
            }

            // Permission alone is not enough: with system Location switched off,
            // Android returns an empty scan list and hides even the connected
            // network's name. A device owner can turn it on, which is the only
            // way an in-app Wi-Fi picker can work on a locked device.
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    d.setLocationEnabled(admin, true);
                } catch (Exception ignored) { }
            }

            // The voice assistant answers a hardware key and a wake word, which
            // lock task does not govern, and will open apps and search the web
            // on request. It has no launcher icon, so hiding apps never catches
            // it - a locked tablet was found still answering Bixby.
            Kiosk.blockAssistant(activity, true);

            if (repo.hideOthers()) hideEverythingElse(ctx);
        }

        try {
            activity.startLockTask();
        } catch (Exception ignored) {
            // not device owner: the launcher still works, it just cannot lock
        }
    }

    /**
     * Hides every launchable app that is not allowed, so it cannot be reached
     * by any route - not a share sheet, not a notification, not a link.
     */
    static void hideEverythingElse(Context ctx) {
        DevicePolicyManager d = dpm(ctx);
        if (d == null || !isDeviceOwner(ctx)) return;

        AppRepo repo = new AppRepo(ctx);
        Set<String> keep = new HashSet<>(repo.allowed());
        keep.add(ctx.getPackageName());
        keep.addAll(NEVER_HIDE);
        // Never hide another home screen. Hiding the stock launcher and then
        // giving up the persistent Home entry leaves the device with nowhere to
        // go - no launcher, and no way to reach one.
        keep.addAll(homePackages(ctx));
        keep.addAll(HELPERS);
        if (repo.allowBrowser()) keep.addAll(browserPackages(ctx));

        // un-hide what is allowed before hiding anything else, so a package can
        // never be left hidden because the pass stopped early on some refusal
        for (String pkg : keep) {
            try {
                d.setApplicationHidden(admin(ctx), pkg, false);
            } catch (Exception ignored) { }
        }

        for (String pkg : launchablePackages(ctx)) {
            if (keep.contains(pkg)) continue;
            try {
                d.setApplicationHidden(admin(ctx), pkg, true);
            } catch (Exception ignored) {
                // some packages refuse; skipping one is better than stopping
            }
        }
    }

    /** Puts every hidden app back. Used when hiding is switched off. */
    static void unhideEverything(Context ctx) {
        DevicePolicyManager d = dpm(ctx);
        if (d == null || !isDeviceOwner(ctx)) return;

        for (String pkg : allPackages(ctx)) {
            try {
                if (d.isApplicationHidden(admin(ctx), pkg)) {
                    d.setApplicationHidden(admin(ctx), pkg, false);
                }
            } catch (Exception ignored) { }
        }
    }

    /**
     * Opens an allowed app, inside the lock.
     *
     * <p>Without {@code setLockTaskEnabled} the app would start outside lock
     * task and the device would be unlocked for as long as it was in front.
     */
    static boolean launch(Activity activity, String pkg) {
        Intent i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (isDeviceOwner(activity)) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLockTaskEnabled(true);
                activity.startActivity(i, opts.toBundle());
            } else {
                activity.startActivity(i);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Opens Android's own settings, allow-listing it just long enough.
     *
     * The way out when something needs fixing on the device itself. Without it
     * a locked launcher has no route to Android at all.
     */
    static void openAndroidSettings(Activity activity) {
        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);
        if (isDeviceOwner(ctx) && d != null) {
            try {
                d.setLockTaskPackages(admin(ctx),
                        lockTaskList(ctx, "com.android.settings"));
                d.setApplicationHidden(admin(ctx), "com.android.settings", false);
            } catch (Exception ignored) { }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    /** Lets Settings through the allow-list briefly, for Wi-Fi. */
    static void openWifiSettings(Activity activity) {
        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);
        if (isDeviceOwner(ctx) && d != null) {
            try {
                d.setLockTaskPackages(admin(ctx),
                        lockTaskList(ctx, "com.android.settings"));
            } catch (Exception ignored) { }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    /**
     * Temporary unlock so Android itself can be reached - to turn on USB
     * debugging, or install an update. Coming back to the launcher re-locks it,
     * and it expires on its own after fifteen minutes.
     */
    static void suspend(Activity activity) {
        suspendedUntil = System.currentTimeMillis() + MAINTENANCE_MS;
        wentAwayWhileUnlocked = false;

        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);
        if (d != null && isDeviceOwner(ctx)) {
            try {
                // Hand Home back, or the unlock is no unlock at all: the button
                // would return here and Android would stay out of reach.
                d.clearPackagePersistentPreferredActivities(admin(ctx), ctx.getPackageName());
                d.setLockTaskFeatures(admin(ctx), 0);
            } catch (Exception ignored) { }
        }
        try {
            activity.stopLockTask();
        } catch (Exception ignored) { }
    }

    /**
     * Switches the lockdown off and leaves it off, keeping device owner.
     *
     * Everything hidden comes back and the persistent Home entry is dropped, or
     * the device would still be stuck on this launcher with nothing to show.
     * Device owner is kept, so {@link #enable} is a button press rather than a
     * factory reset.
     */
    static void disable(Activity activity) {
        Context ctx = activity.getApplicationContext();
        new AppRepo(ctx).setEnabled(false);
        suspendedUntil = 0L;
        wentAwayWhileUnlocked = false;

        Kiosk.blockAssistant(activity, false);
        unhideEverything(ctx);

        DevicePolicyManager d = dpm(ctx);
        if (d != null && isDeviceOwner(ctx)) {
            try {
                d.setLockTaskFeatures(admin(ctx), 0);
                d.setLockTaskPackages(admin(ctx), new String[0]);
                // give the device its own launcher back
                d.clearPackagePersistentPreferredActivities(admin(ctx), ctx.getPackageName());
                d.setStatusBarDisabled(admin(ctx), false);
                d.setKeyguardDisabled(admin(ctx), false);
                if (Build.VERSION.SDK_INT >= 30) {
                    d.setUserControlDisabledPackages(admin(ctx), Collections.<String>emptyList());
                }
            } catch (Exception ignored) { }
        }
        try {
            activity.stopLockTask();
        } catch (Exception ignored) { }
    }

    /** Switches the lockdown back on. */
    static void enable(Activity activity) {
        new AppRepo(activity).setEnabled(true);
        suspendedUntil = 0L;
        wentAwayWhileUnlocked = false;
        apply(activity);
    }

    /** Hands the device back: unhides everything, drops the lock and the grant. */
    static void release(Activity activity) {
        Context ctx = activity.getApplicationContext();
        new AppRepo(ctx).setEnabled(false);
        suspendedUntil = 0L;
        Kiosk.blockAssistant(activity, false);
        unhideEverything(ctx);
        DevicePolicyManager d = dpm(ctx);
        if (d != null && isDeviceOwner(ctx)) {
            try {
                d.setLockTaskFeatures(admin(ctx), 0);
                d.clearPackagePersistentPreferredActivities(admin(ctx), ctx.getPackageName());
                d.setStatusBarDisabled(admin(ctx), false);
                d.setKeyguardDisabled(admin(ctx), false);
                if (Build.VERSION.SDK_INT >= 30) {
                    d.setUserControlDisabledPackages(admin(ctx), Collections.<String>emptyList());
                }
            } catch (Exception ignored) { }
        }
        try {
            activity.stopLockTask();
        } catch (Exception ignored) { }
        try {
            if (d != null) d.clearDeviceOwnerApp(ctx.getPackageName());
        } catch (Exception ignored) { }
    }

    /* ------------------------------------------------------------------ */

    /**
     * Every package with a launcher icon - the apps a person could open.
     *
     * <p>MATCH_UNINSTALLED_PACKAGES matters more than it looks: a hidden app
     * drops out of an ordinary query, so without this a package could be hidden
     * once and then never found again to un-hide it. Ticking an app back on
     * would silently do nothing.
     */
    static List<String> launchablePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        for (ResolveInfo r : ctx.getPackageManager().queryIntentActivities(main, flags)) {
            if (r.activityInfo != null && !out.contains(r.activityInfo.packageName)) {
                out.add(r.activityInfo.packageName);
            }
        }
        return out;
    }

    /**
     * Every package that can open a web link.
     *
     * <p>Signing in to a modern app usually means OAuth in a browser, not an
     * account picker. Hide the browser and the sign-in button does nothing,
     * because nothing on the device can handle the link at all.
     *
     * <p>Queried with MATCH_UNINSTALLED_PACKAGES so an already-hidden browser
     * is still found - otherwise it could be hidden once and never recovered.
     */
    static List<String> browserPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_ALL;
        for (ResolveInfo r : ctx.getPackageManager().queryIntentActivities(web, flags)) {
            if (r.activityInfo != null && !out.contains(r.activityInfo.packageName)) {
                out.add(r.activityInfo.packageName);
            }
        }
        return out;
    }

    /** Every package that can act as a home screen, ours included. */
    static List<String> homePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        for (ResolveInfo r : ctx.getPackageManager().queryIntentActivities(home, flags)) {
            if (r.activityInfo != null && !out.contains(r.activityInfo.packageName)) {
                out.add(r.activityInfo.packageName);
            }
        }
        return out;
    }

    private static List<String> allPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        for (ApplicationInfo a : ctx.getPackageManager()
                .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            out.add(a.packageName);
        }
        return out;
    }
}
