package me.ashell.localadb;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.view.WindowManager;

/** Read-only counterpart of MainService's width / 350 integer scaling. */
final class AdbCaptureProfile {
    static final int MAX_EDGE = 1280;
    static final int JPEG_QUALITY = 75;
    static final int FRAME_INTERVAL_MS = 500;
    // Same capture cadence as the ordinary Accessibility screenshot loop (300 ms).
    private static final int SCREENSHOT_INTERVAL_MS = 300;
    final int sourceWidth, sourceHeight, width, height;

    AdbCaptureProfile(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceWidth > 16384 || sourceHeight > 16384)
            throw new IllegalArgumentException("Invalid display size");
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        int divisor = Math.max(1, sourceWidth / 350);
        double scale = Math.min(1.0 / divisor, (double) MAX_EDGE / Math.max(sourceWidth, sourceHeight));
        width = Math.max(1, (int) (sourceWidth * scale));
        height = Math.max(1, (int) (sourceHeight * scale));
    }

    @SuppressWarnings("deprecation")
    static AdbCaptureProfile current(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            return new AdbCaptureProfile(bounds.width(), bounds.height());
        }
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        return new AdbCaptureProfile(size.x, size.y);
    }

    int maxSize() { return Math.max(8, Math.max(width, height) & ~7); }

    long screenshotIntervalMs() {
        int[] settings = encoding();
        int fps = Math.max(1, settings[1]);
        // JPEG is intra-frame; applying the H.264 bitrate budget here made detailed
        // screens wait up to two seconds. Slow viewers are bounded by their ACK window.
        return Math.max(SCREENSHOT_INTERVAL_MS, (1000L + fps - 1) / fps);
    }
    private static native int[] quality(int width, int height);

    int[] encoding() {
        // Read the ordinary stream's selected quality/FPS without changing its QoS state.
        try {
            int[] value = quality(width, height);
            if (value != null && value.length == 3) return value;
        } catch (LinkageError ignored) { }
        return new int[]{Math.max(200000, width * height * 2), 15, JPEG_QUALITY};
    }
}
