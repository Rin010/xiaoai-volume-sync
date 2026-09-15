package io.github.rin.xiaoaivolumesync;

/** Only hardware/UI key-originated assistant increments are redirected. */
public final class KeyRoutePolicy {
    private KeyRoutePolicy() {}
    public static int route(int stream, int direction, int flags) {
        final int FLAG_FROM_KEY = 0x1000;
        return stream == 11 && (direction == -1 || direction == 1) && (flags & FLAG_FROM_KEY) != 0 ? 3 : stream;
    }
}
