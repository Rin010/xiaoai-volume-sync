package io.github.rin.xiaoaivolumesync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The UI trusts reports only from the actual target UID, never a supplied package name. */
public final class StatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if ("routeStatus".equals(method) && caller == android.os.Process.SYSTEM_UID && extras != null) {
            getContext().getSharedPreferences(Contract.PREFS, 0).edit()
                .putString("routeVersion", extras.getString("version", ""))
                .putLong("routeUpdated", System.currentTimeMillis()).apply();
            return Bundle.EMPTY;
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(caller);
        boolean target = false;
        if (packages != null) for (String name : packages) {
            if (Contract.TARGET.equals(name)) target = true;
        }
        if (!target || !"report".equals(method) || extras == null) {
            throw new SecurityException("Only XiaoAi may report hook state");
        }
        SharedPreferences.Editor out = getContext().getSharedPreferences(Contract.PREFS, 0).edit();
        for (String key : new String[]{"version", "process", "targetVersion", "reason", "error"}) {
            String value = extras.getString(key, "");
            out.putString(key, value.substring(0, Math.min(600, value.length())));
        }
        for (String key : new String[]{"media", "mediaMax", "assistant", "assistantMax", "target", "pid"}) {
            out.putInt(key, extras.getInt(key, -1));
        }
        out.putLong("updated", System.currentTimeMillis());
        out.putBoolean("temporaryMediaMute", extras.getBoolean("temporaryMediaMute"));
        out.apply();
        return Bundle.EMPTY;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
