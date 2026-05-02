package com.longscreenshot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;

/**
 * 授权入口 Activity。
 * 授权成功后启动 ScreenshotService 和 FloatingWindowService，然后 self-finish。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private TextView tvStatus;
    private Button btnAction;

    // 用 static Handler 避免 Activity finish 后被 GC
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus  = findViewById(R.id.tvPermStatus);
        btnAction = findViewById(R.id.btnStart);

        btnAction.setOnClickListener(v -> requestPermission());

        tvStatus.setText("需要屏幕录制授权");
        btnAction.setText("授予权限并启动");
    }

    private void requestPermission() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) return;

        if (resultCode == Activity.RESULT_OK && data != null) {
            ProjectionHolder.set(resultCode, data);
            tvStatus.setText("授权成功，正在启动...");
            btnAction.setEnabled(false);

            // 1. 启动截图服务
            Intent ssIntent = new Intent(this, ScreenshotService.class);
            ssIntent.setAction(ScreenshotService.ACTION_START);
            startForegroundService(ssIntent);

            // 2. 等 1 秒后启动悬浮窗并关闭 Activity
            //    用 static handler 确保 Activity finish 后任务仍执行
            mainHandler.postDelayed(() -> {
                Intent fwIntent = new Intent(getApplicationContext(), FloatingWindowService.class);
                fwIntent.setAction(FloatingWindowService.ACTION_SHOW);
                startService(fwIntent);

                mainHandler.postDelayed(() -> {
                    // 确保在 Activity 线程 finish
                    finish();
                }, 500);
            }, 1000);

        } else {
            tvStatus.setText("授权被拒绝，无法使用");
            btnAction.setText("重新授权");
        }
    }
}
