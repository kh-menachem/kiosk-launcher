package com.mk.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Which apps are on the launcher, and what the device has to offer. */
final class AppRepo {

    private static final String FILE = "com.mk.launcher";
    private static final String KEY_ALLOWED = "allowed";
    private static final String KEY_HIDE = "hide_others";
    private static final String KEY_ENABLED = "locked";
    private static final String KEY_AWAKE = "keep_awake";
    private static final String KEY_STATUS = "show_status";
    private static final String KEY_BROWSER = "allow_browser";

    private final Context ctx;
    private final SharedPreferences sp;

    AppRepo(Context context) {
        this.ctx = context.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** One entry per app the launcher can show. */
    static final class App implements Comparable<App> {
        final String pkg;
        final String label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }

        @Override
        public int compareTo(App other) {
            return label.compareToIgnoreCase(other.label);
        }
    }

    /* ---- the chosen set ---- */

    Set<String> allowed() {
        // a copy: SharedPreferences hands back a set it will reuse, and editing
        // the returned instance corrupts what is stored
        return new LinkedHashSet<>(sp.getStringSet(KEY_ALLOWED, new HashSet<>()));
    }

    void setAllowed(Set<String> packages) {
        sp.edit().putStringSet(KEY_ALLOWED, new HashSet<>(packages)).apply();
    }

    boolean isAllowed(String pkg) {
        return allowed().contains(pkg);
    }

    /**
     * Whether the device is locked down at all. Off means the launcher behaves
     * like an ordinary app until it is switched back on, and it survives a
     * reboot - unlike the temporary maintenance unlock.
     */
    boolean enabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    void setEnabled(boolean on) {
        sp.edit().putBoolean(KEY_ENABLED, on).apply();
    }

    boolean keepAwake() {
        return sp.getBoolean(KEY_AWAKE, false);
    }

    void setKeepAwake(boolean on) {
        sp.edit().putBoolean(KEY_AWAKE, on).apply();
    }

    boolean showStatus() {
        return sp.getBoolean(KEY_STATUS, true);
    }

    void setShowStatus(boolean on) {
        sp.edit().putBoolean(KEY_STATUS, on).apply();
    }

    /**
     * Whether a browser may run inside the lock.
     *
     * <p>On by default: without it, any app that signs in through OAuth has a
     * button that does nothing. The cost is real though - a browser inside the
     * lock can reach the open web, so turn it off for a device that only needs
     * apps which sign in some other way.
     */
    boolean allowBrowser() {
        return sp.getBoolean(KEY_BROWSER, true);
    }

    void setAllowBrowser(boolean on) {
        sp.edit().putBoolean(KEY_BROWSER, on).apply();
    }

    boolean hideOthers() {
        return sp.getBoolean(KEY_HIDE, true);
    }

    void setHideOthers(boolean on) {
        sp.edit().putBoolean(KEY_HIDE, on).apply();
    }

    /* ---- what is installed ---- */

    /**
     * Every app with a launcher icon, this one excluded.
     *
     * <p>Reads through the package manager rather than a hidden-app query, so a
     * package hidden by the policy still appears here - otherwise switching an
     * app off would make it impossible to switch back on.
     */
    List<App> installed() {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS;

        for (ResolveInfo r : pm.queryIntentActivities(main, flags)) {
            if (r.activityInfo == null) continue;
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;   // never list ourselves
            if (!seen.add(pkg)) continue;
            try {
                out.add(new App(pkg,
                        r.loadLabel(pm).toString(),
                        r.loadIcon(pm)));
            } catch (Exception ignored) { }
        }
        Collections.sort(out);
        return out;
    }

    /** The chosen apps, in the order they should appear on the launcher. */
    List<App> forLauncher() {
        Set<String> want = allowed();
        List<App> out = new ArrayList<>();
        for (App a : installed()) {
            if (want.contains(a.pkg)) out.add(a);
        }
        return out;
    }
}
