package com.kidslock.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 锁屏守护（无障碍服务）—— Google TV / 原生 Android TV 防绕过的核心。
 *
 * 背景：Google TV 没有"选择默认桌面"的入口，HOME 键会被系统直接送回
 * Google TV 主屏，原来的 HOME launcher alias 方案在这些设备上不起作用。
 *
 * 无障碍服务可以在系统处理 HOME 之前拿到按键（flagRequestFilterKeyEvents），
 * 并且属于后台启动 Activity 的豁免对象，因此：
 * 1. 锁屏时吞掉 HOME / 最近任务 / 设置 / 语音助手 / 应用快捷键等，只放行
 *    方向键、确认、返回、数字、音量、电源；
 * 2. 锁屏时一旦检测到其他应用切到前台（窗口状态变化），立即把答题界面拉回来；
 * 3. 开机时系统自动绑定本服务，若处于锁屏状态立即弹出答题界面。
 *
 * 未锁屏（家长模式 / 自由观看中）时不拦截任何按键、不做任何事。
 */
public class GuardAccessibilityService extends AccessibilityService {

    private static final String TAG = "GuardA11y";
    private static final long RELAUNCH_DEBOUNCE_MS = 600;

    private PrefManager pref;
    private long lastLaunchAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        pref = new PrefManager(this);
        Log.i(TAG, "Guard accessibility service connected");
        if (pref.isLocked()) {
            launchLockScreen();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (pref == null || !pref.isLocked()) return false;
        int code = event.getKeyCode();
        if (isAllowedWhileLocked(code)) return false;

        // 被拦截的键（HOME 等）：吞掉 DOWN/UP，并确保答题界面在最前面
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Blocked key while locked: " + KeyEvent.keyCodeToString(code));
            launchLockScreen();
        }
        return true;
    }

    /** 锁屏时允许的按键：答题和 PIN 输入所需，以及音量/电源。 */
    private static boolean isAllowedWhileLocked(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return true;
            default:
                return (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                        || (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pref == null || !pref.isLocked()) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        // 自己的界面、系统 UI（音量条等）不处理，避免来回抢焦点
        if (p.equals(getPackageName()) || p.startsWith("com.android.systemui")) return;

        Log.i(TAG, "Foreign window while locked: " + p + ", pulling lock screen back");
        launchLockScreen();
    }

    private void launchLockScreen() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchAt < RELAUNCH_DEBOUNCE_MS) return;
        lastLaunchAt = now;
        Intent i = new Intent(this, LockScreenActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock screen", e);
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** 本服务是否已在系统"无障碍"中开启。 */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(context, GuardAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName c = ComponentName.unflattenFromString(splitter.next());
            if (me.equals(c)) return true;
        }
        return false;
    }

    /** adb 一键开启命令（Google TV 设置里找不到入口时用）。 */
    public static String adbEnableCommand(Context context) {
        String comp = new ComponentName(context, GuardAccessibilityService.class).flattenToShortString();
        return "adb shell settings put secure enabled_accessibility_services " + comp + "\n"
                + "adb shell settings put secure accessibility_enabled 1";
    }
}
