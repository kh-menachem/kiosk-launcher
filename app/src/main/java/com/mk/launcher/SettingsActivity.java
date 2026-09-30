package com.mk.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mk.kiosk.KioskNetwork;
import com.mk.kiosk.KioskPassword;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Choose which apps belong on the device, and the few settings that go with it.
 *
 * <p>Reached only through the corner gesture and the master password. Changes
 * take effect when this screen closes, so ticking several apps is one policy
 * update rather than one per tap.
 */
public class SettingsActivity extends Activity {

    private AppRepo repo;
    private boolean hideOthers;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        repo = new AppRepo(this);
        hideOthers = repo.hideOthers();
        setContentView(build());
    }

    /**
     * Writing the policy on the way out keeps a long ticking session to one
     * update, instead of hiding and unhiding apps under the user's finger.
     */
    private Button chooseButton;

    @Override
    protected void onResume() {
        super.onResume();
        if (chooseButton != null) {
            int n = new AppRepo(this).allowed().size();
            chooseButton.setText("Choose apps   (" + n + " selected)");
        }
    }

    /**
     * Note what this does NOT do: write the app list.
     *
     * <p>It used to, from a copy taken when this screen opened - which quietly
     * undid every change made in the picker, because leaving this screen wrote
     * the stale copy back over them. The list belongs to AppPickerActivity now,
     * and only it may write it.
     */
    @Override
    protected void onPause() {
        super.onPause();
        repo.setHideOthers(hideOthers);
        if (hideOthers) {
            LauncherPolicy.hideEverythingElse(this);
        } else {
            LauncherPolicy.unhideEverything(this);
        }
    }

    private View build() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12141A);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("Launcher settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        TextView state = new TextView(this);
        state.setTextColor(0xFF8A93A6);
        state.setTextSize(13);
        state.setPadding(0, dp(6), 0, dp(12));
        boolean owner = LauncherPolicy.isDeviceOwner(this);
        state.setText(owner
                ? ("Device owner granted. Lockdown is "
                        + (new AppRepo(this).enabled() ? "ON" : "OFF")
                        + ". Apps you tick run locked; everything else is out of reach.")
                : "Device owner NOT granted. Apps will open normally and the device is not locked. "
                        + "Provision with the QR tool to enable locking.");
        root.addView(state);

        root.addView(toggle("Allow a browser inside the lock (needed for OAuth sign-in)",
                new AppRepo(this).allowBrowser(),
                on -> {
                    new AppRepo(this).setAllowBrowser(on);
                    Toast.makeText(this, on
                            ? "Browser allowed - sign-in links will open"
                            : "Browser blocked - OAuth sign-in will not work",
                            Toast.LENGTH_SHORT).show();
                }));

        root.addView(toggle("Hide every other app from the device", hideOthers, on -> {
            hideOthers = on;
            Toast.makeText(this, on
                    ? "Other apps will be hidden"
                    : "Other apps stay installed and reachable", Toast.LENGTH_SHORT).show();
        }));

        TextView pick = new TextView(this);
        pick.setText("Apps on this device");
        pick.setTextColor(Color.WHITE);
        pick.setTextSize(16);
        pick.setPadding(0, dp(16), 0, dp(8));
        root.addView(pick);

        // The picker gets a screen of its own. Wedged in here it showed a
        // handful of rows on a phone with a hundred apps installed.
        Button choose = button("", v ->
                startActivity(new android.content.Intent(this, AppPickerActivity.class)));
        choose.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(choose);
        this.chooseButton = choose;

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(spacer);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        final boolean locked = new AppRepo(this).enabled();

        if (!locked) {
            actions.addView(button("Turn the lockdown back on", v -> {
                LauncherPolicy.enable(this);
                Toast.makeText(this, "Locked down again", Toast.LENGTH_SHORT).show();
                recreate();
            }));
        } else {
            actions.addView(button("Unlock for maintenance", v ->
                    confirmHold("Unlock for maintenance?",
                            "The lock lifts so you can reach Android itself - to turn on USB "
                                    + "debugging, or install an update.\n\nIt comes back the "
                                    + "moment you return to the launcher, and in any case "
                                    + "after fifteen minutes.",
                            () -> {
                                LauncherPolicy.suspend(this);
                                Toast.makeText(this, "Unlocked - returns when you come back",
                                        Toast.LENGTH_LONG).show();
                                finish();
                            })));

            actions.addView(button("Turn the lockdown off", v ->
                    confirmHold("Turn the lockdown off?",
                            "Every hidden app comes back, the device gets its own launcher "
                                    + "again, and it stays that way after a reboot.\n\nDevice "
                                    + "owner is kept, so you can switch it back on from here "
                                    + "at any time. Nothing needs reprovisioning.",
                            () -> {
                                LauncherPolicy.disable(this);
                                Toast.makeText(this, "Lockdown off", Toast.LENGTH_LONG).show();
                                recreate();
                            })));
        }

        actions.addView(toggle("Keep the screen on", new AppRepo(this).keepAwake(),
                on -> new AppRepo(this).setKeepAwake(on)));

        actions.addView(toggle("Show battery and Wi-Fi", new AppRepo(this).showStatus(),
                on -> new AppRepo(this).setShowStatus(on)));

        actions.addView(button("Open Android settings", v ->
                LauncherPolicy.openAndroidSettings(this)));

        actions.addView(button("Restore all hidden apps", v -> {
            LauncherPolicy.unhideEverything(this);
            new AppRepo(this).setHideOthers(false);
            Toast.makeText(this, "Every app is back", Toast.LENGTH_SHORT).show();
            recreate();
        }));

        actions.addView(button("Wi-Fi", v ->
                startActivity(new android.content.Intent(this, WifiActivity.class))));
        actions.addView(button("Reconnect Wi-Fi", v ->
                KioskNetwork.reconnect(this, (ok, msg) ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show())));
        actions.addView(button("Change master password", v -> KioskPassword.change(this)));

        if (LauncherPolicy.isDeviceOwner(this)) {
            Button release = button("Release device owner", v -> confirmRelease());
            release.setTextColor(0xFFD03B3B);
            actions.addView(release);
        }

        actions.addView(button("Done", v -> finish()));
        root.addView(actions);
        return root;
    }

    private View toggle(String label, boolean on, final OnToggle then) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox box = new CheckBox(this);
        box.setChecked(on);
        box.setOnCheckedChangeListener((b, value) -> then.onChanged(value));

        row.addView(t);
        row.addView(box);
        return row;
    }

    private void confirmRelease() {
        confirmHold("Release device owner?",
                "This is the one that cannot be undone from the device.\n\n"
                        + "Every hidden app comes back and the lock stops. Android will not "
                        + "grant device owner again without a factory reset and provisioning "
                        + "from scratch. If you only want the lockdown out of the way, use "
                        + "\"Turn the lockdown off\" instead - that keeps device owner and is "
                        + "reversible.",
                () -> {
                    LauncherPolicy.release(this);
                    Toast.makeText(this, "Device owner released", Toast.LENGTH_LONG).show();
                    finish();
                });
    }

    /**
     * A confirmation that has to be held rather than tapped, the same as the
     * kiosk panel. Leaving a locked device is not something to do by brushing
     * the screen.
     */
    private void confirmHold(String title, String message, final Runnable then) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "\n\nPress and hold to confirm.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Hold", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final long[] downAt = {0};
            yes.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downAt[0] = System.currentTimeMillis();
                        v.postDelayed(() -> {
                            if (downAt[0] > 0 && System.currentTimeMillis() - downAt[0] >= 1400) {
                                dialog.dismiss();
                                then.run();
                            }
                        }, 1450);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        downAt[0] = 0;
                        return true;
                    default:
                        return false;
                }
            });
        });
        dialog.show();
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private interface OnToggle {
        void onChanged(boolean on);
    }
}
