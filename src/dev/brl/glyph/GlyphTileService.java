package dev.brl.glyph;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Shared implementation for the Quick Settings gate tiles. */
public abstract class GlyphTileService extends TileService {

    protected static final int CATEGORY_LIGHTS = -1;
    protected static final int CATEGORY_ALL_OFF = -2;
    protected static final int CATEGORY_VISUALIZER = -3;

    protected abstract int category();

    protected abstract String label();

    protected abstract int icon();

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        int category = category();
        if (category == CATEGORY_LIGHTS) {
            OutputGate.toggleFlashlight(this);
        } else if (category == CATEGORY_ALL_OFF) {
            OutputGate.toggleAllOff(this);
        } else if (category == CATEGORY_VISUALIZER) {
            if (!AudioPulse.hasPermission(this)) {
                // A tile cannot show a runtime-permission dialog. Open the
                // app so the user can grant it, then tap the tile again.
                Intent open = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(open);
            } else {
                OutputGate.toggleVisualizer(this);
            }
        } else {
            OutputGate.toggle(this, category);
        }
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean active = isActive();
        tile.setLabel(label());
        tile.setIcon(android.graphics.drawable.Icon.createWithResource(this, icon()));
        tile.setContentDescription(label() + (active ? " on" : " off"));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        // SystemUI owns tile sizing on Android 16+; this label is what the
        // wider 2x1 form shows while 1x1 shows the icon alone.
        tile.updateTile();
    }

    private boolean isActive() {
        int category = category();
        if (category == CATEGORY_LIGHTS) {
            return OutputGate.flashlight(this);
        }
        if (category == CATEGORY_ALL_OFF) {
            return OutputGate.allOff(this);
        }
        if (category == CATEGORY_VISUALIZER) {
            return OutputGate.visualizer(this);
        }
        return OutputGate.enabled(this, category);
    }
}
