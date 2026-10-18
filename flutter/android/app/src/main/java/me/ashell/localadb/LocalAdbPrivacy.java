package me.ashell.localadb;

import android.content.Context;
import android.os.SystemClock;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

/** Independent ADB-owned overlay. No Accessibility, screen power or capture service changes. */
final class LocalAdbPrivacy {
    private final Context context;
    private final LocalAdbMirror.ShellReady shellReady;
    private Run active;
    private String phase = "idle";
    private String error = "";
    private int pendingPort;
    private static native void state(String phase, String error);

    LocalAdbPrivacy(Context context, LocalAdbMirror.ShellReady shellReady) {
        this.context = context.getApplicationContext();
        this.shellReady = shellReady;
    }

    synchronized void start(int port) {
        if (active != null) {
            if (active.cancelled.get()) pendingPort = port;
            publish(phase, error);
            return;
        }
        if (!shellReady.get() || port <= 0 || port > 65535) {
            publish("error", "请先连接 ADB 并验证 Shell。"); return;
        }
        Run run = new Run(port);
        active = run;
        publish("starting", "");
        run.thread = new Thread(() -> control(run), "ashell-adb-privacy");
        run.thread.setDaemon(true);
        run.thread.start();
        Thread guard = new Thread(() -> {
            while (true) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
                synchronized (LocalAdbPrivacy.this) {
                    if (active != run) return;
                    long now = SystemClock.elapsedRealtime();
                    if (!shellReady.get() && !run.cancelled.get()) stop();
                    if (!run.cancelled.get() && !run.ready && now - run.started > 15000) {
                        run.failure = "ADB 黑屏遮罩启动超时，请重试。";
                        stopCurrent();
                    }
                    if (run.cancelled.get() && now - run.stopAt > 3000) {
                        if (!run.aborted) {
                            run.aborted = true;
                            run.client.abort(); run.thread.interrupt();
                        }
                        // Leave time for the shell-side lease to expire before allowing another overlay.
                        if (now - run.stopAt > 15000) finish(run);
                    }
                }
            }
        }, "ashell-adb-privacy-guard");
        run.guard = guard;
        guard.setDaemon(true);
        guard.start();
    }

    synchronized void stop() {
        pendingPort = 0;
        stopCurrent();
    }

    private void stopCurrent() {
        Run run = active;
        if (run == null) { publish("idle", ""); return; }
        if (run.cancelled.compareAndSet(false, true)) run.stopAt = SystemClock.elapsedRealtime();
        synchronized (run.signal) { run.signal.notifyAll(); }
        if (run.writer == null) { run.client.abort(); run.thread.interrupt(); }
        publish("stopping", run.failure);
    }

    private void publish(String phase, String error) {
        this.phase = phase; this.error = error;
        try { state(phase, error); } catch (LinkageError ignored) { }
    }

    private synchronized void finish(Run run) {
        if (active != run) return;
        if (run.guard != null) run.guard.interrupt();
        active = null;
        publish(run.failure.isEmpty() ? "idle" : "error", run.failure);
        int port = pendingPort;
        pendingPort = 0;
        if (port > 0 && shellReady.get()) start(port);
    }

    private void control(Run run) {
        Thread writer = null;
        String operation = "App 白字绘制";
        try {
            byte[] hint = AdbPrivacyHint.render(context);
            if (run.cancelled.get()) return;
            operation = "ADB 连接";
            run.client.connect(run.port, run.cancelled);
            // This is the installed, signed base APK, not peer-supplied code or a downloaded binary.
            String apk = "'" + context.getApplicationInfo().sourceDir.replace("'", "'\"'\"'") + "'";
            String command = "shell,v2,raw:CLASSPATH=" + apk
                    + " exec app_process / me.ashell.localadb.AdbPrivacyOverlay";
            operation = "遮罩启动";
            try (AdbStream process = run.client.openMirrorStream(command)) {
              try {
                writer = new Thread(() -> {
                    try {
                        // One writer owns startup pixels AND lease commands. Heartbeats must
                        // not interleave with the PNG or be interpreted as image bytes.
                        byte[] startup = ByteBuffer.allocate(5 + hint.length)
                                .put((byte) 1).putInt(hint.length).put(hint).array();
                        writeStartup(process, startup, run.cancelled);
                        do {
                            boolean stop = run.cancelled.get();
                            byte[] packet = {0, 1, 0, 0, 0, (byte) (stop ? 0 : 1)}; // shell-v2 stdin
                            process.write(packet, 0, packet.length); process.flush();
                            if (stop) return;
                            synchronized (run.signal) {
                                if (!run.cancelled.get()) run.signal.wait(1000);
                            }
                        } while (true);
                    } catch (Exception ignored) { run.client.abort(); }
                }, "ashell-adb-privacy-lease");
                run.writer = writer;
                writer.setDaemon(true);
                writer.start();
                byte[] header = new byte[5];
                byte[] buffer = new byte[4096];
                StringBuilder line = new StringBuilder();
                while (true) {
                    readFully(process, header, header.length);
                    int size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    int channel = header[0] & 255;
                    if (size < 0 || size > 65536 || channel < 1 || channel > 3
                            || (channel == 3 && size != 1)) throw new IOException();
                    int exitCode = -1;
                    while (size > 0) {
                        int count = Math.min(size, buffer.length);
                        readFully(process, buffer, count);
                        if (channel == 3) exitCode = buffer[0] & 255;
                        if (channel == 1) {
                            for (int i = 0; i < count; i++) {
                                int ch = buffer[i] & 255;
                                if (ch == '\n') { onLine(run, line.toString()); line.setLength(0); }
                                else if (line.length() < 256) line.append((char) ch);
                            }
                        }
                        size -= count;
                    }
                    if (channel == 3) {
                        synchronized (this) {
                            run.off = true; // shell-v2 confirms the helper process exited
                            if (active == run) {
                                if (!run.cancelled.get() && run.failure.isEmpty())
                                    run.failure = "ADB 黑屏遮罩进程已退出（exit " + exitCode + "）。";
                                stopCurrent();
                            }
                        }
                        break;
                    }
                }
              } finally {
                synchronized (this) {
                    if (active == run) {
                        if (!run.cancelled.get() && !run.off && run.failure.isEmpty())
                            run.failure = "ADB 黑屏遮罩连接中断，正在移除遮罩。";
                        stopCurrent();
                    }
                }
                run.client.abort(); // unblock AdbStream.close before leaving the resource scope
              }
            }
        } catch (Exception | LinkageError failure) {
            if (!run.cancelled.get()) run.failure = "ADB 黑屏" + operation + "失败（"
                    + failure.getClass().getSimpleName() + "）。";
        } finally {
            if (writer != null) writer.interrupt();
            run.client.abort();
            Thread.interrupted();
            run.client.disconnect();
            // An acknowledged OFF/process exit releases the layer. Unexpected transport loss uses its lease.
            if (!run.off && writer != null) {
                synchronized (this) {
                    if (active == run) publish("stopping", run.failure);
                }
                long deadline = SystemClock.elapsedRealtime() + 11000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    try { Thread.sleep(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
                    catch (InterruptedException ignored) { }
                }
            }
            finish(run);
        }
    }

    private synchronized void onLine(Run run, String line) {
        if (active != run) return;
        if ("ASHELL_OVERLAY_READY".equals(line)) {
            run.ready = true;
            if (!run.cancelled.get()) publish("on", "");
        } else if ("ASHELL_OVERLAY_ERROR".equals(line) || line.startsWith("ASHELL_OVERLAY_ERROR:")) {
            run.failure = "ADB 黑屏遮罩创建失败。";
            String[] fields = line.split(":", -1);
            if (fields.length == 4 && fields[1].matches("[1-5]") && fields[2].matches("[0-9]{1,3}")
                    && fields[3].matches("[A-Za-z0-9_$]{1,80}")) {
                String operation = "2".equals(fields[1]) ? "提示图片接收" : "3".equals(fields[1]) ? "图层创建"
                        : "4".equals(fields[1]) ? "提示图片显示" : "5".equals(fields[1]) ? "图层显示" : "启动";
                run.failure = "ADB 黑屏" + operation + "失败（API " + fields[2] + " / " + fields[3] + "）。";
            }
            stopCurrent();
        } else if ("ASHELL_OVERLAY_OFF".equals(line)) {
            run.off = true;
            stopCurrent();
        }
    }

    private static void writeStartup(AdbStream stream, byte[] bytes, AtomicBoolean cancelled) throws IOException {
        // Keep each write within the minimum ADB payload; libadb 3.1.1 cannot safely
        // split arbitrary large writes. The inner length is big-endian, shell-v2 is LE.
        for (int offset = 0; offset < bytes.length;) {
            if (cancelled.get()) throw new InterruptedIOException("Stopped");
            int size = Math.min(4091, bytes.length - offset);
            byte[] packet = ByteBuffer.allocate(5 + size).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0).putInt(size).put(bytes, offset, size).array();
            stream.write(packet, 0, packet.length); stream.flush();
            offset += size;
        }
    }

    private static void readFully(AdbStream stream, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int count = stream.read(buffer, offset, length - offset);
            if (count <= 0) throw new EOFException();
            offset += count;
        }
    }

    private final class Run {
        final int port;
        final long started = SystemClock.elapsedRealtime();
        final LocalAdbClient client = new LocalAdbClient(context, true);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Object signal = new Object();
        volatile String failure = "";
        volatile boolean off;
        boolean ready;
        boolean aborted;
        long stopAt;
        Thread thread, guard;
        volatile Thread writer;
        Run(int port) { this.port = port; }
    }
}
