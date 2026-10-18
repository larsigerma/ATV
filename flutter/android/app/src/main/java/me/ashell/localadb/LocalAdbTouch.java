package me.ashell.localadb;

import android.content.Context;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

/** scrcpy control-only server: ADB touch works with ANY existing frame source or no video. */
final class LocalAdbTouch {
    private final Context context;
    private final LocalAdbMirror.ShellReady shellReady;
    private final Map<Integer, int[]> cursors = new HashMap<>();
    private final ArrayDeque<Event> queue = new ArrayDeque<>();
    private Run active;
    private boolean priority;
    private int port;
    private long retryAfter;
    private long revision;
    private int failures;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static native void status(boolean priority, boolean ready, String error);

    LocalAdbTouch(Context context, LocalAdbMirror.ShellReady shellReady) {
        this.context = context.getApplicationContext(); this.shellReady = shellReady;
    }

    synchronized void enable(int port) {
        this.port = port;
        if (!shellReady.get() || port <= 0 || active != null || SystemClock.elapsedRealtime() < retryAfter) return;
        if (!priority) { priority = true; AdbTouchCoordinates.releaseAccessibilityTouch(); }
        publish(false, "");
        Run run = new Run();
        active = run;
        run.thread = new Thread(() -> control(run), "ashell-adb-touch");
        Thread timeout = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
                synchronized (LocalAdbTouch.this) {
                    if (active != run) return;
                    long now = SystemClock.elapsedRealtime();
                    if (run.stopAt > 0) {
                        if (now - run.stopAt >= 500) run.abort();
                        if (now - run.stopAt >= 2000) { finish(run, ""); return; }
                        continue;
                    }
                    if (!shellReady.get()) { stop(); continue; }
                    if ((!run.ready && now - run.started > 15000)
                            || (run.busySince > 0 && now - run.busySince > 5000)) {
                        run.failure = "ADB 触摸连接超时，正在自动恢复…";
                        run.abort();
                        publish(false, run.failure);
                    }
                }
            }
        }, "ashell-adb-touch-start-timeout");
        run.timeout = timeout;
        run.thread.setDaemon(true);
        timeout.setDaemon(true);
        run.thread.start();
        timeout.start();
    }

    synchronized void stop() {
        port = 0;
        priority = false;
        queue.clear();
        if (active != null) {
            active.paste.stop();
            active.keyboard.stop();
            active.cancelled.set(true);
            // Repeated stop requests must not extend the bounded teardown deadline.
            if (active.stopAt == 0) active.stopAt = SystemClock.elapsedRealtime();
            if (!active.ready) active.abort();
            notifyAll(); // let the writer release DOWN before closing its socket
        }
        publish(false, "");
    }

    private void publish(boolean ready, String error) {
        try { status(priority, ready, error); } catch (LinkageError ignored) { }
    }

    void paste(String owner, String requestId, byte[] text, LocalAdbManager.Reply reply) {
        text(owner, requestId, text, true, reply);
    }

    void syncClipboard(String owner, String requestId, byte[] text, LocalAdbManager.Reply reply) {
        text(owner, requestId, text, false, reply);
    }

    private synchronized void text(String owner, String requestId, byte[] text, boolean paste, LocalAdbManager.Reply reply) {
        if (!shellReady.get() || port <= 0) {
            reply.failure("disconnected", "请先连接并验证 ADB Shell。"); return;
        }
        enable(port);
        if (active == null || active.cancelled.get()) {
            reply.failure("busy", "ADB 输入连接正在恢复，请稍后重试。"); return;
        }
        if (paste) active.paste.submit(owner, requestId, text, reply);
        else active.paste.syncClipboard(owner, requestId, text, reply);
        notifyAll();
    }

    synchronized void cancelClipboardSync(String owner) {
        if (active != null) active.paste.cancelClipboardSync(owner);
    }

    synchronized void cancelPaste(String owner, String requestId) {
        if (active != null) active.paste.cancel(owner, requestId);
    }

    synchronized boolean key(int owner, byte[] event, boolean allowed) {
        if (!shellReady.get()) { if (priority) stop(); return active != null; }
        if (!allowed) { release(owner); return true; }
        if (event.length > 20 * 1024) return true;
        enable(port);
        if (active != null && !active.cancelled.get()) {
            active.keyboard.offer(owner, event);
            notifyAll();
        }
        return true;
    }

    /** Cache legacy coordinates even before ADB becomes available. Return true = consume exclusively. */
    synchronized boolean legacy(int owner, int kind, int mask, int x, int y, boolean allowed) {
        if (kind != 0 && !(mask == 0 || mask == 8 || mask == 16 || mask == 9 || mask == 10
                || mask == 17 || mask == 18 || mask == 3 || mask == 4)) return false;
        if (!cursors.containsKey(owner) && cursors.size() >= 64) return shellReady.get();
        int[] cursor = cursors.get(owner);
        if (cursor == null) { cursor = new int[2]; cursors.put(owner, cursor); }
        int scale = AdbTouchCoordinates.scale();
        if (kind == 0) {
            if (mask == 4 || mask == 6) { cursor[0] = x * scale; cursor[1] = y * scale; }
            else if (mask == 5) { cursor[0] -= x * scale; cursor[1] -= y * scale; }
        } else if (mask == 0 || mask == 8 || mask == 16) { cursor[0] = x * scale; cursor[1] = y * scale; }
        if (!shellReady.get()) { if (priority) stop(); return active != null; }
        if (!allowed) { release(owner); return true; }
        enable(port);
        if (kind == 0) {
            if (mask == 4) offer(new Event(owner, 0, cursor[0], cursor[1]));
            else if (mask == 5) offer(new Event(owner, 2, cursor[0], cursor[1]));
            else if (mask == 6) offer(new Event(owner, 1, cursor[0], cursor[1]));
        } else {
            if (mask == 9) offer(new Event(owner, 0, cursor[0], cursor[1]));
            else if (mask == 10) offer(new Event(owner, 1, cursor[0], cursor[1]));
            else if (mask == 0 || mask == 8 || mask == 16) offer(new Event(owner, 2, cursor[0], cursor[1]));
            else if (mask == 3 || mask == 4) offer(new Event(owner, 10, cursor[0], cursor[1], x, y));
            // Right-button press/release becomes a normal held touch (long press by holding).
            else if (mask == 17) offer(new Event(owner, 0, cursor[0], cursor[1]));
            else if (mask == 18) offer(new Event(owner, 1, cursor[0], cursor[1]));
        }
        return true;
    }

    synchronized void normalized(int owner, int action, int x, int y, int width, int height) {
        if (!shellReady.get() || width <= 0 || height <= 0 || action < 0 || action > 2) return;
        DisplayMetrics metrics = metrics();
        // New peers send physical geometry. Legacy (<=1280) peers still map proportionally.
        if ((width > height) != (metrics.widthPixels > metrics.heightPixels)) { release(owner); return; }
        int px = (int) ((long) Math.max(0, Math.min(x, width - 1)) * metrics.widthPixels / width);
        int py = (int) ((long) Math.max(0, Math.min(y, height - 1)) * metrics.heightPixels / height);
        enable(port);
        offer(new Event(owner, action, px, py));
    }

    synchronized void release(int owner) {
        cursors.remove(owner);
        if (active != null) active.keyboard.release(owner);
        Iterator<Event> events = queue.iterator();
        while (events.hasNext()) { if (events.next().owner == owner) events.remove(); }
        offer(new Event(owner, 1, -1, -1));
    }

    private void offer(Event event) {
        // Coalesce only adjacent moves; never let MOVE erase DOWN/UP ordering.
        Event last = queue.peekLast();
        if (event.action == 2 && last != null && last.action == 2 && last.owner == event.owner) queue.removeLast();
        if (queue.size() >= 64) {
            queue.clear();
            queue.add(new Event(-1, 3, 0, 0)); // overflow releases the gesture
            return;
        }
        queue.add(event);
        notifyAll();
    }

    private DisplayMetrics metrics() {
        AdbCaptureProfile profile = AdbCaptureProfile.current(context);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.widthPixels = profile.sourceWidth;
        metrics.heightPixels = profile.sourceHeight;
        return metrics;
    }

    private void control(Run run) {
        String failure = "";
        try {
            run.client.connect(run.port, run.cancelled);
            String path = "/data/local/tmp/ashell-scrcpy-touch-5.0.1.jar";
            LocalAdbMirror.push(context, run.client, run.cancelled, path);
            String scid = String.format(Locale.US, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
            try (AdbStream process = run.client.openMirrorStream("shell,v2,raw:sh")) {
                // Keep a bounded module cache: an old process must not unlink its replacement.
                String command = "CLASSPATH=" + path
                        + " app_process / com.genymobile.scrcpy.Server 5.0.1 scid=" + scid
                        + " tunnel_forward=true video=false audio=false control=true send_device_meta=false"
                        + " send_dummy_byte=false clipboard_autosync=false power_on=false cleanup=true log_level=error\n";
                byte[] script = command.getBytes(StandardCharsets.US_ASCII);
                byte[] input = ByteBuffer.allocate(script.length + 10).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 0).putInt(script.length).put(script).put((byte) 4).putInt(0).array();
                process.write(input, 0, input.length); process.flush();
                Thread output = new Thread(() -> {
                    try {
                        byte[] buffer = new byte[4096];
                        while (!run.cancelled.get() && process.read(buffer, 0, buffer.length) > 0) { }
                    } catch (IOException ignored) { }
                    synchronized (LocalAdbTouch.this) {
                        if (active == run && !run.cancelled.get()) {
                            run.failure = "ADB 触摸服务已退出，正在自动恢复…";
                            run.abort();
                            publish(false, run.failure);
                        }
                    }
                }, "ashell-adb-touch-output");
                output.setDaemon(true);
                output.start();
                AdbStream control = null;
                long deadline = SystemClock.elapsedRealtime() + 10000;
                while (!run.cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                    try { control = run.client.openMirrorStream("localabstract:scrcpy_" + scid); break; }
                    catch (IOException e) { Thread.sleep(100); }
                }
                if (control == null) throw new IOException("Input socket unavailable");
                try (AdbStream stream = control) {
                  try {
                    Thread replies = new Thread(() -> {
                        try { run.paste.readAcks(stream, run.cancelled); }
                        catch (IOException ignored) {
                            synchronized (LocalAdbTouch.this) {
                                if (active == run && !run.cancelled.get()) {
                                    run.failure = "ADB 输入连接已中断，正在自动恢复…";
                                    run.abort();
                                    publish(false, run.failure);
                                }
                            }
                        }
                    }, "ashell-adb-input-replies");
                    replies.setDaemon(true);
                    replies.start();
                    synchronized (this) {
                        if (active != run || run.cancelled.get()) return;
                        run.ready = true; failures = 0; publish(true, "");
                    }
                    Integer owner = null;
                    int lastX = 0, lastY = 0;
                    long lastEvent = SystemClock.elapsedRealtime();
                    int rotationWidth = 0, rotationHeight = 0;
                    while (!run.cancelled.get() && shellReady.get()) {
                        Event event;
                        synchronized (this) {
                            if (queue.isEmpty() && !run.keyboard.hasPending()) wait(200);
                            // A retired writer must not consume the replacement run's events.
                            if (active != run || run.cancelled.get() || !shellReady.get()) break;
                            event = queue.poll();
                        }
                        run.busySince = SystemClock.elapsedRealtime();
                        run.paste.writeNext(stream);
                        run.keyboard.writeNext(stream);
                        if (event == null && owner == null) { run.busySince = 0; continue; }
                        DisplayMetrics size = metrics();
                        boolean changed = rotationWidth != size.widthPixels || rotationHeight != size.heightPixels;
                        boolean expired = event != null && SystemClock.elapsedRealtime() - event.at > 1500;
                        if (owner != null && (changed || SystemClock.elapsedRealtime() - lastEvent > 10000
                                || (expired && event.owner == owner) || event != null && event.action == 3)) {
                            writeTouch(stream, 1, lastX, lastY, rotationWidth, rotationHeight);
                            owner = null;
                        }
                        rotationWidth = size.widthPixels; rotationHeight = size.heightPixels;
                        if (event == null || expired || event.action == 3) { run.busySince = 0; continue; }
                        if (owner != null && owner != event.owner) { run.busySince = 0; continue; } // one drag lease across PCs
                        if (event.action == 0) {
                            if (owner != null) writeTouch(stream, 1, lastX, lastY, rotationWidth, rotationHeight);
                            owner = event.owner;
                        } else if (event.action != 10 && owner == null) { run.busySince = 0; continue; }
                        if (event.x >= 0) { lastX = Math.max(0, Math.min(event.x, size.widthPixels - 1)); lastY = Math.max(0, Math.min(event.y, size.heightPixels - 1)); }
                        if (event.action == 10) {
                            byte[] scroll = ByteBuffer.allocate(21).put((byte) 3).putInt(lastX).putInt(lastY)
                                    .putShort((short) size.widthPixels).putShort((short) size.heightPixels)
                                    .putShort((short) (Math.max(-1, Math.min(1, event.dx)) * 2048))
                                    .putShort((short) (Math.max(-1, Math.min(1, event.dy)) * 2048)).putInt(0).array();
                            stream.write(scroll, 0, scroll.length); stream.flush();
                        } else {
                            writeTouch(stream, event.action, lastX, lastY, size.widthPixels, size.heightPixels);
                        }
                        lastEvent = SystemClock.elapsedRealtime();
                        if (event.action == 1) owner = null;
                        run.busySince = 0;
                    }
                    if (owner != null) {
                        run.busySince = SystemClock.elapsedRealtime();
                        writeTouch(stream, 1, lastX, lastY, rotationWidth, rotationHeight);
                    }
                  } finally {
                    // Closing the underlying private socket first unblocks libadb stream.close.
                    run.client.abort();
                  }
                }
            }
        } catch (Exception | LinkageError e) {
            if (!run.cancelled.get()) failure = "ADB 触摸连接失败，正在自动恢复…";
        } finally {
            run.abort();
            Thread.interrupted();
            run.client.disconnect();
            finish(run, failure);
        }
    }

    private synchronized void finish(Run run, String failure) {
        if (active != run) return;
        if (run.timeout != null) run.timeout.interrupt();
        run.abort();
        active = null;
        queue.clear();
        run.paste.stop();
        run.keyboard.stop();
        failures = Math.min(5, failures + 1);
        int delay = 1000 * failures;
        retryAfter = SystemClock.elapsedRealtime() + delay;
        priority = port > 0 && shellReady.get();
        publish(false, run.failure.isEmpty() ? failure : run.failure);
        if (priority) main.postDelayed(() -> {
            synchronized (LocalAdbTouch.this) {
                if (revision == run.id && active == null && port > 0 && shellReady.get()) enable(port);
            }
        }, delay + 100);
    }

    private static void writeTouch(AdbStream stream, int action, int x, int y, int width, int height) throws IOException {
        byte[] packet = ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(0)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) (action == 1 ? 0 : 65535)).putInt(0).putInt(0).array();
        stream.write(packet, 0, packet.length); stream.flush();
    }

    private final class Run {
        final long id = ++revision;
        final int port = LocalAdbTouch.this.port;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final LocalAdbPaste paste = new LocalAdbPaste();
        final LocalAdbKeyboard keyboard = new LocalAdbKeyboard();
        final LocalAdbClient client = new LocalAdbClient(context, true);
        Thread thread, timeout;
        boolean ready;
        final long started = SystemClock.elapsedRealtime();
        volatile long stopAt, busySince;
        volatile String failure = "";
        void abort() {
            cancelled.set(true);
            if (stopAt == 0) stopAt = SystemClock.elapsedRealtime();
            client.abort();
            if (thread != null) thread.interrupt();
        }
    }

    private static final class Event {
        final long at = SystemClock.elapsedRealtime();
        final int owner, action, x, y, dx, dy;
        Event(int owner, int action, int x, int y) { this(owner, action, x, y, 0, 0); }
        Event(int owner, int action, int x, int y, int dx, int dy) {
            this.owner = owner; this.action = action; this.x = x; this.y = y; this.dx = dx; this.dy = dy;
        }
    }
}
