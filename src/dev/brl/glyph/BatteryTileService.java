package dev.brl.glyph;

public final class BatteryTileService extends GlyphTileService {
    @Override
    protected int category() {
        return OutputGate.BATTERY;
    }

    @Override
    protected String label() {
        return "Battery glyphs";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_settings;
    }
}
