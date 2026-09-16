package io.github.rin.xiaoaivolumesync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.content.Intent;

/** The UI trusts reports only from the actual target UID, never a supplied package name. */
public final class StatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        String[] packages = getContext().getPackageManager().getPackagesForUid(caller);
        boolean target = false;
        if (packages != null) for (String name : packages) {
            if (Contract.TARGET.equals(name)) target = true;
        }
        if ("getOptions".equals(method) && (target || caller == android.os.Process.SYSTEM_UID)) {
            SharedPreferences p = getContext().getSharedPreferences(Contract.PREFS, 0);
            Bundle out = new Bundle();
            out.putBoolean(Contract.SYNC_KEY, p.getBoolean(Contract.SYNC_KEY, true));
            out.putBoolean(Contract.DIRECT_KEY, p.getBoolean(Contract.DIRECT_KEY, false));
            out.putBoolean(Contract.KEYS_KEY, p.getBoolean(Contract.KEYS_KEY, true));
            out.putBoolean(Contract.MUTE_KEY, p.getBoolean(Contract.MUTE_KEY, true));
            return out;
        }
        if ("frontState".equals(method) && target && extras != null) {
            boolean front = extras.getBoolean("front", false);
            getContext().sendBroadcast(new Intent(Contract.FRONT_ACTION).setPackage("android")
                .putExtra("front", front)
                .putExtra("pid", extras.getInt("pid", -1))
                .putExtra("uid", caller));
            return Bundle.EMPTY;
        }
        if ("routeStatus".equals(method) && caller == android.os.Process.SYSTEM_UID && extras != null) {
            getContext().getSharedPreferences(Contract.PREFS, 0).edit()
                .putString("routeVersion", extras.getString("version", ""))
                .putBoolean("routeEnabled", extras.getBoolean("enabled", false))
                .putBoolean("xiaoaiFront", extras.getBoolean("front", false))
                .putLong("routeUpdated", System.currentTimeMillis()).apply();
            return Bundle.EMPTY;
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
        out.putInt("selectedStream", extras.getInt("selectedStream", -1));
        out.putInt("playbackStream", extras.getInt("playbackStream", -1));
        out.putInt("playbackUsage", extras.getInt("playbackUsage", -1));
        out.putLong("playbackAt", extras.getLong("playbackAt", 0));
        out.putBoolean("syncEnabled", extras.getBoolean("syncEnabled", false));
        out.putBoolean("directMediaEnabled", extras.getBoolean("directMediaEnabled", false));
        out.putBoolean("blockMediaMuteEnabled", extras.getBoolean("blockMediaMuteEnabled", true));
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
