package com.home.tvvoice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** 只截住话筒键（KEYCODE_SEARCH），打开 AI 语音；其他按键原样放行。
 *  另外只记下“当前在前台的应用包名”（不读屏幕内容），好让“搜索 XX”在你正在用的应用里搜。 */
public class MicKeyService extends AccessibilityService {
    static volatile String foreground = "";

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_SEARCH) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent i = new Intent(this, AssistActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("from_key", true).putExtra("fg", foreground);
            startActivity(i);
        }
        return true;   // 吃掉这个键，索尼的语音搜索就不会弹出
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui") || p.equals("android") || p.startsWith("com.sony.dtv.searchapp")) return;
        if (!isApp(p)) return;   // 索尼的悬浮层、提示框之类不算“正在用的应用”
        foreground = p;
    }
    final java.util.HashMap<String, Boolean> appCache = new java.util.HashMap<>();
    boolean isApp(String p) {
        Boolean b = appCache.get(p);
        if (b == null) {
            android.content.pm.PackageManager pm = getPackageManager();
            b = pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null;
            appCache.put(p, b);
        }
        return b;
    }
    @Override public void onInterrupt() { }
}
