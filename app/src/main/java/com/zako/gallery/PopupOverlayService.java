package com.zako.gallery;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that periodically shows a full-screen image overlay.
 *
 * The overlay intentionally consumes all touches so the user cannot close it;
 * it always disappears on its own after the configured duration.
 *
 * Lifecycle notes: at most one overlay exists at a time. Before a new overlay
 * is added, any existing one is fully torn down (dismiss timer cancelled and
 * the view removed), so overlays can never pile up into an undismissable
 * stack. The dismiss timer captures the exact view it belongs to.
 *
 * Scheduling: the next random interval is drawn only after the current
 * overlay has finished displaying, so the countdown always restarts from the
 * moment a popup disappears. Each cycle arms the next popup exactly once
 * ({@link #nextScheduled}); failure paths (no overlay permission, empty
 * gallery) re-arm immediately so the chain never dies silently. A start
 * command that arrives while an overlay is on screen only re-arms the
 * scheduler — the running overlay keeps its own dismiss timer.
 */
public class PopupOverlayService extends Service {

    public static final String ACTION_START = "com.zako.gallery.action.POPUP_START";

    private static final String CHANNEL_ID = "rw_popup_channel";
    private static final int NOTIFICATION_ID = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService executor;

    private int minMinutes = 1;
    private int maxMinutes = 60;
    private int minSeconds = 3;
    private int maxSeconds = 60;

    /** Bitmap currently shown by the overlay; recycled when the view is removed. */
    private Bitmap currentBitmap;

    /** Guards against arming the next popup more than once per cycle. */
    private boolean nextScheduled = false;

    private WindowManager windowManager;
    private View currentView;
    private Runnable dismissRunnable;

    private final Runnable popupTask = new Runnable() {
        @Override
        public void run() {
            nextScheduled = false;
            showOverlayOnce();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        executor = Executors.newSingleThreadExecutor();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "全屏弹出服务",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_popup_title))
                .setContentText(getString(R.string.notif_popup_text))
                .setSmallIcon(R.drawable.ic_stat_popup)
                .build();
        try {
            // The typed variant of startForeground requires API 29.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        // Only drop the scheduler: a start command may arrive while an overlay
        // is on screen (parameter push from the UI), and that overlay's
        // dismiss timer must keep running.
        handler.removeCallbacks(popupTask);

        if (intent != null) {
            minMinutes = intent.getIntExtra("popup_min_minutes", 1);
            maxMinutes = intent.getIntExtra("popup_max_minutes", 60);
            minSeconds = intent.getIntExtra("popup_min_seconds", 3);
            maxSeconds = intent.getIntExtra("popup_max_seconds", 60);
            GalleryPrefs.savePopupParams(this, minMinutes, maxMinutes, minSeconds, maxSeconds);
        } else {
            minMinutes = GalleryPrefs.getPopupMinMinutes(this);
            maxMinutes = GalleryPrefs.getPopupMaxMinutes(this);
            minSeconds = GalleryPrefs.getPopupMinSeconds(this);
            maxSeconds = GalleryPrefs.getPopupMaxSeconds(this);
        }

        if (!GalleryPrefs.isPopupEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        nextScheduled = true;
        int initialDelayMin = minMinutes + random.nextInt(minutesSpan());
        handler.postDelayed(popupTask, initialDelayMin * 60_000L);

        return START_STICKY;
    }

    private int minutesSpan() {
        return Math.max(1, maxMinutes - minMinutes + 1);
    }

    private int secondsSpan() {
        return Math.max(1, maxSeconds - minSeconds + 1);
    }

    /**
     * Arms the next popup exactly once per cycle, with a fresh random delay
     * drawn from the current [minMinutes, maxMinutes] range.
     */
    private void scheduleNextOnce() {
        if (nextScheduled) return;
        nextScheduled = true;
        handler.removeCallbacks(popupTask);
        int delayMin = minMinutes + random.nextInt(minutesSpan());
        handler.postDelayed(popupTask, delayMin * 60_000L);
    }

    private void showOverlayOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(getApplicationContext(), R.string.toast_no_overlay_permission,
                        Toast.LENGTH_SHORT).show();
                scheduleNextOnce();
                return;
            }
        }
        if (windowManager == null) {
            scheduleNextOnce();
            return;
        }
        // The gallery store is seeded the first time the app is opened; skip
        // this round (instead of seeding on the main thread) if it is not
        // ready yet — the chain re-arms immediately.
        if (!GalleryStore.isReady()) {
            scheduleNextOnce();
            return;
        }

        // Re-read the enabled list every round so gallery changes, added or
        // deleted images and toggles take effect without restarting.
        List<File> files = GalleryStore.activeFiles(this, "popup");
        if (files.isEmpty()) {
            Toast.makeText(getApplicationContext(), R.string.toast_no_popup_enabled,
                    Toast.LENGTH_SHORT).show();
            scheduleNextOnce();
            return;
        }
        final File chosen = files.get(random.nextInt(files.size()));

        // Tear down any overlay still on screen before showing a new one.
        removeCurrentOverlay();

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // consume touches so user cannot close
                return true;
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;

        currentView = iv;
        try {
            windowManager.addView(iv, params);
        } catch (Exception e) {
            e.printStackTrace();
            currentView = null;
            scheduleNextOnce();
            return;
        }
        iv.setAlpha(0f);

        // Decode off the main thread, then fade the image in. If the overlay
        // is torn down while decoding, the bitmap is recycled on arrival.
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (executor == null || executor.isShutdown()) {
            removeCurrentOverlay();
            scheduleNextOnce();
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = GalleryStore.decodeSampled(chosen, dm.widthPixels, dm.heightPixels);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (currentView != iv) {
                            if (bmp != null) bmp.recycle();
                            return;
                        }
                        if (bmp == null) {
                            // Undecodable file: drop this popup, keep the chain alive.
                            dismissWithAnimation(iv);
                            scheduleNextOnce();
                            return;
                        }
                        currentBitmap = bmp;
                        iv.setImageBitmap(bmp);
                        iv.animate().alpha(1f).setDuration(220L).start();
                    }
                });
            }
        });

        final View target = iv;
        int durationSec = minSeconds + random.nextInt(secondsSpan());
        dismissRunnable = new Runnable() {
            @Override
            public void run() {
                dismissRunnable = null;
                // Display time is over: fade the overlay out and restart the
                // random countdown for the next popup from this moment.
                dismissWithAnimation(target);
                scheduleNextOnce();
            }
        };
        handler.postDelayed(dismissRunnable, durationSec * 1000L);
    }

    /** Fades the overlay out and removes it once the animation finishes. */
    private void dismissWithAnimation(final View view) {
        if (view == null) return;
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .setDuration(220L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        safeRemoveView(view);
                    }
                })
                .start();
        // Safety net: make sure the view is gone even if the callback is lost.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                safeRemoveView(view);
            }
        }, 1000L);
    }

    private void safeRemoveView(View view) {
        try {
            if (view.getParent() != null) {
                windowManager.removeView(view);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (currentView == view) {
            currentView = null;
            recycleCurrentBitmap();
        }
    }

    /** Recycles the bitmap of the overlay currently being removed, if any. */
    private void recycleCurrentBitmap() {
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = null;
    }

    /** Cancels the pending dismiss timer and removes the current overlay. */
    private void removeCurrentOverlay() {
        if (dismissRunnable != null) {
            handler.removeCallbacks(dismissRunnable);
            dismissRunnable = null;
        }
        if (currentView != null) {
            currentView.animate().cancel();
            safeRemoveView(currentView);
            currentView = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeCurrentOverlay();
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
