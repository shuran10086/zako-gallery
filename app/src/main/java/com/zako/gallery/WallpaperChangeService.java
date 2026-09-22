package com.zako.gallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that changes the wallpaper to a random image from the
 * (user-filtered) wallpaper gallery at random intervals.
 *
 * Scheduling notes: the service keeps exactly one scheduled task at a time.
 * Every start command cancels pending work first, and the task re-schedules
 * itself once per run, so toggling the switch can never leave orphaned
 * chains behind. Parameters are persisted so START_STICKY restarts behave
 * the same as a fresh start from the UI.
 */
public class WallpaperChangeService extends Service {

    public static final String ACTION_START = "com.zako.gallery.action.WALLPAPER_START";

    private static final String CHANNEL_ID = "rw_wallpaper_channel";
    private static final int NOTIFICATION_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService executor;

    private int minMinutes = 1;
    private int maxMinutes = 60;
    private boolean setLockScreen = false;

    private final Runnable changeTask = new Runnable() {
        @Override
        public void run() {
            // Always queue the next run before doing the work, so the chain
            // survives even if performChange throws.
            scheduleNext();
            performChangeAsync();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newSingleThreadExecutor();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "壁纸服务",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_wallpaper_title))
                .setContentText(getString(R.string.notif_wallpaper_text))
                .setSmallIcon(R.drawable.ic_stat_wallpaper)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat() {
        try {
            Notification notification = buildNotification();
            // The typed variant of startForeground requires API 29.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Foreground start not allowed right now; stop cleanly instead of crashing.
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A foreground service must promote itself promptly, even when we are
        // about to stop it.
        startForegroundCompat();

        // Single-flight: drop any previously scheduled work before re-arming.
        // Only the scheduler is removed; a start command may arrive while the
        // service is already running (parameter push from the UI).
        handler.removeCallbacks(changeTask);

        if (intent != null) {
            minMinutes = intent.getIntExtra("min_minutes", 1);
            maxMinutes = intent.getIntExtra("max_minutes", 60);
            setLockScreen = intent.getBooleanExtra("set_lock", false);
            GalleryPrefs.saveWallpaperParams(this, minMinutes, maxMinutes, setLockScreen);
        } else {
            // Restarted by the system with the original intent gone.
            minMinutes = GalleryPrefs.getWallpaperMinMinutes(this);
            maxMinutes = GalleryPrefs.getWallpaperMaxMinutes(this);
            setLockScreen = GalleryPrefs.isWallpaperLockEnabled(this);
        }

        if (!GalleryPrefs.isWallpaperEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int initialDelayMin = minMinutes + random.nextInt(minutesSpan());
        handler.postDelayed(changeTask, initialDelayMin * 60_000L);

        return START_STICKY;
    }

    private int minutesSpan() {
        return Math.max(1, maxMinutes - minMinutes + 1);
    }

    private void scheduleNext() {
        handler.removeCallbacks(changeTask);
        int delayMin = minMinutes + random.nextInt(minutesSpan());
        handler.postDelayed(changeTask, delayMin * 60_000L);
    }

    private void performChangeAsync() {
        if (executor == null || executor.isShutdown()) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    performChange();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void performChange() {
        // Re-read the enabled list every round: gallery changes, added or
        // deleted images and toggles take effect without restarting.
        List<File> files = GalleryStore.activeFiles(this, "wallpaper");
        if (files.isEmpty()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), R.string.toast_no_wallpaper_enabled,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        File chosen = files.get(random.nextInt(files.size()));
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Bitmap bmp = GalleryStore.decodeSampled(chosen, dm.widthPixels, dm.heightPixels);
        if (bmp == null) return;

        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            int which = WallpaperManager.FLAG_SYSTEM;
            if (setLockScreen) which |= WallpaperManager.FLAG_LOCK;
            wm.setBitmap(bmp, null, true, which);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            bmp.recycle();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
