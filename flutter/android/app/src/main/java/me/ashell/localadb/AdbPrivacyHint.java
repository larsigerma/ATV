package me.ashell.localadb;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Render text in the initialized app process; the shell helper receives pixels only. */
final class AdbPrivacyHint {
    private static final String HINT = "正在对接服务中心\n请勿触碰手机屏幕\n避免影响业务";
    static final int MAX_WIDTH = 1024;
    static final int MAX_HEIGHT = 512;
    static final int MAX_BYTES = 256 * 1024;

    private AdbPrivacyHint() { }

    static byte[] render(Context context) throws IOException {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        AdbCaptureProfile profile = AdbCaptureProfile.current(context);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        // Public built-in family with Android's CJK/other-script fallback. No vendor file paths.
        paint.setTypeface(Typeface.SANS_SERIF);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16.5f, metrics));
        float measured = 0;
        for (String line : HINT.split("\n")) measured = Math.max(measured, paint.measureText(line));
        // Fit either orientation so rotation can reuse the same small pixel buffer.
        int available = Math.max(1, Math.min(MAX_WIDTH,
                Math.min(profile.sourceWidth, profile.sourceHeight) - (int) Math.ceil(100f * metrics.density)));
        int width = Math.max(1, Math.min((int) Math.ceil(measured) + 2, available));
        StaticLayout layout = StaticLayout.Builder.obtain(HINT, 0, HINT.length(), paint, width)
                .setIncludePad(true).build();
        float scale = Math.min(1f, (float) MAX_HEIGHT / Math.max(1, layout.getHeight()));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(width * scale)),
                Math.max(1, (int) Math.ceil(layout.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            layout.draw(canvas);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png) || png.size() > MAX_BYTES)
                throw new IOException("Privacy hint encode failed");
            return png.toByteArray();
        } finally { bitmap.recycle(); }
    }
}
