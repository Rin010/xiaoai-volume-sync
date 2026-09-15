import io.github.rin.xiaoaivolumesync.VolumeMath;
import io.github.rin.xiaoaivolumesync.KeyRoutePolicy;

public final class VolumeMathTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(VolumeMath.map(80, 150, 0, 15) == 8, "Observed device mapping");
        check(VolumeMath.map(15, 150, 0, 15) == 2, "Rounding at half step");
        for (int mediaMax : new int[]{1, 15, 25, 100, 150, 255, 1000, Integer.MAX_VALUE}) {
            for (int max : new int[]{1, 15, 25, 100, 255}) {
                check(VolumeMath.map(0, mediaMax, 0, max) == 0, "Zero stays silent");
                check(VolumeMath.map(mediaMax, mediaMax, 0, max) == max, "Maximum endpoint");
                check(VolumeMath.map(-1, mediaMax, 0, max) == 0, "Lower clamp");
                check(VolumeMath.map(Integer.MAX_VALUE, mediaMax, 0, max) == max, "Upper clamp");
                int previous = 0;
                for (int step = 0; step <= 1000; step++) {
                    int input = (int) ((long) mediaMax * step / 1000);
                    int value = VolumeMath.map(input, mediaMax, 0, max);
                    check(value >= previous && value <= max, "Monotonic and bounded");
                    check(input == 0 || value > 0, "Nonzero input remains audible");
                    previous = value;
                }
            }
        }
        check(VolumeMath.map(0, 150, 1, 15) == 1, "Respect a nonzero platform minimum");
        boolean rejected = false;
        try { VolumeMath.map(1, 0, 0, 15); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "Fail safely on invalid range");
        for (int stream = 0; stream <= 13; stream++) {
            for (int direction : new int[]{-100, -1, 0, 1, 100, 101}) {
                for (int flags : new int[]{0, 1, 4, 0x1000, 0x1005, 0x1011}) {
                    int routed = KeyRoutePolicy.route(stream, direction, flags);
                    if (stream == 11 && (direction == 1 || direction == -1) && (flags & 0x1000) != 0) {
                        check(routed == 3, "Assistant key press goes to media");
                    } else check(routed == stream, "Other streams, non-key calls, and mute are preserved");
                }
            }
        }
        System.out.println("PASS: endpoints, zero, monotonicity, nonzero minimum, clamps, dynamic ranges and overflow");
        System.out.println("PASS: assistant key routing and non-target operation isolation");
    }
}
