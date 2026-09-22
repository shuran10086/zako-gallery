package com.zako.gallery;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置 page: which gallery each service uses, management entries and about.
 *
 * The two services can point at different galleries; both default to the
 * built-in 默认图库. Selections are re-read on every resume because the
 * gallery screens can delete the gallery a service currently points at.
 */
public class SettingsFragment extends Fragment implements MainActivity.Page {

    private Spinner spinnerWallpaper;
    private Spinner spinnerPopup;
    private TextView tvVersion;
    private View btnGalleryManage;
    private View btnPermissions;

    /** Galleries behind the spinners, parallel to the adapter entries. */
    private List<GalleryStore.Gallery> galleries = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    /** Suppresses the listener while spinners are being filled programmatically. */
    private boolean suppress = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        spinnerWallpaper = view.findViewById(R.id.spinner_wallpaper_gallery);
        spinnerPopup = view.findViewById(R.id.spinner_popup_gallery);
        tvVersion = view.findViewById(R.id.tv_version);
        btnGalleryManage = view.findViewById(R.id.btn_gallery_manage);
        btnPermissions = view.findViewById(R.id.btn_permissions);

        adapter = new ArrayAdapter<>(requireContext(), R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner);
        spinnerWallpaper.setAdapter(adapter);
        spinnerPopup.setAdapter(adapter);

        spinnerWallpaper.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (!suppress && position >= 0 && position < galleries.size()) {
                    GalleryStore.setSelectedGallery(requireContext(), "wallpaper",
                            galleries.get(position).id);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerPopup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (!suppress && position >= 0 && position < galleries.size()) {
                    GalleryStore.setSelectedGallery(requireContext(), "popup",
                            galleries.get(position).id);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        view.findViewById(R.id.btn_gallery_manage).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), GalleryListActivity.class)));

        view.findViewById(R.id.btn_permissions).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), PermissionGuideActivity.class)));

        tvVersion.setText(getString(R.string.settings_version,
                BuildConfig.VERSION_NAME));
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onStoreReady() {
        refresh();
    }

    /** Called by the host whenever this page becomes the visible one. */
    @Override
    public void onPageShown() {
        refresh();
    }

    /** Reloads the gallery list and re-applies the current selections. */
    private void refresh() {
        if (getView() == null || !GalleryStore.isReady()) return;
        applyLockState();
        suppress = true;
        galleries = GalleryStore.galleries(requireContext());
        adapter.clear();
        for (GalleryStore.Gallery g : galleries) {
            adapter.add(g.name);
        }
        adapter.notifyDataSetChanged();

        selectSpinner(spinnerWallpaper,
                GalleryStore.selectedGallery(requireContext(), "wallpaper"));
        selectSpinner(spinnerPopup,
                GalleryStore.selectedGallery(requireContext(), "popup"));
        suppress = false;
    }

    /**
     * While 锁定主页 is engaged the gallery pickers and the gallery manager
     * entry are frozen; 权限引导 and 关于 stay available.
     */
    private void applyLockState() {
        boolean locked = AppLock.isLocked(requireContext());
        spinnerWallpaper.setEnabled(!locked);
        spinnerPopup.setEnabled(!locked);
        btnGalleryManage.setEnabled(!locked);
    }

    private void selectSpinner(Spinner spinner, String galleryId) {
        for (int i = 0; i < galleries.size(); i++) {
            if (galleries.get(i).id.equals(galleryId)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
}
