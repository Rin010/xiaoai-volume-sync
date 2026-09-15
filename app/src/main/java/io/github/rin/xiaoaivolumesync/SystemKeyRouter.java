package io.github.rin.xiaoaivolumesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** System-server hook changes one argument; all volume policy and application stays in AudioService. */
final class SystemKeyRouter {
    private final AtomicBoolean receiverRegistered = new AtomicBoolean();
    void install(ClassLoader loader) {
        try {
            Class<?> service = XposedHelpers.findClass("com.android.server.audio.AudioService", loader);
            int count = 0;
            for (Method method : service.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!method.getName().equals("adjustStreamVolume") || params.length < 4
                    || params[0] != int.class || params[1] != int.class || params[2] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        int stream = (Integer) p.args[0];
                        int direction = (Integer) p.args[1];
                        int flags = (Integer) p.args[2];
                        int routed = KeyRoutePolicy.route(stream, direction, flags);
                        if (routed != stream) {
                            p.args[0] = routed;
                            Log.i(Contract.TAG, "KEY_ROUTE assistant->media direction=" + direction + " flags=0x" + Integer.toHexString(flags));
                        }
                    }
                });
                count++;
            }
            if (count == 0) throw new NoSuchMethodException("AudioService.adjustStreamVolume(int,int,int,...)");
            XposedBridge.hookAllConstructors(service, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || receiverRegistered.get()) return;
                    try {
                        Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                        if (context == null || !receiverRegistered.compareAndSet(false, true)) return;
                        BroadcastReceiver receiver = new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent intent) {
                                try {
                                    Bundle report = new Bundle();
                                    report.putString("version", Contract.VERSION);
                                    c.getContentResolver().call(Contract.STATUS_URI, "routeStatus", null, report);
                                } catch (Throwable e) { Log.w(Contract.TAG, "Route status unavailable: " + e.getClass().getSimpleName()); }
                            }
                        };
                        IntentFilter filter = new IntentFilter(Contract.ROUTE_STATUS_ACTION);
                        Handler handler = new Handler(Looper.getMainLooper());
                        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Contract.STATUS_PERMISSION, handler, Context.RECEIVER_EXPORTED);
                        else context.registerReceiver(receiver, filter, Contract.STATUS_PERMISSION, handler);
                    } catch (Throwable e) { receiverRegistered.set(false); Log.w(Contract.TAG, "Route status initialization", e); }
                }
            });
            Log.i(Contract.TAG, "SYSTEM_KEY_ROUTER_READY " + Contract.VERSION + " hooks=" + count);
            XposedBridge.log(Contract.TAG + " SYSTEM_KEY_ROUTER_READY " + Contract.VERSION);
        } catch (Throwable e) {
            // No service replacement and no exceptions escape into system_server.
            Log.e(Contract.TAG, "System key router unavailable", e);
            XposedBridge.log(Contract.TAG + " System key router unavailable: " + e);
        }
    }
}
