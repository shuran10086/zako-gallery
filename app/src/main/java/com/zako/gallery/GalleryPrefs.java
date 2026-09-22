package com.zako.gallery;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Central access to SharedPreferences: service settings.
 * The preference store is the single source of truth for service state so that
 * services can restore their parameters after process death or reboot.
 *
 * Gallery contents (galleries, images, enable-state, which gallery a service
 * uses) live in {@link GalleryStore}.
 */
public final class GalleryPrefs {

    public static final String PREFS_NAME = "rw_prefs";

    private GalleryPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------- service enable flags ----------

    public static boolean isWallpaperEnabled(Context ctx) {
        return prefs(ctx).getBoolean("wallpaper_enabled", false);
    }

    public static void setWallpaperEnabled(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean("wallpaper_enabled", value).apply();
    }

    public static boolean isPopupEnabled(Context ctx) {
        return prefs(ctx).getBoolean("popup_enabled", false);
    }

    public static void setPopupEnabled(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean("popup_enabled", value).apply();
    }

    // ---------- wallpaper service parameters ----------

    public static int getWallpaperMinMinutes(Context ctx) {
        return prefs(ctx).getInt("wallpaper_min_minutes", 1);
    }

    public static int getWallpaperMaxMinutes(Context ctx) {
        return prefs(ctx).getInt("wallpaper_max_minutes", 60);
    }

    public static boolean isWallpaperLockEnabled(Context ctx) {
        return prefs(ctx).getBoolean("wallpaper_lock", false);
    }

    public static void saveWallpaperParams(Context ctx, int minMinutes, int maxMinutes, boolean lock) {
        prefs(ctx).edit()
                .putInt("wallpaper_min_minutes", minMinutes)
                .putInt("wallpaper_max_minutes", maxMinutes)
                .putBoolean("wallpaper_lock", lock)
                .apply();
    }

    // ---------- popup service parameters ----------

    public static int getPopupMinMinutes(Context ctx) {
        return prefs(ctx).getInt("popup_min_minutes", 1);
    }

    public static int getPopupMaxMinutes(Context ctx) {
        return prefs(ctx).getInt("popup_max_minutes", 60);
    }

    public static int getPopupMinSeconds(Context ctx) {
        return prefs(ctx).getInt("popup_min_seconds", 3);
    }

    public static int getPopupMaxSeconds(Context ctx) {
        return prefs(ctx).getInt("popup_max_seconds", 60);
    }

    public static void savePopupParams(Context ctx, int minMinutes, int maxMinutes, int minSeconds, int maxSeconds) {
        prefs(ctx).edit()
                .putInt("popup_min_minutes", minMinutes)
                .putInt("popup_max_minutes", maxMinutes)
                .putInt("popup_min_seconds", minSeconds)
                .putInt("popup_max_seconds", maxSeconds)
                .apply();
    }
}
