package me.ashell.localadb;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.WindowManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.muntashirakon.adb.AdbStream;

/** One private shell reader plus app-side drawing; no dumps on disk or dependency on ADB video. */
final class LocalAdbLayout implements AutoCloseable {
    private final Context context;
    private final AdbStream stream;
    private final LocalAdbClient client;
    private final AtomicBoolean cancelled;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object controlLock = new Object();
    private final Thread heartbeat;
    private final DataInputStream input;
    private boolean receivedFrame;
    private long lastRequest;
    private int helperStage;
    private int helperApi = Build.VERSION.SDK_INT;

    LocalAdbLayout(Context context, LocalAdbClient client, AtomicBoolean cancelled) throws Exception {
        this.context = context.getApplicationContext();
        this.client = client;
        this.cancelled = cancelled;
        String apk = context.getApplicationInfo().sourceDir.replace("'", "'\\''");
        stream = client.openMirrorStream("shell,v2,raw:CLASSPATH='" + apk
                + "' exec app_process / me.ashell.localadb.AdbLayoutCapture");
        input = new DataInputStream(new Stdout(stream, cancelled));
        // Synchronous initial lease: a frame request must not overtake this byte.
        control(1);
        heartbeat = new Thread(() -> {
            try {
                while (!closed.get() && !cancelled.get()) {
                    Thread.sleep(1000); control(1);
                }
            } catch (Exception ignored) { }
            client.abort();
        }, "ashell-layout-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void control(int command) throws IOException {
        synchronized (controlLock) {
            if (closed.get() || cancelled.get()) throw new InterruptedIOException("Stopped");
            byte[] packet = {0, 1, 0, 0, 0, (byte) command}; // shell-v2 stdin
            stream.write(packet, 0, packet.length); stream.flush();
        }
    }

    LocalAdbScreenshot.Frame read() throws IOException {
        int appStage = 3;
        try {
            while (!closed.get() && !cancelled.get()) {
                long delay = AdbCaptureProfile.FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastRequest);
                if (lastRequest != 0 && delay > 0) Thread.sleep(delay);
                appStage = 3;
                Geometry before = new Geometry(context);
                appStage = 4;
                control(2); // Only one request can be outstanding on this worker.
                lastRequest = SystemClock.elapsedRealtime();
                AdbLayoutSnapshot snapshot = readSnapshot();
                appStage = 3;
                Geometry after = new Geometry(context);
                if (!before.matches(after)) continue; // Never stretch pre-rotation nodes into a new orientation.
                appStage = 5;
                LocalAdbScreenshot.Frame frame = AdbLayoutRenderer.render(snapshot, after.profile, cancelled);
                appStage = 3;
                if (!after.matches(new Geometry(context))) continue;
                receivedFrame = true;
                return frame;
            }
            throw new InterruptedIOException("Stopped");
        } catch (Failure failure) {
            throw failure;
        } catch (HelperExit exit) {
            throw new Failure("ADB 穿透进程在" + Failure.phase(helperStage) + "阶段退出（exit "
                    + exit.code + (exit.code == 134 ? "，通常为原生 SIGABRT" : "") + " / API " + helperApi + "）。");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Stopped");
        } catch (IOException error) {
            if (appStage == 5) throw new Failure("ADB 穿透节点已收到，但 App 画面编码失败。");
            throw new Failure(receivedFrame ? "ADB 穿透节点数据流已中断，请确认无线调试连接后重试。"
                    : "ADB 穿透首帧前数据流中断（阶段：" + Failure.phase(helperStage) + " / API " + helperApi + "）。");
        } catch (RuntimeException | LinkageError error) {
            throw new Failure(appStage, Build.VERSION.SDK_INT, error.getClass().getSimpleName());
        }
    }

    private AdbLayoutSnapshot readSnapshot() throws IOException {
        int magic;
        while ((magic = input.readInt()) == AdbLayoutSnapshot.PROGRESS) {
            helperStage = input.readInt();
            helperApi = input.readInt();
        }
        if (magic == AdbLayoutSnapshot.ERROR) throw new Failure(input.readInt(), input.readInt(), input.readUTF());
        if (magic != AdbLayoutSnapshot.MAGIC) throw new Failure("ADB 穿透节点格式不匹配，请确认已安装最新 Android 客户端。");
        int size = input.readInt();
        if (size < 4 || size > AdbLayoutSnapshot.MAX_BYTES) throw new Failure("ADB 穿透节点数据长度无效。");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        try {
            return AdbLayoutSnapshot.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException malformed) {
            throw new Failure("ADB 穿透节点数据不完整或无效。");
        }
    }

    /** Geometry is obtained in the real app context, without starting any screen capture. */
    private static final class Geometry {
        final AdbCaptureProfile profile;
        final int rotation;
        @SuppressWarnings("deprecation")
        Geometry(Context context) {
            profile = AdbCaptureProfile.current(context);
            rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        }
        boolean matches(Geometry other) {
            return profile.sourceWidth == other.profile.sourceWidth && profile.sourceHeight == other.profile.sourceHeight
                    && rotation == other.rotation;
        }
    }

    static final class Failure extends IOException {
        Failure(String message) { super(message); }
        Failure(int stage, int api, String type) {
            super("ADB 穿透" + phase(stage) + "失败（API " + api + " / " + safeType(type) + "）。");
        }
        static String phase(int stage) {
            return stage == 1 ? "启动" : stage == 2 ? "连接节点服务" : stage == 3 ? "读取屏幕尺寸"
                    : stage == 4 ? "读取节点" : stage == 5 ? "App 绘图" : stage == 6 ? "发送节点" : "进入节点程序前";
        }
        private static String safeType(String type) {
            return type.length() <= 80 && type.matches("[A-Za-z0-9_$]+") ? type : "FrameworkError";
        }
    }

    private static final class HelperExit extends IOException {
        final int code;
        HelperExit(int code) { this.code = code; }
    }

    @Override public void close() {
        closed.set(true); heartbeat.interrupt(); client.abort();
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static final class Stdout extends InputStream {
        private final AdbStream stream;
        private final AtomicBoolean cancelled;
        private final byte[] header = new byte[5], discard = new byte[4096];
        private int remaining;
        Stdout(AdbStream stream, AtomicBoolean cancelled) { this.stream = stream; this.cancelled = cancelled; }
        private void fully(byte[] bytes) throws IOException {
            int offset = 0;
            while (offset < bytes.length) {
                if (cancelled.get()) throw new EOFException();
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count <= 0) throw new EOFException();
                offset += count;
            }
        }
        @Override public int read() throws IOException {
            byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (remaining == 0) {
                fully(header);
                int channel = header[0] & 255;
                int size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (size < 0 || size > 1048576 || channel < 1 || channel > 3)
                    throw new Failure("ADB 穿透 Shell 数据包无效。");
                if (channel == 3) {
                    if (size != 1) throw new Failure("ADB 穿透 Shell 退出包无效。");
                    byte[] exit = new byte[1]; fully(exit);
                    throw new HelperExit(exit[0] & 255);
                }
                if (channel == 1) { remaining = size; continue; }
                // Drain diagnostics separately; never decode stderr as node content.
                while (size > 0) {
                    if (cancelled.get()) throw new EOFException();
                    int count = stream.read(discard, 0, Math.min(discard.length, size));
                    if (count <= 0) throw new EOFException();
                    size -= count;
                }
            }
            if (cancelled.get()) throw new EOFException();
            int count = stream.read(bytes, offset, Math.min(remaining, length));
            if (count <= 0) throw new EOFException();
            remaining -= count; return count;
        }
    }
}
