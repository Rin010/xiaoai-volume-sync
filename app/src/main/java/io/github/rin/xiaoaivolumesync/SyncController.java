package io.github.rin.xiaoaivolumesync;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;

final class SyncController {
    private static final int MUSIC = 3, ASSISTANT = 11;
    private static final String VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    private static final String DEVICES = "android.media.STREAM_DEVICES_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private final Context context;
    private final String processName;
    private final AudioManager audio;
    final RuntimeOptions options;
    private final Method lastAudible;
    private final Method minimumIndex;
    private final Handler worker;
    private final Object lock = new Object();
    private final ThreadLocal<Boolean> internalWrite = new ThreadLocal<>();
    private volatile String pendingReason = "event";
    private volatile String lastError = "";
    private volatile long lastErrorAt;
    private long lastRewriteAt;
    private long versionCode;
    private String versionName = "unknown";
    private volatile int playbackStream = -1, playbackUsage = -1;
    private volatile long playbackAt;
    private volatile boolean activityFront, overlayFront;
    private boolean lastReportedFront;
    private boolean supported;
    private final Runnable reconcile = () -> syncNow(pendingReason);

    SyncController(Context context, String processName, RuntimeOptions options) throws Exception {
        this.context = context;
        this.processName = processName;
        this.options = options;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        lastAudible = AudioManager.class.getDeclaredMethod("getLastAudibleStreamVolume", int.class);
        lastAudible.setAccessible(true);
        // API 36's public getStreamMinVolume rejects hidden STREAM_ASSISTANT (11).
        minimumIndex = AudioManager.class.getDeclaredMethod("getStreamMinVolumeInt", int.class);
        minimumIndex.setAccessible(true);
        PackageInfo info = context.getPackageManager().getPackageInfo(Contract.TARGET, 0);
        versionName = info.versionName; versionCode = info.getLongVersionCode();
        int min = (Integer) minimumIndex.invoke(audio, ASSISTANT), max = audio.getStreamMaxVolume(ASSISTANT);
        supported = min == 0 && max > 0;
        // A genuinely aliased assistant stream must not be written: that could change media itself.
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            Method alias = AudioManager.class.getDeclaredMethod("getStreamTypeAlias", int.class);
            alias.setAccessible(true);
            if (((Integer) alias.invoke(audio, ASSISTANT)) != ASSISTANT) supported = false;
        } else {
            // XiaoAi has QUERY_AUDIO_STATE but not MODIFY_AUDIO_SETTINGS_PRIVILEGED.
            // Read the same resource used by AudioService instead of requesting more privileges.
            int independent = context.getResources().getIdentifier("config_useAssistantVolume", "bool", "android");
            int singleVolume = context.getResources().getIdentifier("config_singleVolume", "bool", "android");
            if (independent == 0 || !context.getResources().getBoolean(independent)
                || (singleVolume != 0 && context.getResources().getBoolean(singleVolume))) supported = false;
        }
        if (!supported) throw new IllegalStateException("Requires an independent assistant stream with minimum 0");
        HandlerThread thread = new HandlerThread("XiaoAiVolumeSync");
        thread.start(); worker = new Handler(thread.getLooper());
    }

    long targetVersionCode() { return versionCode; }
    boolean isInternalWrite() { return Boolean.TRUE.equals(internalWrite.get()); }
    int savedIndex(int stream) throws Exception {
        options.beginFrameworkBypass();
        try { return (Integer) lastAudible.invoke(audio, stream); }
        finally { options.endFrameworkBypass(); }
    }
    int rawMax(int stream) {
        options.beginFrameworkBypass();
        try { return audio.getStreamMaxVolume(stream); }
        finally { options.endFrameworkBypass(); }
    }
    private int rawMin(int stream) throws Exception {
        options.beginFrameworkBypass();
        try { return (Integer) minimumIndex.invoke(audio, stream); }
        finally { options.endFrameworkBypass(); }
    }
    int readTarget() throws Exception {
        if (!supported) throw new IllegalStateException("Unsupported stream configuration");
        return VolumeMath.map(savedIndex(MUSIC), rawMax(MUSIC), rawMin(ASSISTANT), rawMax(ASSISTANT));
    }

    void start(boolean primaryProcess) {
        BroadcastReceiver unlocked = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                options.refresh();
                if (options.sync) requestSync("user-unlocked");
            }
        };
        IntentFilter unlockedFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(unlocked, unlockedFilter, null, worker, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(unlocked, unlockedFilter, null, worker);
        register(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                options.refresh();
                if (options.sync) requestSync("options-changed");
                report("options-changed");
            }
        }, new IntentFilter(Contract.OPTIONS_ACTION), Contract.STATUS_PERMISSION);
        if (primaryProcess) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(VOLUME); filter.addAction(DEVICES);
            register(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    int stream = intent.getIntExtra(STREAM, -1);
                    if (stream == MUSIC || stream == ASSISTANT) requestSync(stream == MUSIC ? "media-event" : "assistant-event");
                }
            }, filter, null);
            audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                private void changed() {
                    requestSync("device-change");
                    // Routing and saved index restoration can follow device notification asynchronously.
                    worker.postDelayed(() -> requestSync("device-settled"), 350);
                }
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { changed(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { changed(); }
            }, worker);
            register(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) { report("status-request"); }
            }, new IntentFilter(Contract.STATUS_ACTION), Contract.STATUS_PERMISSION);
            if (BuildFlags.DIAGNOSTICS) {
                register(new DebugProbe(this), new IntentFilter(Contract.TEST_ACTION), "android.permission.DUMP");
                register(new DirectMediaProbe(this), new IntentFilter(Contract.DIRECT_TEST_ACTION), "android.permission.DUMP");
            }
        }
        Log.i(Contract.TAG, "READY " + Contract.VERSION + " process=" + processName + " primary=" + primaryProcess + " targetVersion=" + versionName);
        syncNow("startup");
    }

    void trackForeground(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    activity.setVolumeControlStream(options.effectiveDirectMedia() || options.frontKeys ? MUSIC : ASSISTANT);
                    setActivityFront(true);
                } catch (Throwable e) { failure("activity-volume", e); }
            }
            @Override public void onActivityPaused(Activity activity) { setActivityFront(false); }
            @Override public void onActivityCreated(Activity activity, Bundle saved) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    void setActivityFront(boolean value) { activityFront = value; updateFrontState(); }
    void setOverlayFront(boolean value) { overlayFront = value; updateFrontState(); }
    private synchronized void updateFrontState() {
        boolean front = activityFront || overlayFront;
        if (front == lastReportedFront) return;
        try {
            Bundle state = new Bundle(); state.putBoolean("front", front);
            state.putInt("pid", Process.myPid());
            context.getContentResolver().call(Contract.STATUS_URI, "frontState", null, state);
            lastReportedFront = front;
            Log.i(Contract.TAG, "XIAOAI_FRONT " + front + " activity=" + activityFront + " overlay=" + overlayFront);
        } catch (Throwable e) { failure("front-state", e); }
    }

    private void register(BroadcastReceiver receiver, IntentFilter filter, String senderPermission) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, senderPermission, worker, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, senderPermission, worker);
    }

    void requestSync(String reason) {
        if (!options.sync) return;
        pendingReason = reason;
        // A leading-edge task avoids starving synchronization during continuous slider movement.
        if (!worker.hasCallbacks(reconcile)) worker.post(reconcile);
    }

    boolean syncNow(String reason) {
        if (!options.sync) return false;
        if (isInternalWrite()) return true;
        synchronized (lock) {
            if (!options.sync) return false;
            try {
                int target = readTarget();
                int old = savedIndex(ASSISTANT);
                if (old != target) {
                    internalWrite.set(true);
                    try { audio.setStreamVolume(ASSISTANT, target, 0); }
                    finally { internalWrite.remove(); }
                    Log.i(Contract.TAG, "SYNC " + reason + " media=" + savedIndex(MUSIC) + "/" + rawMax(MUSIC) + " assistant=" + old + "->" + target);
                }
                lastError = "";
                worker.removeCallbacks(reportTask);
                pendingReportReason = reason;
                worker.postDelayed(reportTask, 80);
                return true;
            } catch (Throwable e) {
                failure(reason, e);
                return false;
            }
        }
    }

    private volatile String pendingReportReason = "state";
    private final Runnable reportTask = () -> report(pendingReportReason);

    void report(String reason) {
        try {
            Bundle out = new Bundle();
            out.putString("version", Contract.VERSION); out.putString("targetVersion", versionName);
            out.putString("process", processName); out.putString("reason", reason); out.putString("error", lastError);
            out.putInt("media", savedIndex(MUSIC)); out.putInt("mediaMax", rawMax(MUSIC));
            out.putInt("assistant", savedIndex(ASSISTANT)); out.putInt("assistantMax", rawMax(ASSISTANT));
            out.putInt("target", readTarget()); out.putInt("pid", Process.myPid());
            out.putBoolean("temporaryMediaMute", audio.isStreamMute(MUSIC));
            out.putBoolean("syncEnabled", options.sync);
            out.putBoolean("directMediaEnabled", options.effectiveDirectMedia());
            out.putInt("selectedStream", options.effectiveDirectMedia() ? MUSIC : ASSISTANT);
            out.putInt("playbackStream", playbackStream); out.putInt("playbackUsage", playbackUsage);
            out.putLong("playbackAt", playbackAt);
            context.getContentResolver().call(Contract.STATUS_URI, "report", null, out);
        } catch (Throwable e) {
            // Status UI is optional. A stopped/uninstalled companion must never break XiaoAi.
            Log.w(Contract.TAG, "Status report unavailable: " + e.getClass().getSimpleName());
        }
    }

    void failure(String stage, Throwable e) {
        lastError = stage + ": " + e;
        long now = SystemClock.elapsedRealtime();
        if (now - lastErrorAt > 5000) { lastErrorAt = now; Log.e(Contract.TAG, stage, e); }
    }
    void logRewrite(int requested, int target) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRewriteAt > 500) {
            lastRewriteAt = now;
            Log.i(Contract.TAG, "CLAMP assistant request=" + requested + " target=" + target);
        }
    }
    AudioManager audioForTest() { return audio; }
    void played(int stream, int usage) {
        playbackStream = stream; playbackUsage = usage; playbackAt = System.currentTimeMillis();
        Log.i(Contract.TAG, "PLAYBACK AudioTrack stream=" + stream + " usage=" + usage);
        report("playback");
    }
}
