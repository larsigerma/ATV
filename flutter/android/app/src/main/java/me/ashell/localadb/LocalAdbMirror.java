package me.ashell.localadb;

import android.content.Context;
import android.os.SystemClock;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

/** One device-owned video or screenshot source, isolated from shared shell and product capture. */
final class LocalAdbMirror {
    interface ShellReady { boolean get(); }
    private static final String ASSET = "adb-mirror/scrcpy-server-v5.0.1";
    private static final String HASH = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536";
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private final Context context;
    private final ShellReady shellReady;
    private final ScheduledExecutorService guard = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "ashell-adb-video-guard"));
    private Run active;
    private long generation;
    private boolean nativeReady;
    private String mode = "h264";
    private int pendingPort;
    private String pendingMode;
    private String phase = "idle";

    private static native void register(LocalAdbManager manager);
    private static native void state(long generation, String phase, String error, String format);
    private static native void frame(long generation, int width, int height,
                                     boolean config, boolean keyframe, byte[] data, boolean screenshot, int sourceWidth, int sourceHeight);

    LocalAdbMirror(Context context, ShellReady shellReady) {
        this.context = context.getApplicationContext();
        this.shellReady = shellReady;
    }

    synchronized void attach(LocalAdbManager manager) {
        try {
            System.loadLibrary("ashell");
            register(manager);
            nativeReady = true;
        } catch (LinkageError ignored) { }
    }

    synchronized void unavailable() {
        publish(active == null ? "error" : phase, "请先完成 ADB 连接并验证 Shell，等待当前 ADB 操作结束。");
    }

    synchronized void start(int port, String mode) {
        if (!nativeReady) return;
        if (!shellReady.get() || port <= 0 || port > 65535) { unavailable(); return; }
        if (active != null) {
            if (!active.cancelled.get() && active.mode.equals(mode)) { publish(phase, ""); return; }
            pendingPort = port;
            pendingMode = mode;
            stopCurrent("");
            return;
        }
        this.mode = mode;
        generation++;
        Run run;
        try { run = new Run(generation, port, mode); }
        catch (RuntimeException e) { publish("error", "无法读取安卓屏幕尺寸，请稍后重试。"); return; }
        active = run;
        publish("starting", "");
        run.watchdog = guard.scheduleWithFixedDelay(() -> checkHealth(run), 1, 1, TimeUnit.SECONDS);
        run.thread = new Thread(() -> capture(run), "ashell-adb-video");
        run.thread.setDaemon(true);
        run.thread.start();
    }

    synchronized void stop(String reason) {
        pendingPort = 0;
        stopCurrent(reason);
    }

    synchronized void stopMode(String target) {
        // A delayed close from another PC must not tear down a different source.
        // Preserve a replacement mode already requested while the old one exits.
        if (pendingPort > 0 && target.equals(pendingMode)) pendingPort = 0;
        if (active != null && target.equals(active.mode)) stopCurrent("");
        else publish(phase, "");
    }

    private void stopCurrent(String reason) {
        Run run = active;
        if (run == null) { publish("idle", reason); return; }
        if (!run.cancelled.compareAndSet(false, true)) { publish("stopping", run.failure); return; }
        run.stopAt = SystemClock.elapsedRealtime();
        run.failure = reason;
        publish("stopping", reason);
        // Abort only this private ADB transport; its close unblocks every pending stream.
        run.client.abort();
        if (run.thread != null) run.thread.interrupt();
    }

    private synchronized void checkHealth(Run run) {
        if (active != run) return;
        if (run.cancelled.get()) {
            if (SystemClock.elapsedRealtime() - run.stopAt >= 3000) finish(run);
            return;
        }
        if (!shellReady.get()) stop("ADB Shell 已断开，投屏已停止。");
        else if (SystemClock.elapsedRealtime() - active.lastPacket > 20000)
            stop("ADB 投屏启动或画面接收超时，请重试。");
        else if ("h264".equals(run.mode)) {
            try {
                if (run.profile.maxSize() != AdbCaptureProfile.current(context).maxSize()) {
                    // Ordinary capture recalculates its integer divisor on rotation; do the same independently.
                    pendingPort = run.port; pendingMode = run.mode; stopCurrent("");
                }
            } catch (RuntimeException e) { stop("屏幕尺寸读取失败，请重新开启 ADB 画面。"); }
        }
    }

    private void publish(String phase, String error) {
        this.phase = phase;
        if (nativeReady) state(generation, phase, error, mode);
    }

    private synchronized void finish(Run run) {
        if (run.watchdog != null) run.watchdog.cancel(false);
        if (active != run) return;
        active = null;
        publish(run.failure.isEmpty() ? "idle" : "error", run.failure);
        if (pendingPort > 0) {
            int port = pendingPort;
            String mode = pendingMode;
            pendingPort = 0;
            start(port, mode);
        }
    }

    private void capture(Run run) {
        try {
            run.client.connect(run.port, run.cancelled);
            if (!"h264".equals(run.mode)) { captureScreenshots(run); return; }
            int[] encoding = run.profile.encoding();
            String scid = String.format(Locale.US, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
            // One bounded module cache: an old EXIT trap must never remove a replacement's jar.
            String path = "/data/local/tmp/ashell-scrcpy-5.0.1.jar";
            push(context, run.client, run.cancelled, path);
            // All arguments are fixed or generated locally. No peer-controlled shell interpolation.
            String command = "CLASSPATH=" + path + " app_process / com.genymobile.scrcpy.Server 5.0.1"
                    + " scid=" + scid + " tunnel_forward=true audio=false control=false"
                    + " video_codec=h264 max_size=" + run.profile.maxSize() + " max_fps=" + encoding[1]
                    + " video_bit_rate=" + encoding[0]
                    + " video_codec_options=i-frame-interval=1,repeat-previous-frame-after:long=100000"
                    + " send_device_meta=false send_dummy_byte=false power_on=false"
                    + " cleanup=true log_level=error";
            try (AdbStream process = run.client.openMirrorStream("shell,v2,raw:sh")) {
                byte[] script = (command + "\n").getBytes(StandardCharsets.US_ASCII);
                byte[] input = ByteBuffer.allocate(script.length + 10).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 0).putInt(script.length).put(script).put((byte) 4).putInt(0).array();
                process.write(input, 0, input.length);
                process.flush();
                Thread output = new Thread(() -> drainProcess(run, process), "ashell-scrcpy-output");
                output.setDaemon(true);
                output.start();
                AdbStream video = null;
                long deadline = SystemClock.elapsedRealtime() + 10000;
                while (!run.cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                    try { video = run.client.openMirrorStream("localabstract:scrcpy_" + scid); break; }
                    catch (IOException e) { Thread.sleep(150); }
                }
                if (video == null) throw new IOException("Server socket unavailable");
                try (AdbStream stream = video) { readVideo(run, stream); }
                finally { output.interrupt(); }
            }
        } catch (Exception | LinkageError e) {
            if (!run.cancelled.get()) run.failure = e instanceof LocalAdbLayout.Failure ? e.getMessage() : "layout".equals(run.mode)
                    ? "ADB 穿透独立 Shell 通道建立失败，请确认无线调试及 ADB Shell 连接后重试。"
                    : "jpeg".equals(run.mode) ? "ADB 系统截图失败，请确认无线调试及 Shell 连接。系统保护内容仍受截图限制。"
                    : "ADB 投屏失败，请确认无线调试仍开启并重新连接。设备编码器可能不支持当前采集。";
        } catch (OutOfMemoryError e) {
            run.failure = "ADB 画面内存不足，已停止本次采集。";
        } finally {
            // Clear interruption before joining the private ADB reader during close.
            Thread.interrupted();
            run.client.disconnect();
            finish(run);
        }
    }

    private void captureScreenshots(Run run) throws Exception {
        boolean streaming = false;
        byte[] previous = null;
        long lastSent = 0;
        try (LocalAdbScreenshot capture = new LocalAdbScreenshot();
             LocalAdbLayout layout = "layout".equals(run.mode) ? new LocalAdbLayout(context, run.client, run.cancelled) : null) {
          while (!run.cancelled.get()) {
            long started = SystemClock.elapsedRealtime();
            LocalAdbScreenshot.Frame screenshot = layout == null ? capture.capture(run.client, run.cancelled) : layout.read();
            synchronized (this) {
                if (active != run || run.cancelled.get()) return;
                run.lastPacket = SystemClock.elapsedRealtime();
                if (!streaming) { publish("streaming", ""); streaming = true; }
                // Static screens need no repeated payload; a one-second key frame admits new viewers.
                if (!java.util.Arrays.equals(previous, screenshot.jpeg) || started - lastSent >= 1000) {
                    frame(run.id, screenshot.width, screenshot.height, false, true, screenshot.jpeg, true,
                            screenshot.sourceWidth, screenshot.sourceHeight);
                    previous = screenshot.jpeg; lastSent = started;
                }
            }
            if (layout == null) {
                AdbCaptureProfile profile = new AdbCaptureProfile(screenshot.sourceWidth, screenshot.sourceHeight);
                long interval = profile.screenshotIntervalMs();
                Thread.sleep(Math.max(1, interval - (SystemClock.elapsedRealtime() - started)));
            }
          }
        }
    }

    private void drainProcess(Run run, AdbStream stream) {
        try {
            byte[] header = new byte[5];
            byte[] discard = new byte[4096];
            while (!run.cancelled.get()) {
                readFully(run, stream, header);
                int channel = header[0] & 255;
                int size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (size < 0 || size > 65536 || channel < 1 || channel > 3) throw new IOException();
                while (size > 0) {
                    int count = stream.read(discard, 0, Math.min(size, discard.length));
                    if (count <= 0) throw new EOFException();
                    size -= count;
                }
                if (channel == 3) break;
            }
        } catch (Exception ignored) { }
        synchronized (this) {
            if (active == run && !run.cancelled.get()) stop("scrcpy 服务端已退出，请重新开启投屏。");
        }
    }

    private void readVideo(Run run, AdbStream stream) throws Exception {
        byte[] codec = new byte[4];
        readFully(run, stream, codec);
        if (ByteBuffer.wrap(codec).getInt() != 0x68323634) throw new IOException("Expected H264");
        byte[] header = new byte[12];
        int width = 0, height = 0;
        boolean streaming = false;
        while (!run.cancelled.get()) {
            readFully(run, stream, header);
            ByteBuffer meta = ByteBuffer.wrap(header); // scrcpy 5.0.1, big-endian
            long flags = meta.getLong();
            int size = meta.getInt();
            if ((flags & Long.MIN_VALUE) != 0) {
                width = ByteBuffer.wrap(header, 4, 4).getInt();
                height = size;
                if (width <= 0 || height <= 0 || width > 1280 || height > 1280) throw new IOException("Invalid size");
                continue;
            }
            if (width == 0 || size <= 0 || size > MAX_PACKET) throw new IOException("Invalid packet");
            byte[] data = new byte[size];
            readFully(run, stream, data);
            run.lastPacket = SystemClock.elapsedRealtime();
            boolean config = (flags & (1L << 62)) != 0;
            boolean key = (flags & (1L << 61)) != 0;
            synchronized (this) {
                if (active != run || run.cancelled.get()) return;
                if (!streaming && !config) { publish("streaming", ""); streaming = true; }
                AdbCaptureProfile profile = AdbCaptureProfile.current(context);
                if ((width > height) != (profile.sourceWidth > profile.sourceHeight)) continue;
                frame(run.id, width, height, config, key, data, false, profile.sourceWidth, profile.sourceHeight);
            }
        }
    }

    static void push(Context context, LocalAdbClient client, AtomicBoolean cancelled, String path) throws Exception {
        // Verify the packaged binary before sending it to the shell-owned temp directory.
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(ASSET)) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) != -1) hash.update(chunk, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(Locale.US, "%02x", b & 255));
        if (!HASH.contentEquals(hex)) throw new IOException("Server checksum mismatch");
        try (AdbStream sync = client.openMirrorStream("sync:"); InputStream in = context.getAssets().open(ASSET)) {
            byte[] destination = (path + ",33152").getBytes(StandardCharsets.US_ASCII); // regular file 0600
            writeSync(sync, "SEND", destination, destination.length);
            byte[] chunk = new byte[32768];
            int count;
            while ((count = in.read(chunk)) != -1) {
                if (cancelled.get()) throw new IOException("Stopped");
                writeSync(sync, "DATA", chunk, count);
            }
            byte[] done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put("DONE".getBytes(StandardCharsets.US_ASCII)).putInt((int) (System.currentTimeMillis() / 1000)).array();
            sync.write(done, 0, done.length);
            sync.flush();
            byte[] reply = new byte[8];
            int offset = 0;
            while (offset < reply.length) {
                if (cancelled.get()) throw new IOException("Stopped");
                int countRead = sync.read(reply, offset, reply.length - offset);
                if (countRead <= 0) throw new EOFException();
                offset += countRead;
            }
            if (reply[0] != 'O' || reply[1] != 'K' || reply[2] != 'A' || reply[3] != 'Y') throw new IOException("Push failed");
        }
    }

    private static void writeSync(AdbStream stream, String id, byte[] data, int size) throws IOException {
        byte[] packet = ByteBuffer.allocate(size + 8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(StandardCharsets.US_ASCII)).putInt(size).put(data, 0, size).array();
        stream.write(packet, 0, packet.length);
        stream.flush();
    }

    private static void readFully(Run run, AdbStream stream, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            if (run.cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Stopped");
            int count = stream.read(bytes, offset, bytes.length - offset);
            if (count <= 0) throw new EOFException();
            offset += count;
        }
    }

    private final class Run {
        final long id;
        final int port;
        final String mode;
        final AdbCaptureProfile profile = AdbCaptureProfile.current(context);
        final LocalAdbClient client = new LocalAdbClient(context, true);
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile long lastPacket = SystemClock.elapsedRealtime();
        volatile String failure = "";
        long stopAt;
        Thread thread;
        ScheduledFuture<?> watchdog;
        Run(long id, int port, String mode) { this.id = id; this.port = port; this.mode = mode; }
    }
}
