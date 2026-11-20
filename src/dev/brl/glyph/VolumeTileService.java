package dev.brl.glyph;

public final class VolumeTileService extends GlyphTileService {
    @Override
    protected int category() {
        return OutputGate.VOLUME;
    }

    @Override
    protected String label() {
        return "Volume glyphs";
    }

    @Override
    protected int icon() {
        return R.drawable.ic_control;
    }
}
