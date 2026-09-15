package io.github.rin.xiaoaivolumesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import org.json.JSONObject;

/** Registered only by a diagnostic build, with DUMP sender permission (ADB shell/root). */
final class DebugProbe extends BroadcastReceiver {
    private final SyncController sync;
    DebugProbe(SyncController sync) { this.sync = sync; }
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject result = new JSONObject();
        AudioManager audio = sync.audioForTest();
        boolean originallyMuted = audio.isStreamMute(3);
        boolean didMute = false;
        try {
            int expected = sync.readTarget();
            int mediaBefore = sync.savedIndex(3);
            // Exercise the real hooked entry point with an intentionally conflicting write.
            audio.setStreamVolume(11, audio.getStreamMaxVolume(11), 0);
            result.put("writeClamped", sync.savedIndex(11) == expected);
            audio.adjustStreamVolume(11, AudioManager.ADJUST_RAISE, 0);
            result.put("adjustClamped", sync.savedIndex(11) == expected);
            if (!originallyMuted) {
                audio.adjustStreamVolume(3, AudioManager.ADJUST_MUTE, 0);
                didMute = true;
            }
            sync.syncNow("probe-media-muted");
            result.put("temporaryMutePreservesTarget", sync.savedIndex(11) == expected);
            result.put("mediaIndexUnchanged", sync.savedIndex(3) == mediaBefore);
            if (sync.targetVersionCode() == 507013033L) {
                Class<?> managerClass = Class.forName("com.xiaomi.voiceassistant.l", false, context.getClassLoader());
                Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
                managerClass.getDeclaredMethod("ensureXiaoaiVolume").invoke(manager);
                result.put("floorHookRespectsTarget", sync.savedIndex(11) == expected);
            }
            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(3200).setTransferMode(AudioTrack.MODE_STATIC).build();
            try {
                track.write(new byte[3200], 0, 3200); // Silence: exercise real playback without speaking.
                track.play();
                result.put("assistantPlaybackStarted", track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING);
                result.put("assistantUsagePreserved", track.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ASSISTANT);
                result.put("playbackTargetCorrect", sync.savedIndex(11) == expected);
                track.stop();
            } finally { track.release(); }
            result.put("expected", expected);
            result.put("media", mediaBefore);
            result.put("assistant", sync.savedIndex(11));
        } catch (Throwable e) {
            try { result.put("error", e.toString()); } catch (Exception ignored) {}
        } finally {
            if (didMute) audio.adjustStreamVolume(3, AudioManager.ADJUST_UNMUTE, 0);
        }
        Log.i(Contract.TAG, "SELF_TEST " + result);
        setResultCode(1); setResultData(result.toString());
        sync.report("self-test");
    }
}
