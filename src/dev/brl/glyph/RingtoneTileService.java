package dev.brl.glyph;

public final class RingtoneTileService extends GlyphTileService {
    @Override
    protected int category() {
        return OutputGate.RINGTONE;
    }

    @Override
    protected String label() {
        return "Ringtone glyphs";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_bell;
    }
}
