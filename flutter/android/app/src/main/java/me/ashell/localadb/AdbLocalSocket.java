package me.ashell.localadb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellable loopback-only transport. Upstream 3.1.1 pairing hides its socket and has no
 * handshake timeout. Owning both ends here lets cancellation/timeout close blocked native I/O
 * without reflection, modifying the library, or stopping any product service.
 */
final class AdbLocalSocket implements Closeable {
    private final ServerSocket listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Socket upstream;
    private volatile Socket downstream;

    AdbLocalSocket() throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        listener.setSoTimeout(10000);
    }

    int port() {
        return listener.getLocalPort();
    }

    boolean isClosed() {
        return closed.get();
    }

    void start(int targetPort) throws IOException {
        Socket target = new Socket();
        upstream = target;
        if (closed.get()) {
            target.close();
            throw new IOException("Cancelled");
        }
        target.connect(new InetSocketAddress("127.0.0.1", targetPort), 3000);
        target.setTcpNoDelay(true);
        daemon("local-adb-accept", () -> {
            try {
                Socket peer = listener.accept();
                downstream = peer;
                listener.close();
                if (closed.get()) {
                    peer.close();
                    return;
                }
                peer.setTcpNoDelay(true);
                daemon("local-adb-upload", () -> copy(peer, target));
                copy(target, peer);
            } catch (IOException ignored) {
                close();
            }
        });
    }

    private void copy(Socket from, Socket to) {
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            byte[] buffer = new byte[16384];
            int size;
            while (!closed.get() && (size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
        } catch (IOException ignored) {
            // Closing either side cancels both pumps, including a blocked write.
        } finally {
            close();
        }
    }

    private static void daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        quietClose(listener);
        quietClose(upstream);
        quietClose(downstream);
    }

    private static void quietClose(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (IOException ignored) { }
    }
}
