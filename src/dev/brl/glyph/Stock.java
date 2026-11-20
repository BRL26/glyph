package dev.brl.glyph;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** State of the ROM's stock glyph package, including disabled-for-user installs. */
public final class Stock {

    public static final String PACKAGE = "co.aospa.glyph";

    public static final int ENABLED = 0;
    public static final int DISABLED = 1;
    public static final int REMOVED = 2;

    private static final String TAG = "GlyphStock";
    private static final String CACHE = "stock_state_cache";
    private static final int UNKNOWN = -1;
    private static long lastProbe;

    private Stock() {
    }

    public static int state(Context context) {
        int cached = sp(context).getInt(CACHE, UNKNOWN);
        if (cached != UNKNOWN) {
            return cached;
        }
        return packageManagerState(context);
    }

    public static void setState(Context context, int state) {
        sp(context).edit().putInt(CACHE, state).apply();
    }

    public static String label(Context context) {
        int state = state(context);
        return state == REMOVED ? "Removed" : state == DISABLED ? "Disabled" : "Enabled";
    }

    /**
     * PackageManager hides a disabled system package from normal lookups on this
     * ROM, so ask the root shell. Runs off the main thread and caches the result.
     */
    public static void refresh(final Context context, final Runnable done) {
        long now = System.currentTimeMillis();
        if (now - lastProbe < 4000L) {
            if (done != null) {
                new Handler(Looper.getMainLooper()).post(done);
            }
            return;
        }
        lastProbe = now;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String script = "if pm list packages -d " + PACKAGE
                        + " | grep -q .; then echo DISABLED; "
                        + "elif pm list packages " + PACKAGE
                        + " | grep -q .; then echo ENABLED; else echo REMOVED; fi";
                final int state = parse(Root.get().read(script, 2500L));
                if (state != UNKNOWN) {
                    setState(app, state);
                    Log.d(TAG, "root probe state=" + state);
                }
                if (done != null) {
                    new Handler(Looper.getMainLooper()).post(done);
                }
            }
        }, "glyph-stock-probe").start();
    }

    private static int parse(String output) {
        if (output.contains("DISABLED")) {
            return DISABLED;
        }
        if (output.contains("ENABLED")) {
            return ENABLED;
        }
        if (output.contains("REMOVED")) {
            return REMOVED;
        }
        return UNKNOWN;
    }

    private static int packageManagerState(Context context) {
        PackageManager manager = context.getPackageManager();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_UNINSTALLED_PACKAGES;
        try {
            return stateOf(manager.getApplicationInfo(PACKAGE, flags));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Fall back to the installed-app list below.
        }
        for (ApplicationInfo info : manager.getInstalledApplications(flags)) {
            if (PACKAGE.equals(info.packageName)) {
                return stateOf(info);
            }
        }
        return REMOVED;
    }

    private static int stateOf(ApplicationInfo info) {
        if ((info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            return REMOVED;
        }
        return info.enabled ? ENABLED : DISABLED;
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("glyph", Context.MODE_PRIVATE);
    }
}
