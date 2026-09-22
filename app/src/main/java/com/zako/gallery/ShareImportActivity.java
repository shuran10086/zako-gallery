package com.zako.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives images shared from other apps (system gallery, WeChat, ...) and
 * copies them into a gallery the user picks.
 *
 * <p>This is the escape hatch for pictures the app cannot list itself —
 * vendor-privileged albums, files the scanner skipped — because the sharing
 * app hands over readable content URIs.</p>
 */
public class ShareImportActivity extends AppCompatActivity {

    private View progressOverlay;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private List<Uri> uris = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_import);
        progressOverlay = findViewById(R.id.progress_overlay);

        uris = extractImages(getIntent());
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_no_images, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // The store may still need seeding when the app was not running.
        GalleryStore.prepareAsync(this, this::askGallery);
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void askGallery() {
        List<GalleryStore.Gallery> galleries = GalleryStore.galleries(this);
        if (galleries.isEmpty()) {
            finish();
            return;
        }
        String[] names = new String[galleries.size()];
        for (int i = 0; i < galleries.size(); i++) {
            names[i] = galleries.get(i).name;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_choose_gallery)
                .setItems(names, (d, which) -> importInto(galleries.get(which).id))
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void importInto(String galleryId) {
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
                finish();
            });
        });
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> extractImages(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) out.addAll(list);
        }
        return out;
    }
}
