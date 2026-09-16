package io.github.rin.xiaoaivolumesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import org.json.JSONObject;

/** Diagnostic-only probe: exercises XiaoAi's own selector and a silent player. */
final class DirectMediaProbe extends BroadcastReceiver {
    private final SyncController sync;
    DirectMediaProbe(SyncController sync) { this.sync = sync; }
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject out = new JSONObject();
        try {
            if (!sync.options.effectiveDirectMedia()) throw new IllegalStateException("Direct media hook is off or unavailable");
            AudioManager audio = sync.audioForTest();
            int mediaBefore = sync.savedIndex(3);
            boolean mutedBefore = audio.isStreamMute(3);
            String selectorName = XiaoAiCompat.streamSelector(sync.targetVersionCode());
            if (selectorName == null) throw new IllegalStateException("Unsupported XiaoAi version");
            Class<?> selector = Class.forName(selectorName, false, context.getClassLoader());
            out.put("selectedMedia", (Integer) selector.getDeclaredMethod("getVoiceAssistStreamType").invoke(null) == 3);
            Class<?> mute = Class.forName("com.xiaomi.voiceassistant.utils.b0", false, context.getClassLoader());
            mute.getDeclaredMethod("setMusicStreamMute", AudioManager.class).invoke(null, audio);
            out.put("internalMuteBlocked", audio.isStreamMute(3) == mutedBefore);
            Class<?> manager = Class.forName("com.xiaomi.voiceassistant.l", false, context.getClassLoader());
            Object instance = manager.getDeclaredMethod("getInstance").invoke(null);
            manager.getDeclaredMethod("ensureXiaoaiVolume").invoke(instance);
            out.put("volumeFloorBlocked", sync.savedIndex(3) == mediaBefore);
            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(3200).setTransferMode(AudioTrack.MODE_STATIC).build();
            try {
                track.write(new byte[3200], 0, 3200);
                track.play();
                out.put("playbackStarted", track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING);
                out.put("playbackUsesMedia", track.getStreamType() == 3
                    && track.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA);
                out.put("mediaIndexUnchanged", sync.savedIndex(3) == mediaBefore);
                track.stop();
            } finally { track.release(); }
            out.put("media", sync.savedIndex(3));
        } catch (Throwable e) {
            try { out.put("error", e.toString()); } catch (Exception ignored) {}
        }
        Log.i(Contract.TAG, "DIRECT_TEST " + out);
        setResultCode(1); setResultData(out.toString());
        sync.report("direct-test");
    }
}
