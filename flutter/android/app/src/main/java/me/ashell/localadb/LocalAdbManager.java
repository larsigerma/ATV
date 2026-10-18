package me.ashell.localadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android process owns the identity/connection. PC sessions own requests, never the identity. */
public final class LocalAdbManager {
    interface Reply {
        void success(Object value);
        void failure(String code, String message);
    }

    private static LocalAdbManager instance;
    static synchronized LocalAdbManager get(Context context) {
        if (instance == null) instance = new LocalAdbManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalAdbClient client;
    private final LocalAdbMirror mirror;
    private final LocalAdbTouch touch;
    private final LocalAdbPrivacy privacy;
    private final LocalAdbCommands commands;
    private final LocalAdbKeepAlive keepAlive;
    private final SharedPreferences records;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "ashell-local-adb"));
    private final ArrayDeque<Operation> queue = new ArrayDeque<>();
    private Operation current;
    private long revision;
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private Map<String, Object> lastPublished;
    private volatile Map<String, Object> discovered = new HashMap<>();
    private static final String RECOVERY_OWNER = "adb-recovery";
    private boolean keepConnection, observedConnected;
    private int recoveryAttempts;
    private long nextRecoveryAt;
    // One process-owned health loop, independent of every local/PC page and frame source.
    private final Runnable health = new Runnable() {
        @Override public void run() {
            reconcileConnection();
            changed();
            if (keepConnection && !client.isConnected() && current == null && queue.isEmpty()
                    && recoveryAttempts < 3 && SystemClock.elapsedRealtime() >= nextRecoveryAt) {
                recoveryAttempts++;
                nextRecoveryAt = SystemClock.elapsedRealtime() + Math.min(10000, 2000L << recoveryAttempts);
                execute(RECOVERY_OWNER, "connect", new HashMap<>(), new Reply() {
                    @Override public void success(Object value) { }
                    @Override public void failure(String code, String message) { }
                });
            }
            main.postDelayed(this, 1000);
        }
    };

    private LocalAdbManager(Context context) {
        this.context = context;
        client = new LocalAdbClient(context);
        records = context.getSharedPreferences("local_adb_record_v1", Context.MODE_PRIVATE);
        mirror = new LocalAdbMirror(context, client::isConnected);
        touch = new LocalAdbTouch(context, client::isConnected);
        privacy = new LocalAdbPrivacy(context, client::isConnected);
        commands = new LocalAdbCommands(context, main, client::isConnected, this::changed);
        keepAlive = new LocalAdbKeepAlive(context, commands, this::changed);
        RemoteAdbNative.attach(this);
        mirror.attach(this);
        changed();
        main.postDelayed(health, 1000);
    }

    private void reconcileConnection() {
        boolean connected = client.isConnected();
        if (!connected && observedConnected && !transportChanging()) {
            mirror.stop("ADB Shell 已断开，画面已停止。");
            privacy.stop();
            touch.stop();
            commands.cancel(null, null);
            nextRecoveryAt = SystemClock.elapsedRealtime() + 2000;
        }
        observedConnected = connected;
        if (connected && !transportChanging()) touch.enable(records.getInt("connectPort", 0));
        keepAlive.update(connected && !transportChanging(), records.getInt("connectPort", 0));
    }

    // Independent JNI entry; no long-running video operation occupies the shell queue.
    public void controlMirror(String action) {
        main.post(() -> {
            if ("start".equals(action) || "screenshot".equals(action) || "layout".equals(action)) {
                if (transportChanging() || !client.isConnected()) {
                    mirror.unavailable();
                } else {
                    mirror.start(records.getInt("connectPort", 0), "layout".equals(action) ? "layout"
                            : "screenshot".equals(action) ? "jpeg" : "h264");
                }
            } else if ("stop".equals(action)) mirror.stop("");
            else if ("stop_video".equals(action)) mirror.stopMode("h264");
            else if ("stop_screenshot".equals(action)) mirror.stopMode("jpeg");
            else if ("stop_layout".equals(action)) mirror.stopMode("layout");
            else if ("black_on".equals(action)) privacy.start(records.getInt("connectPort", 0));
            else if ("black_off".equals(action)) privacy.stop();
            else if ("stop_all".equals(action)) { mirror.stop(""); privacy.stop(); }
        });
    }

    public boolean routeAdbTouch(int owner, int kind, int mask, int x, int y, boolean allowed) {
        return touch.legacy(owner, kind, mask, x, y, allowed);
    }

    public boolean routeAdbKey(int owner, byte[] event, boolean allowed) {
        return touch.key(owner, event, allowed);
    }

    public void injectMirrorTouch(int owner, int action, int x, int y, int width, int height) {
        touch.normalized(owner, action, x, y, width, height);
    }

    public void releaseTouchOwner(int owner) { touch.release(owner); }

    Map<String, Object> status() {
        Map<String, Object> value = new HashMap<>();
        value.put("supported", Build.VERSION.SDK_INT >= 30);
        value.put("apiVersion", 4);
        value.put("textInputSupported", true);
        value.put("clipboardSyncSupported", true);
        value.put("mirrorVersion", 2);
        value.put("connected", client.isConnected());
        value.put("paired", Build.VERSION.SDK_INT >= 30
                && records.getBoolean("paired", false) && AdbIdentity.exists(context));
        value.put("pairedAt", records.getLong("pairedAt", 0));
        value.put("connectPort", records.getInt("connectPort", 0));
        value.put("busy", current != null || commands.running() != 0);
        value.put("connectionBusy", transportChanging());
        value.put("queued", queue.size() + commands.queued());
        value.put("runningCommands", commands.running());
        value.put("recovering", keepConnection && !client.isConnected() && recoveryAttempts < 3);
        value.put("operation", current == null ? commands.running() == 0 ? "" : "shell" : current.kind);
        value.put("revision", revision);
        value.put("instance", instanceId);
        value.put("phase", current == null ? "" : current.phase);
        value.put("discovery", discovered);
        keepAlive.addStatus(value);
        return value;
    }

    void execute(String owner, String kind, Map<String, Object> args, Reply reply) {
        execute(owner, "", kind, args, reply);
    }

    private void execute(String owner, String requestId, String kind, Map<String, Object> args, Reply reply) {
        for (String key : args.keySet()) {
            if (!("port".equals(key) || "connectPort".equals(key)
                    || "code".equals(key) || "command".equals(key)
                    || ("paste".equals(kind) || "clipboard".equals(kind)) && "text".equals(key)
                    || "cancel".equals(kind) && "requestId".equals(key))) {
                reply.failure("invalid", "ADB 请求包含不支持的参数，请更新客户端后使用配对码连接。");
                return;
            }
        }
        if ("status".equals(kind)) { changed(); reply.success(status()); return; }
        if ("cancel".equals(kind)) {
            Object target = args.get("requestId");
            if (target != null && (!(target instanceof String) || ((String) target).isEmpty()
                    || ((String) target).length() > 64)) {
                reply.failure("invalid", "无效取消请求。"); return;
            }
            cancelOwner(owner, (String) target);
            reply.success(null);
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            reply.failure("unsupported", "配对码无线调试需要 Android 11 或以上版本。");
            return;
        }
        if ("paste".equals(kind) || "clipboard".equals(kind)) {
            Object value = args.get("text");
            if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).indexOf('\0') >= 0) {
                reply.failure("invalid", "请输入非空且不含 NUL 的文本。"); return;
            }
            byte[] text = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (text.length > 16384) {
                reply.failure("invalid", "单次输入文本不能超过 16 KiB（UTF-8）。"); return;
            }
            if (transportChanging()) {
                reply.failure("busy", "ADB 连接正在变更，请稍后重试。"); return;
            }
            if ("clipboard".equals(kind)) touch.syncClipboard(owner, requestId, text, reply);
            else touch.paste(owner, requestId, text, reply);
            return;
        }
        if (!("pair".equals(kind) || "pairConnect".equals(kind) || "discover".equals(kind)
                || "connect".equals(kind) || "shell".equals(kind)
                || "disconnect".equals(kind) || "revoke".equals(kind))) {
            reply.failure("invalid", "未知 ADB 操作。"); return;
        }
        int port = 0;
        Object rawPort = args.get("port");
        if (rawPort != null) {
            if (!(rawPort instanceof Number) || ((Number) rawPort).doubleValue() != ((Number) rawPort).intValue()) {
                reply.failure("invalid", "端口必须是整数。"); return;
            }
            port = ((Number) rawPort).intValue();
        }
        if (port < 0 || port > 65535) {
            reply.failure("invalid", "端口必须为 1–65535；留空可自动发现。"); return;
        }
        int connectPort = 0;
        Object rawConnectPort = args.get("connectPort");
        if (rawConnectPort != null) {
            if (!(rawConnectPort instanceof Number)
                    || ((Number) rawConnectPort).doubleValue() != ((Number) rawConnectPort).intValue()
                    || ((Number) rawConnectPort).intValue() < 0 || ((Number) rawConnectPort).intValue() > 65535) {
                reply.failure("invalid", "连接端口必须为 1–65535；留空可自动发现。"); return;
            }
            connectPort = ((Number) rawConnectPort).intValue();
        }
        String text = "";
        if ("pair".equals(kind) || "pairConnect".equals(kind)) {
            Object code = args.get("code");
            if (!(code instanceof String) || !((String) code).matches("[0-9]{6}")) {
                reply.failure("invalid", "请输入 6 位配对码。"); return;
            }
            text = (String) code;
        } else if ("shell".equals(kind)) {
            Object command = args.get("command");
            if (!(command instanceof String) || ((String) command).trim().isEmpty()
                    || ((String) command).length() > 4096 || ((String) command).indexOf('\0') >= 0) {
                reply.failure("invalid", "请输入 1–4096 字符且不含 NUL 的 shell 命令。"); return;
            }
            text = (String) command;
        }
        if ("shell".equals(kind)) {
            if (transportChanging()) {
                reply.failure("busy", "ADB 正在配对、连接或断开，请稍后执行命令。"); return;
            }
            commands.submit(owner, requestId, records.getInt("connectPort", 0), text, reply);
            return;
        }
        if (!RECOVERY_OWNER.equals(owner) && !"discover".equals(kind)) {
            // Explicit connection actions supersede recovery. Never auto-reconnect after
            // disconnect/revoke/cancel, and never replay pairing codes or shell commands.
            keepConnection = false;
            recoveryAttempts = 0;
            Iterator<Operation> pending = queue.iterator();
            while (pending.hasNext()) if (RECOVERY_OWNER.equals(pending.next().owner)) pending.remove();
            if (current != null && RECOVERY_OWNER.equals(current.owner)) cancelCurrent("cancelled");
        }
        if ("revoke".equals(kind) || "disconnect".equals(kind)) {
            // Global device action: cancel all queued work before invalidating the shared identity.
            while (!queue.isEmpty()) {
                Operation pending = queue.remove();
                pending.text = "";
                pending.reply.failure("cancelled", "ADB 正在断开或撤销，排队请求已取消。");
            }
            if (current != null) cancelCurrent("cancelled");
        } else if (queue.size() >= 7) {
            reply.failure("busy", "Android ADB 队列已满，请稍后重试。"); return;
        }
        queue.add(new Operation(owner, requestId, kind, port, connectPort, text, reply));
        changed();
        startNext();
    }

    private void startNext() {
        if (current != null || queue.isEmpty()) return;
        Operation operation = queue.remove();
        if ("pair".equals(operation.kind) || "pairConnect".equals(operation.kind)
                || "disconnect".equals(operation.kind) || "revoke".equals(operation.kind)
                || "connect".equals(operation.kind) && (!client.isConnected()
                    || operation.port != 0 && operation.port != records.getInt("connectPort", 0))) {
            mirror.stop("");
            touch.stop();
            privacy.stop();
            keepAlive.update(false, 0);
            commands.cancel(null, null);
        }
        current = operation;
        changed();
        operation.timeout = () -> { if (current == operation) cancelCurrent("timeout"); };
        // At most 8 operations, each <=35s. PC routes retain results for 310s.
        long timeout = "pairConnect".equals(operation.kind) ? 35000 : 20000;
        operation.deadline = SystemClock.elapsedRealtime() + timeout;
        main.postDelayed(operation.timeout, timeout);
        worker.execute(() -> {
            Object value = null;
            boolean failed = false;
            operation.thread = Thread.currentThread();
            try {
                if (operation.cancelled.get()) throw new InterruptedException();
                switch (operation.kind) {
                    case "discover":
                        operation.phase = "正在扫描本机配对和连接端口";
                        value = discover(operation, "both", 6500).asMap();
                        break;
                    case "pair":
                    case "pairConnect": {
                        operation.phase = "正在发现配对端口";
                        int port = operation.port;
                        if (port == 0) {
                            AdbPortDiscovery.Ports ports = discover(operation, "pair", 6500);
                            if (ports.ambiguous) throw new AdbFailure("发现多个本机配对端口，请手动填写系统配对码窗口中的端口。");
                            port = ports.pairPort;
                        }
                        if (port == 0) throw new AdbFailure("没有发现本机配对端口。请保持系统配对窗口打开；也可手动填写端口。");
                        operation.phase = "正在配对";
                        operation.claimTransport();
                        client.pair(port, operation.text, operation.cancelled);
                        if (!operation.cancelled.get()) {
                            records.edit().putBoolean("paired", true)
                                    .putLong("pairedAt", System.currentTimeMillis()).apply();
                            operation.pairCompleted = true;
                            main.post(this::changed);
                        }
                        operation.text = "";
                        if ("pairConnect".equals(operation.kind)) {
                            operation.phase = "配对成功，正在连接并验证 Shell";
                            try { value = connect(operation, operation.connectPort); }
                            catch (Exception | LinkageError e) {
                                String detail = e instanceof LocalAdbClient.ConnectionFailure || e instanceof AdbFailure
                                        ? e.getMessage() : "连接或 Shell 验证未完成。";
                                throw new AdbFailure("配对已成功，记录已保留。" + detail
                                        + "可点击“我已配对，连接”重试，或手填无线调试首页的连接端口。");
                            }
                        }
                        break;
                    }
                    case "connect": value = connect(operation, operation.port); break;
                    case "disconnect":
                        operation.claimTransport();
                        client.disconnect();
                        break;
                    case "revoke":
                        operation.claimTransport();
                        client.disconnect();
                        if (!records.edit().clear().commit()) throw new IllegalStateException("Record removal failed");
                        AdbIdentity.revoke(context);
                        discovered = new HashMap<>();
                        break;
                    default: throw new IllegalStateException();
                }
            } catch (Exception | LinkageError error) {
                failed = true;
                if (error instanceof AdbFailure || error instanceof LocalAdbClient.ConnectionFailure)
                    operation.failure = error.getMessage();
                if (operation.usesTransport) client.disconnect();
            } finally {
                operation.text = "";
                operation.thread = null;
                if (operation.cancelled.get() && operation.usesTransport) client.disconnect();
                Thread.interrupted();
            }
            Object result = value;
            boolean error = failed;
            main.post(() -> {
                main.removeCallbacks(operation.timeout);
                if (current != operation) return;
                current = null;
                if (!operation.cancelled.get() && !error && client.isConnected()
                        && ("connect".equals(operation.kind) || "pairConnect".equals(operation.kind))) {
                    // A later explicit disconnect already in the queue retains precedence.
                    if (!transportChanging()) { keepConnection = true; recoveryAttempts = 0; }
                }
                reconcileConnection();
                changed();
                if (!client.isConnected()) touch.stop();
                if (operation.cancelled.get()) operation.reply.failure(operation.cancelReason,
                        operation.pairCompleted ? "配对已成功，后续连接已取消或超时。可点击“我已配对”重新连接。"
                                : "timeout".equals(operation.cancelReason) ? "ADB 操作超时，请查看当前配对/连接状态后重试。" : "ADB 操作已取消。");
                else if (error) operation.reply.failure("adb_failed", operation.failure.isEmpty()
                        ? failureMessage(operation.kind) : operation.failure);
                else if (("connect".equals(operation.kind) || "pairConnect".equals(operation.kind)) && !client.isConnected())
                    operation.reply.failure("disconnected", "配对记录已保留，但 ADB 连接在验证后断开，请保持无线调试开启后重新连接。");
                else operation.reply.success(result);
                startNext();
            });
        });
    }

    private AdbPortDiscovery.Ports discover(Operation operation, String required,
                                            long millis) throws InterruptedException {
        AdbPortDiscovery.Ports ports = AdbPortDiscovery.find(context, operation.cancelled, required, millis);
        // Keep public scan results separate from the remembered last successful connection.
        Map<String, Object> result = ports.asMap();
        result.put("scannedAt", System.currentTimeMillis());
        discovered = result;
        return ports;
    }

    private String connect(Operation operation, int port) throws Exception {
        if (client.isConnected() && (port == 0 || port == records.getInt("connectPort", 0)))
            return "ADB 已连接，Shell 已验证。";
        final boolean automatic = port == 0;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (operation.cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long remaining = operation.deadline - SystemClock.elapsedRealtime();
            if (remaining < 1000) break;
            if (automatic) {
                operation.phase = attempt == 0 ? "正在发现连接端口" : "正在重新发现无线调试连接端口";
                AdbPortDiscovery.Ports ports = discover(operation, "connect", Math.min(remaining - 500, attempt == 0 ? 3500 : 1500));
                if (ports.ambiguous) throw new AdbFailure("发现多个本机连接端口，请手动填写无线调试首页的连接端口。");
                port = ports.connectPort;
                if (port == 0) port = records.getInt("connectPort", 0);
            }
            if (port > 0) {
                operation.phase = "正在连接并验证 Shell";
                operation.claimTransport();
                try {
                    remaining = operation.deadline - SystemClock.elapsedRealtime();
                    if (remaining < 1000) break;
                    String result = client.connect(port, operation.cancelled, Math.min(7000, remaining - 250));
                    if (!operation.cancelled.get()) records.edit().putInt("connectPort", port).putBoolean("paired", true).apply();
                    return result;
                } catch (Exception error) {
                    last = error;
                    if (operation.cancelled.get() || Thread.currentThread().isInterrupted()) throw error;
                }
            }
            // Only connection/id probes are retried. Never replay a user's shell command or pairing code.
            if (attempt == 0) Thread.sleep(350);
        }
        if (last != null) throw last;
        throw new AdbFailure("没有发现可用连接端口。请确认无线调试已开启，或手动填写无线调试首页的连接端口。");
    }

    private boolean transportChanging() {
        if (current != null && !"discover".equals(current.kind)) return true;
        for (Operation operation : queue) if (!"discover".equals(operation.kind)) return true;
        return false;
    }

    private void changed() {
        Map<String, Object> snapshot = status();
        snapshot.remove("revision");
        if (snapshot.equals(lastPublished)) return;
        lastPublished = new HashMap<>(snapshot);
        snapshot.put("revision", ++revision);
        try { RemoteAdbNative.state(new JSONObject(snapshot).toString()); }
        catch (Exception | LinkageError ignored) { }
    }

    private static final class AdbFailure extends Exception {
        AdbFailure(String message) { super(message); }
    }

    private String failureMessage(String kind) {
        if ("pair".equals(kind) || "pairConnect".equals(kind)) return "配对失败，请保持系统配对窗口打开并核对配对端口与配对码。";
        if ("discover".equals(kind)) return "未能扫描端口，请保持无线调试开启，或手动填写端口。";
        if ("connect".equals(kind)) return "连接或 shell 验证失败，请填写无线调试首页的连接端口；若已在系统忘记配对，请重新配对。";
        if ("revoke".equals(kind)) return "撤销未完整完成；ADB 已断开，请在系统无线调试中忘记对应配对后重试。";
        return "ADB 操作失败，连接已断开，请重新连接。";
    }

    private void cancelCurrent(String reason) {
        Operation operation = current;
        if (operation == null || !operation.cancelled.compareAndSet(false, true)) return;
        operation.cancelReason = reason;
        if (operation.usesTransport) client.abort();
        Thread thread = operation.thread;
        if (thread != null) thread.interrupt();
        // Also close the reader if cancellation arrives after worker completion but before
        // its main-looper result. This is queued before startNext can submit another job.
        if (operation.usesTransport) worker.execute(client::disconnect);
    }

    void cancelOwner(String owner) {
        cancelOwner(owner, null);
    }

    private void cancelOwner(String owner, String requestId) {
        commands.cancel(owner, requestId);
        touch.cancelPaste(owner, requestId);
        Iterator<Operation> pending = queue.iterator();
        while (pending.hasNext()) {
            Operation operation = pending.next();
            if (operation.owner.equals(owner) && (requestId == null || requestId.equals(operation.requestId))) {
                pending.remove();
                operation.text = "";
                operation.reply.failure("cancelled", "请求已取消。");
            }
        }
        if (current != null && current.owner.equals(owner)
                && (requestId == null || requestId.equals(current.requestId))) cancelCurrent("cancelled");
        changed();
    }

    // Called from the isolated JNI bridge, on arbitrary native threads. No work runs there.
    public void dispatchRemote(String owner, String token, String payload) {
        main.post(() -> {
            Reply reply = new Reply() {
                private void finish(boolean ok, Object value, String code, String message) {
                    try {
                        JSONObject out = new JSONObject().put("ok", ok).put("state", new JSONObject(status()));
                        if (ok) out.put("result", value instanceof Map ? new JSONObject((Map<?, ?>) value)
                                : value == null ? JSONObject.NULL : value);
                        else out.put("error", code).put("message", message);
                        RemoteAdbNative.complete(token, out.toString());
                    } catch (Exception | LinkageError ignored) { }
                }
                @Override public void success(Object value) { finish(true, value, "", ""); }
                @Override public void failure(String code, String message) { finish(false, null, code, message); }
            };
            try {
                JSONObject request = new JSONObject(payload);
                Map<String, Object> args = new HashMap<>();
                Iterator<String> keys = request.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!("v".equals(key) || "id".equals(key) || "kind".equals(key) || "method".equals(key))) {
                        args.put(key, request.isNull(key) ? null : request.get(key));
                    }
                }
                execute("remote:" + owner, request.optString("id", ""), request.getString("method"), args, reply);
            } catch (Exception ignored) { reply.failure("invalid", "无效 ADB 请求。"); }
        });
    }

    public void disconnectRemote(String owner) {
        main.post(() -> cancelOwner("remote:" + owner));
    }

    public void cancelClipboardSync(String owner) {
        main.post(() -> touch.cancelClipboardSync("remote:" + owner));
    }

    private static final class Operation {
        final String owner;
        final String requestId;
        final String kind;
        final int port;
        final int connectPort;
        volatile boolean usesTransport;
        boolean pairCompleted;
        volatile String phase = "";
        String failure = "";
        volatile String text;
        final Reply reply;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Thread thread;
        String cancelReason = "cancelled";
        Runnable timeout;
        long deadline;
        Operation(String owner, String requestId, String kind, int port, int connectPort, String text, Reply reply) {
            this.owner = owner; this.requestId = requestId; this.kind = kind; this.port = port; this.connectPort = connectPort;
            this.text = text; this.reply = reply;
        }
        void claimTransport() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            usesTransport = true;
        }
    }
}
