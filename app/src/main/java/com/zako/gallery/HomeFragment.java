package com.zako.gallery;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.slider.RangeSlider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * 主页 page: the two function cards (auto wallpaper, full-screen popup).
 *
 * All service state lives in SharedPreferences; the UI is restored from there
 * in onCreate/onResume so rotating the screen or returning to the app always
 * shows the real state. Interval and display duration are configured as
 * [min, max] ranges on a single RangeSlider each. Whenever the user lets go of
 * a slider while the matching service is running, the new parameters are
 * pushed to the service immediately.
 */
public class HomeFragment extends Fragment implements MainActivity.Page {

    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 60;
    private static final int MIN_SECONDS = 3;
    private static final int MAX_SECONDS = 60;

    private SwitchMaterial wallpaperSwitch;
    private SwitchMaterial popupSwitch;
    private SwitchMaterial lockscreenSwitch;

    private RangeSlider rangeWallpaperInterval;
    private RangeSlider rangePopupInterval;
    private RangeSlider rangePopupDuration;
    private TextView wallpaperRangeLabel;
    private TextView popupIntervalRangeLabel;
    private TextView popupDurationRangeLabel;

    private TextView tvWallpaperGallery;
    private TextView tvPopupGallery;

    private View homeContent;
    private View lockNotice;

    /** True while the UI is being restored programmatically; suppresses side effects. */
    private boolean restoring = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        wallpaperSwitch = view.findViewById(R.id.switch_wallpaper);
        popupSwitch = view.findViewById(R.id.switch_popup);
        lockscreenSwitch = view.findViewById(R.id.switch_lockscreen);
        rangeWallpaperInterval = view.findViewById(R.id.range_wallpaper_interval);
        rangePopupInterval = view.findViewById(R.id.range_popup_interval);
        rangePopupDuration = view.findViewById(R.id.range_popup_duration);
        wallpaperRangeLabel = view.findViewById(R.id.label_wallpaper_interval_range);
        popupIntervalRangeLabel = view.findViewById(R.id.label_popup_interval_range);
        popupDurationRangeLabel = view.findViewById(R.id.label_popup_duration_range);
        tvWallpaperGallery = view.findViewById(R.id.tv_wallpaper_gallery);
        tvPopupGallery = view.findViewById(R.id.tv_popup_gallery);
        homeContent = view.findViewById(R.id.home_content);
        lockNotice = view.findViewById(R.id.lock_notice);

        // Live label updates while dragging (also fires on programmatic changes).
        // Guarded by `restoring`: BaseSlider dispatches programmatic changes
        // while it is still filling its values list, so listeners must not
        // read the sliders during restore; restoreUiState updates the labels
        // once, after every slider holds its full [min, max] pair.
        RangeSlider.OnChangeListener labelUpdater =
                new RangeSlider.OnChangeListener() {
                    @Override
                    public void onValueChange(RangeSlider slider, float value, boolean fromUser) {
                        if (restoring) return;
                        updateAllLabels();
                    }
                };
        rangeWallpaperInterval.addOnChangeListener(labelUpdater);
        rangePopupInterval.addOnChangeListener(labelUpdater);
        rangePopupDuration.addOnChangeListener(labelUpdater);

        // Persist and push to the service only once the user lets go, so a
        // drag does not re-arm the schedule dozens of times.
        RangeSlider.OnSliderTouchListener persistAndPush =
                new RangeSlider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(RangeSlider slider) {
                    }

                    @Override
                    public void onStopTrackingTouch(RangeSlider slider) {
                        if (restoring) return;
                        if (slider == rangeWallpaperInterval) {
                            saveWallpaperParams();
                            pushWallpaperParamsIfRunning();
                        } else {
                            savePopupParams();
                            pushPopupParamsIfRunning();
                        }
                    }
                };
        rangeWallpaperInterval.addOnSliderTouchListener(persistAndPush);
        rangePopupInterval.addOnSliderTouchListener(persistAndPush);
        rangePopupDuration.addOnSliderTouchListener(persistAndPush);

        lockscreenSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (restoring) return;
                GalleryPrefs.saveWallpaperParams(requireContext(),
                        GalleryPrefs.getWallpaperMinMinutes(requireContext()),
                        GalleryPrefs.getWallpaperMaxMinutes(requireContext()),
                        isChecked);
                pushWallpaperParamsIfRunning();
            }
        });

        wallpaperSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (restoring) return;
                GalleryPrefs.setWallpaperEnabled(requireContext(), isChecked);
                if (isChecked) {
                    saveWallpaperParams();
                    pushWallpaperParamsIfRunning();
                } else {
                    requireContext().stopService(
                            new Intent(requireContext(), WallpaperChangeService.class));
                }
            }
        });

        popupSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (restoring) return;
                GalleryPrefs.setPopupEnabled(requireContext(), isChecked);
                if (isChecked) {
                    savePopupParams();
                    pushPopupParamsIfRunning();
                } else {
                    requireContext().stopService(
                            new Intent(requireContext(), PopupOverlayService.class));
                }
            }
        });

        restoreUiState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-sync with the persisted state (the real source of truth) and
        // refresh gallery lines, which the gallery screens can change.
        if (getView() != null) {
            restoreUiState();
        }
    }

    /** Called by the host once the gallery store has been seeded/loaded. */
    @Override
    public void onStoreReady() {
        if (getView() != null) {
            restoreUiState();
        }
    }

    /** Called by the host whenever this page becomes the visible one. */
    @Override
    public void onPageShown() {
        if (getView() != null) {
            restoreUiState();
        }
    }

    /** Restores switches and range sliders from SharedPreferences. */
    private void restoreUiState() {
        if (getView() == null) return;
        applyLockState();
        restoring = true;
        try {
            lockscreenSwitch.setChecked(GalleryPrefs.isWallpaperLockEnabled(requireContext()));

            int wallMin = GalleryPrefs.getWallpaperMinMinutes(requireContext());
            int wallMax = GalleryPrefs.getWallpaperMaxMinutes(requireContext());
            setRange(rangeWallpaperInterval, wallMin, wallMax, MIN_MINUTES, MAX_MINUTES);

            int popMin = GalleryPrefs.getPopupMinMinutes(requireContext());
            int popMax = GalleryPrefs.getPopupMaxMinutes(requireContext());
            setRange(rangePopupInterval, popMin, popMax, MIN_MINUTES, MAX_MINUTES);

            int durMin = GalleryPrefs.getPopupMinSeconds(requireContext());
            int durMax = GalleryPrefs.getPopupMaxSeconds(requireContext());
            setRange(rangePopupDuration, durMin, durMax, MIN_SECONDS, MAX_SECONDS);

            updateAllLabels();

            wallpaperSwitch.setChecked(GalleryPrefs.isWallpaperEnabled(requireContext()));
            popupSwitch.setChecked(GalleryPrefs.isPopupEnabled(requireContext()));

            updateGalleryLines();
        } finally {
            restoring = false;
        }
    }

    /**
     * While 锁定主页 is engaged the function cards are replaced by the
     * "锁定中" notice. Running services keep going; only the controls are
     * hidden until the password is typed on the 密码 page.
     */
    private void applyLockState() {
        boolean locked = AppLock.isLocked(requireContext());
        homeContent.setVisibility(locked ? View.GONE : View.VISIBLE);
        lockNotice.setVisibility(locked ? View.VISIBLE : View.GONE);
    }

    /**
     * Positions both thumbs of a range slider. Stored values are normalized
     * (an older build could persist min &gt; max) and clamped into bounds.
     */
    private void setRange(RangeSlider slider, int min, int max, int boundLo, int boundHi) {
        int lo = Math.max(boundLo, Math.min(min, max));
        int hi = Math.min(boundHi, Math.max(min, max));
        if (lo > hi) lo = hi;
        slider.setValues((float) lo, (float) hi);
    }

    private void updateAllLabels() {
        int wallMin = rangeLow(rangeWallpaperInterval);
        int wallMax = rangeHigh(rangeWallpaperInterval);
        wallpaperRangeLabel.setText(getString(R.string.wallpaper_interval_range, wallMin, wallMax));

        int popMin = rangeLow(rangePopupInterval);
        int popMax = rangeHigh(rangePopupInterval);
        popupIntervalRangeLabel.setText(getString(R.string.popup_interval_range, popMin, popMax));

        int durMin = rangeLow(rangePopupDuration);
        int durMax = rangeHigh(rangePopupDuration);
        popupDurationRangeLabel.setText(getString(R.string.popup_duration_range, durMin, durMax));
    }

    /**
     * Lower end of a range slider. Defensive about list size: while
     * BaseSlider applies a programmatic {@code setValues} it can dispatch
     * change callbacks before both values are in place.
     */
    private static int rangeLow(RangeSlider slider) {
        List<Float> values = slider.getValues();
        if (values == null || values.isEmpty()) return Math.round(slider.getValueFrom());
        if (values.size() < 2) return Math.round(values.get(0));
        return Math.round(Math.min(values.get(0), values.get(1)));
    }

    /**
     * Upper end of a range slider. Defensive about list size; see
     * {@link #rangeLow}.
     */
    private static int rangeHigh(RangeSlider slider) {
        List<Float> values = slider.getValues();
        if (values == null || values.isEmpty()) return Math.round(slider.getValueTo());
        if (values.size() < 2) return Math.round(values.get(0));
        return Math.round(Math.max(values.get(0), values.get(1)));
    }

    private void saveWallpaperParams() {
        GalleryPrefs.saveWallpaperParams(requireContext(),
                rangeLow(rangeWallpaperInterval), rangeHigh(rangeWallpaperInterval),
                lockscreenSwitch.isChecked());
    }

    private void savePopupParams() {
        GalleryPrefs.savePopupParams(requireContext(),
                rangeLow(rangePopupInterval), rangeHigh(rangePopupInterval),
                rangeLow(rangePopupDuration), rangeHigh(rangePopupDuration));
    }

    /**
     * Starts or refreshes the wallpaper service with the current parameters.
     * Re-sending the start intent to a running service makes it pick up the
     * new range immediately (its onStartCommand re-arms the schedule).
     */
    private void pushWallpaperParamsIfRunning() {
        if (!GalleryPrefs.isWallpaperEnabled(requireContext())) return;
        Intent svc = new Intent(requireContext(), WallpaperChangeService.class)
                .setAction(WallpaperChangeService.ACTION_START);
        svc.putExtra("min_minutes", rangeLow(rangeWallpaperInterval));
        svc.putExtra("max_minutes", rangeHigh(rangeWallpaperInterval));
        svc.putExtra("set_lock", lockscreenSwitch.isChecked());
        ContextCompat.startForegroundService(requireContext(), svc);
    }

    /** Starts or refreshes the popup service with the current parameters. */
    private void pushPopupParamsIfRunning() {
        if (!GalleryPrefs.isPopupEnabled(requireContext())) return;
        Intent svc = new Intent(requireContext(), PopupOverlayService.class)
                .setAction(PopupOverlayService.ACTION_START);
        svc.putExtra("popup_min_minutes", rangeLow(rangePopupInterval));
        svc.putExtra("popup_max_minutes", rangeHigh(rangePopupInterval));
        svc.putExtra("popup_min_seconds", rangeLow(rangePopupDuration));
        svc.putExtra("popup_max_seconds", rangeHigh(rangePopupDuration));
        ContextCompat.startForegroundService(requireContext(), svc);
    }

    /** Shows "图库：<名称> · 已启用 x / y 张" under each function card. */
    private void updateGalleryLines() {
        // Before the store is seeded the host shows a blocking overlay; leave
        // the lines blank instead of triggering the (blocking) seed here.
        // onStoreReady() re-renders once seeding finishes.
        if (!GalleryStore.isReady()) {
            tvWallpaperGallery.setText("");
            tvPopupGallery.setText("");
            return;
        }
        tvWallpaperGallery.setText(galleryLine("wallpaper"));
        tvPopupGallery.setText(galleryLine("popup"));
    }

    private String galleryLine(String which) {
        GalleryStore.Gallery g = GalleryStore.gallery(requireContext(),
                GalleryStore.selectedGallery(requireContext(), which));
        if (g == null) return getString(R.string.home_gallery_line, "-", 0, 0);
        return getString(R.string.home_gallery_line, g.name, g.activeCount(), g.totalCount());
    }
}
