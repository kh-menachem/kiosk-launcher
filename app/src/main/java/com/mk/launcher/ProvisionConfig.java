package com.mk.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mk.kiosk.Kiosk;
import com.mk.kiosk.KioskPassword;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Applies whatever configuration the provisioning QR carried, so a device can
 * arrive set up instead of being set up by hand.
 *
 * <p>Everything here is optional. A QR with no configuration leaves the
 * launcher behaving exactly as it does today - an empty grid and a prompt to
 * choose a master password - so this costs nothing when it is not used.
 *
 * <h3>Keys</h3>
 * <pre>
 * apps            ["com.example.one", "com.example.two"]   what appears on the grid
 * appManifestUrl  "https://host/apps.json"                 apps to install and keep current
 * masterPassword  "…"                                      set instead of prompting
 * hideOthers      true / false
 * allowBrowser    true / false
 * keepAwake       true / false
 * showStatus      true / false
 * </pre>
 *
 * <p>The keys are this app's own. The library hands back the bundle verbatim
 * and ascribes no meaning to it, so another app built on the same library is
 * free to use entirely different ones.
 */
final class ProvisionConfig {

    private ProvisionConfig() { }

    private static final String TAG = LauncherPolicy.TAG;
    private static final String KEY_APPLIED = "provision_applied";
    private static final String KEY_MANIFEST = "app_manifest_url";

    /**
     * Applies the configuration once, the first time the app runs after
     * provisioning.
     *
     * <p>Marked applied afterwards, so somebody who later changes the app list
     * by hand does not find the QR's list back the next time the app starts.
     *
     * @return true when configuration was found and applied
     */
    static boolean applyOnce(Activity activity) {
        Context ctx = activity.getApplicationContext();
        if (prefs(ctx).getBoolean(KEY_APPLIED, false)) return false;

        String json = Kiosk.provisioningExtras(ctx);
        if (json == null || json.isEmpty()) {
            // Nothing was sent. Mark it done so an unconfigured device does not
            // look for a bundle on every launch.
            prefs(ctx).edit().putBoolean(KEY_APPLIED, true).apply();
            return false;
        }

        try {
            JSONObject cfg = new JSONObject(json);
            AppRepo repo = new AppRepo(ctx);

            JSONArray apps = cfg.optJSONArray("apps");
            if (apps != null) {
                Set<String> allowed = new LinkedHashSet<>();
                for (int i = 0; i < apps.length(); i++) {
                    String pkg = apps.optString(i, "").trim();
                    if (!pkg.isEmpty()) allowed.add(pkg);
                }
                repo.setAllowed(allowed);
                Log.i(TAG, "provisioned app list: " + allowed);
            }

            if (cfg.has("hideOthers"))   repo.setHideOthers(cfg.optBoolean("hideOthers", true));
            if (cfg.has("allowBrowser")) repo.setAllowBrowser(cfg.optBoolean("allowBrowser", true));
            if (cfg.has("keepAwake"))    repo.setKeepAwake(cfg.optBoolean("keepAwake", false));
            if (cfg.has("showStatus"))   repo.setShowStatus(cfg.optBoolean("showStatus", true));

            String password = cfg.optString("masterPassword", "");
            if (!password.isEmpty() && !KioskPassword.isSet(ctx)) {
                KioskPassword.set(ctx, password);
                Log.i(TAG, "master password set from provisioning");
            }

            String manifest = cfg.optString("appManifestUrl", "");
            if (!manifest.isEmpty()) {
                prefs(ctx).edit().putString(KEY_MANIFEST, manifest).apply();
            }

            prefs(ctx).edit().putBoolean(KEY_APPLIED, true).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not apply provisioning config: " + json, e);
            // Not marked applied: a transient parse problem should not cost the
            // device its configuration for good.
            return false;
        }
    }

    /** Where to look for apps to install and keep current, or empty. */
    static String manifestUrl(Context ctx) {
        return prefs(ctx).getString(KEY_MANIFEST, "");
    }

    static void setManifestUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_MANIFEST, url == null ? "" : url).apply();
    }

    /**
     * Installs and updates whatever the manifest lists, off the main thread.
     *
     * <p>Quiet by design: this runs whenever the launcher comes to the front,
     * and a kiosk should not be interrupted by progress it cannot act on.
     */
    static void syncApps(final Context ctx, final Runnable onChanged) {
        final String url = manifestUrl(ctx);
        if (url.isEmpty()) return;

        new Thread(() -> {
            int changed = AppInstaller.syncFrom(ctx, url, null);
            if (changed > 0 && onChanged != null) {
                new Handler(Looper.getMainLooper()).post(onChanged);
            }
        }, "app-sync").start();
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences("com.mk.launcher", Context.MODE_PRIVATE);
    }
}
