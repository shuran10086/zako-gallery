package com.zako.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app album browser used instead of the system photo picker.
 *
 * <p>Two levels: a grid of folders (plus a synthetic "recent" album) and a
 * multi-select grid of one folder's images. The selection is returned to the
 * caller as absolute file paths, which {@link GalleryStore} copies into the
 * target gallery.</p>
 *
 * <p>Folders come from {@link AlbumScanner}, which walks the file system, so
 * folders the media scanner never indexed (WeChat, QQ, forum apps, ...) show
 * up just like in the OEM gallery. The scan runs on a background thread; the
 * photo permission is checked by the caller before this screen opens.</p>
 */
public class AlbumBrowserActivity extends AppCompatActivity
        implements AlbumFolderAdapter.Listener, AlbumImageAdapter.Listener {

    public static final String EXTRA_PATHS = "selected_paths";

    private static final int MAX_PICK = 30;

    private enum Mode { FOLDERS, IMAGES }

    private RecyclerView rv;
    private TextView title;
    private TextView subtitle;
    private View emptyBox;
    private TextView partialHint;
    private MaterialButton btnGoSettings;
    private MaterialButton btnConfirm;
    private View progressOverlay;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final List<AlbumScanner.Album> albums = new ArrayList<>();
    private final List<File> recent = new ArrayList<>();
    /** Folder rows exactly as displayed (recent album first). */
    private final List<AlbumScanner.Album> folderRows = new ArrayList<>();

    private AlbumFolderAdapter folderAdapter;
    private AlbumImageAdapter imageAdapter;

    private Mode mode = Mode.FOLDERS;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_browser);

        title = findViewById(R.id.ab_title);
        subtitle = findViewById(R.id.ab_subtitle);
        emptyBox = findViewById(R.id.ab_empty_box);
        partialHint = findViewById(R.id.ab_partial_hint);
        btnGoSettings = findViewById(R.id.btn_ab_settings);
        btnConfirm = findViewById(R.id.btn_ab_confirm);
        progressOverlay = findViewById(R.id.progress_overlay);
        rv = findViewById(R.id.ab_rv);

        folderAdapter = new AlbumFolderAdapter(this, this);
        imageAdapter = new AlbumImageAdapter(this, this, MAX_PICK);

        ImageButton back = findViewById(R.id.btn_ab_back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // In image mode, back returns to the folder grid instead of leaving.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode == Mode.IMAGES) {
                    showFolders();
                } else {
                    finish();
                }
            }
        });

        btnConfirm.setOnClickListener(v -> confirmSelection());
        btnGoSettings.setOnClickListener(v -> openAppSettings());

        startScan();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------- scanning ----------

    private void startScan() {
        progressOverlay.setVisibility(View.VISIBLE);
        io.execute(() -> {
            List<AlbumScanner.Album> scanned = AlbumScanner.scan();
            List<File> recentFiles = AlbumScanner.recentFiles(scanned, MAX_PICK);
            runOnUiThread(() -> {
                progressOverlay.setVisibility(View.GONE);
                albums.clear();
                albums.addAll(scanned);
                recent.clear();
                recent.addAll(recentFiles);
                if (albums.isEmpty()) {
                    showEmpty();
                } else {
                    showFolders();
                }
            });
        });
    }

    private void showEmpty() {
        mode = Mode.FOLDERS;
        title.setText(R.string.album_title);
        subtitle.setText(R.string.album_empty);
        rv.setVisibility(View.GONE);
        btnConfirm.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        // A granted permission with zero folders usually means an Android 14
        // partial ("select photos only") grant: hint at the fix.
        boolean partial = PhotoAccess.granted(this);
        partialHint.setVisibility(partial ? View.VISIBLE : View.GONE);
        btnGoSettings.setVisibility(partial ? View.VISIBLE : View.GONE);
    }

    // ---------- folder level ----------

    private void showFolders() {
        mode = Mode.FOLDERS;
        title.setText(R.string.album_title);
        subtitle.setText(getString(R.string.album_folder_subtitle, albums.size()));
        btnConfirm.setVisibility(View.GONE);
        emptyBox.setVisibility(View.GONE);

        List<AlbumScanner.Album> rows = new ArrayList<>();
        if (!recent.isEmpty()) {
            AlbumScanner.Album r = new AlbumScanner.Album(getString(R.string.album_recent));
            r.recent = true;
            r.files.addAll(recent);
            rows.add(r);
        }
        rows.addAll(albums);

        rv.setVisibility(View.VISIBLE);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setAdapter(folderAdapter);
        folderAdapter.setAlbums(rows);
        folderRows.clear();
        folderRows.addAll(rows);
    }

    @Override
    public void onAlbumClick(int position) {
        if (position < 0 || position >= folderRows.size()) return;
        AlbumScanner.Album album = folderRows.get(position);
        showImages(album.name, album.files);
    }

    // ---------- image level ----------

    private void showImages(String name, List<File> files) {
        mode = Mode.IMAGES;
        title.setText(name);
        subtitle.setText(getString(R.string.album_image_subtitle, files.size()));
        emptyBox.setVisibility(View.GONE);
        rv.setVisibility(View.VISIBLE);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setAdapter(imageAdapter);
        imageAdapter.setFiles(files);
        btnConfirm.setVisibility(View.VISIBLE);
        updateConfirm(0);
    }

    @Override
    public void onSelectionChanged(int count) {
        updateConfirm(count);
    }

    private void updateConfirm(int count) {
        btnConfirm.setText(count > 0
                ? getString(R.string.album_confirm, count)
                : getString(R.string.album_confirm_empty));
        btnConfirm.setEnabled(count > 0);
    }

    private void confirmSelection() {
        List<String> paths = imageAdapter.selectedPaths();
        if (paths.isEmpty()) return;
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(paths));
        setResult(RESULT_OK, data);
        finish();
    }

    // ---------- helpers ----------

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }
}
