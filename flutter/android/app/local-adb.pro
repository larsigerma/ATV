# libadb accesses Conscrypt reflectively; SPAKE2 and Conscrypt contain JNI entrypoints.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
# Independent JNI bridge and the pinned libadb TLS identity-cache reset.
-keep class me.ashell.localadb.RemoteAdbNative { *; }
-keep class me.ashell.localadb.LocalAdbMirror { native <methods>; }
-keep class me.ashell.localadb.LocalAdbTouch { native <methods>; }
-keepclassmembers class me.ashell.localadb.LocalAdbManager {
    public void dispatchRemote(java.lang.String, java.lang.String, java.lang.String);
    public void disconnectRemote(java.lang.String);
    public void cancelClipboardSync(java.lang.String);
    public void controlMirror(java.lang.String);
    public boolean routeAdbTouch(int, int, int, int, int, boolean);
    public boolean routeAdbKey(int, byte[], boolean);
    public void injectMirrorTouch(int, int, int, int, int, int);
    public void releaseTouchOwner(int);
}
-keep class io.github.muntashirakon.adb.SslUtils { *; }
# Pinned 3.1.1 OPEN readiness adapter; preserve reflected stream/connection members.
-keepclassmembers class io.github.muntashirakon.adb.AdbConnection { *; }
-keepclassmembers class io.github.muntashirakon.adb.AdbStream { *; }
