package me.ashell.localadb;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded command lane. Each command owns its socket; cancelling it never revokes shared Shell. */
final class LocalAdbCommands {
    private final Context context;
    private final Handler main;
    private final LocalAdbMirror.ShellReady shellReady;
    private final Runnable changed;
    private final ArrayDeque<Job> queued = new ArrayDeque<>();
    private final List<Job> running = new ArrayList<>();

    LocalAdbCommands(Context context, Handler main, LocalAdbMirror.ShellReady shellReady, Runnable changed) {
        this.context = context;
        this.main = main;
        this.shellReady = shellReady;
        this.changed = changed;
    }

    // All queue operations and replies are owned by the Android main looper.
    int running() { return running.size(); }
    int queued() { return queued.size(); }

    void submit(String owner, String requestId, int port, String command, LocalAdbManager.Reply reply) {
        if (!shellReady.get() || port <= 0) {
            reply.failure("disconnected", "请先连接 ADB 并验证 Shell。");
            return;
        }
        if (running.size() + queued.size() >= 8) {
            reply.failure("busy", "ADB 命令队列已满，请等待或取消已有命令。");
            return;
        }
        queued.add(new Job(owner, requestId, port, command, reply));
        changed.run();
        startNext();
    }

    void cancel(String owner, String requestId) {
        Iterator<Job> iterator = queued.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!matches(job, owner, requestId)) continue;
            iterator.remove();
            job.command = "";
            job.reply.failure("cancelled", "ADB 命令已取消。");
        }
        for (Job job : running) if (matches(job, owner, requestId)) cancel(job, "cancelled");
        changed.run();
    }

    private boolean matches(Job job, String owner, String requestId) {
        return (owner == null || owner.equals(job.owner))
                && (requestId == null || requestId.equals(job.requestId));
    }

    private void cancel(Job job, String reason) {
        if (!job.cancelled.compareAndSet(false, true)) return;
        job.reason = reason;
        job.client.abort();
        if (job.thread != null) job.thread.interrupt();
    }

    private void startNext() {
        // Keep different PCs/controllers responsive without concurrent writes to one libadb connection.
        while (running.size() < 2 && !queued.isEmpty()) {
            Job job = queued.remove();
            if (!shellReady.get()) {
                job.command = "";
                job.reply.failure("disconnected", "ADB 已断开，排队命令未执行。");
                continue;
            }
            running.add(job);
            job.timeout = () -> { if (running.contains(job)) cancel(job, "timeout"); };
            main.postDelayed(job.timeout, 20000);
            job.thread = new Thread(() -> execute(job), "ashell-adb-command");
            job.thread.setDaemon(true);
            job.thread.start();
            changed.run();
        }
    }

    private void execute(Job job) {
        Object result = null;
        boolean failed = false;
        try {
            job.client.connect(job.port, job.cancelled);
            if (!shellReady.get() || job.cancelled.get()) throw new InterruptedException();
            result = job.client.runShell(job.command, job.cancelled);
        } catch (Exception | LinkageError error) {
            failed = true;
        } finally {
            job.command = "";
            job.client.abort();
            Thread.interrupted();
            job.client.disconnect();
        }
        Object value = result;
        boolean error = failed;
        main.post(() -> {
            main.removeCallbacks(job.timeout);
            running.remove(job);
            changed.run();
            if (job.cancelled.get()) job.reply.failure(job.reason,
                    "timeout".equals(job.reason) ? "ADB 命令超时，已关闭本次命令通道；其他 ADB 功能不受影响。"
                            : "ADB 命令已取消。");
            else if (error) job.reply.failure("adb_failed", "ADB 命令执行失败，请确认无线调试连接；命令不会自动重放。");
            else job.reply.success(value);
            startNext();
        });
    }

    private final class Job {
        final String owner, requestId;
        final int port;
        final LocalAdbManager.Reply reply;
        final LocalAdbClient client = new LocalAdbClient(context);
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile String command;
        String reason = "cancelled";
        Runnable timeout;
        Thread thread;

        Job(String owner, String requestId, int port, String command, LocalAdbManager.Reply reply) {
            this.owner = owner; this.requestId = requestId; this.port = port;
            this.command = command; this.reply = reply;
        }
    }
}
