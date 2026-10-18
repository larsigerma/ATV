package me.ashell.localadb;

/** Separate JNI contract; does not use the product service/capture JNI bridge. */
public final class RemoteAdbNative {
    private RemoteAdbNative() { }

    static void attach(LocalAdbManager manager) {
        try {
            System.loadLibrary("ashell");
            register(manager);
        } catch (LinkageError ignored) {
            // Older native builds report unavailable to the PC; local ADB still works.
        }
    }

    private static native void register(LocalAdbManager manager);
    static native void complete(String token, String payload);
    static native void state(String payload);
}
