package com.zako.gallery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * List adapter for the gallery manager screen. Each row shows a gallery with
 * its image counts and the rename / delete actions (delete hidden for the
 * protected default gallery).
 */
public class GalleryFolderAdapter extends RecyclerView.Adapter<GalleryFolderAdapter.VH> {

    public interface Listener {
        void onOpen(String galleryId);

        void onRename(GalleryStore.Gallery gallery);

        void onDelete(GalleryStore.Gallery gallery);
    }

    private final List<GalleryStore.Gallery> galleries = new ArrayList<>();
    private final Listener listener;

    public GalleryFolderAdapter(Listener listener) {
        this.listener = listener;
    }

    /** Replaces the displayed galleries (the live objects are mutated in place). */
    public void setGalleries(List<GalleryStore.Gallery> newGalleries) {
        galleries.clear();
        galleries.addAll(newGalleries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gallery_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final GalleryStore.Gallery g = galleries.get(position);
        holder.name.setText(g.name);
        holder.meta.setText(holder.itemView.getContext().getString(
                R.string.gallery_folder_meta, g.activeCount(), g.totalCount()));
        holder.tag.setText(g.builtin
                ? R.string.gallery_tag_builtin : R.string.gallery_tag_custom);
        holder.tag.setTextColor(holder.itemView.getContext().getColor(
                g.builtin ? R.color.pink : R.color.text_secondary));
        // The default gallery is protected and cannot be deleted.
        holder.delete.setVisibility(g.builtin ? View.INVISIBLE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || listener == null) return;
            listener.onOpen(galleries.get(pos).id);
        });
        holder.rename.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || listener == null) return;
            listener.onRename(galleries.get(pos));
        });
        holder.delete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || listener == null) return;
            listener.onDelete(galleries.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return galleries.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView tag;
        final ImageButton rename;
        final ImageButton delete;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_name);
            meta = itemView.findViewById(R.id.tv_meta);
            tag = itemView.findViewById(R.id.tv_tag);
            rename = itemView.findViewById(R.id.btn_rename);
            delete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
