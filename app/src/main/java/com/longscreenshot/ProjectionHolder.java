package com.longscreenshot;

import android.content.Intent;

/**
 * 在同一进程内传递 MediaProjection 授权数据（内存共享）。
 * MainActivity 授权后存入，ScreenshotService 直接读取。
 * 前提是：MainActivity 不 finish，进程不被回收。
 */
public class ProjectionHolder {
    private static int sResultCode = -1;
    private static Intent sData = null;

    public static void set(int rc, Intent data) {
        sResultCode = rc;
        sData = data;
    }

    public static int getResultCode() { return sResultCode; }
    public static Intent getData() { return sData; }
    public static boolean isReady() { return sData != null && sResultCode != -1; }

    public static void clear() {
        sResultCode = -1;
        sData = null;
    }
}
