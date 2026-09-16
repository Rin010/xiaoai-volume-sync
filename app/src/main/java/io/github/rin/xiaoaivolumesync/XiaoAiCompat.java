package io.github.rin.xiaoaivolumesync;

/** Explicit mappings for XiaoAi builds whose obfuscated audio classes were inspected. */
final class XiaoAiCompat {
    private static final long PAD_7_13_33 = 507013033L;
    private static final long PHONE_7_13_21 = 507013021L;

    static String streamSelector(long versionCode) {
        if (versionCode == PAD_7_13_33) return "k00.o";
        if (versionCode == PHONE_7_13_21) return "d00.o";
        return null;
    }
    static boolean supportsDirectMedia(long versionCode) { return streamSelector(versionCode) != null; }
    static boolean supportsKnownVolumeFloor(long versionCode) {
        return versionCode == PAD_7_13_33 || versionCode == PHONE_7_13_21;
    }
    private XiaoAiCompat() {}
}
