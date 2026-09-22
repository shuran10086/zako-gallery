package com.zako.gallery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Restores the two services after a device reboot or an app update.
 * Service state lives in SharedPreferences, so the receiver only needs to
 * read the enable flags and restart whatever was running.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        final Context app = context.getApplicationContext();
        // Seed/load the gallery store first (async after an app update), then
        // restore whatever services were running. goAsync keeps the process
        // alive long enough to finish the seeding.
        final PendingResult pending = goAsync();
        GalleryStore.prepareAsync(app, new Runnable() {
            @Override
            public void run() {
                try {
                    startServices(app);
                } finally {
                    pending.finish();
                }
            }
        });
    }

    private void startServices(Context context) {
        if (GalleryPrefs.isWallpaperEnabled(context)) {
            Intent svc = new Intent(context, WallpaperChangeService.class)
                    .setAction(WallpaperChangeService.ACTION_START);
            ContextCompat.startForegroundService(context, svc);
        }
        if (GalleryPrefs.isPopupEnabled(context)) {
            Intent svc = new Intent(context, PopupOverlayService.class)
                    .setAction(PopupOverlayService.ACTION_START);
            ContextCompat.startForegroundService(context, svc);
        }
    }
}
