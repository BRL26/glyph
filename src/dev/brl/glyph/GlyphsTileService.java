package dev.brl.glyph;

public final class GlyphsTileService extends GlyphTileService {
    @Override
    protected int category() {
        return CATEGORY_VISUALIZER;
    }

    @Override
    protected String label() {
        return "Glyph visualizer";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_tile_glyphs;
    }
}
