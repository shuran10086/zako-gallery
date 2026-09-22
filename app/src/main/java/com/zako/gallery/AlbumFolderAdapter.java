package com.zako.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Grid adapter for the album browser's folder level. Each cell shows the
 * folder's newest image as cover, its display name and image count. The
 * synthetic "recent" album (first row when present) carries a badge.
 *
 * Thumbnails are decoded downsampled and cached in an LruCache keyed by
 * absolute path, mirroring {@link GalleryImageAdapter}.
 */
public class AlbumFolderAdapter extends RecyclerView.Adapter<AlbumFolderAdapter.VH> {

    public interface Listener {
        void onAlbumClick(int position);
    }

    private final List<AlbumScanner.Album> albums = new ArrayList<>();
    private final Listener listener;
    private final int targetW;
    private final int targetH;

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    public AlbumFolderAdapter(Context ctx, Listener listener) {
        this.listener = listener;
        float density = ctx.getResources().getDisplayMetrics().density;
        this.targetW = (int) (200 * density * 2);
        this.targetH = (int) (160 * density * 2);
    }

    public void setAlbums(List<AlbumScanner.Album> newAlbums) {
        albums.clear();
        albums.addAll(newAlbums);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final AlbumScanner.Album album = albums.get(position);
        holder.name.setText(album.name);
        holder.count.setText(holder.itemView.getContext().getString(
                R.string.album_count, album.files.size()));
        holder.badge.setVisibility(album.recent ? View.VISIBLE : View.GONE);

        File cover = album.files.isEmpty() ? null : album.files.get(0);
        holder.cover.setImageBitmap(cover == null ? null : thumbnail(cover));

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || listener == null) return;
            listener.onAlbumClick(pos);
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
        return albums.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.cover.setImageDrawable(null);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;
        final TextView count;
        final TextView badge;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.img_cover);
            name = itemView.findViewById(R.id.tv_name);
            count = itemView.findViewById(R.id.tv_count);
            badge = itemView.findViewById(R.id.tv_badge);
        }
    }
}
