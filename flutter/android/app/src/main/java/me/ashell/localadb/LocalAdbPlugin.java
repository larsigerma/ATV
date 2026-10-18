package me.ashell.localadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Local UI adapter. The Android process manager is shared with authenticated PC requests. */
public final class LocalAdbPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private MethodChannel channel;
    private Activity activity;
    private Context context;
    private LocalAdbManager manager;
    private final String owner = "local:" + UUID.randomUUID();
    private boolean attached;

    @Override public void onAttachedToEngine(FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        manager = LocalAdbManager.get(context);
        RemoteAdbNative.attach(manager);
        channel = new MethodChannel(binding.getBinaryMessenger(), "me.ashell/local_adb/v1");
        channel.setMethodCallHandler(this);
        attached = true;
    }

    @Override public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        if (!attached) { result.error("unavailable", "ADB 页面已关闭。", null); return; }
        if ("openSettings".equals(call.method)) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                if (activity != null) activity.startActivity(intent);
                else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                result.success(null);
            } catch (Exception ignored) {
                result.error("settings", "请在系统设置中手动打开开发者选项。", null);
            }
            return;
        }
        Map<String, Object> args = new HashMap<>();
        if (call.arguments instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) call.arguments).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    result.error("invalid", "无效 ADB 参数。", null);
                    return;
                }
                args.put((String) entry.getKey(), entry.getValue());
            }
        } else if (call.arguments != null) {
            result.error("invalid", "无效 ADB 参数。", null);
            return;
        }
        manager.execute(owner, call.method, args, new LocalAdbManager.Reply() {
            @Override public void success(Object value) { if (attached) result.success(value); }
            @Override public void failure(String code, String message) {
                if (attached) result.error(code, message, null);
            }
        });
    }

    @Override public void onDetachedFromEngine(FlutterPluginBinding binding) {
        attached = false;
        channel.setMethodCallHandler(null);
        manager.cancelOwner(owner);
        // Other PCs and the device identity outlive this Flutter page/engine.
        activity = null;
    }
    @Override public void onAttachedToActivity(ActivityPluginBinding binding) { activity = binding.getActivity(); }
    @Override public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) { onAttachedToActivity(binding); }
    @Override public void onDetachedFromActivityForConfigChanges() { activity = null; }
    @Override public void onDetachedFromActivity() { activity = null; }
}
