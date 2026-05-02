package com.longscreenshot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private TextView tvStatus;
    private Button btnAction;

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
            // 用静态变量传递，避免 IBinder 序列化失效
            ProjectionHolder.set(resultCode, data);

            tvStatus.setText("授权成功，正在启动...");
            btnAction.setEnabled(false);

            // 1. 先启动截图服务并立即创建 MediaProjection
            Intent ssIntent = new Intent(this, ScreenshotService.class);
            ssIntent.setAction(ScreenshotService.ACTION_START);
            startForegroundService(ssIntent);

            // 2. 稍等一下再显示悬浮窗，确保 ScreenshotService 已完成初始化
            new android.os.Handler().postDelayed(() -> {
                // 显示悬浮窗（直接进入截图模式）
                Intent fwIntent = new Intent(this, FloatingWindowService.class);
                fwIntent.setAction(FloatingWindowService.ACTION_SHOW);
                startService(fwIntent);

                // 再延迟关闭 Activity
                new android.os.Handler().postDelayed(() -> finish(), 800);
            }, 500);
        } else {
            tvStatus.setText("授权被拒绝，无法使用");
            btnAction.setText("重新授权");
        }
    }
}
