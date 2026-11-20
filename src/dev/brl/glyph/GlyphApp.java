package dev.brl.glyph;

import android.app.Application;

public final class GlyphApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.migrate(this);
        Root.get().init(this);
        Root.get().prepare();
    }
}
