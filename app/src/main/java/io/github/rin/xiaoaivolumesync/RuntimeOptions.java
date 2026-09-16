package io.github.rin.xiaoaivolumesync;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/** A snapshot of the switches, read from the module's UID-checked provider. */
final class RuntimeOptions {
    volatile boolean sync = true, directMedia = false, frontKeys = true;
    volatile boolean directAvailable;
    private final Context context;
    RuntimeOptions(Context context) { this.context = context; refresh(); }
    boolean effectiveDirectMedia() { return directMedia && directAvailable; }
    void refresh() {
        try {
            Bundle values = context.getContentResolver().call(Contract.STATUS_URI, "getOptions", null, null);
            if (values == null) throw new IllegalStateException("No settings reply");
            sync = values.getBoolean(Contract.SYNC_KEY, true);
            directMedia = values.getBoolean(Contract.DIRECT_KEY, false);
            frontKeys = values.getBoolean(Contract.KEYS_KEY, true);
            Log.i(Contract.TAG, "OPTIONS sync=" + sync + " direct=" + directMedia + " frontKeys=" + frontKeys);
        } catch (Throwable e) { Log.w(Contract.TAG, "Options unavailable; using last values: " + e.getClass().getSimpleName()); }
    }
}
