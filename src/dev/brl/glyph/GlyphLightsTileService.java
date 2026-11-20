package dev.brl.glyph;

public final class GlyphLightsTileService extends GlyphTileService {
    @Override
    protected int category() {
        return CATEGORY_LIGHTS;
    }

    @Override
    protected String label() {
        return "Glyph lights";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_launcher;
    }
}
