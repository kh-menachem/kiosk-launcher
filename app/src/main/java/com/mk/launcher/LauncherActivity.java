package com.mk.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mk.kiosk.KioskPassword;
import com.mk.kiosk.KioskStatus;

import java.util.List;

/**
 * The home screen: a grid of the apps that were chosen, and nothing else.
 *
 * <p>Seven taps in the top-left corner then two in the top-right asks for the
 * master password and opens
 * the settings, the same gesture the kiosk library uses everywhere else.
 */
public class LauncherActivity extends Activity {

    /** The launcher's own background. The status strip is derived from it. */
    static final int BACKGROUND = 0xFF12141A;


    private GridLayout grid;
    private TextView empty;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());

        // Whatever the provisioning QR carried, applied once. A device with no
        // configuration behaves exactly as before, so this costs nothing unused.
        ProvisionConfig.applyOnce(this);

        if (!KioskPassword.isSet(this)) {
            // First run with no password from provisioning: the device must not
            // end up locked with no way into its own controls.
            getWindow().getDecorView().post(() -> KioskPassword.choose(this, null));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppRepo repo = new AppRepo(this);

        if (LauncherPolicy.shouldReapply(this)) LauncherPolicy.apply(this);

        // Tapping the Wi-Fi icon opens our own Wi-Fi screen rather than
        // Android's. Settings is kept off the allow-list on purpose here - it
        // is a way out of the launcher - so the library's default would be
        // handing back the escape route we just closed.
        // The strip's AUTO style reads the theme, but this screen paints its own
        // background in code, so the theme is not what is actually on screen.
        // Derive the strip from the colour that is really behind it instead, so
        // restyling the launcher cannot leave the strip on the old palette.
        // Flat, with no container behind the icons: this screen is a plain
        // dark field and a chip floating on it reads as a box that wandered in
        // from somewhere else.
        KioskStatus.coloursForFlat(BACKGROUND);

        KioskStatus.wifiAction(() ->
                startActivity(new Intent(this, WifiActivity.class)));
        KioskStatus.attach(this, repo.showStatus());
        keepAwake(repo.keepAwake());
        paint();

        // Install anything the manifest lists that is missing or outdated, in
        // the background. Repaint if the grid gained an app.
        ProvisionConfig.syncApps(this, this::paint);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // a real trip away from the launcher ends a maintenance unlock
        LauncherPolicy.noteLeft();
    }

    private void keepAwake(boolean on) {
        if (on) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Home was pressed while already here; nothing to do but stay put. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        paint();
    }

    /** There is nowhere to go back to from a home screen. */
    @Override
    public void onBackPressed() {
        // deliberately empty
    }

    /* ---- the way in: seven taps top-left, then two top-right ---- */

    private static final int LEFT_TAPS = 7;
    private static final int RIGHT_TAPS = 2;
    private static final long WINDOW_MS = 3000L;
    private static final float CORNER = 0.15f;

    /** How far through the sequence we are: counting left taps, then right. */
    private int left = 0;
    private int right = 0;
    private long lastTapAt = 0;
    private boolean asking = false;

    /**
     * The launcher owns its window, so it can watch touches directly rather
     * than wrapping the window callback the way the library has to.
     *
     * <p>Two corners rather than one: seven taps in the same place is a thing a
     * bored person can arrive at, and a child certainly can. Having to finish
     * somewhere else makes it a sequence you need to know rather than one you
     * can stumble into.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View decor = getWindow().getDecorView();
            long now = System.currentTimeMillis();

            if (decor.getWidth() > 0) {
                float w = decor.getWidth(), h = decor.getHeight();
                boolean top        = e.getY() < h * CORNER;
                boolean inTopLeft  = top && e.getX() < w * CORNER;
                boolean inTopRight = top && e.getX() > w - (w * CORNER);

                // a pause anywhere in the sequence starts it over
                if (now - lastTapAt > WINDOW_MS) {
                    left = 0;
                    right = 0;
                }
                lastTapAt = now;

                if (left < LEFT_TAPS) {
                    // still on the left half of the sequence
                    if (inTopLeft) left++;
                    else left = 0;
                } else if (inTopRight) {
                    if (++right >= RIGHT_TAPS) {
                        left = 0;
                        right = 0;
                        openSettings();
                    }
                } else if (!inTopLeft) {
                    // wandered off before finishing: start again
                    left = 0;
                    right = 0;
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void openSettings() {
        if (asking) return;                 // one prompt at a time
        asking = true;
        KioskPassword.require(this,
                () -> {
                    asking = false;
                    startActivity(new Intent(this, SettingsActivity.class));
                },
                () -> asking = false);
    }

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(pad, dp(48), pad, pad);

        empty = new TextView(this);
        empty.setTextColor(0xFF8A93A6);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(80), 0, 0);
        empty.setText("No apps chosen yet.\n\n"
                + "Tap the top-left corner seven times to open settings\n"
                + "and pick which apps belong on this device.");

        grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);

        root.addView(empty);
        root.addView(scroll);
        return root;
    }

    private void paint() {
        if (grid == null) return;
        grid.removeAllViews();

        List<AppRepo.App> apps = new AppRepo(this).forLauncher();
        empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);

        // wider screens get more columns; a 4-across grid on a tablet wastes it
        int columns = Math.max(3, getResources().getConfiguration().screenWidthDp / 150);
        grid.setColumnCount(columns);

        for (AppRepo.App app : apps) {
            grid.addView(tile(app));
        }
    }

    private View tile(final AppRepo.App app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(16), dp(8), dp(16));
        box.setClickable(true);
        box.setOnClickListener(v -> {
            if (!LauncherPolicy.launch(this, app.pkg)) {
                Toast.makeText(this, app.label + " would not open", Toast.LENGTH_SHORT).show();
            }
        });

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(64), dp(64));
        icon.setLayoutParams(ip);

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setPadding(0, dp(8), 0, 0);

        box.addView(icon);
        box.addView(label);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        box.setLayoutParams(lp);
        return box;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
