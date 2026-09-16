package io.github.rin.xiaoaivolumesync;

import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** XiaoAi presents as a status-bar sub-panel, which does not resume a XiaoAi Activity. */
final class OverlayFrontTracker {
    private final SyncController sync;
    private final Set<View> roots = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));
    OverlayFrontTracker(SyncController sync) { this.sync = sync; }
    void install(ClassLoader loader) {
        Class<?> manager = XposedHelpers.findClass("android.view.WindowManagerImpl", loader);
        if (XposedBridge.hookAllMethods(manager, "addView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.args.length < 2 || !(p.args[0] instanceof View)
                    || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) p.args[1];
                if (!"voice_assist_root".equals(String.valueOf(params.getTitle()))) return;
                roots.add((View) p.args[0]);
                Log.i(Contract.TAG, "XIAOAI_OVERLAY_ADDED title=" + params.getTitle());
                sync.setOverlayFront(true);
            }
        }).isEmpty()) throw new IllegalStateException("WindowManagerImpl.addView not found");
        XC_MethodHook removed = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.args.length == 0 || !(p.args[0] instanceof View)) return;
                if (roots.remove(p.args[0])) {
                    Log.i(Contract.TAG, "XIAOAI_OVERLAY_REMOVED");
                    sync.setOverlayFront(!roots.isEmpty());
                }
            }
        };
        XposedBridge.hookAllMethods(manager, "removeView", removed);
        XposedBridge.hookAllMethods(manager, "removeViewImmediate", removed);
        Log.i(Contract.TAG, "XIAOAI_OVERLAY_TRACKER_READY");
    }
}
