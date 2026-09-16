package io.github.rin.xiaoaivolumesync;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/** A snapshot of the switches, read from the module's UID-checked provider. */
final class RuntimeOptions {
    volatile boolean sync = true, directMedia = false, frontKeys = true, blockMediaMute = true;
    volatile boolean directAvailable;
    private final Context context;
    private final ThreadLocal<Integer> frameworkBypass = new ThreadLocal<>();
    RuntimeOptions(Context context) { this.context = context; refresh(); }
    boolean effectiveDirectMedia() { return directMedia && directAvailable; }
    boolean bypassFrameworkRedirect() {
        Integer depth = frameworkBypass.get();
        return depth != null && depth > 0;
    }
    void beginFrameworkBypass() {
        Integer depth = frameworkBypass.get();
        frameworkBypass.set(depth == null ? 1 : depth + 1);
    }
    void endFrameworkBypass() {
        Integer depth = frameworkBypass.get();
        if (depth == null || depth <= 1) frameworkBypass.remove();
        else frameworkBypass.set(depth - 1);
    }
    void refresh() {
        try {
            Bundle values = context.getContentResolver().call(Contract.STATUS_URI, "getOptions", null, null);
            if (values == null) throw new IllegalStateException("No settings reply");
            sync = values.getBoolean(Contract.SYNC_KEY, true);
            directMedia = values.getBoolean(Contract.DIRECT_KEY, false);
            frontKeys = values.getBoolean(Contract.KEYS_KEY, true);
            blockMediaMute = values.getBoolean(Contract.MUTE_KEY, true);
            Log.i(Contract.TAG, "OPTIONS sync=" + sync + " direct=" + directMedia
                + " frontKeys=" + frontKeys + " blockMediaMute=" + blockMediaMute);
        } catch (Throwable e) { Log.w(Contract.TAG, "Options unavailable; using last values: " + e.getClass().getSimpleName()); }
    }
}
