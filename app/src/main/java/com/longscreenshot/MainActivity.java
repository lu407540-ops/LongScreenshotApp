package com.longscreenshot;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.media.projection.MediaProjectionManager;
import android.content.Context;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "LongScreenshotPrefs";
    private static final String KEY_RESULT_CODE = "result_code";

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_CODE_STORAGE = 1002;
    private static final int REQUEST_CODE_OVERLAY = 1003;
    private static final int REQUEST_CODE_NOTIFICATION = 1004;

    private Button btnStart;
    private TextView tvPermStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        tvPermStatus = findViewById(R.id.tvPermStatus);

        btnStart.setOnClickListener(v -> requestScreenCapture());

        // 检查所有权限状态
        updatePermissionStatus();
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        // 存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_CODE_STORAGE);
            }
        }

        // 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
        }

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_NOTIFICATION);
            }
        }
    }

    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();

        boolean hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean hasStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
        boolean hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;

        status.append("悬浮窗权限: ").append(hasOverlay ? "✅" : "❌").append("\n");
        status.append("存储权限: ").append(hasStorage ? "✅" : "❌").append("\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.append("通知权限: ").append(hasNotification ? "✅" : "❌").append("\n");
        }

        tvPermStatus.setText(status.toString());

        boolean allGranted = hasOverlay && hasStorage && hasNotification;
        btnStart.setEnabled(allGranted);
        if (allGranted) {
            btnStart.setAlpha(1.0f);
        } else {
            btnStart.setAlpha(0.5f);
        }
    }

    private void requestScreenCapture() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
            return;
        }

        // 请求录屏授权
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // 保存授权信息到 SharedPreferences
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt(KEY_RESULT_CODE, resultCode).apply();

                // 启动悬浮窗服务
                Intent floatIntent = new Intent(this, FloatingWindowService.class);
                floatIntent.setAction(FloatingWindowService.ACTION_SHOW);
                startForegroundService(floatIntent);

                // 关闭 MainActivity
                Toast.makeText(this, "授权成功！悬浮窗已就绪\n切到目标App后点击「开始长截图」",
                        Toast.LENGTH_LONG).show();
                finish();
            } else {
                Toast.makeText(this, "录屏授权被拒绝，无法使用长截图功能", Toast.LENGTH_LONG).show();
            }
        }

        if (requestCode == REQUEST_CODE_OVERLAY) {
            updatePermissionStatus();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();

        if (requestCode == REQUEST_CODE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能保存截图", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限已获取", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
