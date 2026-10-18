package me.ashell.localadb;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-side software renderer. Its buffers never enter the product Accessibility/MediaProjection pipeline. */
final class AdbLayoutRenderer {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect screen;
    private final float textSize;

    private AdbLayoutRenderer(AdbCaptureProfile profile) {
        screen = new Rect(0, 0, profile.sourceWidth, profile.sourceHeight);
        float pixel = (float) profile.sourceWidth / profile.width;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(pixel);
        text.setColor(Color.WHITE);
        // Only the app renders text. Use the public built-in family and its script fallback.
        text.setTypeface(Typeface.SANS_SERIF);
        textSize = Math.max(24f, 11f * pixel);
    }

    static LocalAdbScreenshot.Frame render(AdbLayoutSnapshot snapshot, AdbCaptureProfile profile,
                                          AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) throw new InterruptedIOException("Stopped");
        Bitmap bitmap = Bitmap.createBitmap(profile.width, profile.height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            canvas.scale((float) profile.width / profile.sourceWidth, (float) profile.height / profile.sourceHeight);
            AdbLayoutRenderer renderer = new AdbLayoutRenderer(profile);
            int drawn = 0;
            for (AdbLayoutSnapshot.Node node : snapshot.nodes) {
                if (cancelled.get()) throw new InterruptedIOException("Stopped");
                Rect bounds = new Rect(node.left, node.top, node.right, node.bottom);
                if (!bounds.intersect(renderer.screen)) continue;
                renderer.outline.setColor(node.clickable ? Color.GREEN : Color.LTGRAY);
                canvas.drawRect(bounds, renderer.outline);
                drawn++;
                if (!node.text.isEmpty()) renderer.drawText(canvas, bounds, node.text);
            }
            if (drawn == 0) renderer.drawText(canvas, renderer.screen,
                    "当前界面未提供可绘制的布局节点\n请切换到系统设置等标准界面确认");
            if (cancelled.get()) throw new InterruptedIOException("Stopped");
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, profile.encoding()[2], jpeg)
                    || jpeg.size() > 2 * 1024 * 1024) throw new IOException("Layout encode failed");
            return new LocalAdbScreenshot.Frame(profile.width, profile.height,
                    profile.sourceWidth, profile.sourceHeight, jpeg.toByteArray());
        } finally { bitmap.recycle(); }
    }

    private void drawText(Canvas canvas, Rect bounds, String label) {
        if (bounds.width() <= 2 || bounds.height() <= 2) return;
        text.setTextSize(textSize);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float available = bounds.height() - 2f;
        float lineHeight = metrics.descent - metrics.ascent;
        if (lineHeight > available) text.setTextSize(textSize * available / lineHeight);
        metrics = text.getFontMetrics();
        int lines = Math.max(1, Math.min(8, (int) (available / (metrics.descent - metrics.ascent))));
        // Color only actual digits; keep the same shaping, wrapping and bounds for mixed text.
        SpannableString styled = new SpannableString(label);
        int start = -1;
        for (int i = 0; i < label.length();) {
            int codePoint = label.codePointAt(i);
            boolean digit = Character.isDigit(codePoint);
            if (digit && start < 0) start = i;
            if (!digit && start >= 0) {
                styled.setSpan(new ForegroundColorSpan(Color.RED), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = -1;
            }
            i += Character.charCount(codePoint);
        }
        if (start >= 0) styled.setSpan(new ForegroundColorSpan(Color.RED), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        StaticLayout layout = StaticLayout.Builder.obtain(styled, 0, styled.length(), text, bounds.width() - 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        int saved = canvas.save();
        try {
            canvas.clipRect(bounds);
            canvas.translate(bounds.left + 1, bounds.top + 1);
            layout.draw(canvas);
        } finally { canvas.restoreToCount(saved); }
    }
}
