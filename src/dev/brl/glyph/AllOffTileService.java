package dev.brl.glyph;

public final class AllOffTileService extends GlyphTileService {
    @Override
    protected int category() {
        return CATEGORY_ALL_OFF;
    }

    @Override
    protected String label() {
        return "All glyphs off";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_debug;
    }
}
