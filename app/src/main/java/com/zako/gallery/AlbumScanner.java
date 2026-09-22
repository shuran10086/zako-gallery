package com.zako.gallery;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans shared storage for image folders ("albums") by walking the file
 * system directly.
 *
 * <p>The system photo picker only lists folders the media scanner has
 * indexed, which hides the folders many apps keep for their own pictures
 * (WeChat, QQ, forums, ...). Walking the file system instead surfaces every
 * folder that actually holds images, matching what the OEM gallery shows.
 * Reading public files needs the photo permission, which the app requests
 * before opening the browser; an Android 14 partial grant simply surfaces
 * fewer files.</p>
 *
 * <p>Blocking: call off the main thread.</p>
 *
 * <p>Besides the top-level folders, {@code Pictures/Gallery/owner/} gets a
 * dedicated pass: each folder below it is one user-created album, named after
 * the folder, because gallery apps store new albums there.</p>
 */
public final class AlbumScanner {

    /** One album: a display name plus its image files, newest first. */
    public static final class Album {
        public final String name;
        public final List<File> files = new ArrayList<>();

        /** True for the synthetic "recent" album built by the caller. */
        public boolean recent;

        Album(String name) {
            this.name = name;
        }
    }

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"));

    /** Top-level folders worth scanning; the rest of /sdcard is noise. */
    private static final List<String> TOP_DIRS = Arrays.asList(
            "DCIM", "Pictures", "Download", "Documents");

    /** Directories never worth entering. */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "Android", ".thumbnails", ".thumbdata", ".auxiliary", ".trashed",
            "cache", "Cache", ".cache"));

    /** Depth below a top-level folder; keeps deep app junk out. */
    private static final int MAX_DEPTH = 3;

    /**
     * Path below the storage root where gallery apps create one folder per
     * user album: {@code Pictures/Gallery/owner/<album>/image.jpg}. Each child
     * folder of {@code owner} is treated as its own album.
     */
    private static final String OWNER_ALBUM_PATH =
            "Pictures" + File.separator + "Gallery" + File.separator + "owner";

    /** How deep images may sit inside one of those album folders. */
    private static final int OWNER_ALBUM_DEPTH = 4;

    private AlbumScanner() {
    }

    /** Blocking scan of shared storage. Albums with no images are not returned. */
    public static List<Album> scan() {
        List<Album> albums = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.isDirectory()) return albums;
        for (File top : safeListFiles(root)) {
            if (!top.isDirectory()) continue;
            if (!TOP_DIRS.contains(top.getName())) continue;
            walk(top, top, 1, albums);
        }
        // The owner-album tree sits below MAX_DEPTH and needs its own pass.
        scanOwnerAlbums(root, albums);
        for (Album a : albums) {
            Collections.sort(a.files,
                    (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        }
        Collections.sort(albums, (x, y) -> Long.compare(newest(y), newest(x)));
        return albums;
    }

    /**
     * Scans {@code <storage>/Pictures/Gallery/owner/}: every child folder is
     * one album named after it (a user album "abc" holding 1.jpg shows up as
     * album "abc"). Images may sit a few levels below the album folder.
     */
    private static void scanOwnerAlbums(File root, List<Album> albums) {
        File owner = new File(root, OWNER_ALBUM_PATH);
        if (!owner.isDirectory()) return;
        for (File child : safeListFiles(owner)) {
            if (!child.isDirectory()) continue;
            String n = child.getName();
            if (n.startsWith(".") || SKIP_DIRS.contains(n)) continue;
            // Raw folder name: the user picked it and expects to see it.
            collectImages(child, findOrCreateByName(albums, n), 1);
        }
    }

    /** Recursively adds every image below an album folder to that album. */
    private static void collectImages(File dir, Album album, int depth) {
        if (depth > OWNER_ALBUM_DEPTH) return;
        for (File f : safeListFiles(dir)) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".") || SKIP_DIRS.contains(n)) continue;
                collectImages(f, album, depth + 1);
            } else if (isImage(f)) {
                album.files.add(f);
            }
        }
    }

    /** Newest images across all albums, newest first, capped at {@code limit}. */
    public static List<File> recentFiles(List<Album> albums, int limit) {
        List<File> all = new ArrayList<>();
        for (Album a : albums) all.addAll(a.files);
        Collections.sort(all, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    private static void walk(File dir, File top, int depth, List<Album> albums) {
        for (File f : safeListFiles(dir)) {
            if (f.isDirectory()) {
                if (depth >= MAX_DEPTH) continue;
                String n = f.getName();
                if (n.startsWith(".") || SKIP_DIRS.contains(n)) continue;
                walk(f, top, depth + 1, albums);
            } else if (isImage(f)) {
                findOrCreate(albums, albumDirFor(dir, top), top).files.add(f);
            }
        }
    }

    /**
     * The folder an image is grouped under: the first level below the scanned
     * top-level folder, or the top-level folder itself for loose files.
     */
    private static File albumDirFor(File dir, File top) {
        if (dir.equals(top)) return top;
        File cur = dir;
        while (cur.getParentFile() != null && !cur.getParentFile().equals(top)) {
            cur = cur.getParentFile();
        }
        return cur;
    }

    /**
     * Finds the album for a folder, creating it on first sight. Albums are
     * matched by display name, so the same app folder under DCIM and Pictures
     * merges into one entry.
     */
    private static Album findOrCreate(List<Album> albums, File albumDir, File top) {
        return findOrCreateByName(albums, displayName(albumDir.equals(top)
                ? top.getName() : albumDir.getName()));
    }

    /** Finds the album with this display name, creating it on first sight. */
    private static Album findOrCreateByName(List<Album> albums, String name) {
        for (Album a : albums) {
            if (a.name.equals(name)) return a;
        }
        Album a = new Album(name);
        albums.add(a);
        return a;
    }

    /** Friendly names for the folders that show up on most devices. */
    private static String displayName(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "camera":
            case "100media":
            case "100andro":
                return "相机";
            case "screenshots":
            case "screenshot":
                return "屏幕截图";
            case "download":
                return "下载内容";
            case "weixin":
            case "wechat":
                return "微信";
            case "pictures":
                return "图片";
            case "dcim":
                return "相机";
            case "documents":
                return "文档";
            default:
                return raw;
        }
    }

    private static long newest(Album a) {
        return a.files.isEmpty() ? 0L : a.files.get(0).lastModified();
    }

    private static boolean isImage(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return IMAGE_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static File[] safeListFiles(File dir) {
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }
}
