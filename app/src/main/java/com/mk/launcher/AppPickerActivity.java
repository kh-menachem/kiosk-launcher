package com.mk.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Choosing which apps belong on the device, on a screen of its own.
 *
 * <p>This used to be a short list wedged between the toggles and the buttons on
 * the settings screen, which left a few rows visible on a phone with a hundred
 * apps installed. Here the list gets the whole screen and a search box.
 *
 * <p>Ticks are written on the way out, so working through a long list is one
 * policy update rather than one per tap - hiding and un-hiding apps under
 * somebody's finger is slow and makes the list jump.
 */
public class AppPickerActivity extends Activity {

    private AppRepo repo;
    private Set<String> chosen;
    private List<AppRepo.App> all;
    private String filter = "";

    private LinearLayout list;
    private TextView count;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        repo = new AppRepo(this);
        chosen = new LinkedHashSet<>(repo.allowed());
        all = repo.installed();
        setContentView(build());
        paint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        repo.setAllowed(chosen);
        if (repo.hideOthers()) {
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
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("Choose apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        count = new TextView(this);
        count.setTextColor(0xFF8A93A6);
        count.setTextSize(13);
        count.setPadding(0, dp(4), 0, dp(10));
        root.addView(count);

        final EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF6C7488);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter = s.toString().trim().toLowerCase(Locale.getDefault());
                paint();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(search);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        // weight 1: the list takes everything left over, which is the point
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(wide(button("Clear all", v -> {
            chosen.clear();
            paint();
        })));
        actions.addView(wide(button("Done", v -> finish())));
        root.addView(actions);
        return root;
    }

    private void paint() {
        list.removeAllViews();

        int shown = 0;
        for (AppRepo.App app : all) {
            if (!matches(app)) continue;
            list.addView(row(app));
            shown++;
        }
        if (shown == 0) {
            TextView none = new TextView(this);
            none.setTextColor(0xFF8A93A6);
            none.setPadding(0, dp(24), 0, 0);
            none.setText("Nothing matches \"" + filter + "\".");
            list.addView(none);
        }
        count.setText(chosen.size() + " selected   ·   " + all.size() + " installed"
                + (filter.isEmpty() ? "" : ("   ·   " + shown + " shown")));
    }

    private boolean matches(AppRepo.App app) {
        if (filter.isEmpty()) return true;
        // package name too, so an app can be found when its label is not what
        // you remember - or when two apps share a label
        return app.label.toLowerCase(Locale.getDefault()).contains(filter)
                || app.pkg.toLowerCase(Locale.getDefault()).contains(filter);
    }

    private View row(final AppRepo.App app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(12), 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);

        TextView pkg = new TextView(this);
        pkg.setText(app.pkg);
        pkg.setTextColor(0xFF6C7488);
        pkg.setTextSize(11);
        pkg.setSingleLine(true);

        text.addView(label);
        text.addView(pkg);

        final CheckBox check = new CheckBox(this);
        check.setChecked(chosen.contains(app.pkg));
        check.setOnClickListener(v -> {
            if (check.isChecked()) chosen.add(app.pkg); else chosen.remove(app.pkg);
            count.setText(chosen.size() + " selected   ·   " + all.size() + " installed");
        });

        box.addView(icon);
        box.addView(text);
        box.addView(check);
        // the whole row is the target; a checkbox alone is a small thing to hit
        box.setOnClickListener(v -> check.performClick());
        return box;
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
