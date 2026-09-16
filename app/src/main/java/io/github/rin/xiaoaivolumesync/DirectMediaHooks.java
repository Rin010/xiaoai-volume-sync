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

/** XiaoAi's built-in media-stream path, enabled by its own switch. */
final class DirectMediaHooks {
    private final RuntimeOptions options;
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    DirectMediaHooks(RuntimeOptions options) { this.options = options; }

    void install(ClassLoader loader, String selectorClass) throws Throwable {
        if (selectorClass == null) throw new IllegalArgumentException("No stream selector mapping");
        Class<?> stream = Class.forName(selectorClass, false, loader);
        Class<?> volume = Class.forName("com.xiaomi.voiceassistant.l", false, loader);
        Class<?> mute = Class.forName("com.xiaomi.voiceassistant.utils.b0", false, loader);
        Method select = stream.getDeclaredMethod("getVoiceAssistStreamType");
        Method[] suppressed = {
            mute.getDeclaredMethod("setMusicStreamMute", AudioManager.class),
            mute.getDeclaredMethod("setMusicStreamUnMute", AudioManager.class),
            volume.getDeclaredMethod("checkMute"),
            volume.getDeclaredMethod("ensureXiaoaiVolume"),
            volume.getDeclaredMethod("setPreAutoChangeVolume", int.class),
            volume.getDeclaredMethod("j"), // automatic volume increase
            volume.getDeclaredMethod("k")  // restore pre-increase volume
        };
        if (select.getReturnType() != int.class) throw new NoSuchMethodException("Stream selector return type");
        for (Method m : suppressed) if (m.getReturnType() != void.class) throw new NoSuchMethodException(m.toString());
        try {
            hook(select, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (options.effectiveDirectMedia()) p.setResult(AudioManager.STREAM_MUSIC);
                }
            });
            for (Method method : suppressed) hook(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (options.effectiveDirectMedia()) p.setResult(null);
                }
            });
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
            options.directAvailable = true;
            Log.i(Contract.TAG, "DIRECT_MEDIA_HOOKS_READY " + Contract.VERSION + " hooks=" + hooks.size());
        } catch (Throwable e) {
            for (XC_MethodHook.Unhook h : hooks) try { h.unhook(); } catch (Throwable ignored) {}
            options.directAvailable = false;
            throw e;
        }
    }
    private void hook(Method method, XC_MethodHook callback) { hooks.add(XposedBridge.hookMethod(method, callback)); }
    private static AudioAttributes playbackAttributes(AudioAttributes original) {
        if (original.getUsage() != AudioAttributes.USAGE_ASSISTANT) return original;
        // Only playback objects change; audio-focus requests keep their original attributes.
        return new AudioAttributes.Builder(original).setUsage(AudioAttributes.USAGE_MEDIA).build();
    }
}
