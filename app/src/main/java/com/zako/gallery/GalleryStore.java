package com.zako.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single source of truth for galleries and their images.
 *
 * <p>Galleries are plain records (id, name, items) persisted as one JSON blob
 * in SharedPreferences; the image files themselves live in app-private
 * storage under {@code files/galleries/<galleryId>/}. Every image — seeded or
 * user-added — is a file, so enable/disable and delete behave identically for
 * all of them.</p>
 *
 * <p>The first release ships no preinstalled images: the default gallery is
 * created empty and users fill all galleries themselves (album browser,
 * share import).</p>
 *
 * <p>The parsed model is cached in memory and re-persisted on every mutation.
 * All public methods are safe to call from any thread.</p>
 */
public final class GalleryStore {

    public static final String GALLERY_DEFAULT = "default";

    private static final String PREFS_NAME = GalleryPrefs.PREFS_NAME;
    private static final String KEY_STORE = "gallery_store_v2";

    private static final Object LOCK = new Object();
    private static Model model;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private GalleryStore() {
    }

    // ---------- model ----------

    /** One image inside a gallery: a file name plus its enable flag. */
    public static final class Item {
        public final String fileName;
        public boolean enabled;

        Item(String fileName, boolean enabled) {
            this.fileName = fileName;
            this.enabled = enabled;
        }
    }

    /** A named, ordered list of images. The default gallery cannot be deleted. */
    public static final class Gallery {
        public final String id;
        public String name;
        public final boolean builtin;
        public final List<Item> items = new ArrayList<>();

        Gallery(String id, String name, boolean builtin) {
            this.id = id;
            this.name = name;
            this.builtin = builtin;
        }

        public int totalCount() {
            return items.size();
        }

        public int activeCount() {
            int n = 0;
            for (Item it : items) {
                if (it.enabled) n++;
            }
            return n;
        }
    }

    private static final class Model {
        final List<Gallery> galleries = new ArrayList<>();
        String wallpaperGallery = GALLERY_DEFAULT;
        String popupGallery = GALLERY_DEFAULT;
    }

    // ---------- lifecycle ----------

    /** True once the store has been loaded or seeded in this process. */
    public static boolean isReady() {
        return model != null;
    }

    /**
     * Loads the store, seeding the default gallery from assets on first run
     * (or upgrading from v1.0). Synchronous: callers on the main thread should
     * prefer {@link #prepareAsync}, except services which run off the main
     * thread or skip rounds while the store is not ready yet.
     */
    public static void ensureInitialized(Context ctx) {
        synchronized (LOCK) {
            if (model != null) return;
            Model m = new Model();
            SharedPreferences prefs = prefs(ctx);
            String json = prefs.getString(KEY_STORE, null);
            if (json != null) {
                try {
                    parseInto(m, new JSONObject(json));
                    model = m;
                    return;
                } catch (JSONException e) {
                    e.printStackTrace();
                    // Corrupt blob: fall through and rebuild from assets.
                }
            }
            createDefaultGallery(ctx.getApplicationContext(), m);
            model = m;
            save(ctx.getApplicationContext(), m);
        }
    }

    /**
     * Makes the store ready without blocking the caller, then runs onDone on
     * the main thread. If the store is already loaded, onDone runs at once.
     */
    public static void prepareAsync(final Context ctx, @Nullable final Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        if (isReady()) {
            if (onDone != null) onDone.run();
            return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                ensureInitialized(app);
                if (onDone != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(onDone);
                }
            }
        });
    }

    /**
     * Creates the (empty) default gallery. Earlier builds seeded bundled
     * wallpapers into it; the first release ships without any preinstalled
     * images, so users fill their own galleries from the start.
     */
    private static void createDefaultGallery(Context ctx, Model m) {
        m.galleries.add(new Gallery(GALLERY_DEFAULT,
                ctx.getString(R.string.gallery_default_name), true));
    }

    // ---------- queries ----------

    /** Live galleries in display order. The default gallery comes first. */
    public static List<Gallery> galleries(Context ctx) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            List<Gallery> copy = new ArrayList<>(model.galleries);
            Collections.sort(copy, (a, b) -> {
                if (a.builtin != b.builtin) return a.builtin ? -1 : 1;
                return a.id.compareTo(b.id);
            });
            return copy;
        }
    }

    @Nullable
    public static Gallery gallery(Context ctx, String id) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            for (Gallery g : model.galleries) {
                if (g.id.equals(id)) return g;
            }
            return null;
        }
    }

    /** Id of the gallery a service draws images from ("wallpaper" / "popup"). */
    public static String selectedGallery(Context ctx, String which) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            String id = "popup".equals(which) ? model.popupGallery : model.wallpaperGallery;
            if (findGallery(model, id) == null) id = GALLERY_DEFAULT;
            return id;
        }
    }

    public static void setSelectedGallery(Context ctx, String which, String galleryId) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            if (findGallery(model, galleryId) == null) return;
            if ("popup".equals(which)) {
                model.popupGallery = galleryId;
            } else {
                model.wallpaperGallery = galleryId;
            }
            save(ctx, model);
        }
    }

    /**
     * Enabled images of the gallery selected for a service, as files ready to
     * decode. Missing files (deleted out from under us) are skipped.
     */
    public static List<File> activeFiles(Context ctx, String which) {
        ensureInitialized(ctx);
        List<File> result = new ArrayList<>();
        synchronized (LOCK) {
            Gallery g = findGallery(model, selectedGallery(ctx, which));
            if (g == null) return result;
            File dir = galleryDir(ctx, g.id);
            for (Item it : g.items) {
                if (!it.enabled) continue;
                File f = new File(dir, it.fileName);
                if (f.isFile()) result.add(f);
            }
        }
        return result;
    }

    @Nullable
    private static Gallery findGallery(Model m, String id) {
        for (Gallery g : m.galleries) {
            if (g.id.equals(id)) return g;
        }
        return null;
    }

    // ---------- gallery CRUD ----------

    /** Creates an empty gallery. Returns the new id, or null if the name is taken or empty. */
    @Nullable
    public static String createGallery(Context ctx, String name) {
        ensureInitialized(ctx);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return null;
        synchronized (LOCK) {
            for (Gallery g : model.galleries) {
                if (g.name.equals(trimmed)) return null;
            }
            String id = "g" + System.currentTimeMillis();
            model.galleries.add(new Gallery(id, trimmed, false));
            save(ctx, model);
            return id;
        }
    }

    /** Renames a gallery. False if empty, taken, or the gallery does not exist. */
    public static boolean renameGallery(Context ctx, String id, String name) {
        ensureInitialized(ctx);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return false;
        synchronized (LOCK) {
            for (Gallery g : model.galleries) {
                if (g.name.equals(trimmed) && !g.id.equals(id)) return false;
            }
            Gallery g = findGallery(model, id);
            if (g == null) return false;
            g.name = trimmed;
            save(ctx, model);
            return true;
        }
    }

    /**
     * Deletes a gallery and its files. The default gallery is protected.
     * Selections that pointed at the deleted gallery fall back to the default
     * gallery. Returns false when the gallery is protected or unknown.
     */
    public static boolean deleteGallery(Context ctx, String id) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            Gallery g = findGallery(model, id);
            if (g == null || g.builtin) return false;
            model.galleries.remove(g);
            if (model.wallpaperGallery.equals(id)) model.wallpaperGallery = GALLERY_DEFAULT;
            if (model.popupGallery.equals(id)) model.popupGallery = GALLERY_DEFAULT;
            save(ctx, model);
        }
        deleteRecursive(galleryDir(ctx, id));
        return true;
    }

    // ---------- image operations ----------

    /**
     * Copies picked images into the gallery and registers them. Returns how
     * many were added. Blocking: call from a background thread.
     */
    public static int addImages(Context ctx, String galleryId, List<Uri> uris) {
        ensureInitialized(ctx);
        if (uris == null || uris.isEmpty()) return 0;
        int added = 0;
        synchronized (LOCK) {
            Gallery g = findGallery(model, galleryId);
            if (g == null) return 0;
            File dir = galleryDir(ctx, galleryId);
            if (!dir.exists() && !dir.mkdirs()) return 0;
            for (Uri uri : uris) {
                String name = uniqueFileName(ctx, uri);
                if (name == null) continue;
                File target = new File(dir, name);
                try {
                    if (!copyUri(ctx, uri, target)) continue;
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }
                g.items.add(new Item(name, true));
                added++;
            }
            if (added > 0) save(ctx, model);
        }
        return added;
    }

    /** Deletes one image file and its record. False if unknown. */
    public static boolean deleteItem(Context ctx, String galleryId, String fileName) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            Gallery g = findGallery(model, galleryId);
            if (g == null) return false;
            Item found = null;
            for (Item it : g.items) {
                if (it.fileName.equals(fileName)) {
                    found = it;
                    break;
                }
            }
            if (found == null) return false;
            g.items.remove(found);
            save(ctx, model);
        }
        new File(galleryDir(ctx, galleryId), fileName).delete();
        return true;
    }

    public static void setItemEnabled(Context ctx, String galleryId, String fileName, boolean enabled) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            Gallery g = findGallery(model, galleryId);
            if (g == null) return;
            for (Item it : g.items) {
                if (it.fileName.equals(fileName)) {
                    it.enabled = enabled;
                    break;
                }
            }
            save(ctx, model);
        }
    }

    /** Enables every image in a gallery. */
    public static void enableAllItems(Context ctx, String galleryId) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            Gallery g = findGallery(model, galleryId);
            if (g == null) return;
            for (Item it : g.items) it.enabled = true;
            save(ctx, model);
        }
    }

    // ---------- file helpers ----------

    /** Directory holding a gallery's image files (may not exist yet). */
    public static File galleryDir(Context ctx, String galleryId) {
        return new File(ctx.getFilesDir(), "galleries/" + galleryId);
    }

    public static File imageFile(Context ctx, String galleryId, String fileName) {
        return new File(galleryDir(ctx, galleryId), fileName);
    }

    /** Timestamped, collision-free file name keeping the source extension. */
    @Nullable
    private static String uniqueFileName(Context ctx, Uri uri) {
        String ext = extensionOf(ctx, uri);
        return System.currentTimeMillis() + "_"
                + Long.toHexString(System.nanoTime() & 0xFFFFFFL) + "." + ext;
    }

    private static String extensionOf(Context ctx, Uri uri) {
        String name = queryDisplayName(ctx, uri);
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                if (ext.matches("[a-z0-9]{1,5}")) return ext;
            }
        }
        String type = ctx.getContentResolver().getType(uri);
        if (type != null) {
            if (type.endsWith("/png")) return "png";
            if (type.endsWith("/webp")) return "webp";
        }
        return "jpg";
    }

    @Nullable
    private static String queryDisplayName(Context ctx, Uri uri) {
        if (isFileUri(uri)) {
            // Album browser / share imports may hand over plain file paths.
            String path = uri.getPath();
            if (path == null) return null;
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
            return slash >= 0 && slash < path.length() - 1
                    ? path.substring(slash + 1) : path;
        }
        try (Cursor c = ctx.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean isFileUri(Uri uri) {
        return uri != null && "file".equals(uri.getScheme());
    }

    private static boolean copyUri(Context ctx, Uri uri, File target) throws Exception {
        if (isFileUri(uri)) {
            // Direct file copy: public storage is readable with the photo
            // permission, and this avoids ContentResolver entirely.
            File src = new File(uri.getPath());
            if (!src.isFile()) return false;
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return true;
            }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    // ---------- decoding ----------

    /**
     * Decodes an image file downsampled to about the requested size. Returns
     * null when the file cannot be decoded; the caller must handle that.
     */
    @Nullable
    public static Bitmap decodeSampled(File file, int reqW, int reqH) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        options.inSampleSize = calculateInSampleSize(options, reqW, reqH);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqH || width > reqW) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // ---------- persistence ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void save(Context ctx, Model m) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            JSONArray arr = new JSONArray();
            for (Gallery g : m.galleries) {
                JSONObject gj = new JSONObject();
                gj.put("id", g.id);
                gj.put("name", g.name);
                gj.put("builtin", g.builtin);
                JSONArray items = new JSONArray();
                for (Item it : g.items) {
                    JSONObject ij = new JSONObject();
                    ij.put("f", it.fileName);
                    ij.put("e", it.enabled);
                    items.put(ij);
                }
                gj.put("items", items);
                arr.put(gj);
            }
            root.put("galleries", arr);
            JSONObject sel = new JSONObject();
            sel.put("wallpaper", m.wallpaperGallery);
            sel.put("popup", m.popupGallery);
            root.put("selection", sel);
            prefs(ctx).edit().putString(KEY_STORE, root.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private static void parseInto(Model m, JSONObject root) throws JSONException {
        JSONArray arr = root.getJSONArray("galleries");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject gj = arr.getJSONObject(i);
            Gallery g = new Gallery(gj.getString("id"), gj.getString("name"),
                    gj.optBoolean("builtin", false));
            JSONArray items = gj.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject ij = items.getJSONObject(j);
                    g.items.add(new Item(ij.getString("f"), ij.optBoolean("e", true)));
                }
            }
            m.galleries.add(g);
        }
        JSONObject sel = root.optJSONObject("selection");
        if (sel != null) {
            m.wallpaperGallery = sel.optString("wallpaper", GALLERY_DEFAULT);
            m.popupGallery = sel.optString("popup", GALLERY_DEFAULT);
        }
        if (findGallery(m, GALLERY_DEFAULT) == null) {
            // Should not happen, but never leave the app without a default gallery.
            m.galleries.add(new Gallery(GALLERY_DEFAULT, "默认图库", true));
        }
    }

    /** Test/debug helper: names of all galleries. */
    static Set<String> debugGalleryIds(Context ctx) {
        ensureInitialized(ctx);
        synchronized (LOCK) {
            Set<String> ids = new HashSet<>();
            for (Gallery g : model.galleries) ids.add(g.id);
            return ids;
        }
    }
}
