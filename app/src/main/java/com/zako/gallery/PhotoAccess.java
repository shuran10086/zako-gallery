package com.zako.gallery;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Photo access permission helpers.
 *
 * <p>The app declares {@code READ_MEDIA_IMAGES} (Android 13+) / {@code
 * READ_EXTERNAL_STORAGE} (older), so the toggle exists in system settings.
 * The permission is requested once on first launch and re-checked whenever
 * the user opens the photo picker to add images: without it the picker is
 * not opened at all.</p>
 *
 * <p>Android 14's "select photos only" grant counts as granted here — the
 * picker works regardless of scope.</p>
 */
public final class PhotoAccess {

    private static final String KEY_REQUESTED_ONCE = "photo_permission_requested_once";

    private PhotoAccess() {
    }

    /** The permission name to request on this API level. */
    public static String permissionName() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** True when photo access is (at least partially) granted. */
    public static boolean granted(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, permissionName())
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True when the startup prompt has already been shown once. */
    public static boolean wasRequested(Context ctx) {
        return prefs(ctx).getBoolean(KEY_REQUESTED_ONCE, false);
    }

    public static void markRequested(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_REQUESTED_ONCE, true).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(GalleryPrefs.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
