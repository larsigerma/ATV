package me.ashell.localadb;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.util.Map;

/** Main-looper owned, one battery-exemption attempt per verified ADB connection. */
final class LocalAdbKeepAlive {
    private final PowerManager power;
    private final String packageName;
    private final LocalAdbCommands commands;
    private final Runnable changed;
    private boolean attempted, busy;
    private String error = "";

    LocalAdbKeepAlive(Context context, LocalAdbCommands commands, Runnable changed) {
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        packageName = context.getPackageName();
        this.commands = commands;
        this.changed = changed;
    }

    void update(boolean ready, int port) {
        if (!ready) {
            attempted = false;
            return;
        }
        // A cancelled old command must finish releasing its private connection first.
        // The manager's existing health loop can then handle the new connection.
        if (attempted || busy) return;
        attempted = true;
        error = "";
        if (Boolean.TRUE.equals(isExempt())) return;
        busy = true;
        String argument = "'+" + packageName.replace("'", "'\\''") + "'";
        commands.submit("adb-keep-alive", "", port,
                "dumpsys deviceidle whitelist " + argument, new LocalAdbManager.Reply() {
                    @Override public void success(Object value) {
                        boolean commandOk = value instanceof Map
                                && Integer.valueOf(0).equals(((Map<?, ?>) value).get("exitCode"));
                        finish(commandOk ? "系统未确认电池优化豁免，请检查系统电池设置。"
                                : "保活命令未成功执行，请检查系统电池优化设置。");
                    }
                    @Override public void failure(String code, String message) {
                        finish(message);
                    }
                });
    }

    private void finish(String failure) {
        busy = false;
        error = Boolean.TRUE.equals(isExempt()) ? "" : failure;
        changed.run();
    }

    private Boolean isExempt() {
        if (Build.VERSION.SDK_INT < 23 || power == null) return null;
        try { return power.isIgnoringBatteryOptimizations(packageName); }
        catch (RuntimeException ignored) { return null; }
    }

    void addStatus(Map<String, Object> status) {
        Boolean exempt = isExempt();
        status.put("keepAliveSupported", Build.VERSION.SDK_INT >= 30);
        status.put("keepAliveExempt", exempt);
        status.put("keepAliveBusy", busy);
        status.put("keepAliveError", Boolean.TRUE.equals(exempt) ? "" : error);
    }
}
