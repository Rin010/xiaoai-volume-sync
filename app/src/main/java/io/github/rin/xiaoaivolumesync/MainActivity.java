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
        body.addView(text("小爱音量跟随", 28, Color.rgb(24, 92, 82)));
        body.addView(text("媒体音量调好，小爱一起跟随。", 17, Color.DKGRAY));
        status = text("正在检查小爱进程…", 17, Color.rgb(24, 92, 82));
        body.addView(status);
        Button refresh = new Button(this); refresh.setText("刷新运行状态");
        refresh.setOnClickListener(v -> requestStatus()); body.addView(refresh);
        body.addView(text("启用方法", 20, Color.BLACK));
        body.addView(text("1. 在 LSPosed 中启用「小爱音量跟随」。\n2. 勾选「超级小爱 / 小爱同学」和「系统框架」。\n3. 重启平板，让音量键转发生效。\n4. 返回这里刷新状态。", 16, Color.DKGRAY));
        body.addView(text("跟随规则", 20, Color.BLACK));
        body.addView(text("按两条音量流的最大档位换算。媒体为零时小爱为零；媒体非零时，小爱至少保留一个档位。\n\n识别期间小爱临时静音媒体，不会把小爱音量一起归零。小爱的自动音量调整会受跟随规则约束。", 16, Color.DKGRAY));
        body.addView(text("当前版本的范围", 20, Color.BLACK));
        body.addView(text("小爱激活时，音量键会调整媒体音量，小爱再跟随新值。\n\n系统中的小爱滑块仍会显示，手动拖动它不会反向改变媒体音量。小爱未运行时不持续同步，启动和播放前会校正。\n\n停用：在 LSPosed 禁用本模块，再重启平板。", 15, Color.DKGRAY));
        body.addView(text("版本 " + Contract.VERSION + " · 无网络权限", 13, Color.GRAY));
    }
    @Override public void onResume() { super.onResume(); requestStatus(); }
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
        String route = p.getLong("routeUpdated", 0) >= requestedAt ? "已加载 " + p.getString("routeVersion", "") : "未收到实时回应，请确认系统框架作用域并重启";
        status.setText(heading + "\n\n媒体：" + p.getInt("media", -1) + " / " + p.getInt("mediaMax", -1)
            + "\n小爱：" + p.getInt("assistant", -1) + " / " + p.getInt("assistantMax", -1)
            + "\n目标档位：" + p.getInt("target", -1)
            + "\n媒体流当前静音：" + (p.getBoolean("temporaryMediaMute", false) ? "是" : "否")
            + "\n模块：" + p.getString("version", "") + "\n小爱版本：" + p.getString("targetVersion", "")
            + "\n音量键转发：" + route
            + "\n回报时间：" + DateFormat.getDateTimeInstance().format(new Date(updated))
            + (error.isEmpty() ? "" : "\n异常：" + error));
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
