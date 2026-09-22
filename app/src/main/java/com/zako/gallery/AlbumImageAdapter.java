package com.zako.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grid adapter for the album browser's image level: tap to (de)select, up to
 * {@code max} images. Selected cells are dimmed with a pink check badge.
 *
 * Thumbnails are decoded downsampled and cached in an LruCache keyed by
 * absolute path, mirroring {@link GalleryImageAdapter}.
 */
public class AlbumImageAdapter extends RecyclerView.Adapter<AlbumImageAdapter.VH> {

    public interface Listener {
        void onSelectionChanged(int count);
    }

    private final List<File> files = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Listener listener;
    private final Context ctx;
    private final int max;
    private final int targetW;
    private final int targetH;

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    public AlbumImageAdapter(Context ctx, Listener listener, int max) {
        this.ctx = ctx;
        this.listener = listener;
        this.max = max;
        float density = ctx.getResources().getDisplayMetrics().density;
        this.targetW = (int) (160 * density * 2);
        this.targetH = (int) (160 * density * 2);
    }

    /** Replaces the displayed files and clears the selection. */
    public void setFiles(List<File> newFiles) {
        files.clear();
        files.addAll(newFiles);
        selected.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(0);
    }

    /** Absolute paths of the selected images, in display order. */
    public List<String> selectedPaths() {
        List<String> out = new ArrayList<>();
        for (File f : files) {
            if (selected.contains(f.getAbsolutePath())) out.add(f.getAbsolutePath());
        }
        return out;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album_image, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final File file = files.get(position);
        holder.thumb.setImageBitmap(thumbnail(file));

        final boolean sel = selected.contains(file.getAbsolutePath());
        holder.scrim.setVisibility(sel ? View.VISIBLE : View.GONE);
        holder.check.setBackgroundResource(sel
                ? R.drawable.bg_badge_circle : R.drawable.bg_badge_dim);
        holder.check.setColorFilter(ContextCompat.getColor(ctx,
                sel ? R.color.white : R.color.text_secondary));
        holder.check.setAlpha(sel ? 1f : 0.7f);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            File f = files.get(pos);
            String path = f.getAbsolutePath();
            if (!selected.remove(path)) {
                if (selected.size() >= max) {
                    Toast.makeText(ctx, R.string.album_max_reached,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                selected.add(path);
            }
            notifyItemChanged(pos);
            if (listener != null) listener.onSelectionChanged(selected.size());
        });
    }

    private Bitmap thumbnail(File file) {
        String key = file.getAbsolutePath();
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;
        Bitmap bmp = GalleryStore.decodeSampled(file, targetW, targetH);
        if (bmp != null) cache.put(key, bmp);
        return bmp;
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.thumb.setImageDrawable(null);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final View scrim;
        final ImageView check;

        VH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.img_thumb);
            scrim = itemView.findViewById(R.id.scrim_selected);
            check = itemView.findViewById(R.id.img_check);
        }
    }
}
