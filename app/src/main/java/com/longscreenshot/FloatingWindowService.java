package com.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class FloatingWindowService extends Service {

    private static final String TAG = "FloatingWindow";
    public static final String ACTION_SHOW = "ACTION_SHOW";
    public static final String ACTION_HIDE = "ACTION_HIDE";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";
    public static final String ACTION_SERVICE_STATUS = "ACTION_SERVICE_STATUS";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";

    private static final String PREFS_NAME = "LongScreenshotPrefs";
    private static final String KEY_RESULT_CODE = "result_code";
    private static final String NOTIFICATION_CHANNEL_ID = "long_screenshot_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private LinearLayout layoutStandby;   // 待机模式布局
    private LinearLayout layoutCapturing; // 截图模式布局
    private Button btnStart;              // 开始按钮
    private Button btnCapture;            // 截取当前帧
    private Button btnSave;               // 保存并停止
    private TextView tvHeight, tvSize, tvFrames;
    private TextView tvStatusText;

    private int screenWidth, screenHeight;
    private boolean isCapturing = false;
    private int frameCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        createNotificationChannel();

        // 注册广播接收器：监听截图服务的信息更新和状态变化
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE);
        filter.addAction(ACTION_SERVICE_STATUS);
        registerReceiver(updateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_SHOW.equals(action)) {
            showFloatingWindow();
        } else if (ACTION_HIDE.equals(action)) {
            hideFloatingWindow();
            stopSelf();
        }

        return START_STICKY;
    }

    // ===================== 悬浮窗创建 =====================

    private void showFloatingWindow() {
        if (floatingView != null) return;

        // 创建前景通知（Android 8.0+ 需要）
        startForeground(NOTIFICATION_ID, buildNotification("长截图工具就绪"));

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        // 初始化待机模式视图
        layoutStandby = floatingView.findViewById(R.id.layoutStandby);
        layoutCapturing = floatingView.findViewById(R.id.layoutCapturing);
        btnStart = floatingView.findViewById(R.id.btnStart);
        btnCapture = floatingView.findViewById(R.id.btnCapture);
        btnSave = floatingView.findViewById(R.id.btnSave);
        tvHeight = floatingView.findViewById(R.id.tvHeight);
        tvSize = floatingView.findViewById(R.id.tvSize);
        tvFrames = floatingView.findViewById(R.id.tvFrames);
        tvStatusText = floatingView.findViewById(R.id.tvStatusText);

        // 按钮事件
        btnStart.setOnClickListener(v -> startCapturing());
        btnCapture.setOnClickListener(v -> captureFrame());
        btnSave.setOnClickListener(v -> stopAndSave());

        // 窗口参数
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = 16;
        params.y = 0;

        // 拖动悬浮窗
        floatingView.setOnTouchListener(new FloatingTouchListener());

        windowManager.addView(floatingView, params);

        // 默认显示待机模式
        showStandbyMode();
    }

    private void hideFloatingWindow() {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
    }

    // ===================== 模式切换 =====================

    private void showStandbyMode() {
        if (layoutStandby != null) layoutStandby.setVisibility(View.VISIBLE);
        if (layoutCapturing != null) layoutCapturing.setVisibility(View.GONE);
        updateNotification("长截图工具就绪");
    }

    private void showCapturingMode() {
        if (layoutStandby != null) layoutStandby.setVisibility(View.GONE);
        if (layoutCapturing != null) layoutCapturing.setVisibility(View.VISIBLE);
        frameCount = 0;
        updateCaptureInfo(0, 0, 0);
        updateNotification("正在截图中...");
    }

    // ===================== 截图控制 =====================

    private void startCapturing() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int resultCode = prefs.getInt(KEY_RESULT_CODE, -1);

        if (resultCode == -1) {
            Toast.makeText(this, "录屏授权已过期，请重新打开App授权", Toast.LENGTH_LONG).show();
            // 重新打开 MainActivity 授权
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            hideFloatingWindow();
            stopSelf();
            return;
        }

        isCapturing = true;
        showCapturingMode();

        // 启动 ScreenshotService
        Intent serviceIntent = new Intent(this, ScreenshotService.class);
        serviceIntent.setAction(ScreenshotService.ACTION_START);
        startForegroundService(serviceIntent);
    }

    private void captureFrame() {
        if (!isCapturing) return;

        // 通知 ScreenshotService 截取一帧
        Intent intent = new Intent(this, ScreenshotService.class);
        intent.setAction(ScreenshotService.ACTION_CAPTURE);
        startForegroundService(intent);
    }

    private void stopAndSave() {
        if (!isCapturing) return;
        isCapturing = false;

        // 通知 ScreenshotService 停止并保存
        Intent intent = new Intent(this, ScreenshotService.class);
        intent.setAction(ScreenshotService.ACTION_STOP);
        startForegroundService(intent);

        // 切回待机模式
        showStandbyMode();
        updateNotification("长截图工具就绪");
    }

    // ===================== 信息更新 =====================

    private BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_UPDATE.equals(action)) {
                int height = intent.getIntExtra("height", 0);
                int size = intent.getIntExtra("size", 0);
                int frames = intent.getIntExtra("frames", 0);
                updateCaptureInfo(height, size, frames);
            } else if (ACTION_SERVICE_STATUS.equals(action)) {
                String status = intent.getStringExtra(EXTRA_STATUS);
                if ("saved".equals(status)) {
                    String path = intent.getStringExtra("path");
                    isCapturing = false;
                    showStandbyMode();
                    updateNotification("长截图工具就绪");
                    Toast.makeText(FloatingWindowService.this, "已保存: " + path, Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    private void updateCaptureInfo(int height, int size, int frames) {
        if (tvHeight != null) tvHeight.setText("高度: " + height + " px");
        if (tvSize != null) {
            if (size > 1024) {
                tvSize.setText("大小: " + String.format("%.1f", size / 1024.0) + " MB");
            } else {
                tvSize.setText("大小: " + size + " KB");
            }
        }
        if (tvFrames != null) tvFrames.setText("帧数: " + frames);
        frameCount = frames;
    }

    // ===================== 通知 =====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "长截图服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("长截图后台服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("长截图工具")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    // ===================== 拖动监听 =====================

    private class FloatingTouchListener implements View.OnTouchListener {
        private int initialX, initialY;
        private float initialTouchX, initialTouchY;
        private boolean isDragging = false;
        private long startTime;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    startTime = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - initialTouchX);
                    int dy = (int) (event.getRawY() - initialTouchY);
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        params.x = initialX - dx; // END gravity, 所以减
                        params.y = initialY + dy;
                        windowManager.updateViewLayout(floatingView, params);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    // 如果没有拖动，让按钮自己处理点击
                    if (!isDragging && (System.currentTimeMillis() - startTime) < 200) {
                        return false; // 不消费事件，让按钮处理
                    }
                    return true;
            }
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideFloatingWindow();
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
