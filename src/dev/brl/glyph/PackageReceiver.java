package dev.brl.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** SystemUI keeps the old Tile icon object across an in-place app update. */
public final class PackageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            GlyphTiles.requestAll(context);
            GlyphWidgetProvider.updateAll(context);
        }
    }
}
