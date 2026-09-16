package io.github.rin.xiaoaivolumesync;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private volatile SyncController controller;
    private boolean installed;
    private boolean systemInstalled;

    @Override public synchronized void handleLoadPackage(final XC_LoadPackage.LoadPackageParam pkg) {
        if ("android".equals(pkg.packageName) && !systemInstalled) {
            systemInstalled = true;
            new SystemKeyRouter().install(pkg.classLoader);
            return;
        }
        if (!Contract.TARGET.equals(pkg.packageName) || installed) return;
        installed = true;
        XposedBridge.log(Contract.TAG + " " + Contract.VERSION + " loading in " + pkg.processName);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (controller != null) return;
                try {
                    RuntimeOptions switches = new RuntimeOptions((Context) p.args[0]);
                    SyncController ready = new SyncController((Context) p.args[0], pkg.processName, switches);
                    controller = ready;
                    try { new DirectMediaHooks(switches).install(pkg.classLoader); }
                    catch (Throwable e) { switches.directAvailable = false; error("direct-media-hooks", e); }
                    ready.start(Contract.TARGET.equals(pkg.processName));
                    if (Contract.TARGET.equals(pkg.processName)) {
                        ready.trackForeground((Application) p.thisObject);
                        try { new OverlayFrontTracker(ready).install(pkg.classLoader); }
                        catch (Throwable e) { error("overlay-front", e); }
                    }
                    hookKnownVolumeFloor(pkg, ready);
                } catch (Throwable e) { error("initialize", e); }
            }
        });
        XposedHelpers.findAndHookMethod(AudioManager.class, "setStreamVolume", int.class, int.class, int.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    SyncController c = controller;
                    if (c == null || c.isInternalWrite() || (int) p.args[0] != 11) return;
                    if (c.options.effectiveDirectMedia()) { p.setResult(null); return; }
                    if (!c.options.sync) return;
                    try {
                        int requested = (int) p.args[1];
                        int target = c.readTarget();
                        p.args[1] = target;
                        if (requested != target) c.logRewrite(requested, target);
                    } catch (Throwable e) { c.failure("set-volume-hook", e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    SyncController c = controller;
                    if (c != null && c.options.sync && !c.isInternalWrite() && (int) p.args[0] == 11) c.requestSync("assistant-write");
                }
            });
        XposedHelpers.findAndHookMethod(AudioManager.class, "adjustStreamVolume", int.class, int.class, int.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    SyncController c = controller;
                    int stream = (int) p.args[0], direction = (int) p.args[1];
                    // Preserve mute/unmute and all media operations, including XiaoAi's focus logic.
                    if (c == null || c.isInternalWrite() || stream != 11 || direction < -1 || direction > 1) return;
                    if (c.options.effectiveDirectMedia()) { p.setResult(null); return; }
                    if (!c.options.sync) return;
                    if (c.syncNow("assistant-adjust")) p.setResult(null);
                }
            });
        XposedHelpers.findAndHookMethod(AudioTrack.class, "play", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                SyncController c = controller;
                if (c == null) return;
                try {
                    if (((AudioTrack) p.thisObject).getAudioAttributes().getUsage() == 16) c.syncNow("before-play");
                } catch (Throwable e) { c.failure("before-play", e); }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                SyncController c = controller;
                if (c == null || p.hasThrowable()) return;
                try {
                    AudioTrack track = (AudioTrack) p.thisObject;
                    c.played(track.getStreamType(), track.getAudioAttributes().getUsage());
                } catch (Throwable e) { c.failure("playback-report", e); }
            }
        });
        XposedBridge.hookAllConstructors(AudioTrack.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                SyncController c = controller;
                if (c == null || p.hasThrowable()) return;
                try {
                    if (((AudioTrack) p.thisObject).getAudioAttributes().getUsage() == 16) c.syncNow("track-created");
                } catch (Throwable e) { c.failure("track-created", e); }
            }
        });
    }

    private void hookKnownVolumeFloor(XC_LoadPackage.LoadPackageParam pkg, SyncController c) {
        // Optional private guard. Framework AudioManager hooks remain the primary protection.
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.l", pkg.classLoader, "ensureXiaoaiVolume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (c.syncNow("volume-floor")) p.setResult(null);
                }
            });
            Log.i(Contract.TAG, "Installed XiaoAi volume-floor guard for " + c.targetVersionCode());
        } catch (Throwable e) { error("optional-volume-floor", e); }
    }
    private static void error(String stage, Throwable e) {
        Log.e(Contract.TAG, stage, e);
        XposedBridge.log(Contract.TAG + " " + stage + ": " + e);
    }
}
