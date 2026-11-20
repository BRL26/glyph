package dev.brl.glyph;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;

/** Re-binds every Glyph tile so SystemUI drops cached icon/label state. */
final class GlyphTiles {

    private static final Class<?>[] TILES = {
            MusicTileService.class,
            VolumeTileService.class,
            BatteryTileService.class,
            RingtoneTileService.class,
            GlyphsTileService.class,
            AllOffTileService.class,
            GlyphLightsTileService.class
    };

    private GlyphTiles() {
    }

    static void requestAll(Context context) {
        for (Class<?> service : TILES) {
            try {
                TileService.requestListeningState(context,
                        new ComponentName(context, service));
            } catch (RuntimeException ignored) {
                // SystemUI may reject a bind while the shade is restarting;
                // the next package/boot refresh will retry.
            }
        }
    }
}
