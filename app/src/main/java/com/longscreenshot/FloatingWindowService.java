package com.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class FloatingWindowService extends Service {

    public static final String ACTION_SHOW = "ACTION_SHOW";
    public static final String ACTION_HIDE = "ACTION_HIDE";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private TextView tvWidth, tvHeight, tvSize;

    private int screenWidth, screenHeight;

    private BroadcastReceiver updateReceiver;
    private static final String CHANNEL_ID = "floating_channel";
    private static final int NOTIFY_ID = 2;

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建通知渠道
        createNotificationChannel();

        // 立即设为前台服务
        startForeground(NOTIFY_ID, buildNotification("悬浮窗服务", "悬浮窗运行中"));

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 注册广播接收器
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE.equals(intent.getAction())) {
                    int width = intent.getIntExtra("width", 0);
                    int height = intent.getIntExtra("height", 0);
                    int size = intent.getIntExtra("size", 0);
                    updateInfo(width, height, size);
                }
            }
        };
        registerReceiver(updateReceiver, new IntentFilter(ACTION_UPDATE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_SHOW.equals(action)) {
            showFloatingWindow();
        } else if (ACTION_HIDE.equals(action)) {
            hideFloatingWindow();
        }

        return START_STICKY;
    }

    private void showFloatingWindow() {
        if (floatingView != null) return;

        // 创建悬浮窗视图
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        // 初始化视图
        tvWidth = floatingView.findViewById(R.id.tvWidth);
        tvHeight = floatingView.findViewById(R.id.tvHeight);
        tvSize = floatingView.findViewById(R.id.tvSize);

        // 设置窗口参数
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

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screenWidth - 320; // 右侧显示
        params.y = 100;

        // 设置触摸事件（拖动悬浮窗）
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        // 添加悬浮窗
        windowManager.addView(floatingView, params);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("悬浮窗后台运行通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    private void hideFloatingWindow() {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
    }

    private void updateInfo(int width, int height, int sizeKB) {
        if (tvWidth != null && tvHeight != null && tvSize != null) {
            tvWidth.setText("宽度: " + width + " px");
            tvHeight.setText("高度: " + height + " px");
            tvSize.setText("大小: " + sizeKB + " KB");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideFloatingWindow();
        if (updateReceiver != null) {
            unregisterReceiver(updateReceiver);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
