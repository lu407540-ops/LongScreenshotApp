package com.longscreenshot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * 授权入口 Activity。
 * 授权成功后启动 ScreenshotService 和 FloatingWindowService，
 * 然后 hide 到后台（不 finish），保持进程不被回收。
 */
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
            // 直接存到内存（同一进程，不走序列化）
            ProjectionHolder.set(resultCode, data);
            Log.d("MainActivity", "ProjectionHolder saved, resultCode=" + resultCode);
            tvStatus.setText("授权成功，正在启动...");
            btnAction.setEnabled(false);

            // 1. 启动截图服务
            Intent ssIntent = new Intent(this, ScreenshotService.class);
            ssIntent.setAction(ScreenshotService.ACTION_START);
            startForegroundService(ssIntent);

            // 2. 等 1 秒后启动悬浮窗，然后 hide Activity（不 finish）
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent fwIntent = new Intent(this, FloatingWindowService.class);
                fwIntent.setAction(FloatingWindowService.ACTION_SHOW);
                ContextCompat.startForegroundService(this, fwIntent);

                // 不 finish，只 hide，保持进程不被回收
                moveTaskToBack(true);
                tvStatus.setText("已在后台运行，请使用悬浮窗操作");
                btnAction.setText("回到前台");
                btnAction.setEnabled(true);
                btnAction.setOnClickListener(v -> {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                });
            }, 1000);

        } else {
            tvStatus.setText("授权被拒绝，无法使用");
            btnAction.setText("重新授权");
        }
    }
}
