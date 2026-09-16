package io.github.rin.xiaoaivolumesync;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Redirects XiaoAi's assistant-volume reads and playback at stable Android framework APIs. */
final class DirectMediaHooks {
    private final RuntimeOptions options;
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    DirectMediaHooks(RuntimeOptions options) { this.options = options; }

    void install(ClassLoader loader) throws Throwable {
        try {
            int readHooks = 0;
            for (String name : new String[]{"getStreamVolume", "getStreamMaxVolume", "getStreamMinVolume",
                "getStreamMinVolumeInt", "getLastAudibleStreamVolume", "isStreamMute"}) {
                try {
                    Method method = AudioManager.class.getDeclaredMethod(name, int.class);
                    method.setAccessible(true);
                    hook(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (options.effectiveDirectMedia() && !options.bypassFrameworkRedirect()
                                && (Integer) p.args[0] == 11) p.args[0] = AudioManager.STREAM_MUSIC;
                        }
                    });
                    readHooks++;
                } catch (NoSuchMethodException ignored) {}
            }
            if (readHooks < 3) throw new NoSuchMethodException("Required AudioManager volume readers");
            hooks.addAll(XposedBridge.hookAllConstructors(AudioTrack.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!options.effectiveDirectMedia() || p.args.length == 0) return;
                    // Builder's private constructor starts with Context, then AudioAttributes.
                    // Rewrite every AudioAttributes argument, not just the first argument.
                    for (int i = 0; i < p.args.length; i++) {
                        if (p.args[i] instanceof AudioAttributes)
                            p.args[i] = playbackAttributes((AudioAttributes) p.args[i]);
                    }
                    if (p.args.length >= 6 && p.args[0] instanceof Integer && (Integer) p.args[0] == 11) p.args[0] = 3;
                }
            }));
            hook(MediaPlayer.class.getDeclaredMethod("setAudioAttributes", AudioAttributes.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (options.effectiveDirectMedia() && p.args[0] != null) p.args[0] = playbackAttributes((AudioAttributes) p.args[0]);
                }
            });
            hook(MediaPlayer.class.getDeclaredMethod("setAudioStreamType", int.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (options.effectiveDirectMedia() && (Integer) p.args[0] == 11) p.args[0] = 3;
                }
            });
            int privateGuards = installPrivateGuards(loader);
            options.directAvailable = true;
            Log.i(Contract.TAG, "DIRECT_MEDIA_HOOKS_READY " + Contract.VERSION
                + " frameworkReads=" + readHooks + " privateGuards=" + privateGuards
                + " hooks=" + hooks.size());
        } catch (Throwable e) {
            for (XC_MethodHook.Unhook h : hooks) try { h.unhook(); } catch (Throwable ignored) {}
            options.directAvailable = false;
            throw e;
        }
    }
    private void hook(Method method, XC_MethodHook callback) { hooks.add(XposedBridge.hookMethod(method, callback)); }
    private int installPrivateGuards(ClassLoader loader) {
        int installed = 0;
        try {
            Class<?> mute = Class.forName("com.xiaomi.voiceassistant.utils.b0", false, loader);
            installed += hookOptionalVoid(mute, "setMusicStreamMute", true, AudioManager.class);
            installed += hookOptionalVoid(mute, "setMusicStreamUnMute", true, AudioManager.class);
        } catch (Throwable e) { Log.w(Contract.TAG, "Optional media-mute guards unavailable: " + e); }
        try {
            Class<?> volume = Class.forName("com.xiaomi.voiceassistant.l", false, loader);
            installed += hookOptionalVoid(volume, "checkMute", true);
            installed += hookOptionalVoid(volume, "ensureXiaoaiVolume", false);
            installed += hookOptionalVoid(volume, "setPreAutoChangeVolume", false, int.class);
            // Current automatic set/restore helpers. They are optional because their obfuscated
            // names can change without affecting the framework-level redirect.
            installed += hookOptionalVoid(volume, "j", false);
            installed += hookOptionalVoid(volume, "k", false);
        } catch (Throwable e) { Log.w(Contract.TAG, "Optional auto-volume guards unavailable: " + e); }
        return installed;
    }
    private int hookOptionalVoid(Class<?> owner, String name, boolean mediaMuteGuard, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != void.class) return 0;
            hook(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (mediaMuteGuard ? options.blockMediaMute : options.effectiveDirectMedia()) p.setResult(null);
                }
            });
            return 1;
        } catch (Throwable e) {
            Log.w(Contract.TAG, "Optional guard unavailable " + owner.getName() + "#" + name + ": " + e.getClass().getSimpleName());
            return 0;
        }
    }
    private static AudioAttributes playbackAttributes(AudioAttributes original) {
        if (original.getUsage() != AudioAttributes.USAGE_ASSISTANT) return original;
        // Only playback objects change; audio-focus requests keep their original attributes.
        return new AudioAttributes.Builder(original).setUsage(AudioAttributes.USAGE_MEDIA).build();
    }
}
