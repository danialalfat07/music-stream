package com.dnialify.musicstream;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Phase 8 - diagnostic entry point (Quick Settings tile).
 * Tap opens the native stream settings dialog. No playback logic here.
 * Requires API 24+; tile simply does nothing on older platforms.
 */
public class StreamTileService extends TileService {
    private static final String TAG_DIAG = "DnialifyDiag";

    @Override
    public void onClick() {
        super.onClick();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.d(TAG_DIAG, "StreamTileService requires API 24+");
                return;
            }
            Log.d(TAG_DIAG, "StreamTileService onClick -> StreamSettingsActivity");
            Intent i = new Intent(this, StreamSettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(i);
            } else {
                unlockAndRun(() -> {
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        Log.d(TAG_DIAG, "StreamTileService startActivity err " + e);
                    }
                });
            }
        } catch (Exception e) {
            Log.d(TAG_DIAG, "StreamTileService onClick err " + e);
        }
    }
}
