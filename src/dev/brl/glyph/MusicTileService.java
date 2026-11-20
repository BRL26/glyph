package dev.brl.glyph;

public final class MusicTileService extends GlyphTileService {
    @Override
    protected int category() {
        return OutputGate.MUSIC;
    }

    @Override
    protected String label() {
        return "Music glyphs";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_tune;
    }
}
