package me.ashell.localadb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android NSD discovery of this phone only. Uses the same two standard service
 * types as aShellYou; no adb executable, settings automation or network port sweep.
 * Worker owns lifetime; NSD callbacks share one bounded, serialized resolver.
 */
final class AdbPortDiscovery {
    private static final String PAIR = "_adb-tls-pairing._tcp.";
    private static final String CONNECT = "_adb-tls-connect._tcp.";

    static final class Ports {
        final int pairPort;
        final int connectPort;
        final boolean ambiguous;
        Ports(int pairPort, int connectPort, boolean ambiguous) {
            this.pairPort = pairPort;
            this.connectPort = connectPort;
            this.ambiguous = ambiguous;
        }
        Map<String, Object> asMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("pairPort", pairPort);
            map.put("connectPort", connectPort);
            map.put("ambiguous", ambiguous);
            return map;
        }
    }

    // required: pair, connect or both.
    static Ports find(Context context, AtomicBoolean cancelled, String required,
                      long waitMillis) throws InterruptedException {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return new Ports(0, 0, false);
        class Scan {
            final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
            final Map<String, Integer> pair = new HashMap<>();
            final Map<String, Integer> connect = new HashMap<>();
            final Set<String> present = new HashSet<>();
            final Map<String, Integer> retries = new HashMap<>();
            boolean resolving;
            boolean closed;
            String key(NsdServiceInfo info) { return info.getServiceType() + "/" + info.getServiceName(); }
            boolean pairing(NsdServiceInfo info) { return info.getServiceType().contains("_adb-tls-pairing."); }
            synchronized void add(NsdServiceInfo info) {
                if (closed || cancelled.get() || present.size() >= 64 || pending.size() >= 32) return;
                if (present.add(key(info))) pending.add(info);
                next();
            }
            synchronized void lost(NsdServiceInfo info) {
                String key = key(info);
                present.remove(key);
                pair.remove(key);
                connect.remove(key);
                retries.remove(key);
                pending.removeIf(value -> key(value).equals(key));
                notifyAll();
            }
            synchronized void next() {
                if (closed || cancelled.get() || resolving || pending.isEmpty()) return;
                resolving = true;
                NsdServiceInfo info = pending.remove();
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo value, int error) {
                            failed(info, error);
                        }
                        @Override public void onServiceResolved(NsdServiceInfo value) {
                            finished(info, value);
                        }
                    });
                } catch (RuntimeException ignored) { finished(info, null); }
            }
            synchronized void failed(NsdServiceInfo info, int error) {
                int attempt = retries.containsKey(key(info)) ? retries.get(key(info)) : 0;
                if (!closed && !cancelled.get() && attempt < 3
                        && error == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    retries.put(key(info), attempt + 1);
                    // An older NSD resolve can finish after discovery was stopped.
                    // Back off rather than recursively resolving on the callback thread.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        synchronized (Scan.this) {
                            if (!closed && !cancelled.get() && present.contains(key(info))) pending.add(info);
                            resolving = false;
                            next();
                        }
                    }, 250);
                } else finished(info, null);
            }
            synchronized void finished(NsdServiceInfo original, NsdServiceInfo value) {
                resolving = false;
                if (!closed && !cancelled.get() && present.contains(key(original))
                        && value != null && isLocal(value.getHost())
                        && value.getPort() > 0 && value.getPort() <= 65535) {
                    (pairing(original) ? pair : connect).put(key(original), value.getPort());
                    notifyAll();
                }
                next();
            }
            int unique(Map<String, Integer> values) {
                Set<Integer> ports = new HashSet<>(values.values());
                return ports.size() == 1 ? ports.iterator().next() : 0;
            }
            synchronized Ports result() {
                return new Ports(unique(pair), unique(connect),
                        new HashSet<>(pair.values()).size() > 1 || new HashSet<>(connect.values()).size() > 1);
            }
            synchronized boolean ready() {
                if (resolving || !pending.isEmpty()) return false;
                Ports p = result();
                return "pair".equals(required) ? p.pairPort != 0
                        : "connect".equals(required) ? p.connectPort != 0
                        : p.pairPort != 0 && p.connectPort != 0;
            }
        }
        Scan scan = new Scan();
        List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
        try {
            String[] types = "pair".equals(required) ? new String[]{PAIR}
                    : "connect".equals(required) ? new String[]{CONNECT} : new String[]{PAIR, CONNECT};
            for (String type : types) {
                NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String type) { }
                    @Override public void onDiscoveryStopped(String type) { }
                    @Override public void onStartDiscoveryFailed(String type, int error) { }
                    @Override public void onStopDiscoveryFailed(String type, int error) { }
                    @Override public void onServiceFound(NsdServiceInfo value) { scan.add(value); }
                    @Override public void onServiceLost(NsdServiceInfo value) { scan.lost(value); }
                };
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
                    listeners.add(listener);
                } catch (RuntimeException ignored) { }
            }
            long deadline = SystemClock.elapsedRealtime() + waitMillis;
            synchronized (scan) {
                long readyAt = 0;
                while (!listeners.isEmpty() && !cancelled.get()) {
                    long now = SystemClock.elapsedRealtime();
                    if (scan.ready()) {
                        if (readyAt == 0) readyAt = now;
                        // Allow nearby callbacks to reveal multiple local advertisements.
                        if (now - readyAt >= 350) break;
                    } else readyAt = 0;
                    long left = deadline - SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    scan.wait(Math.min(left, 200));
                }
                if (cancelled.get()) throw new InterruptedException();
                return scan.result();
            }
        } finally {
            synchronized (scan) { scan.closed = true; scan.pending.clear(); }
            for (NsdManager.DiscoveryListener listener : listeners) {
                try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static boolean isLocal(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress()) return true;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) if (address.equals(addresses.nextElement())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
