package com.zako.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/**
 * Grid adapter for one gallery. Every image is a file, so thumbnails are
 * decoded with BitmapFactory downsampling and cached in an LruCache keyed by
 * file name; scrolling a grid of full-size images stays smooth and cannot
 * exhaust memory.
 */
public class GalleryImageAdapter extends RecyclerView.Adapter<GalleryImageAdapter.VH> {

    public static class Item {
        public final String fileName;
        public boolean enabled;

        public Item(String fileName, boolean enabled) {
            this.fileName = fileName;
            this.enabled = enabled;
        }
    }

    public interface Listener {
        void onToggle(int position, boolean newState);

        void onDelete(int position);
    }

    private final Context ctx;
    private final File dir;
    private final List<Item> items;
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

    public GalleryImageAdapter(Context ctx, File dir, List<Item> items, Listener listener) {
        this.ctx = ctx;
        this.dir = dir;
        this.items = items;
        this.listener = listener;
        float density = ctx.getResources().getDisplayMetrics().density;
        // Thumbnails only need to cover the grid cell; decode at ~2x for quality.
        this.targetW = (int) (200 * density * 2);
        this.targetH = (int) (260 * density * 2);
    }

    public void setItems(List<Item> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_gallery_image, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final Item it = items.get(position);
        holder.img.setImageBitmap(getThumbnail(it.fileName));
        holder.btn.setImageResource(it.enabled
                ? R.drawable.ic_badge_check : R.drawable.ic_badge_hidden);
        holder.btn.setContentDescription(ctx.getString(
                it.enabled ? R.string.cd_toggle_disable : R.string.cd_toggle_enable));
        holder.scrim.setVisibility(it.enabled ? View.GONE : View.VISIBLE);

        holder.btn.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            boolean newState = !items.get(pos).enabled;
            items.get(pos).enabled = newState;
            notifyItemChanged(pos);
            if (listener != null) listener.onToggle(pos, newState);
        });
        holder.delete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (listener != null) listener.onDelete(pos);
        });
    }

    private Bitmap getThumbnail(String fileName) {
        Bitmap cached = cache.get(fileName);
        if (cached != null) return cached;
        Bitmap bmp = GalleryStore.decodeSampled(new File(dir, fileName), targetW, targetH);
        if (bmp != null) cache.put(fileName, bmp);
        return bmp;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.img.setImageDrawable(null);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView img;
        final ImageButton btn;
        final ImageButton delete;
        final View scrim;

        VH(@NonNull View itemView) {
            super(itemView);
            img = itemView.findViewById(R.id.img_item);
            btn = itemView.findViewById(R.id.btn_toggle);
            delete = itemView.findViewById(R.id.btn_delete);
            scrim = itemView.findViewById(R.id.scrim_overlay);
        }
    }
}
