package dev.framelink.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.nio.ByteBuffer;
import java.util.HashMap;

/** Trim MP4 without recompression; video start is aligned to an earlier keyframe. */
public final class VideoTrimmer {
    private VideoTrimmer() {}

    public static Uri trim(Context context, Uri source, long startMs, long endMs) throws Exception {
        if (source == null || startMs < 0 || endMs <= startMs)
            throw new IllegalArgumentException("End must be greater than start.");
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "FrameLink-clip-" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FrameLink");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IllegalStateException("Cannot create output video.");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor fd = null;
        boolean started = false, success = false;
        try {
            extractor.setDataSource(context, source, null);
            fd = resolver.openFileDescriptor(destination, "rw");
            if (fd == null) throw new IllegalStateException("Cannot open output video.");
            muxer = new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            HashMap<Integer, Integer> tracks = new HashMap<>();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                tracks.put(i, muxer.addTrack(format));
                extractor.selectTrack(i);
            }
            if (tracks.isEmpty()) throw new IllegalStateException("No MP4 video/audio tracks found.");
            long startUs = startMs * 1000L, endUs = endMs * 1000L;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long alignedStart = extractor.getSampleTime();
            if (alignedStart < 0) throw new IllegalStateException("Clip start is past video duration.");
            ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            muxer.start();
            started = true;
            long samples = 0;
            while (true) {
                int inTrack = extractor.getSampleTrackIndex();
                if (inTrack < 0) break;
                long t = extractor.getSampleTime();
                if (t < 0 || t >= endUs) break;
                Integer outTrack = tracks.get(inTrack);
                if (outTrack != null && t >= alignedStart) {
                    buffer.clear();
                    int len = extractor.readSampleData(buffer, 0);
                    if (len < 0) break;
                    info.offset = 0;
                    info.size = len;
                    info.presentationTimeUs = Math.max(0, t - alignedStart);
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(outTrack, buffer, info);
                    samples++;
                }
                if (!extractor.advance()) break;
            }
            if (samples == 0) throw new IllegalStateException("No video samples in selected range.");
            muxer.stop();
            started = false;
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(destination, values, null, null);
            success = true;
            return destination;
        } finally {
            if (muxer != null) {
                if (started) { try { muxer.stop(); } catch (Exception ignored) {} }
                muxer.release();
            }
            extractor.release();
            if (fd != null) fd.close();
            if (!success) resolver.delete(destination, null, null);
        }
    }
}
