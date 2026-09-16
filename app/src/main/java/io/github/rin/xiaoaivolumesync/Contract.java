package io.github.rin.xiaoaivolumesync;

import android.net.Uri;

public final class Contract {
    public static final String MODULE = "io.github.rin.xiaoaivolumesync";
    public static final String TARGET = "com.miui.voiceassist";
    public static final String VERSION = "1.2.1";
    public static final String TAG = "XiaoAiVolumeSync";
    public static final Uri STATUS_URI = Uri.parse("content://" + MODULE + ".status");
    public static final String STATUS_ACTION = MODULE + ".REQUEST_STATUS";
    public static final String ROUTE_STATUS_ACTION = MODULE + ".REQUEST_ROUTE_STATUS";
    public static final String OPTIONS_ACTION = MODULE + ".OPTIONS_CHANGED";
    public static final String FRONT_ACTION = MODULE + ".XIAOAI_FRONT_CHANGED";
    public static final String STATUS_PERMISSION = MODULE + ".permission.STATUS";
    public static final String TEST_ACTION = MODULE + ".RUN_SELF_TEST";
    public static final String DIRECT_TEST_ACTION = MODULE + ".RUN_DIRECT_TEST";
    public static final String PREFS = "hook_status";
    public static final String SYNC_KEY = "syncEnabled";
    public static final String DIRECT_KEY = "directMediaEnabled";
    public static final String KEYS_KEY = "frontKeysEnabled";
    private Contract() {}
}
