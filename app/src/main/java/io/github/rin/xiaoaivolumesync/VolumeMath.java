package io.github.rin.xiaoaivolumesync;

/** Maps saved stream indices, independently of temporary stream mute. */
public final class VolumeMath {
    private VolumeMath() {}
    public static int map(int media, int mediaMax, int assistantMin, int assistantMax) {
        if (mediaMax <= 0 || assistantMin < 0 || assistantMax < assistantMin || assistantMax <= 0) {
            throw new IllegalArgumentException("Invalid audio volume range");
        }
        int bounded = Math.max(0, Math.min(media, mediaMax));
        if (bounded == 0) return assistantMin;
        int mapped = (int) Math.round((double) bounded * assistantMax / mediaMax);
        return Math.min(assistantMax, Math.max(Math.max(1, assistantMin), mapped));
    }
}
