package com.longscreenshot;

import android.Manifest;
import android.content.Intent;
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

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_CODE_STORAGE = 1002;
    private static final int REQUEST_CODE_OVERLAY = 1003;

    private TextView tvStatus, tvWidth, tvHeight, tvSize;
    private Button btnStart, btnStop;
    private android.widget.LinearLayout layoutInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkAndRequestPermissions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvWidth = findViewById(R.id.tvWidth);
        tvHeight = findViewById(R.id.tvHeight);
        tvSize = findViewById(R.id.tvSize);
        layoutInfo = findViewById(R.id.layoutInfo);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startScreenshot());
        btnStop.setOnClickListener(v -> stopScreenshot());
    }

    private void checkAndRequestPermissions() {
        // 检查存储权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_STORAGE);
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
        }
    }

    private void startScreenshot() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
            return;
        }

        // 启动MediaProjection
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
    }

    private void stopScreenshot() {
        Intent intent = new Intent(this, ScreenshotService.class);
        intent.setAction(ScreenshotService.ACTION_STOP);
        startService(intent);

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("已停止");
        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            // 启动截图服务
            Intent serviceIntent = new Intent(this, ScreenshotService.class);
            serviceIntent.setAction(ScreenshotService.ACTION_START);
            serviceIntent.putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode);
            serviceIntent.putExtra(ScreenshotService.EXTRA_DATA, data);
            startService(serviceIntent);

            // 启动悬浮窗服务
            Intent floatIntent = new Intent(this, FloatingWindowService.class);
            floatIntent.setAction(FloatingWindowService.ACTION_SHOW);
            startService(floatIntent);

            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            tvStatus.setText("正在截图...");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));

            Toast.makeText(this, "截图已启动，悬浮窗将显示实时信息", Toast.LENGTH_SHORT).show();
        }

        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能显示实时信息", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能保存截图", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止服务
        stopScreenshot();
    }
}
