package com.mk.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wi-Fi, inside the launcher.
 *
 * <p>The status bar's Wi-Fi icon is a way out of the kiosk - tapping it opens
 * quick settings and the device is loose. Blocking it is only reasonable if
 * there is still some way to join a network, so this is that way.
 *
 * <p>Joining a network needs privileges an ordinary app lost in Android 10.
 * A device owner keeps them, which is why this can exist here at all.
 */
public class WifiActivity extends Activity {

    private WifiManager wifi;
    private LinearLayout list;
    private TextView status;
    private BroadcastReceiver scanDone;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanDone = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { paint(); }
        };
        registerReceiver(scanDone, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        scan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(scanDone);
        } catch (Exception ignored) { }
    }

    private View build() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12141A);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("Wi-Fi");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF8A93A6);
        status.setTextSize(13);
        status.setPadding(0, dp(6), 0, dp(12));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(wide(button("Rescan", v -> scan())));
        actions.addView(wide(button("Reconnect", v ->
                com.mk.kiosk.KioskNetwork.reconnect(this, (ok, msg) ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()))));
        actions.addView(wide(button("Done", v -> finish())));
        root.addView(actions);
        return root;
    }

    private void scan() {
        if (wifi == null) {
            status.setText("No Wi-Fi hardware on this device.");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            try {
                wifi.setWifiEnabled(true);      // device owner may still do this
            } catch (Exception ignored) { }
        }
        status.setText("Scanning...");
        try {
            wifi.startScan();
        } catch (Exception ignored) { }
        paint();
    }

    private void paint() {
        if (wifi == null) return;
        list.removeAllViews();

        String current = "";
        try {
            current = wifi.getConnectionInfo().getSSID().replace("\"", "");
        } catch (Exception ignored) { }
        status.setText(current.isEmpty() || current.equals("<unknown ssid>")
                ? "Not connected" : ("Connected to " + current));

        List<ScanResult> results = new ArrayList<>();
        try {
            results = wifi.getScanResults();
        } catch (Exception ignored) {
            // without location permission this comes back empty rather than throwing
        }
        if (results.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextColor(0xFF8A93A6);
            none.setPadding(0, dp(24), 0, 0);
            none.setText("No networks found. Tap Rescan.\n\n"
                    + "If this stays empty, location permission is missing - it is "
                    + "what Android requires before an app may list Wi-Fi networks.");
            list.addView(none);
            return;
        }

        // strongest first, one row per name
        Collections.sort(results, (a, b) -> b.level - a.level);
        Set<String> seen = new HashSet<>();
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.trim().isEmpty()) continue;
            if (!seen.add(r.SSID)) continue;
            list.addView(row(r, r.SSID.equals(current)));
        }
    }

    private View row(final ScanResult r, boolean connected) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));
        box.setClickable(true);
        box.setOnClickListener(v -> join(r));

        TextView name = new TextView(this);
        name.setText(r.SSID + (connected ? "   (connected)" : ""));
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);

        TextView sub = new TextView(this);
        sub.setTextColor(0xFF6C7488);
        sub.setTextSize(12);
        int bars = WifiManager.calculateSignalLevel(r.level, 5);
        sub.setText(secured(r) ? ("secured   signal " + bars + "/4")
                               : ("open   signal " + bars + "/4"));

        box.addView(name);
        box.addView(sub);
        return box;
    }

    /** WPA3 / SAE access points need a different key exchange from WPA2. */
    private static boolean isSae(ScanResult r) {
        return r.capabilities != null && r.capabilities.contains("SAE");
    }

    private static boolean secured(ScanResult r) {
        String c = r.capabilities == null ? "" : r.capabilities;
        return c.contains("WEP") || c.contains("PSK") || c.contains("EAP") || c.contains("SAE");
    }

    private void join(final ScanResult r) {
        if (!secured(r)) {
            connect(r.SSID, null, false);
            return;
        }
        final EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle(r.SSID)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect",
                        (d, w) -> connect(r.SSID, pass.getText().toString(), isSae(r)))
                .show();
    }

    /**
     * Saves the network and switches to it.
     *
     * <p>WifiConfiguration is deprecated and ignored for ordinary apps since
     * Android 10, but a device owner is still allowed to use it, and it is the
     * only route that connects without asking the user to approve a suggestion.
     */
    private void connect(String ssid, String password) {
        connect(ssid, password, false);
    }

    private void connect(String ssid, String password, boolean sae) {
        if (wifi == null) return;
        try {
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                conf.preSharedKey = "\"" + password + "\"";
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                // WPA3 is a different key exchange; a PSK-only config silently
                // fails to associate with an SAE-only access point. Offering
                // both lets a transitional AP pick whichever it speaks.
                if (sae && Build.VERSION.SDK_INT >= 29) {
                    try {
                        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
                    } catch (Exception ignored) { }
                }
            }

            int id = wifi.addNetwork(conf);
            if (id < 0) {
                // already saved: find it and use the existing entry
                for (WifiConfiguration existing : wifi.getConfiguredNetworks()) {
                    if (existing.SSID != null && existing.SSID.equals(conf.SSID)) {
                        id = existing.networkId;
                        break;
                    }
                }
            }
            if (id < 0) {
                Toast.makeText(this, "Android would not save that network",
                        Toast.LENGTH_LONG).show();
                return;
            }
            wifi.disconnect();
            wifi.enableNetwork(id, true);
            wifi.reconnect();
            Toast.makeText(this, "Connecting to " + ssid, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not connect: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private View wide(Button b) {
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
