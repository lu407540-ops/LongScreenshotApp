package com.longscreenshot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * 持久化存储 MediaProjection 授权数据。
 * 用 SharedPreferences 存 Intent 的 URI 字符串，避免静态变量在进程重启后丢失。
 */
public class ProjectionHolder {
    private static final String PREF_NAME = "long_screenshot_pref";
    private static final String KEY_RESULT_CODE = "result_code";
    private static final String KEY_INTENT_URI = "intent_uri";

    // 内存缓存，避免每次都读 SP
    private static int sResultCode = -1;
    private static Intent sData = null;
    private static boolean sLoaded = false;

    /** MainActivity 授权成功后调用：存到 SP + 内存 */
    public static void set(Context context, int rc, Intent data) {
        sResultCode = rc;
        sData = data;
        sLoaded = true;

        String uri = data.toUri(Intent.URI_INTENT_SCHEME);
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_RESULT_CODE, rc)
                .putString(KEY_INTENT_URI, uri)
                .apply();
    }

    /** ScreenshotService 启动时调用：先从内存读，没有再从 SP 读 */
    public static void load(Context context) {
        if (sLoaded && sData != null) return;

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int rc = sp.getInt(KEY_RESULT_CODE, -1);
        String uri = sp.getString(KEY_INTENT_URI, null);

        if (rc != -1 && uri != null) {
            try {
                sResultCode = rc;
                sData = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                sLoaded = true;
            } catch (Exception e) {
                sResultCode = -1;
                sData = null;
                sLoaded = false;
            }
        }
    }

    public static int getResultCode() { return sResultCode; }
    public static Intent getData() { return sData; }
    public static boolean isReady() { return sData != null && sResultCode != -1; }

    public static void clear(Context context) {
        sResultCode = -1;
        sData = null;
        sLoaded = false;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }
}
