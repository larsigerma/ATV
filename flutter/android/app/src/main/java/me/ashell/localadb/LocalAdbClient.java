package me.ashell.localadb;

import android.content.Context;
import android.os.Build;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;

/** Worker-thread owner of a local ADB connection. No product JNI, services or peer protocol. */
final class LocalAdbClient {
    static final class ConnectionFailure extends IOException {
        ConnectionFailure(boolean handshakeComplete) {
            super(handshakeComplete
                    ? "ADB 连接握手成功，但 Shell 通道或 UID 验证未完成，请重新连接。"
                    : "无线调试连接握手未完成，请确认连接端口有效，且系统仍保留本应用配对记录。");
        }
    }
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ashell-adb-connect-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;
    private final boolean boundedClose;
    private volatile AdbLocalSocket socket;
    private volatile AdbConnection connection;
    private volatile boolean shellVerified;

    LocalAdbClient(Context context) {
        this(context, true);
    }

    LocalAdbClient(Context context, boolean boundedClose) {
        this.context = context.getApplicationContext();
        this.boundedClose = boundedClose;
    }

    boolean isConnected() {
        AdbConnection value = connection;
        AdbLocalSocket transport = socket;
        return shellVerified && value != null && transport != null && !transport.isClosed()
                && value.isConnected() && value.isConnectionEstablished();
    }

    void pair(int port, String code, AtomicBoolean cancelled) throws Exception {
        disconnect();
        check(cancelled);
        AdbIdentity identity = AdbIdentity.load(context);
        AdbLocalSocket transport = newTransport(port, cancelled);
        PairingConnectionCtx pairing = null;
        try {
            pairing = new PairingConnectionCtx("127.0.0.1", transport.port(),
                    code.getBytes(StandardCharsets.UTF_8), identity.privateKey, identity.certificate, "AShell local ADB");
            check(cancelled);
            pairing.start();
            check(cancelled);
        } finally {
            transport.close();
            // Upstream close may encounter partially initialized streams after a failed handshake.
            if (pairing != null) {
                try { pairing.close(); } catch (Exception ignored) { }
            }
            socket = null;
        }
    }

    String connect(int port, AtomicBoolean cancelled) throws Exception {
        return connect(port, cancelled, 10000);
    }

    String connect(int port, AtomicBoolean cancelled, long timeoutMillis) throws Exception {
        disconnect();
        check(cancelled);
        // Bound TLS AND the first shell OPEN/read. libadb's OPEN wait has no deadline.
        // Synchronize completion with interruption so a late timer cannot cancel the next job.
        Object lease = new Object();
        boolean[] finished = {false};
        AtomicBoolean expired = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            synchronized (lease) {
                if (finished[0]) return;
                expired.set(true);
                abort();
                caller.interrupt();
            }
        }, Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        boolean handshakeComplete = false;
        try {
            AdbIdentity identity = AdbIdentity.load(context);
            AdbLocalSocket transport = newTransport(port, cancelled);
            AdbConnection value = new AdbConnection.Builder("127.0.0.1", transport.port())
                    .setApi(Build.VERSION.SDK_INT).setPrivateKey(identity.privateKey)
                    .setCertificate(identity.certificate).setDeviceName("AShell local ADB").build();
            connection = value;
            check(cancelled);
            if (!value.connect(8, TimeUnit.SECONDS, true)) throw new IllegalStateException("Connection timed out");
            handshakeComplete = true;
            Map<String, Object> probe = AdbShell.execute(value, "id -u", cancelled);
            String output = (String) probe.get("output");
            if (output == null || !("2000".equals(output.trim()) || "0".equals(output.trim()))
                    || !Integer.valueOf(0).equals(probe.get("exitCode"))) {
                throw new IOException("Shell UID verification failed");
            }
            check(cancelled);
            synchronized (lease) {
                if (expired.get() || cancelled.get() || transport.isClosed() || !value.isConnectionEstablished())
                    throw new IOException("ADB verification expired");
                shellVerified = true;
                finished[0] = true;
            }
            return "uid=" + output.trim();
        } catch (Exception | LinkageError e) {
            disconnect();
            if (!cancelled.get() && !(e instanceof LinkageError)) throw new ConnectionFailure(handshakeComplete);
            throw e;
        } finally {
            synchronized (lease) { finished[0] = true; }
            deadline.cancel(false);
            if (expired.get() && !cancelled.get()) Thread.interrupted();
        }
    }

    Map<String, Object> runShell(String command, AtomicBoolean cancelled) throws Exception {
        if (!isConnected()) throw new IllegalStateException("Not connected");
        check(cancelled);
        AdbConnection value = connection;
        if (value == null) throw new IllegalStateException("Not connected");
        Map<String, Object> result = AdbShell.execute(value, command, cancelled);
        check(cancelled);
        return result;
    }

    // Used only by the mirror's private LocalAdbClient, never by the shared shell client.
    AdbStream openMirrorStream(String destination) throws Exception {
        AdbConnection value = connection;
        if (!isConnected() || value == null) throw new IllegalStateException("ADB disconnected");
        return AdbStreamCompat.open(value, destination);
    }

    private AdbLocalSocket newTransport(int port, AtomicBoolean cancelled) throws Exception {
        check(cancelled);
        AdbLocalSocket transport = new AdbLocalSocket();
        socket = transport;
        try {
            check(cancelled);
            transport.start(port);
            check(cancelled);
            return transport;
        } catch (Exception e) {
            transport.close();
            socket = null;
            throw e;
        }
    }

    /** Nonblocking cancellation, callable from the main thread while a worker is in native I/O. */
    void abort() {
        shellVerified = false;
        AdbLocalSocket value = socket;
        if (value != null) value.close();
    }

    /** Worker-only: upstream close joins its reader thread. */
    void disconnect() {
        abort();
        AdbConnection value = connection;
        connection = null;
        socket = null;
        if (value != null) {
            if (!boundedClose) {
                try { value.close(); } catch (Exception ignored) { }
                return;
            }
            // No ADB lane may pin its state machine on libadb's unbounded reader join.
            Thread cleanup = new Thread(() -> {
                try { value.close(); } catch (Exception ignored) { }
            }, "ashell-adb-private-close");
            cleanup.setDaemon(true);
            cleanup.start();
            try { cleanup.join(1000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (cleanup.isAlive()) cleanup.interrupt();
        }
    }

    private static void check(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
}
