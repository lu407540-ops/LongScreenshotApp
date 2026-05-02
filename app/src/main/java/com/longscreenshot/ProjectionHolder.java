package com.longscreenshot;

import android.content.Intent;

/**
 * 在同一进程内传递 MediaProjection 授权数据。
 * 因为 data Intent 内含 IBinder，通过 Intent extras 传递会失效，
 * 所以用静态变量直接传递引用。
 */
public class ProjectionHolder {
    public static int resultCode = -1;
    public static Intent data = null;

    public static void set(int rc, Intent d) {
        resultCode = rc;
        data = d;
    }

    public static void clear() {
        resultCode = -1;
        data = null;
    }
}
