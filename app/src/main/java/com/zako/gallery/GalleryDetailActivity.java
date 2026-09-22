package com.zako.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One gallery's images: enable/disable any image, delete images, add images
 * with the in-app album browser, or enable everything again.
 *
 * The store is the single source of truth: every action mutates it and the
 * grid is re-rendered from it, so rotation or leaving and coming back always
 * shows the real state.
 */
public class GalleryDetailActivity extends AppCompatActivity
        implements GalleryImageAdapter.Listener {

    public static final String EXTRA_GALLERY_ID = "gallery_id";

    private String galleryId;
    private GalleryImageAdapter adapter;
    private List<GalleryImageAdapter.Item> items = new ArrayList<>();

    private TextView title;
    private TextView subtitle;
    private TextView emptyView;
    private View progressOverlay;
    private RecyclerView rv;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /**
     * Result of the in-app album browser: absolute file paths of the images
     * the user selected, handed to the same copy pipeline the picker used.
     */
    private final ActivityResultLauncher<Intent> browserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            return;
                        }
                        ArrayList<String> paths = result.getData()
                                .getStringArrayListExtra(AlbumBrowserActivity.EXTRA_PATHS);
                        if (paths == null || paths.isEmpty()) return;
                        List<Uri> uris = new ArrayList<>();
                        for (String path : paths) {
                            uris.add(Uri.fromFile(new File(path)));
                        }
                        onImagesPicked(uris);
                    });

    /**
     * Re-request raised from the add-images flow. Opens the album browser
     * when the user grants access; otherwise it stays closed (no permission,
     * no adding).
     */
    private final ActivityResultLauncher<String> photoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                permRequesting = false;
                if (Boolean.TRUE.equals(granted)) {
                    launchBrowser();
                } else {
                    Toast.makeText(this, R.string.toast_photo_permission_denied,
                            Toast.LENGTH_SHORT).show();
                }
            });

    /** Guards against launching a second permission dialog from double taps. */
    private boolean permRequesting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery_detail);

        galleryId = getIntent().getStringExtra(EXTRA_GALLERY_ID);
        if (galleryId == null) {
            finish();
            return;
        }

        title = findViewById(R.id.gd_title);
        subtitle = findViewById(R.id.gd_subtitle);
        emptyView = findViewById(R.id.tv_empty);
        progressOverlay = findViewById(R.id.progress_overlay);
        rv = findViewById(R.id.rv_images);

        findViewById(R.id.btn_gd_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_add_images).setOnClickListener(v -> pickImages());
        findViewById(R.id.btn_enable_all).setOnClickListener(v -> {
            GalleryStore.enableAllItems(this, galleryId);
            render();
        });

        adapter = new GalleryImageAdapter(this, GalleryStore.galleryDir(this, galleryId),
                items, this);
        int spacing = getResources().getDimensionPixelSize(R.dimen.space_xs);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.addItemDecoration(new GridSpacingItemDecoration(2, spacing, true));
        rv.setAdapter(adapter);

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    /** Rebuilds the grid from the store. Finishes if the gallery disappeared. */
    private void render() {
        if (galleryId == null) return;
        if (!GalleryStore.isReady()) {
            // Reached before seeding finished (host normally blocks until it
            // is done); render as soon as the store becomes available.
            GalleryStore.prepareAsync(this, this::render);
            return;
        }
        GalleryStore.Gallery g = GalleryStore.gallery(this, galleryId);
        if (g == null) {
            finish();
            return;
        }
        title.setText(g.name);
        subtitle.setText(getString(R.string.gallery_count_active, g.activeCount(), g.totalCount()));

        List<GalleryImageAdapter.Item> fresh = new ArrayList<>();
        for (GalleryStore.Item it : g.items) {
            fresh.add(new GalleryImageAdapter.Item(it.fileName, it.enabled));
        }
        items = fresh;
        adapter.setItems(fresh);

        boolean empty = fresh.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void pickImages() {
        if (PhotoAccess.granted(this)) {
            launchBrowser();
            return;
        }
        // Not granted yet: ask now. The browser only opens when this succeeds.
        if (permRequesting) return;
        permRequesting = true;
        photoPermLauncher.launch(PhotoAccess.permissionName());
    }

    private void launchBrowser() {
        browserLauncher.launch(new Intent(this, AlbumBrowserActivity.class));
    }

    private void onImagesPicked(@NonNull List<Uri> uris) {
        if (uris.isEmpty()) return;
        progressOverlay.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int added = GalleryStore.addImages(getApplicationContext(), galleryId, uris);
            runOnUiThread(() -> {
                progressOverlay.setVisibility(View.GONE);
                if (added > 0) {
                    Toast.makeText(this, getString(R.string.gallery_added, added),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.gallery_add_failed,
                            Toast.LENGTH_SHORT).show();
                }
                render();
            });
        });
    }

    @Override
    public void onToggle(int position, boolean newState) {
        GalleryImageAdapter.Item it = items.get(position);
        GalleryStore.setItemEnabled(this, galleryId, it.fileName, newState);
        GalleryStore.Gallery g = GalleryStore.gallery(this, galleryId);
        if (g != null) {
            subtitle.setText(getString(R.string.gallery_count_active,
                    g.activeCount(), g.totalCount()));
        }
    }

    @Override
    public void onDelete(int position) {
        final GalleryImageAdapter.Item it = items.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gallery_delete_image_title)
                .setMessage(R.string.gallery_delete_image_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    GalleryStore.deleteItem(this, galleryId, it.fileName);
                    render();
                })
                .show();
    }
}
