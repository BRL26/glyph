package dev.brl.glyph;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    // Material 3 dark baseline palette.
    private static final int BG = 0xFF141218;
    private static final int SURFACE = 0xFF1D1B20;
    private static final int SURFACE_2 = 0xFF211F26;
    private static final int SURFACE_3 = 0xFF2B2930;
    private static final int PRIMARY = 0xFFD0BCFF;
    private static final int ON_PRIMARY = 0xFF381E72;
    private static final int PRIMARY_CONTAINER = 0xFF4F378B;
    private static final int ON_PRIMARY_CONTAINER = 0xFFEADDFF;
    private static final int SECONDARY = 0xFFCCC2DC;
    private static final int SECONDARY_CONTAINER = 0xFF4A4458;
    private static final int ON_SECONDARY_CONTAINER = 0xFFE8DEF8;
    private static final int ON_SURFACE = 0xFFE6E0E9;
    private static final int VARIANT = 0xFFCAC4D0;
    private static final int OUTLINE = 0xFF938F99;

    private static final int STOCK_ENABLED = Stock.ENABLED;
    private static final int STOCK_DISABLED = Stock.DISABLED;
    private static final int STOCK_REMOVED = Stock.REMOVED;

    private static final int STOCK_ACTION_DISABLE = 0;
    private static final int STOCK_ACTION_REMOVE = 1;
    private static final int STOCK_ACTION_RESTORE = 2;
    private static final int STOCK_ACTION_REINSTALL = 3;

    private static final String[] ZONE_NAMES = {"Right", "Bottom left", "Top left"};
    private static final char[] ZONE_ID = {'A', 'B', 'C'};
    private static final String[] STYLE_NAMES = {"Pulse", "Breathe", "Wave"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private View[] navItems;
    private ImageView[] navIcons;
    private TextView[] navLabels;
    private int tab;
    private boolean subPage;
    private int subParentTab;
    private Switch masterSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(buildShell());
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            goBack();
                        }
                    });
        }
        showTab(0);
        ArrayList<String> permissions = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Root.get().init(getApplicationContext());
        final int before = stockState();
        Stock.refresh(this, new Runnable() {
            @Override
            public void run() {
                if (stockState() != before && !subPage) {
                    showTab(tab);
                }
            }
        });
        if (Prefs.allowed(this) && (Prefs.anyZoneHas(this, Prefs.B_VOLUME)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL))) {
            GlyphService.refresh(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && tab == 1) {
            showTab(1);
        } else if (requestCode == 2 && !subPage) {
            showTab(tab);
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private void goBack() {
        if (subPage) {
            showTab(subParentTab);
            return;
        }
        if (tab != 0) {
            showTab(0);
            return;
        }
        finish();
    }

    // --------------------------------------------------------------- shell

    private View buildShell() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(content);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackground(rounded(SURFACE_2, 28, 0));
        nav.setElevation(dp(6));
        nav.setClipChildren(false);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80));
        navParams.setMargins(dp(14), dp(8), dp(14), dp(12));
        outer.addView(nav, navParams);

        navItems = new View[4];
        navIcons = new ImageView[4];
        navLabels = new TextView[4];
        for (int i = 0; i < 4; i++) {
            nav.addView(buildNavItem(i), new LinearLayout.LayoutParams(0, dp(64), 1f));
        }

        outer.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, Math.max(bars.bottom, dp(8)));
                return insets;
            }
        });
        outer.requestApplyInsets();
        return outer;
    }

    private View buildNavItem(final int index) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(4), dp(4), dp(2));

        ImageView icon = new ImageView(this);
        int iconId = index == 0 ? R.drawable.ic_home
                : index == 1 ? R.drawable.ic_tune
                : index == 2 ? R.drawable.ic_bell : R.drawable.ic_debug;
        icon.setImageResource(iconId);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(16), dp(4), dp(16), dp(4));
        item.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(32)));

        TextView label = new TextView(this);
        label.setText(index == 0 ? "Home" : index == 1 ? "Phone"
                : index == 2 ? "Alerts" : "Debug");
        label.setTextSize(10.5f);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dp(2), 0, 0);
        item.addView(label);

        navItems[index] = item;
        navIcons[index] = icon;
        navLabels[index] = label;
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(index);
            }
        });
        return item;
    }

    private void showTab(int index) {
        tab = index;
        subPage = false;
        subParentTab = index;
        selectNav(index);
        View page;
        if (index == 0) {
            page = buildHome();
        } else if (index == 1) {
            page = buildConfigure();
        } else if (index == 2) {
            page = buildAlerts();
        } else {
            page = buildDebug();
        }
        setPage(page);
    }

    private void showSubPage(View page) {
        subParentTab = tab;
        subPage = true;
        selectNav(tab);
        setPage(page);
    }

    private void selectNav(int selected) {
        for (int i = 0; i < 4; i++) {
            if (navItems[i] == null) {
                continue;
            }
            boolean on = i == selected;
            navItems[i].setBackground(null);
            navIcons[i].setBackground(on ? rounded(SECONDARY_CONTAINER, 16, 0) : null);
            navIcons[i].setImageTintList(ColorStateList.valueOf(
                    on ? ON_SECONDARY_CONTAINER : VARIANT));
            navLabels[i].setTextColor(on ? ON_SURFACE : VARIANT);
        }
    }

    private void setPage(View page) {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        content.addView(scroll);
        page.setAlpha(0f);
        page.setTranslationY(dp(10));
        page.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    // --------------------------------------------------------------- home

    private View buildHome() {
        LinearLayout root = page();
        root.addView(expressiveHeadline("Glyph"));
        root.addView(caption("Nothing Phone (3a) · 36 LEDs · direct control"));
        root.addView(spacer(18));

        LinearLayout permit = card();
        LinearLayout permitRow = new LinearLayout(this);
        permitRow.setOrientation(LinearLayout.HORIZONTAL);
        permitRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout permitLabels = column();
        permitLabels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        permitLabels.addView(title("On"));
        permitLabels.addView(caption("Allow Glyph to drive the lights. "
                + "This switch does not turn them on."));
        permitRow.addView(permitLabels);
        masterSwitch = m3Switch(Prefs.master(this));
        masterSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Prefs.setMaster(MainActivity.this, checked);
                if (!checked) {
                    GlyphEngine.get().clear();
                }
                Toast.makeText(MainActivity.this,
                        checked ? "Glyph is allowed to light" : "Glyph lighting disabled",
                        Toast.LENGTH_SHORT).show();
            }
        });
        permitRow.addView(masterSwitch);
        permit.addView(permitRow);
        permit.addView(spacer(10));
        permit.addView(caption(Prefs.quiet(this)
                ? "Quiet hours are active right now."
                : "Behaviors below only run while this is on."));
        root.addView(permit);
        root.addView(spacer(12));
        TextView off = filledButton("Force all off");
        off.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        off.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GlyphService.stop(MainActivity.this);
                GlyphEngine.get().clear();
                Toast.makeText(MainActivity.this, "All glyphs off", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(off);
        root.addView(spacer(10));
        int replacement = stockState();
        root.addView(caption(replacement == STOCK_ENABLED
                ? "The stock glyph service is still enabled. Finish setup in Debug."
                : replacement == STOCK_REMOVED
                ? "Stock glyph service is removed. Glyph is the replacement."
                : "Stock glyph service is disabled. Glyph is the replacement."));
        root.addView(spacer(16));

        LinearLayout phoneCard = card();
        phoneCard.addView(title("Phone glyphs"));
        phoneCard.addView(caption("Three strips, 36 LEDs. Choose what each strip shows."));
        TextView openPhone = tonalButton("Open phone setup");
        openPhone.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        openPhone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(1);
            }
        });
        phoneCard.addView(openPhone);
        root.addView(phoneCard);
        root.addView(spacer(10));

        LinearLayout alertCard = card();
        alertCard.addView(title("Notification rules"));
        alertCard.addView(caption("Filter by app, sender or message text, "
                + "and enable sound-reactive pulses."));
        TextView openAlerts = tonalButton("Open alert settings");
        openAlerts.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        openAlerts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(2);
            }
        });
        alertCard.addView(openAlerts);
        root.addView(alertCard);
        return root;
    }

    // ---------------------------------------------------------- configure

    private View buildConfigure() {
        LinearLayout root = page();
        root.addView(expressiveHeadline("Phone"));
        root.addView(caption("Tap a strip to choose what it shows."));
        root.addView(spacer(16));

        boolean allStripsOff = true;
        for (int i = 0; i < 3; i++) {
            if (Prefs.zoneOn(this, i)) {
                allStripsOff = false;
                break;
            }
        }
        if (allStripsOff) {
            LinearLayout warning = card();
            warning.addView(title("All strips are off"));
            warning.addView(caption("Nothing will light until at least one strip is enabled. "
                    + "The master switch on Home must also be on."));
            TextView enableAll = filledButton("Enable all strips");
            enableAll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            enableAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    for (int i = 0; i < 3; i++) {
                        Prefs.setZoneOn(MainActivity.this, i, true);
                    }
                    GlyphEngine.get().apply(MainActivity.this);
                    GlyphService.refresh(MainActivity.this);
                    showTab(1);
                }
            });
            warning.addView(enableAll);
            root.addView(warning);
            root.addView(spacer(12));
        }

        LinearLayout hero = heroCard();
        GlyphPhoneView phone = new GlyphPhoneView(this);
        phone.setValues(zoneOnArray(), zoneLevelArray());
        phone.setClickableZones(true);
        phone.setOnZoneClickListener(new GlyphPhoneView.OnZoneClickListener() {
            @Override
            public void onZoneClick(int zone) {
                showZone(zone);
            }
        });
        hero.addView(phone, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        root.addView(hero);

        LinearLayout zoneChips = new LinearLayout(this);
        zoneChips.setOrientation(LinearLayout.HORIZONTAL);
        zoneChips.setPadding(0, dp(12), 0, 0);
        for (int i = 0; i < 3; i++) {
            final int zone = i;
            TextView chip = chip("Zone " + ZONE_ID[i], false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showZone(zone);
                }
            });
            zoneChips.addView(chip);
        }
        root.addView(zoneChips);

        root.addView(spacer(12));
        LinearLayout all = card();
        all.addView(title("All strips"));
        all.addView(caption("Set brightness and apply behaviors to A, B and C together."));
        TextView openAll = tonalButton("Configure all");
        openAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAll();
            }
        });
        all.addView(openAll);
        root.addView(all);

        root.addView(spacer(12));
        LinearLayout alertLink = card();
        alertLink.addView(title("Notification rules"));
        alertLink.addView(caption("Filters, sound pulses and quiet hours are on the Alerts tab."));
        TextView openAlerts = tonalButton("Open Alerts");
        openAlerts.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        openAlerts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(2);
            }
        });
        alertLink.addView(openAlerts);
        root.addView(alertLink);
        return root;
    }

    private View buildAlerts() {
        LinearLayout root = page();
        root.addView(expressiveHeadline("Alerts"));
        root.addView(caption("Notification filters, live sound pulses and quiet hours."));
        root.addView(spacer(16));

        LinearLayout sound = card();
        sound.addView(title("Sound-reactive glyphs"));
        sound.addView(caption("While a ringtone or notification sound is audible, "
                + "all 36 LEDs follow the audio. The previous behavior returns when it stops."));
        sound.addView(statusRow("Audio access", AudioPulse.hasPermission(this)
                ? "Granted" : "Missing"));
        if (!AudioPulse.hasPermission(this)) {
            TextView grant = tonalButton("Allow audio access");
            grant.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            grant.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    requestPermissions(new String[]{
                            android.Manifest.permission.RECORD_AUDIO}, 2);
                }
            });
            sound.addView(grant);
        }
        sound.addView(spacer(8));
        sound.addView(audioRow("Calls", "Animate while a call rings; works on silent or vibrate",
                Prefs.audioPulseCalls(this), true));
        sound.addView(audioRow("Notifications", "Animate for every alert, even silent or vibrate",
                Prefs.audioPulseNotifications(this), false));
        sound.addView(caption("Reads the audio Android is already playing; "
                + "it does not record from the microphone."));
        root.addView(sound);
        root.addView(spacer(12));

        root.addView(section("Rules"));
        root.addView(caption("Filters for essential notifications and progress displays."));
        root.addView(ruleRow("Essential notifications", Rules.ESSENTIAL));
        root.addView(spacer(8));
        root.addView(ruleRow("Notification progress", Rules.NOTIFICATION));
        root.addView(spacer(8));
        root.addView(ruleRow("Media progress", Rules.MEDIA));
        root.addView(spacer(18));

        root.addView(section("Quiet hours"));
        root.addView(quietCard());
        root.addView(spacer(12));

        LinearLayout access = card();
        access.addView(title("Notification access"));
        access.addView(statusRow("Listener", notificationAccess() ? "Granted" : "Missing"));
        TextView settings = tonalButton("Notification settings");
        settings.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });
        access.addView(settings);
        root.addView(access);
        return root;
    }

    private View audioRow(String label, String hint, boolean checked, final boolean calls) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title(label));
        labels.addView(caption(hint));
        row.addView(labels);
        Switch toggle = m3Switch(checked);
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean enabled) {
                if (calls) {
                    Prefs.setAudioPulseCalls(MainActivity.this, enabled);
                } else {
                    Prefs.setAudioPulseNotifications(MainActivity.this, enabled);
                }
                if (enabled && calls
                        && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            android.Manifest.permission.READ_PHONE_STATE}, 1);
                }
                if (enabled && !AudioPulse.hasPermission(MainActivity.this)) {
                    requestPermissions(new String[]{
                            android.Manifest.permission.RECORD_AUDIO}, 2);
                }
            }
        });
        row.addView(toggle);
        card.addView(row);
        return card;
    }

    private void showZone(final int zone) {
        LinearLayout root = page();
        root.addView(topBar("Zone " + ZONE_ID[zone], new Runnable() {
            @Override
            public void run() {
                showTab(1);
            }
        }));
        root.addView(caption(ZONE_NAMES[zone] + " · " + Prefs.ZONE_LEDS[zone] + " LEDs"));
        root.addView(spacer(14));

        LinearLayout enable = card();
        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout enableLabels = column();
        enableLabels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        enableLabels.addView(title("Strip enabled"));
        enableLabels.addView(caption("Turn this strip off without changing its assignments."));
        enableRow.addView(enableLabels);
        final Switch enabled = m3Switch(Prefs.zoneOn(this, zone));
        enabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Prefs.setZoneOn(MainActivity.this, zone, checked);
                GlyphEngine.get().apply(MainActivity.this);
                GlyphService.refresh(MainActivity.this);
            }
        });
        enableRow.addView(enabled);
        enable.addView(enableRow);
        root.addView(enable);
        root.addView(spacer(10));

        LinearLayout level = card();
        LinearLayout levelHead = new LinearLayout(this);
        levelHead.setOrientation(LinearLayout.HORIZONTAL);
        levelHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView levelName = title("Strip brightness");
        levelName.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        levelHead.addView(levelName);
        final TextView levelValue = title(Prefs.zoneLevel(this, zone) + "%");
        levelValue.setTextColor(PRIMARY);
        levelHead.addView(levelValue);
        level.addView(levelHead);
        SeekBar bar = m3Slider(Prefs.zoneLevel(this, zone));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                levelValue.setText(progress + "%");
                if (fromUser) {
                    Prefs.setZoneLevel(MainActivity.this, zone, progress);
                    GlyphEngine.get().apply(MainActivity.this);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        level.addView(bar);
        root.addView(level);
        root.addView(spacer(10));

        root.addView(section("Shows"));
        for (int i = 0; i < Prefs.BEHAVIOR_COUNT; i++) {
            if (!Behavior.PER_ZONE[i]) {
                continue;
            }
            if (i > 0) {
                root.addView(spacer(8));
            }
            root.addView(behaviorRow(zone, i));
        }

        root.addView(spacer(12));
        TextView alertRules = tonalButton("Notification rules");
        alertRules.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        alertRules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(2);
            }
        });
        root.addView(alertRules);

        root.addView(spacer(14));
        TextView preview = tonalButton("Preview this strip");
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GlyphEngine.get().previewZone(MainActivity.this, zone);
                Toast.makeText(MainActivity.this, "Preview sent", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(preview);
        showSubPage(root);
    }

    private View behaviorRow(final int zone, final int index) {
        final int bit = Prefs.behaviorBit(index);
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title(Prefs.BEHAVIOR_NAMES[index]));
        labels.addView(caption(Prefs.BEHAVIOR_DESCRIPTIONS[index]));
        row.addView(labels);
        Switch toggle = m3Switch(Prefs.hasBehavior(this, zone, bit));
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Prefs.setBehavior(MainActivity.this, zone, bit, checked);
                GlyphEngine.get().apply(MainActivity.this);
                GlyphService.refresh(MainActivity.this);
            }
        });
        row.addView(toggle);
        card.addView(row);
        return card;
    }

    private void showAll() {
        LinearLayout root = page();
        root.addView(topBar("All strips", new Runnable() {
            @Override
            public void run() {
                showTab(1);
            }
        }));
        root.addView(caption("Bulk changes that leave individual assignments intact."));
        root.addView(spacer(14));

        LinearLayout brightness = card();
        LinearLayout brightHead = new LinearLayout(this);
        brightHead.setOrientation(LinearLayout.HORIZONTAL);
        brightHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView brightName = title("Global brightness");
        brightName.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brightHead.addView(brightName);
        final TextView brightValue = title(Prefs.brightness(this) + "%");
        brightValue.setTextColor(PRIMARY);
        brightHead.addView(brightValue);
        brightness.addView(brightHead);
        SeekBar bar = m3Slider(Prefs.brightness(this));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightValue.setText(progress + "%");
                if (fromUser) {
                    Prefs.setBrightness(MainActivity.this, progress);
                    GlyphEngine.get().apply(MainActivity.this);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        brightness.addView(bar);
        root.addView(brightness);
        root.addView(spacer(10));

        LinearLayout levels = card();
        levels.addView(title("Set every strip to"));
        levels.addView(caption("A quick way to make all strips the same brightness."));
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(8), 0, 0);
        int[] values = {25, 50, 75, 100};
        for (final int value : values) {
            TextView chip = chip(value + "%", false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    for (int i = 0; i < 3; i++) {
                        Prefs.setZoneLevel(MainActivity.this, i, value);
                    }
                    GlyphEngine.get().apply(MainActivity.this);
                    GlyphService.refresh(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            "All strips at " + value + "%", Toast.LENGTH_SHORT).show();
                }
            });
            presets.addView(chip);
        }
        levels.addView(presets);
        root.addView(levels);

        root.addView(section("Behaviors everywhere"));
        for (int i = 0; i < Prefs.BEHAVIOR_COUNT; i++) {
            if (!Behavior.PER_ZONE[i]) {
                continue;
            }
            if (i > 0) {
                root.addView(spacer(8));
            }
            root.addView(bulkBehaviorRow(i));
        }

        root.addView(section("Alerts"));
        LinearLayout alertLink = card();
        alertLink.addView(title("Rules and quiet hours"));
        alertLink.addView(caption("Filters, sound pulses and the quiet window live on the Alerts tab."));
        TextView openAlerts = tonalButton("Open Alerts");
        openAlerts.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        openAlerts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTab(2);
            }
        });
        alertLink.addView(openAlerts);
        root.addView(alertLink);

        root.addView(spacer(14));
        TextView off = filledButton("Force all off");
        off.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        off.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GlyphService.stop(MainActivity.this);
                GlyphEngine.get().clear();
                Toast.makeText(MainActivity.this, "All glyphs off", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(off);
        showSubPage(root);
    }

    private View bulkBehaviorRow(final int index) {
        final int bit = Prefs.behaviorBit(index);
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title(Prefs.BEHAVIOR_NAMES[index]));
        labels.addView(caption("Apply to A, B and C"));
        row.addView(labels);
        Switch toggle = m3Switch(Prefs.allZonesHave(this, bit));
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Prefs.setBehaviorAll(MainActivity.this, bit, checked);
                GlyphEngine.get().apply(MainActivity.this);
                GlyphService.refresh(MainActivity.this);
            }
        });
        row.addView(toggle);
        card.addView(row);
        return card;
    }

    private View quietCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title("Quiet hours"));
        labels.addView(caption("Clear the strips during this window."));
        row.addView(labels);
        Switch toggle = m3Switch(Prefs.scheduleEnabled(this));
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Prefs.setScheduleEnabled(MainActivity.this, checked);
                Schedule.apply(MainActivity.this);
                if (checked && Prefs.quiet(MainActivity.this)) {
                    GlyphEngine.get().clear();
                } else {
                    GlyphEngine.get().apply(MainActivity.this);
                }
            }
        });
        row.addView(toggle);
        card.addView(row);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setPadding(0, dp(12), 0, 0);
        final TextView from = chip("From " + formatTime(Prefs.scheduleStart(this)), true);
        from.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickTime(Prefs.scheduleStart(MainActivity.this), true, from);
            }
        });
        final TextView to = chip("To " + formatTime(Prefs.scheduleEnd(this)), false);
        to.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickTime(Prefs.scheduleEnd(MainActivity.this), false, to);
            }
        });
        times.addView(from);
        times.addView(to);
        card.addView(times);
        return card;
    }

    private void pickTime(int current, final boolean start, final TextView label) {
        TimePickerDialog dialog = new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker picker, int hour, int minute) {
                        int value = hour * 60 + minute;
                        if (start) {
                            Prefs.setSchedule(MainActivity.this, value,
                                    Prefs.scheduleEnd(MainActivity.this));
                            label.setText("From " + formatTime(value));
                        } else {
                            Prefs.setSchedule(MainActivity.this,
                                    Prefs.scheduleStart(MainActivity.this), value);
                            label.setText("To " + formatTime(value));
                        }
                        Schedule.apply(MainActivity.this);
                        if (Prefs.quiet(MainActivity.this)) {
                            GlyphEngine.get().clear();
                        } else {
                            GlyphEngine.get().apply(MainActivity.this);
                        }
                    }
                }, current / 60, current % 60, DateFormat.is24HourFormat(this));
        dialog.show();
    }

    private String formatTime(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // -------------------------------------------------------------- rules

    private View ruleRow(final String title, final String scope) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title(title));
        int count = Rules.all(this, scope).size();
        boolean essential = Rules.ESSENTIAL.equals(scope);
        labels.addView(caption(count == 0
                ? (essential ? "No rules — add one to light this bar"
                : "No rules — accept matching notifications")
                : count + (count == 1 ? " rule" : " rules")));
        row.addView(labels);
        TextView arrow = title("›");
        arrow.setTextColor(PRIMARY);
        arrow.setTextSize(24);
        row.addView(arrow);
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRules(title, scope);
            }
        });
        return card;
    }

    private void showRules(final String title, final String scope) {
        final int parent = subPage ? subParentTab : tab;
        LinearLayout root = page();
        root.addView(topBar(title + " rules", new Runnable() {
            @Override
            public void run() {
                showTab(parent);
            }
        }));
        root.addView(caption(Rules.ESSENTIAL.equals(scope)
                ? "The bar stays lit only while a notification matches an enabled rule. "
                + "Add at least one rule to turn it on."
                : "If no rules are enabled, all matching notifications are accepted. "
                + "A notification matching any enabled rule is allowed."));
        root.addView(spacer(12));

        List<Rules.Rule> rules = Rules.all(this, scope);
        if (rules.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(title("No rules yet"));
            empty.addView(caption("Add a rule to filter by app, sender or message text."));
            root.addView(empty);
            root.addView(spacer(10));
        } else {
            for (int i = 0; i < rules.size(); i++) {
                if (i > 0) {
                    root.addView(spacer(8));
                }
                root.addView(ruleCard(rules.get(i), title, scope));
            }
            root.addView(spacer(10));
        }

        TextView add = filledButton("Add rule");
        add.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRuleEditor(null, title, scope);
            }
        });
        root.addView(add);
        showSubPage(root);
    }

    private View ruleCard(final Rules.Rule rule, final String title, final String scope) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(title(rule.pkg.isEmpty() ? "Any app" : appLabel(rule.pkg)));
        labels.addView(caption(ruleSummary(rule)));
        row.addView(labels);
        Switch toggle = m3Switch(rule.enabled);
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Rules.Rule copy = rule.copy();
                copy.enabled = checked;
                Rules.upsert(MainActivity.this, scope, copy);
                showRules(title, scope);
            }
        });
        row.addView(toggle);
        card.addView(row);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView delete = textButton("Delete");
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Rules.delete(MainActivity.this, scope, rule.id);
                showRules(title, scope);
            }
        });
        actions.addView(delete);
        card.addView(actions);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRuleEditor(rule, title, scope);
            }
        });
        return card;
    }

    private void showRuleEditor(final Rules.Rule existing, final String title, final String scope) {
        final Rules.Rule rule = existing == null ? new Rules.Rule() : existing.copy();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));

        form.addView(section("App"));
        final TextView appValue = actionButton(rule.pkg.isEmpty() ? "Any app" : appLabel(rule.pkg),
                SURFACE_3, ON_SURFACE, 0);
        appValue.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        appValue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAppPicker(rule.pkg, appValue);
            }
        });
        form.addView(appValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(spacer(12));

        form.addView(section("Sender or title contains"));
        EditText titleField = new EditText(this);
        titleField.setHint("e.g. Alex");
        titleField.setSingleLine(true);
        titleField.setText(rule.title);
        titleField.setTextColor(ON_SURFACE);
        titleField.setHintTextColor(VARIANT);
        titleField.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(titleField);
        form.addView(spacer(8));

        form.addView(section("Message contains"));
        EditText textField = new EditText(this);
        textField.setHint("e.g. dinner");
        textField.setText(rule.text);
        textField.setTextColor(ON_SURFACE);
        textField.setHintTextColor(VARIANT);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textField.setMaxLines(3);
        form.addView(textField);
        form.addView(spacer(12));

        LinearLayout enabledRow = new LinearLayout(this);
        enabledRow.setOrientation(LinearLayout.HORIZONTAL);
        enabledRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView enabledLabel = title("Rule enabled");
        enabledLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        enabledRow.addView(enabledLabel);
        final Switch enabled = m3Switch(rule.enabled);
        enabledRow.addView(enabled);
        form.addView(enabledRow);
        form.addView(caption("Leave app or text empty to match anything."));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add rule" : "Edit rule")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rule.pkg = ((String) appValue.getTag()) == null
                                ? rule.pkg : (String) appValue.getTag();
                        rule.title = titleField.getText().toString().trim();
                        rule.text = textField.getText().toString().trim();
                        rule.enabled = enabled.isChecked();
                        Rules.upsert(MainActivity.this, scope, rule);
                        showRules(title, scope);
                    }
                });
        if (existing != null) {
            builder.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Rules.delete(MainActivity.this, scope, rule.id);
                    showRules(title, scope);
                }
            });
        }
        builder.show();
    }

    private void showAppPicker(final String current, final TextView appValue) {
        final PackageManager manager = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>(manager.getInstalledApplications(0));
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return String.CASE_INSENSITIVE_ORDER.compare(
                        a.loadLabel(manager).toString(), b.loadLabel(manager).toString());
            }
        });
        final List<String> packages = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        packages.add("");
        labels.add("Any app");
        for (ApplicationInfo info : apps) {
            if (info.packageName.equals(getPackageName())) {
                continue;
            }
            packages.add(info.packageName);
            labels.add(info.loadLabel(manager) + " · " + info.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("App")
                .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = packages.get(which);
                        appValue.setTag(pkg);
                        appValue.setText(pkg.isEmpty() ? "Any app" : labels.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String ruleSummary(Rules.Rule rule) {
        StringBuilder builder = new StringBuilder();
        if (rule.pkg.isEmpty()) {
            builder.append("Any app");
        }
        if (!rule.title.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append("title: ").append(rule.title);
        }
        if (!rule.text.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append("text: ").append(rule.text);
        }
        if (builder.length() == 0) {
            builder.append("Matches every notification");
        }
        return builder.toString();
    }

    // -------------------------------------------------------------- debug

    private View buildDebug() {
        LinearLayout root = page();
        root.addView(expressiveHeadline("Debug & About"));
        root.addView(caption("Replacement controls, hardware tests and diagnostics."));
        root.addView(spacer(16));

        LinearLayout about = card();
        about.addView(title("Glyph"));
        about.addView(caption("A direct, dependency-free replacement for Nothing's glyph "
                + "service. Frames go straight to the aw20036 LED driver."));
        about.addView(caption("Version " + versionName() + " · Material 3 Expressive controls "
                + "built from framework views."));
        root.addView(about);
        root.addView(spacer(10));

        LinearLayout replace = card();
        int state = stockState();
        replace.addView(title("Replace the stock glyph app"));
        if (state == STOCK_REMOVED) {
            replace.addView(caption("co.aospa.glyph is removed for user 0. "
                    + "Glyph is the only glyph service."));
        } else if (state == STOCK_DISABLED) {
            replace.addView(caption("co.aospa.glyph is installed but disabled, so it cannot "
                    + "run in the background. Remove it for a clean replacement, or restore it."));
        } else {
            replace.addView(caption("co.aospa.glyph is installed and enabled, so both services "
                    + "can drive the LEDs. Disable it so only Glyph runs."));
        }
        if (state == STOCK_ENABLED) {
            TextView disable = filledButton("Disable stock & replace");
            disable.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            disable.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmStock(STOCK_ACTION_DISABLE);
                }
            });
            replace.addView(disable);
        } else if (state == STOCK_DISABLED) {
            TextView remove = filledButton("Remove stock app");
            remove.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmStock(STOCK_ACTION_REMOVE);
                }
            });
            replace.addView(remove);
            replace.addView(spacer(8));
            TextView restore = outlineButton("Restore stock app");
            restore.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            restore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmStock(STOCK_ACTION_RESTORE);
                }
            });
            replace.addView(restore);
        } else {
            TextView reinstall = outlineButton("Reinstall stock app");
            reinstall.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            reinstall.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmStock(STOCK_ACTION_REINSTALL);
                }
            });
            replace.addView(reinstall);
        }
        replace.addView(caption("Root is required. Changes apply to user 0 and can be reversed here."));
        root.addView(replace);
        root.addView(spacer(10));

        LinearLayout tests = card();
        tests.addView(title("Test the hardware"));
        tests.addView(caption("These actions write directly and skip the behavior rules."));
        LinearLayout testRow1 = new LinearLayout(this);
        testRow1.setOrientation(LinearLayout.HORIZONTAL);
        testRow1.setPadding(0, dp(10), 0, 0);
        TextView white = filledButton("All white");
        white.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                testAllWhite();
            }
        });
        TextView off = tonalButton("Off");
        off.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GlyphService.stop(MainActivity.this);
                GlyphEngine.get().clear();
                Toast.makeText(MainActivity.this, "All glyphs off", Toast.LENGTH_SHORT).show();
            }
        });
        addWeighted(testRow1, white);
        addWeighted(testRow1, off);
        tests.addView(testRow1);

        LinearLayout testRow2 = new LinearLayout(this);
        testRow2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int zone = i;
            TextView zoneButton = chip("Zone " + ZONE_ID[i], false);
            zoneButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    testZone(zone);
                }
            });
            testRow2.addView(zoneButton);
        }
        tests.addView(testRow2);

        LinearLayout testRow3 = new LinearLayout(this);
        testRow3.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int style = i;
            TextView styleButton = chip(STYLE_NAMES[i], false);
            styleButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    GlyphEngine.get().preview(MainActivity.this, style);
                    Toast.makeText(MainActivity.this, STYLE_NAMES[style], Toast.LENGTH_SHORT).show();
                }
            });
            testRow3.addView(styleButton);
        }
        tests.addView(testRow3);

        LinearLayout testRow4 = new LinearLayout(this);
        testRow4.setOrientation(LinearLayout.HORIZONTAL);
        TextView notification = tonalButton("Pulse");
        notification.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!Prefs.anyZoneHas(MainActivity.this, Prefs.B_ESSENTIAL)) {
                    Toast.makeText(MainActivity.this,
                            "Assign Essential notifications to a strip first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                GlyphEngine.get().playNotification(MainActivity.this);
            }
        });
        TextView charging = tonalButton("Charge fill");
        charging.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GlyphEngine.get().playChargingBrief(MainActivity.this);
            }
        });
        addWeighted(testRow4, notification);
        addWeighted(testRow4, charging);
        tests.addView(testRow4);

        TextView audioTest = outlineButton("Sound pulse test");
        audioTest.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        audioTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                testAudioPulse();
            }
        });
        tests.addView(audioTest);
        root.addView(tests);
        root.addView(spacer(10));

        LinearLayout debug = card();
        debug.addView(title("Debugging"));
        debug.addView(caption("Live state and root controls."));
        debug.addView(statusRow("Root", Root.get().isReady() ? "Active" : "Not connected"));
        debug.addView(statusRow("Frame sink", Root.get().isReady() ? "Root shell" : "None"));
        debug.addView(statusRow("Volume helper", Root.get().helperAlive()
                ? "Active" : "Not running"));
        debug.addView(statusRow("LED driver", "/sys/class/leds/aw20036_led"));
        debug.addView(statusRow("Gate", Prefs.master(this) ? "On" : "Off"));
        debug.addView(statusRow("Quiet hours", Prefs.quiet(this) ? "Active" : "Inactive"));
        debug.addView(statusRow("Notification access", notificationAccess() ? "Granted" : "Missing"));
        debug.addView(statusRow("Audio capture", AudioPulse.hasPermission(this)
                ? "Ready" : "Permission missing"));
        debug.addView(statusRow("Stock package", stockStateLabel()));

        LinearLayout debugActions = new LinearLayout(this);
        debugActions.setOrientation(LinearLayout.HORIZONTAL);
        debugActions.setPadding(0, dp(10), 0, 0);
        TextView testRoot = tonalButton("Test root");
        testRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                testRoot();
            }
        });
        TextView restart = outlineButton("Reconnect root");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Root.get().close();
                Root.get().exec("true");
                GlyphService.refresh(MainActivity.this);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showTab(3);
                    }
                }, 700L);
            }
        });
        addWeighted(debugActions, testRoot);
        addWeighted(debugActions, restart);
        debug.addView(debugActions);

        LinearLayout debugActions2 = new LinearLayout(this);
        debugActions2.setOrientation(LinearLayout.HORIZONTAL);
        TextView notif = tonalButton("Notification settings");
        notif.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });
        TextView refresh = outlineButton("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Stock.refresh(MainActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        showTab(3);
                    }
                });
            }
        });
        addWeighted(debugActions2, notif);
        addWeighted(debugActions2, refresh);
        debug.addView(debugActions2);
        root.addView(debug);
        return root;
    }

    private void testAllWhite() {
        // Go through the engine so Off always wins and the test fades out
        // instead of leaving a frame latched on the LEDs.
        GlyphEngine.get().previewRaw(Prefs.fullFrame(), 2500L);
        Toast.makeText(this, "All LEDs white for 2.5s", Toast.LENGTH_SHORT).show();
    }

    private void testZone(int zone) {
        GlyphEngine.get().previewRaw(Prefs.fullZone(zone), 2200L);
        Toast.makeText(this, "Zone " + ZONE_ID[zone] + " on", Toast.LENGTH_SHORT).show();
    }

    private void testAudioPulse() {
        if (!AudioPulse.hasPermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        GlyphService.start(this, GlyphService.ACTION_AUDIO_TEST);
        Toast.makeText(this, "Playing the test ringtone", Toast.LENGTH_SHORT).show();
    }

    private void testRoot() {
        Toast.makeText(this, "Approve the root request if it appears", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.get().test();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                ok ? "Root active" : "Root was denied", Toast.LENGTH_SHORT).show();
                        showTab(3);
                    }
                });
            }
        }).start();
    }

    private void confirmStock(final int action) {
        String title;
        String message;
        String positive;
        if (action == STOCK_ACTION_DISABLE) {
            title = "Disable stock glyph app?";
            message = "This disables co.aospa.glyph and stops its background process. "
                    + "Glyph keeps working through root. You can restore it here.";
            positive = "Disable";
        } else if (action == STOCK_ACTION_REMOVE) {
            title = "Remove stock glyph app?";
            message = "This uninstalls co.aospa.glyph for user 0, removes its stored settings "
                    + "and stops it permanently. You can reinstall the system app here later.";
            positive = "Remove";
        } else if (action == STOCK_ACTION_RESTORE) {
            title = "Restore stock glyph app?";
            message = "The stock glyph service will run again alongside Glyph.";
            positive = "Restore";
        } else {
            title = "Reinstall stock glyph app?";
            message = "This installs the system copy of co.aospa.glyph for user 0 again. "
                    + "It will run alongside Glyph until you disable it.";
            positive = "Reinstall";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (action == STOCK_ACTION_DISABLE) {
                            Prefs.setReplaceStock(MainActivity.this, true);
                            Stock.setState(MainActivity.this, STOCK_DISABLED);
                            Root.get().exec("pm disable-user --user 0 co.aospa.glyph; "
                                    + "am force-stop co.aospa.glyph; "
                                    + "for p in $(ps -A -o PID,ARGS | grep '[c]o.aospa.glyph' "
                                    + "| awk '{print $1}'); do kill -STOP $p; done");
                        } else if (action == STOCK_ACTION_REMOVE) {
                            Prefs.setReplaceStock(MainActivity.this, true);
                            Stock.setState(MainActivity.this, STOCK_REMOVED);
                            Root.get().exec("pm uninstall --user 0 co.aospa.glyph; "
                                    + "am force-stop co.aospa.glyph; "
                                    + "for p in $(ps -A -o PID,ARGS | grep '[c]o.aospa.glyph' "
                                    + "| awk '{print $1}'); do kill -STOP $p; done");
                        } else if (action == STOCK_ACTION_RESTORE) {
                            Prefs.setReplaceStock(MainActivity.this, false);
                            Stock.setState(MainActivity.this, STOCK_ENABLED);
                            Root.get().exec("pm enable co.aospa.glyph; "
                                    + "for p in $(ps -A -o PID,ARGS | grep '[c]o.aospa.glyph' "
                                    + "| awk '{print $1}'); do kill -CONT $p; done");
                        } else {
                            Prefs.setReplaceStock(MainActivity.this, false);
                            Stock.setState(MainActivity.this, STOCK_ENABLED);
                            Root.get().exec("cmd package install-existing --user 0 co.aospa.glyph");
                        }
                        Toast.makeText(MainActivity.this, positive + " requested",
                                Toast.LENGTH_SHORT).show();
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                showTab(3);
                            }
                        }, 1500L);
                    }
                })
                .show();
    }

    private int stockState() {
        return Stock.state(this);
    }

    private String stockStateLabel() {
        return Stock.label(this);
    }

    private boolean notificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName() + "/"
                + NotifyListener.class.getName());
    }

    private String versionName() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "2.0" : version;
        } catch (PackageManager.NameNotFoundException e) {
            return "2.0";
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            return manager.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------ widgets

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(36));
        return page;
    }

    private View topBar(String title, final Runnable back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(8));
        TextView arrow = new TextView(this);
        arrow.setText("←");
        arrow.setTextSize(26);
        arrow.setTextColor(PRIMARY);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(6), 0, dp(12), 0);
        arrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                back.run();
            }
        });
        bar.addView(arrow);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(24);
        label.setTextColor(ON_SURFACE);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(label);
        return bar;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(SURFACE, 28, 0));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setElevation(dp(1));
        return card;
    }

    private LinearLayout heroCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{SURFACE_3, SURFACE});
        bg.setCornerRadius(dp(32));
        card.setBackground(bg);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        return card;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView expressiveHeadline(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(40);
        view.setTextColor(ON_SURFACE);
        view.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        view.setLetterSpacing(-0.025f);
        return view;
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15.5f);
        view.setTextColor(ON_SURFACE);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView caption(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12.5f);
        view.setTextColor(VARIANT);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView section(String value) {
        TextView view = new TextView(this);
        view.setText(value.toUpperCase(Locale.US));
        view.setTextSize(10.5f);
        view.setTextColor(PRIMARY);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0.12f);
        view.setPadding(dp(4), dp(20), 0, dp(8));
        return view;
    }

    private TextView chip(String value, boolean selected) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12.5f);
        view.setTextColor(selected ? ON_SECONDARY_CONTAINER : ON_SURFACE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setBackground(ripple(rounded(selected ? SECONDARY_CONTAINER : SURFACE_3, 999,
                selected ? 0 : OUTLINE)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private TextView filledButton(String text) {
        return actionButton(text, PRIMARY, ON_PRIMARY, 0);
    }

    private TextView tonalButton(String text) {
        return actionButton(text, SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER, 0);
    }

    private TextView outlineButton(String text) {
        return actionButton(text, Color.TRANSPARENT, ON_SURFACE, OUTLINE);
    }

    private TextView textButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(PRIMARY);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setMinHeight(dp(40));
        view.setOnClickListener(null);
        view.setClickable(true);
        return view;
    }

    private TextView actionButton(String text, int background, int textColor, int stroke) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(textColor);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setMinHeight(dp(48));
        view.setBackground(ripple(rounded(background, 999, stroke)));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private Drawable ripple(Drawable background) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), background, null);
    }

    private Switch m3Switch(boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setShowText(false);
        int[][] states = new int[][]{{android.R.attr.state_checked}, new int[]{}};
        toggle.setTrackTintList(new ColorStateList(states, new int[]{PRIMARY, SURFACE_3}));
        toggle.setThumbTintList(new ColorStateList(states, new int[]{ON_PRIMARY, OUTLINE}));
        return toggle;
    }

    private SeekBar m3Slider(int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        bar.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        bar.setPadding(0, dp(8), 0, dp(8));
        return bar;
    }

    private View statusRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView name = caption(label);
        name.setPadding(0, 0, dp(12), 0);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);
        TextView state = title(value);
        state.setTextSize(13);
        state.setTextColor(PRIMARY);
        row.addView(state);
        return row;
    }

    private View spacer(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return view;
    }

    private void addWeighted(LinearLayout row, TextView button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(8);
        row.addView(button, params);
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private boolean[] zoneOnArray() {
        boolean[] values = new boolean[3];
        for (int i = 0; i < 3; i++) {
            values[i] = Prefs.master(this) && Prefs.zoneOn(this, i);
        }
        return values;
    }

    private float[] zoneLevelArray() {
        float[] values = new float[3];
        for (int i = 0; i < 3; i++) {
            values[i] = Prefs.zoneLevel(this, i) / 100f;
        }
        return values;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
