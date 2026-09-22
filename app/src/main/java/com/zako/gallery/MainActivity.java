package com.zako.gallery;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Navigation host. Owns the bottom navigation bar and switches between the
 * three pages: 主页 (functions), 密码, 设置. The app's only launcher activity;
 * everything else is opened from the pages.
 *
 * Before the first frame of content is useful, the gallery store is seeded
 * from assets (first launch, or upgrading from v1.0). A blocking overlay is
 * shown while that runs and pages are notified once the store is ready.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 1001;

    static final String TAG_HOME = "home";
    static final String TAG_SETTINGS = "settings";
    static final String TAG_PASSWORD = "password";

    private BottomNavigationView bottomNav;
    private View seedingOverlay;

    /**
     * Startup photo-permission prompt. The result is checked on demand: the
     * gallery screen re-requests (or refuses to open the picker) when the
     * user adds images.
     */
    private final ActivityResultLauncher<String> photoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        seedingOverlay = findViewById(R.id.seeding_overlay);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_settings) {
                showPage(TAG_SETTINGS);
            } else if (id == R.id.nav_password) {
                showPage(TAG_PASSWORD);
            } else {
                showPage(TAG_HOME);
            }
            return true;
        });

        if (savedInstanceState == null) {
            showPage(TAG_HOME);
        }

        prepareStore();
        requestNotificationPermissionIfNeeded();
    }

    /**
     * Seeds the gallery store if this is the first run (or an upgrade from
     * v1.0). Pages refresh themselves when the store becomes ready. The
     * one-time photo permission prompt follows once the app is usable.
     */
    private void prepareStore() {
        if (GalleryStore.isReady()) {
            seedingOverlay.setVisibility(View.GONE);
            requestPhotoPermissionOnce();
            return;
        }
        seedingOverlay.setVisibility(View.VISIBLE);
        GalleryStore.prepareAsync(this, () -> {
            seedingOverlay.setVisibility(View.GONE);
            forEachPage(page -> page.onStoreReady());
            requestPhotoPermissionOnce();
        });
    }

    /**
     * Asks for photo access the first time the app runs (so the toggle exists
     * in system settings and the picker can be opened later). Never repeats:
     * denials are handled on demand by the gallery screen.
     */
    private void requestPhotoPermissionOnce() {
        if (PhotoAccess.granted(this) || PhotoAccess.wasRequested(this)) return;
        PhotoAccess.markRequested(this);
        photoPermLauncher.launch(PhotoAccess.permissionName());
    }

    /** Instantiates or reveals the page with the given tag. */
    private void showPage(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment target = fm.findFragmentByTag(tag);
        FragmentTransaction ft = fm.beginTransaction();
        hideAll(fm, ft);
        if (target == null) {
            target = createPage(tag);
            ft.add(R.id.fragment_container, target, tag);
        } else {
            ft.show(target);
        }
        ft.setPrimaryNavigationFragment(target);
        ft.commit();
        // Run the transaction synchronously, then let the freshly shown page
        // re-read its state. Without the primary-navigation fragment the
        // manager does not resume/shown pages on tab switches, so onResume
        // alone would not refresh them until the app itself is paused and
        // resumed; this callback makes the switch take effect immediately.
        fm.executePendingTransactions();
        if (target instanceof Page) ((Page) target).onPageShown();
    }

    private Fragment createPage(String tag) {
        switch (tag) {
            case TAG_SETTINGS:
                return new SettingsFragment();
            case TAG_PASSWORD:
                return new PasswordFragment();
            default:
                return new HomeFragment();
        }
    }

    private void hideAll(FragmentManager fm, FragmentTransaction ft) {
        for (String tag : new String[]{TAG_HOME, TAG_SETTINGS, TAG_PASSWORD}) {
            Fragment f = fm.findFragmentByTag(tag);
            if (f != null && f.isAdded()) ft.hide(f);
        }
    }

    private void forEachPage(PageAction action) {
        FragmentManager fm = getSupportFragmentManager();
        for (String tag : new String[]{TAG_HOME, TAG_SETTINGS, TAG_PASSWORD}) {
            Fragment f = fm.findFragmentByTag(tag);
            if (f instanceof Page) action.run((Page) f);
        }
    }

    private interface PageAction {
        void run(Page page);
    }

    /** Implemented by every page so the host can notify them. */
    public interface Page {
        void onStoreReady();

        /** Called right after this page becomes the visible one. */
        void onPageShown();
    }

    @Override
    public void onBackPressed() {
        // Back from a secondary page returns to 主页 first.
        if (bottomNav.getSelectedItemId() != R.id.nav_home) {
            bottomNav.setSelectedItemId(R.id.nav_home);
            return;
        }
        super.onBackPressed();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }
    }
}
