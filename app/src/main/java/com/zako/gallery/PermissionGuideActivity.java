package com.zako.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Permission guidance screen. Shows the live status of the two permissions
 * that matter (battery whitelist and overlay) and deep-links to the system
 * settings pages. The click behaviour of the buttons is unchanged.
 */
public class PermissionGuideActivity extends AppCompatActivity {

    private TextView statusBattery;
    private TextView statusOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_guide);

        statusBattery = findViewById(R.id.status_battery);
        statusOverlay = findViewById(R.id.status_overlay);

        findViewById(R.id.btn_battery).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        findViewById(R.id.btn_overlay).setOnClickListener(v -> openOverlaySettings());

        findViewById(R.id.btn_app_settings).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        findViewById(R.id.btn_pg_back).setOnClickListener(v -> finish());
    }

    /**
     * Opens the overlay ("display over other apps") permission page.
     * Tries the per-app page first, then the generic list, and finally the
     * app details page, because many OEM ROMs do not honour the per-app
     * intent and drop the user in a list where the app is hard to find.
     */
    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent direct = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        if (safeStart(direct)) return;
        Intent list = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        if (safeStart(list)) return;
        // Guaranteed to exist on every device; the overlay toggle lives here.
        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean safeStart(Intent intent) {
        if (intent.resolveActivity(getPackageManager()) == null) return false;
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    private void updateStatuses() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOk = pm != null
                && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);

        setStatus(statusBattery, batteryOk);
        setStatus(statusOverlay, overlayOk);
    }

    private void setStatus(TextView view, boolean granted) {
        view.setText(granted ? R.string.status_granted : R.string.status_not_granted);
        view.setTextColor(ContextCompat.getColor(this,
                granted ? R.color.status_ok : R.color.status_warn));
    }
}
