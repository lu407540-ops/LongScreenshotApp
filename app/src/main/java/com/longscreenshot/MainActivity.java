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

        tvStatus  = findViewById(R.id.tv_status);
        btnAction = findViewById(R.id.btn_action);

        btnAction.setOnClickListener(v -> requestPermission());

        // 检查是否已有授权缓存
        if (hasSavedPermission()) {
            tvStatus.setText("已授权，正在启动...");
            startServicesWithSavedPermission();
        } else {
            tvStatus.setText("需要屏幕录制授权");
            btnAction.setText("授予权限");
        }
    }

    private boolean hasSavedPermission() {
        return getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("granted", false);
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
            // 保存授权信息
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(ScreenshotService.KEY_RESULT_CODE, resultCode)
                    .putBoolean("granted", true)
                    .apply();

            // 启动服务并传递 data Intent
            startServices(resultCode, data);
            finish(); // 关闭 Activity，悬浮窗接管
        } else {
            tvStatus.setText("授权被拒绝，无法使用");
            btnAction.setText("重新授权");
        }
    }

    /** 有缓存授权时：用保存的 resultCode + 重新构建的 Intent 启动 */
    private void startServicesWithSavedPermission() {
        int resultCode = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .getInt(ScreenshotService.KEY_RESULT_CODE, Activity.RESULT_OK);
        // 重新请求一次权限以获取有效的 data Intent
        requestPermission();
    }

    /** 首次授权：用真实的 data Intent 启动服务 */
    private void startServices(int resultCode, Intent data) {
        android.util.Log.d("MainActivity", "Starting services with permission");

        // 启动 ScreenshotService，传递 data
        Intent ssIntent = new Intent(this, ScreenshotService.class);
        ssIntent.setAction(ScreenshotService.ACTION_START);
        ssIntent.putExtra("resultCode", resultCode);
        ssIntent.putExtra("data", data);
        startForegroundService(ssIntent);

        // 显示悬浮窗
        Intent fwIntent = new Intent(this, FloatingWindowService.class);
        fwIntent.setAction(FloatingWindowService.ACTION_SHOW);
        startService(fwIntent);
    }
}
