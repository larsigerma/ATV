package me.ashell.localadb;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.Semaphore;

/** Shell-only node reader. Rendering belongs to the app, not this standalone app_process VM. */
public final class AdbLayoutCapture {
    private static volatile long heartbeat = SystemClock.elapsedRealtime();
    private AdbLayoutCapture() { }

    public static void main(String[] args) {
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
        System.setOut(System.err); // Only the private binary protocol may use stdout.
        Semaphore requests = new Semaphore(0);
        Thread lease = new Thread(() -> {
            try {
                while (SystemClock.elapsedRealtime() - heartbeat < 10000) Thread.sleep(500);
            } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "ashell-layout-lease");
        lease.setDaemon(true);
        lease.start();
        try {
            progress(output, 1);
            if (android.os.Process.myUid() != 2000 && android.os.Process.myUid() != 0)
                throw new SecurityException();
            if (System.in.read() != 1) return;
            heartbeat = SystemClock.elapsedRealtime();
            Thread input = new Thread(() -> {
                try {
                    int command;
                    while ((command = System.in.read()) != -1) {
                        heartbeat = SystemClock.elapsedRealtime();
                        if (command == 2) requests.release();
                        else if (command != 1) break;
                    }
                } catch (Exception ignored) { }
                System.exit(0); // Binder death removes only this helper's UiAutomation.
            }, "ashell-layout-input");
            input.setDaemon(true);
            input.start();
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            // UiAutomation's shell constructor needs a running Looper, not ActivityThread.
            // In particular, never call systemMain(): it initializes system-server HWUI.
            new Thread(() -> capture(requests, output), "ashell-layout-nodes").start();
            Looper.loop();
        } catch (Throwable error) {
            failure(output, 1, error);
        } finally {
            System.exit(0);
        }
    }

    private static void capture(Semaphore requests, DataOutputStream output) {
        UiAutomation automation = null;
        int stage = 2;
        try {
            progress(output, stage);
            Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
            Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            // This constructor is used by the platform's shell UiAutomator. It also avoids
            // Context/UserManager/display and application initialization in the shell VM.
            automation = UiAutomation.class.getConstructor(Looper.class, connection)
                    .newInstance(Looper.getMainLooper(), binder);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            if (service == null) throw new IllegalStateException();
            service.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            service.packageNames = null;
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(service);

            stage = 4;
            progress(output, stage);
            // API 34+: UiAutomation.clearCache(); API 33: per-connection client cache;
            // API 30-32: process-local client cache. No cache in the product process is touched.
            Method clearCache;
            Object cacheOwner;
            Object[] cacheArgs = new Object[0];
            try {
                clearCache = UiAutomation.class.getMethod("clearCache");
                cacheOwner = automation;
            } catch (NoSuchMethodException oldApi) {
                Class<?> client = Class.forName("android.view.accessibility.AccessibilityInteractionClient");
                cacheOwner = client.getMethod("getInstance").invoke(null);
                try { clearCache = client.getMethod("clearCache"); }
                catch (NoSuchMethodException perConnection) {
                    clearCache = client.getMethod("clearCache", int.class);
                    cacheArgs = new Object[]{UiAutomation.class.getMethod("getConnectionId").invoke(automation)};
                }
            }
            while (true) {
                // The app requests exactly one snapshot after consuming/rendering the last.
                // No unsolicited frames can accumulate in libadb's input queue.
                requests.acquire();
                stage = 4;
                progress(output, stage);
                clearCache.invoke(cacheOwner, cacheArgs);
                AdbLayoutSnapshot snapshot = new AdbLayoutNodes().read(automation);
                stage = 6;
                progress(output, stage);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                snapshot.write(new DataOutputStream(bytes));
                if (bytes.size() > AdbLayoutSnapshot.MAX_BYTES) throw new IllegalStateException();
                synchronized (output) {
                    output.writeInt(AdbLayoutSnapshot.MAGIC);
                    output.writeInt(bytes.size());
                    bytes.writeTo(output); output.flush();
                }
            }
        } catch (Throwable error) {
            failure(output, stage, error);
        } finally {
            if (automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Throwable ignored) { }
            }
            System.exit(0);
        }
    }

    private static void progress(DataOutputStream output, int stage) throws java.io.IOException {
        synchronized (output) {
            output.writeInt(AdbLayoutSnapshot.PROGRESS);
            output.writeInt(stage); output.writeInt(Build.VERSION.SDK_INT); output.flush();
        }
    }

    private static void failure(DataOutputStream output, int stage, Throwable error) {
        // Actual phase/API/type only. Never copy node text or raw exception messages into errors.
        for (int i = 0; i < 8 && error.getCause() != null && error.getCause() != error; i++) error = error.getCause();
        String type = error.getClass().getSimpleName();
        if (type.length() > 80) type = type.substring(0, 80);
        try {
            synchronized (output) {
                output.writeInt(AdbLayoutSnapshot.ERROR);
                output.writeInt(stage); output.writeInt(Build.VERSION.SDK_INT);
                output.writeUTF(type); output.flush();
            }
        } catch (Exception closed) { }
    }
}
