package com.mk.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Installs and updates apps without anyone tapping anything.
 *
 * <p>A device owner may install silently, which is the only way a kiosk can be
 * set up and kept current without a Google account on it - and an account is
 * the thing that would cost the adb route back in if the device ever locked
 * itself out.
 *
 * <p>Deliberately generic: it takes a manifest of package names and URLs and
 * knows nothing about which apps they are.
 *
 * <h3>The manifest</h3>
 * <pre>
 * {
 *   "com.example.app": { "versionCode": 42, "url": "https://host/app.apk" },
 *   "com.example.two": { "versionCode":  7, "url": "https://host/two.apk",
 *                        "splits": ["https://host/two.config.arm64_v8a.apk"] }
 * }
 * </pre>
 *
 * <p>Nothing is installed unless its versionCode is higher than what is on the
 * device, so a check that finds nothing new costs one small download.
 */
final class AppInstaller {

    private AppInstaller() { }

    private static final String TAG = LauncherPolicy.TAG;
    private static final int TIMEOUT_MS = 30000;

    interface Progress {
        void onStep(String message);
    }

    /**
     * Reads the manifest and installs anything missing or outdated.
     *
     * Runs on the calling thread and does network work, so call it from a
     * background thread.
     *
     * @return how many packages were installed or updated
     */
    static int syncFrom(Context ctx, String manifestUrl, Progress progress) {
        int done = 0;
        try {
            say(progress, "Checking for apps...");
            JSONObject manifest = new JSONObject(fetchText(manifestUrl));

            for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject entry = manifest.optJSONObject(pkg);
                if (entry == null) continue;

                long want = entry.optLong("versionCode", -1);
                long have = installedVersion(ctx, pkg);
                if (have >= 0 && want >= 0 && have >= want) continue;   // current

                String url = entry.optString("url", "");
                if (url.isEmpty()) continue;

                List<String> parts = new ArrayList<>();
                parts.add(url);
                org.json.JSONArray splits = entry.optJSONArray("splits");
                if (splits != null) {
                    for (int i = 0; i < splits.length(); i++) parts.add(splits.optString(i));
                }

                say(progress, (have < 0 ? "Installing " : "Updating ") + pkg);
                if (install(ctx, pkg, parts)) done++;
            }
        } catch (Exception e) {
            Log.w(TAG, "app sync failed for " + manifestUrl, e);
            say(progress, "Could not reach the app server");
        }
        return done;
    }

    /** -1 when the package is not installed. */
    static long installedVersion(Context ctx, String pkg) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * Streams each APK into an install session and commits it.
     *
     * <p>Several parts in one session is how a split app is installed; a
     * single-APK app is just the one part. Installing splits separately fails -
     * they are one package, and the base has to arrive with them.
     */
    private static boolean install(Context ctx, String pkg, List<String> urls) {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(pkg);

            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            int n = 0;
            for (String url : urls) {
                if (url == null || url.isEmpty()) continue;
                try (InputStream in = open(url);
                     OutputStream out = session.openWrite("part" + (n++), 0, -1)) {
                    byte[] buf = new byte[65536];
                    int read;
                    while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
                    session.fsync(out);
                }
            }

            // A device owner's install is approved without a prompt, so the
            // result is only interesting for the log.
            IntentSender sender = resultSender(ctx, sessionId);
            session.commit(sender);
            session.close();
            session = null;
            Log.i(TAG, "install committed for " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "install failed for " + pkg, e);
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) { }
            }
            return false;
        } finally {
            if (session != null) session.close();
        }
    }

    private static IntentSender resultSender(Context ctx, int sessionId) {
        Intent intent = new Intent(ctx, InstallResultReceiver.class);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            flags |= android.app.PendingIntent.FLAG_MUTABLE;
        }
        return android.app.PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                .getIntentSender();
    }

    private static InputStream open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() / 100 != 2) {
            throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        }
        return c.getInputStream();
    }

    private static String fetchText(String url) throws IOException {
        try (InputStream in = open(url)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            return out.toString("UTF-8");
        }
    }

    private static void say(Progress p, String message) {
        Log.i(TAG, message);
        if (p != null) p.onStep(message);
    }
}
