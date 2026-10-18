package me.ashell.localadb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

/** Bounded, binary shell-v2 screenshots. No Accessibility, MediaProjection or disk files. */
final class LocalAdbScreenshot implements AutoCloseable {
    private static final int MAX_PNG = 16 * 1024 * 1024;
    private static final int MAX_RAW = 24 * 1024 * 1024;
    private boolean pngFallback;
    private final RawCapture raw = new RawCapture();

    static final class Frame {
        final int width, height, sourceWidth, sourceHeight;
        final byte[] jpeg;
        Frame(int width, int height, int sourceWidth, int sourceHeight, byte[] jpeg) {
            this.width = width; this.height = height; this.jpeg = jpeg;
            this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        }
    }

    Frame capture(LocalAdbClient client, AtomicBoolean cancelled) throws Exception {
        if (!pngFallback) {
            try {
                raw.begin();
                readScreenshot(client, cancelled, false, raw, MAX_RAW + 16);
                Bitmap source = raw.bitmap();
                return encode(source, new AdbCaptureProfile(source.getWidth(), source.getHeight()), cancelled);
            } catch (UnsupportedRawFormat ignored) {
                // Vendor pixel/header formats may differ. Probe once per run, then use PNG.
                raw.close();
                pngFallback = true;
            }
        }
        return capturePng(client, cancelled);
    }

    private static void readScreenshot(LocalAdbClient client, AtomicBoolean cancelled, boolean png,
                                       OutputStream output, int limit) throws Exception {
        try (AdbStream stream = client.openMirrorStream("shell,v2,raw:screencap" + (png ? " -p" : ""))) {
            byte[] header = new byte[5];
            byte[] buffer = new byte[32768];
            int stderr = 0;
            int stdout = 0;
            while (true) {
                readFully(stream, header, header.length, cancelled);
                int channel = header[0] & 255;
                int size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (size < 0 || size > limit || channel < 1 || channel > 3) throw new IOException("Invalid screenshot packet");
                if (channel == 3) {
                    if (size != 1) throw new IOException("Invalid screenshot exit");
                    readFully(stream, buffer, 1, cancelled);
                    if (buffer[0] != 0) throw new IOException("System screenshot failed");
                    break;
                }
                if (channel == 1) {
                    if (size > limit - stdout) throw new IOException("Screenshot too large");
                    stdout += size;
                }
                if (channel == 2) {
                    stderr += size;
                    if (stderr > 65536) throw new IOException("Screenshot stderr too large");
                }
                while (size > 0) {
                    int count = Math.min(size, buffer.length);
                    readFully(stream, buffer, count, cancelled);
                    if (channel == 1) output.write(buffer, 0, count);
                    size -= count;
                }
            }
        }
        if (cancelled.get()) throw new IOException("Stopped");
    }

    private static Frame capturePng(LocalAdbClient client, AtomicBoolean cancelled) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        readScreenshot(client, cancelled, true, png, MAX_PNG);
        byte[] bytes = png.toByteArray();
        if (bytes.length < 24 || bytes[0] != (byte) 137 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G')
            throw new IOException("Expected PNG screenshot");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 8192 || options.outHeight > 8192
                || (long) options.outWidth * options.outHeight > 32L * 1024 * 1024) throw new IOException("Invalid screenshot dimensions");
        AdbCaptureProfile profile = new AdbCaptureProfile(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        // Decode near the transmission size, avoiding a full-resolution intermediate.
        while (options.outWidth / (options.inSampleSize * 2) >= profile.width
                && options.outHeight / (options.inSampleSize * 2) >= profile.height) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inScaled = false;
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (source == null) throw new IOException("Screenshot decode failed");
        try { return encode(source, profile, cancelled); }
        finally { source.recycle(); }
    }

    private static Frame encode(Bitmap source, AdbCaptureProfile profile, AtomicBoolean cancelled) throws IOException {
        Bitmap resized = source;
        try {
            int width = profile.width;
            int height = profile.height;
            if (width != source.getWidth() || height != source.getHeight()) resized = Bitmap.createScaledBitmap(source, width, height, true);
            if (cancelled.get()) throw new IOException("Stopped");
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (!resized.compress(Bitmap.CompressFormat.JPEG, profile.encoding()[2], jpeg) || jpeg.size() > 2 * 1024 * 1024)
                throw new IOException("Screenshot encode failed");
            return new Frame(width, height, profile.sourceWidth, profile.sourceHeight, jpeg.toByteArray());
        } finally {
            if (resized != source) resized.recycle();
        }
    }

    @Override public void close() { raw.close(); }

    private static final class UnsupportedRawFormat extends IOException { }

    /** AOSP Android 11+ raw screencap: LE width/height/pixel-format/color-space, packed rows.
     * Reuse the full-size buffer and bitmap for this run; only the scaled JPEG crosses the network. */
    private static final class RawCapture extends OutputStream {
        private final byte[] header = new byte[16];
        private int headerSize, position, width, height, format, color;
        private byte[] pixels;
        private Bitmap source;

        void begin() { headerSize = 0; position = 0; }

        @Override public void write(int value) throws IOException {
            byte[] single = {(byte) value}; write(single, 0, 1);
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (headerSize < 16) {
                int count = Math.min(length, 16 - headerSize);
                System.arraycopy(data, offset, header, headerSize, count);
                headerSize += count; offset += count; length -= count;
                if (headerSize < 16) return;
                ByteBuffer info = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int w = info.getInt(), h = info.getInt(), f = info.getInt(), c = info.getInt();
                int bpp = f == 1 || f == 2 ? 4 : f == 4 ? 2 : 0; // RGBA_8888, RGBX_8888, RGB_565
                long size = (long) w * h * bpp;
                if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || bpp == 0 || size > MAX_RAW || c < 0 || c > 2
                        || (f == 4 && ((w & 1) != 0 || c == 2)))
                    throw new UnsupportedRawFormat();
                if (pixels == null || width != w || height != h || format != f || color != c) {
                    close();
                    pixels = new byte[(int) size];
                    width = w; height = h; format = f; color = c;
                }
            }
            if (length > pixels.length - position) throw new UnsupportedRawFormat();
            System.arraycopy(data, offset, pixels, position, length);
            position += length;
        }

        Bitmap bitmap() throws IOException {
            if (headerSize != 16 || pixels == null || position != pixels.length) throw new UnsupportedRawFormat();
            if (source == null) {
                Bitmap.Config config = format == 4 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                source = Bitmap.createBitmap(width, height, config, false,
                        ColorSpace.get(color == 2 ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB));
            }
            if (source.getByteCount() != pixels.length) throw new UnsupportedRawFormat();
            source.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            return source;
        }

        @Override public void close() {
            if (source != null) source.recycle();
            source = null; pixels = null;
        }
    }

    private static void readFully(AdbStream stream, byte[] buffer, int size, AtomicBoolean cancelled) throws IOException {
        int offset = 0;
        while (offset < size) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Stopped");
            int count = stream.read(buffer, offset, size - offset);
            if (count <= 0) throw new EOFException();
            offset += count;
        }
    }
}
