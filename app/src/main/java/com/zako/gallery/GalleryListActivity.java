package com.zako.gallery;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Gallery manager screen: lists every gallery and offers create / rename /
 * delete. The default gallery is protected against deletion. Tapping a row
 * opens its images in {@link GalleryDetailActivity}.
 */
public class GalleryListActivity extends AppCompatActivity
        implements GalleryFolderAdapter.Listener {

    private TextView subtitle;
    private RecyclerView rv;
    private GalleryFolderAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The normal entry is the 设置 page button, which is disabled while
        // 锁定主页 is engaged; this is only for a stale intent or recents.
        if (AppLock.isLocked(this)) {
            Toast.makeText(this, R.string.toast_gallery_locked, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_gallery_list);

        subtitle = findViewById(R.id.gl_subtitle);
        rv = findViewById(R.id.rv_galleries);
        rv.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_gl_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_new_gallery).setOnClickListener(v -> showCreateDialog());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        if (!GalleryStore.isReady()) {
            GalleryStore.prepareAsync(this, this::render);
            return;
        }
        List<GalleryStore.Gallery> galleries = GalleryStore.galleries(this);
        if (adapter == null) {
            adapter = new GalleryFolderAdapter(this);
            rv.setAdapter(adapter);
        }
        adapter.setGalleries(galleries);
        subtitle.setText(getString(R.string.gallery_list_subtitle, galleries.size()));
    }

    private void showCreateDialog() {
        View box = nameInput(null);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gallery_new)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = inputText(box);
                    String id = GalleryStore.createGallery(this, name);
                    if (id == null) {
                        Toast.makeText(this, R.string.gallery_name_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, R.string.gallery_created, Toast.LENGTH_SHORT).show();
                    render();
                    // Straight into the empty new gallery so images can be added.
                    Intent it = new Intent(this, GalleryDetailActivity.class);
                    it.putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID, id);
                    startActivity(it);
                })
                .show();
    }

    @Override
    public void onOpen(String galleryId) {
        Intent it = new Intent(this, GalleryDetailActivity.class);
        it.putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID, galleryId);
        startActivity(it);
    }

    @Override
    public void onRename(GalleryStore.Gallery gallery) {
        View box = nameInput(gallery.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gallery_rename)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (GalleryStore.renameGallery(this, gallery.id, inputText(box))) {
                        Toast.makeText(this, R.string.gallery_renamed,
                                Toast.LENGTH_SHORT).show();
                        render();
                    } else {
                        Toast.makeText(this, R.string.gallery_name_invalid,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    public void onDelete(GalleryStore.Gallery gallery) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gallery_delete)
                .setMessage(getString(R.string.gallery_delete_confirm, gallery.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    GalleryStore.deleteGallery(this, gallery.id);
                    Toast.makeText(this, R.string.gallery_deleted, Toast.LENGTH_SHORT).show();
                    render();
                })
                .show();
    }

    /** Builds the dialog view for the create / rename dialogs. */
    @NonNull
    private View nameInput(@Nullable String preset) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_l);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText input = new EditText(this);
        input.setTag("input");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setHint(R.string.gallery_name_hint);
        if (preset != null) {
            input.setText(preset);
            input.setSelection(preset.length());
        }
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    @NonNull
    private static String inputText(@NonNull View dialogView) {
        View tag = dialogView.findViewWithTag("input");
        return tag instanceof EditText ? ((EditText) tag).getText().toString() : "";
    }
}
