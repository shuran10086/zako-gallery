package com.zako.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Local app lock. Two persisted pieces of state:
 *
 * <p><b>locked</b> — the position of the "锁定主页" switch and, by
 * construction, the lock itself: switch on ⇔ home page functions and the
 * gallery settings are frozen.</p>
 *
 * <p><b>authorizedUntil</b> — deadline of the grace window that opens when
 * the password is verified. While it is open the switch may be flipped
 * freely; when it expires the switch freezes again and the password is
 * required once more. The window never releases the lock by itself.</p>
 *
 * <p>The password itself is stored as a random salt plus the salted SHA-256,
 * never in the clear. Allowed characters are English letters, digits and
 * {@code . * $ %} — see {@link #hasValidCharset(String)}.</p>
 */
public final class AppLock {

    /** Length of the grace window after a successful password verification. */
    public static final long GRACE_MS = 10_000L;

    private static final String KEY_SALT = "lock_salt";
    private static final String KEY_HASH = "lock_password_hash";
    /** Switch position; always equals the lock state. */
    private static final String KEY_LOCKED = "lock_locked";
    /** elapsedRealtime timestamp until which the switch may be flipped. */
    private static final String KEY_AUTHORIZED_UNTIL = "lock_authorized_until";

    private AppLock() {
    }

    /** True when every character is a letter, digit or one of {@code . * $ %}. */
    public static boolean hasValidCharset(String password) {
        if (password == null || password.isEmpty()) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '*' || c == '$' || c == '%';
            if (!ok) return false;
        }
        return true;
    }

    public static boolean hasPassword(Context ctx) {
        return prefs(ctx).getString(KEY_HASH, null) != null;
    }

    /** Stores a new password (charset must already have been validated). */
    public static void setPassword(Context ctx, String password) {
        String salt = randomSalt();
        prefs(ctx).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hash(salt, password))
                .apply();
    }

    public static boolean verify(Context ctx, String password) {
        SharedPreferences p = prefs(ctx);
        String salt = p.getString(KEY_SALT, null);
        String hash = p.getString(KEY_HASH, null);
        if (salt == null || hash == null || password == null) return false;
        return hash.equals(hash(salt, password));
    }

    /** True while the home page and gallery settings are locked. */
    public static boolean isLocked(Context ctx) {
        return hasPassword(ctx) && prefs(ctx).getBoolean(KEY_LOCKED, false);
    }

    /**
     * Opens the grace window: from now on the switch may be turned off (and
     * on again) without a new password until the deadline. Does not touch the
     * lock state.
     */
    public static void authorize(Context ctx) {
        prefs(ctx).edit()
                .putLong(KEY_AUTHORIZED_UNTIL, SystemClock.elapsedRealtime() + GRACE_MS)
                .apply();
    }

    /** Milliseconds left in the grace window, 0 once it has expired. */
    public static long authorityRemainingMs(Context ctx) {
        if (!hasPassword(ctx)) return 0;
        long until = prefs(ctx).getLong(KEY_AUTHORIZED_UNTIL, 0L);
        long left = until - SystemClock.elapsedRealtime();
        return Math.max(0L, left);
    }

    /** True while the grace window is still open. */
    public static boolean isAuthorized(Context ctx) {
        return authorityRemainingMs(ctx) > 0;
    }

    /** Drops the grace window (the unlock is final; no auto re-lock later). */
    public static void clearAuthority(Context ctx) {
        prefs(ctx).edit().remove(KEY_AUTHORIZED_UNTIL).apply();
    }

    /**
     * The switch was flipped. Turning the lock on is always allowed; turning
     * it off ends the grace window, so a later re-lock needs the password
     * again. Flipping on inside the window keeps the window running.
     */
    public static void setSwitchOn(Context ctx, boolean on) {
        SharedPreferences.Editor e = prefs(ctx).edit().putBoolean(KEY_LOCKED, on);
        if (!on) e.remove(KEY_AUTHORIZED_UNTIL);
        e.apply();
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes("UTF-8"));
            byte[] digest = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(GalleryPrefs.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
