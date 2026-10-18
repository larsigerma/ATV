package me.ashell.localadb;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shell-side node reader. No Canvas, Bitmap, fonts, encoder or system-process initialization. */
final class AdbLayoutNodes {
    private final AdbLayoutSnapshot snapshot = new AdbLayoutSnapshot();
    private int remaining = AdbLayoutSnapshot.MAX_NODES;

    @SuppressWarnings("deprecation")
    AdbLayoutSnapshot read(UiAutomation automation) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        Set<Integer> windowIds = new HashSet<>();
        List<AccessibilityWindowInfo> windows;
        RuntimeException queryError = null;
        boolean queried = false;
        try { windows = automation.getWindows(); }
        catch (RuntimeException unavailable) { windows = Collections.emptyList(); queryError = unavailable; }
        try {
            for (int i = windows.size() - 1; i >= 0; i--) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                try {
                    AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= 33
                            ? window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST) : window.getRoot();
                    addRoot(roots, windowIds, root); queried = true;
                } catch (RuntimeException staleWindow) { queryError = staleWindow; }
            }
            // A status-bar root must not suppress the app/active-window query.
            boolean activeRootFound = false;
            try {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                activeRootFound = root != null;
                addRoot(roots, windowIds, root); queried = true;
            } catch (RuntimeException unavailable) { queryError = unavailable; }
            if (!activeRootFound) {
                boolean focusFound = false;
                try {
                    AccessibilityNodeInfo focus = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    focusFound = focus != null;
                    addRoot(roots, windowIds, focus); queried = true;
                } catch (RuntimeException unavailable) { queryError = unavailable; }
                if (!focusFound) {
                    try { addRoot(roots, windowIds, automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)); queried = true; }
                    catch (RuntimeException unavailable) { queryError = unavailable; }
                }
            }
            if (!queried && queryError != null) throw queryError;
            for (AccessibilityNodeInfo root : roots) readNode(root, 0);
        } finally {
            for (AccessibilityNodeInfo root : roots) root.recycle();
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        return snapshot;
    }

    @SuppressWarnings("deprecation")
    private void addRoot(List<AccessibilityNodeInfo> roots, Set<Integer> ids, AccessibilityNodeInfo root) {
        if (root == null) return;
        AccessibilityWindowInfo window = null;
        boolean keep = false;
        try {
            window = root.getWindow();
            if (window != null && window.getDisplayId() != Display.DEFAULT_DISPLAY) return;
            int id = root.getWindowId();
            if (id < 0 ? !roots.contains(root) : ids.add(id)) { roots.add(root); keep = true; }
        } finally {
            if (window != null) window.recycle();
            if (!keep) root.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void readNode(AccessibilityNodeInfo node, int depth) {
        if (remaining-- <= 0 || depth > 64) return;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean password = node.isPassword();
            CharSequence label = password ? "••••" : node.getText();
            if (!password && TextUtils.isEmpty(label)) label = node.getContentDescription();
            if (!password && TextUtils.isEmpty(label)) label = node.getHintText();
            // Preserve bounds even if an overlay marks the node invisible. Never synthesize
            // missing nodes or copy password text into the helper/app stream.
            snapshot.add(bounds.left, bounds.top, bounds.right, bounds.bottom, node.isClickable(), label);
        } catch (RuntimeException staleNode) { }
        int children;
        try { children = node.getChildCount(); }
        catch (RuntimeException staleNode) { return; }
        for (int i = 0; i < children && remaining > 0; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = Build.VERSION.SDK_INT >= 33
                        ? node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST) : node.getChild(i);
                if (child != null) readNode(child, depth + 1);
            } catch (RuntimeException staleChild) {
                // A disappearing child does not invalidate its siblings.
            } finally { if (child != null) child.recycle(); }
        }
    }
}
