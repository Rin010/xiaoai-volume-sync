package io.github.rin.xiaoaivolumesync;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private TextView status;
    private final Handler handler = new Handler();
    private long requestedAt;
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String content, int size, int color) {
        TextView v = new TextView(this);
        v.setText(content); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, dp(10), 0, dp(10));
        return v;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(242, 248, 245));
        getWindow().setNavigationBarColor(Color.rgb(242, 248, 245));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(28), dp(28), dp(24));
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(28) + bars.left, dp(28) + bars.top, dp(28) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        body.setBackgroundColor(Color.rgb(242, 248, 245));
        scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        WindowInsetsController barsController = getWindow().getInsetsController();
        if (barsController != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            barsController.setSystemBarsAppearance(appearance, appearance);
        }
        body.addView(text("小爱音量设置", 28, Color.rgb(24, 92, 82)));
        body.addView(text("按需要选择音量的来源与按键行为。", 17, Color.DKGRAY));
        body.addView(text("功能开关", 20, Color.BLACK));
        SharedPreferences settings = getSharedPreferences(Contract.PREFS, 0);
        body.addView(option("小爱音量同步媒体音量", Contract.SYNC_KEY, settings.getBoolean(Contract.SYNC_KEY, true)));
        body.addView(option("小爱同学使用媒体音量", Contract.DIRECT_KEY, settings.getBoolean(Contract.DIRECT_KEY, false)));
        body.addView(option("小爱同学前台按键修改媒体音量", Contract.KEYS_KEY, settings.getBoolean(Contract.KEYS_KEY, true)));
        body.addView(text("“使用媒体音量”打开后，小爱的播放会走媒体流；“同步”同时打开时，独立小爱滑块仍会跟随媒体，但不再决定小爱的播报音量。\n\n关闭前台按键转发而仍使用独立小爱流时，音量键可能会被同步规则拉回。切换播放流后请重启小爱进程，使已缓存的播放器重新创建。", 15, Color.DKGRAY));
        status = text("正在检查小爱进程…", 17, Color.rgb(24, 92, 82));
        body.addView(status);
        Button refresh = new Button(this); refresh.setText("刷新运行状态");
        refresh.setOnClickListener(v -> requestStatus()); body.addView(refresh);
        body.addView(text("启用方法", 20, Color.BLACK));
        body.addView(text("1. 在 LSPosed 中启用「小爱音量跟随」。\n2. 勾选「超级小爱 / 小爱同学」和「系统框架」。\n3. 重启平板，让系统按键 Hook 加载。\n4. 选开关后刷新运行状态。", 16, Color.DKGRAY));
        body.addView(text("停用模块：在 LSPosed 禁用，再重启平板。", 15, Color.DKGRAY));
        body.addView(text("版本 " + Contract.VERSION + " · 无网络权限", 13, Color.GRAY));
    }
    @Override public void onResume() { super.onResume(); requestStatus(); }
    private Switch option(String label, String key, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label); control.setTextColor(Color.BLACK); control.setTextSize(16);
        control.setPadding(0, dp(10), 0, dp(10)); control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> {
            getSharedPreferences(Contract.PREFS, 0).edit().putBoolean(key, value).apply();
            sendBroadcast(new Intent(Contract.OPTIONS_ACTION).setPackage(Contract.TARGET));
            sendBroadcast(new Intent(Contract.OPTIONS_ACTION).setPackage("android"));
            requestStatus();
        });
        return control;
    }
    private void requestStatus() {
        requestedAt = System.currentTimeMillis();
        status.setText("正在检查小爱进程…");
        sendBroadcast(new Intent(Contract.STATUS_ACTION).setPackage(Contract.TARGET));
        sendBroadcast(new Intent(Contract.ROUTE_STATUS_ACTION).setPackage("android"));
        handler.postDelayed(this::renderStatus, 1200);
    }
    private void renderStatus() {
        if (isFinishing() || isDestroyed()) return;
        SharedPreferences p = getSharedPreferences(Contract.PREFS, 0);
        long updated = p.getLong("updated", 0);
        if (updated == 0) {
            status.setText("尚未收到小爱进程的模块回报。\n请按下面步骤启用模块，再打开小爱。\n仅安装 APK 不代表已经生效。");
            return;
        }
        String heading = updated >= requestedAt ? "已收到小爱进程实时回报" : "历史回报（当前未收到回应）";
        String error = p.getString("error", "");
        String routeVersion = p.getString("routeVersion", "");
        String route = p.getLong("routeUpdated", 0) >= requestedAt
            ? (Contract.VERSION.equals(routeVersion) ? (p.getBoolean("routeEnabled", false) ? "已启用" : "已关闭") + " " + routeVersion
                : "系统仍运行 " + routeVersion + "，请重启平板")
            : "未收到实时回应，请确认系统框架作用域并重启";
        status.setText(heading + "\n\n媒体：" + p.getInt("media", -1) + " / " + p.getInt("mediaMax", -1)
            + "\n小爱：" + p.getInt("assistant", -1) + " / " + p.getInt("assistantMax", -1)
            + "\n目标档位：" + p.getInt("target", -1)
            + "\n媒体流当前静音：" + (p.getBoolean("temporaryMediaMute", false) ? "是" : "否")
            + "\n播放选择：流 " + p.getInt("selectedStream", -1) + (p.getInt("selectedStream", -1) == 3 ? "（媒体）" : "（小爱）")
            + "\n最近 AudioTrack：流 " + p.getInt("playbackStream", -1) + "，用途 " + p.getInt("playbackUsage", -1)
            + "\n同步开关实际值：" + (p.getBoolean("syncEnabled", false) ? "开" : "关")
            + "\n使用媒体实际值：" + (p.getBoolean("directMediaEnabled", false) ? "开" : "关")
            + "\n模块：" + p.getString("version", "") + "\n小爱版本：" + p.getString("targetVersion", "")
            + "\n前台音量键转发：" + route
            + "\n系统识别小爱前台：" + (p.getBoolean("xiaoaiFront", false) ? "是" : "否")
            + "\n回报时间：" + DateFormat.getDateTimeInstance().format(new Date(updated))
            + (error.isEmpty() ? "" : "\n异常：" + error));
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
