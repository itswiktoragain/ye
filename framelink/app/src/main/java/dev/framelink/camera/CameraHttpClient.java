package dev.framelink.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GoPro local HTTP media endpoints. All methods are blocking; use a worker. */
public final class CameraHttpClient {
    public static final class MediaFile {
        public final String directory, name;
        public final long size;
        MediaFile(String directory, String name, long size) {
            this.directory = directory;
            this.name = name;
            this.size = size;
        }
        public boolean video() {
            String ext = name.toLowerCase(Locale.ROOT);
            return ext.endsWith(".mp4") || ext.endsWith(".lrv");
        }
        public String displaySize() {
            if (size < 0) return "Unknown size";
            if (size < 1048576) return String.format(Locale.ROOT, "%.0f KB", size / 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", size / 1048576.0);
        }
    }

    public interface Progress { void onProgress(long received, long total); }

    private static final String BASE = "http://10.5.5.9:8080";
    private final Context context;
    private final CameraWifi wifi;

    public CameraHttpClient(Context context, CameraWifi wifi) {
        this.context = context.getApplicationContext();
        this.wifi = wifi;
    }

    private HttpURLConnection open(String path) throws IOException {
        URL url = new URL(BASE + path);
        Network network = wifi.getNetwork();
        HttpURLConnection c = (HttpURLConnection)
                (network == null ? url.openConnection() : network.openConnection(url));
        c.setConnectTimeout(6500);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("GoPro HTTP " + code);
        }
        return c;
    }

    private String readText(String path) throws Exception {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() > 3_000_000) throw new IOException("Unexpectedly large media listing.");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }

    public List<MediaFile> listMedia() throws Exception {
        JSONObject response = new JSONObject(readText("/gopro/media/list"));
        JSONArray directories = response.optJSONArray("media");
        ArrayList<MediaFile> files = new ArrayList<>();
        if (directories == null) return files;
        for (int i = 0; i < directories.length(); i++) {
            JSONObject dir = directories.optJSONObject(i);
            if (dir == null) continue;
            String path = dir.optString("d", "");
            JSONArray entries = dir.optJSONArray("fs");
            if (entries == null || path.isEmpty()) continue;
            for (int j = 0; j < entries.length(); j++) {
                JSONObject item = entries.optJSONObject(j);
                if (item == null) continue;
                String name = item.optString("n", "");
                if (name.isEmpty()) continue;
                files.add(new MediaFile(path, name, item.optLong("s", -1)));
            }
        }
        return files;
    }

    private String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String segment(String value) {
        return escape(value).replace("%2F", "/");
    }

    public Bitmap getThumbnail(MediaFile file) throws Exception {
        String path = "/gopro/media/thumbnail?path=" + escape(file.directory + "/" + file.name);
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeStream(in, null, options);
        } finally { c.disconnect(); }
    }

    public Uri download(MediaFile file, Progress progress) throws Exception {
        String safe = file.name.replaceAll("[^A-Za-z0-9._-]", "_");
        String lower = safe.toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".mp4") || lower.endsWith(".lrv") ? "video/mp4"
            : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
            : lower.endsWith(".png") ? "image/png" : "application/octet-stream";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FrameLink");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("Could not create file in Downloads.");
        boolean success = false;
        try {
            String path = "/videos/DCIM/" + segment(file.directory) + "/" + segment(file.name);
            HttpURLConnection c = open(path);
            try (InputStream in = c.getInputStream();
                 OutputStream out = resolver.openOutputStream(destination)) {
                if (out == null) throw new IOException("Could not open destination.");
                long count = 0;
                long total = c.getContentLengthLong();
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    count += n;
                    if (progress != null) progress.onProgress(count, total);
                }
            } finally { c.disconnect(); }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(destination, values, null, null);
            success = true;
            return destination;
        } finally {
            if (!success) resolver.delete(destination, null, null);
        }
    }
}
