package com.longscreenshot;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;

public class FloatingWindowService extends Service {

    private static final String TAG = "FloatingWindow";
    public static final String ACTION_SHOW   = "com.longscreenshot.FLOAT_SHOW";
    public static final String ACTION_HIDE   = "com.longscreenshot.FLOAT_HIDE";
    public static final String ACTION_UPDATE = "com.longscreenshot.FLOAT_UPDATE";

    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private LinearLayout layoutStandby, layoutCapturing;
    private Button btnCapture, btnSave;
    private TextView tvStatusText, tvHeight, tvSize, tvFrames;

    private boolean isShowing = false;
    private BroadcastReceiver updateReceiver;
    private BroadcastReceiver hideReceiver;

    private static final String CHANNEL_ID = "floating_channel";
    private static final int NOTIFY_ID = 1003;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createChannel();
        startForeground(NOTIFY_ID, buildNotif("长截图悬浮窗运行中..."));
        Log.d(TAG, "onCreate: 悬浮窗服务已创建并设为前台");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID, "悬浮窗服务", android.app.NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("长截图悬浮窗");
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private android.app.Notification buildNotif(String text) {
        return new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("长截图悬浮窗")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次 onStartCommand 都必须确保前台通知存在（Android 12+ 要求）
        startForeground(NOTIFY_ID, buildNotif("长截图悬浮窗运行中..."));

        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: " + action);
        if (ACTION_SHOW.equals(action)) {
            show();
        } else if (ACTION_HIDE.equals(action)) {
            hide();
        }
        return START_STICKY;
    }

    // ===================== 显示 / 隐藏 =====================

    private void show() {
        if (isShowing) {
            Log.d(TAG, "Already showing");
            return;
        }
        isShowing = true;

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 300;

        wm.addView(floatingView, params);
        bind();
        registerReceivers();
        Log.d(TAG, "Shown");
    }

    private void hide() {
        if (!isShowing) return;
        isShowing = false;
        if (floatingView != null) {
            try { wm.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
        }
        unregisterReceivers();
        stopSelf();
        Log.d(TAG, "Hidden and stopped");
    }

    // ===================== 绑定视图 =====================

    private void bind() {
        if (floatingView == null) return;

        layoutStandby   = floatingView.findViewById(R.id.layoutStandby);
        layoutCapturing = floatingView.findViewById(R.id.layoutCapturing);
        Button btnStart  = floatingView.findViewById(R.id.btnStart);
        btnCapture       = floatingView.findViewById(R.id.btnCapture);
        btnSave          = floatingView.findViewById(R.id.btnSave);
        tvStatusText     = floatingView.findViewById(R.id.tvStatusText);
        tvHeight         = floatingView.findViewById(R.id.tvHeight);
        tvSize           = floatingView.findViewById(R.id.tvSize);
        tvFrames         = floatingView.findViewById(R.id.tvFrames);

        // 直接进入截图模式
        if (layoutStandby != null)   layoutStandby.setVisibility(View.GONE);
        if (layoutCapturing != null) layoutCapturing.setVisibility(View.VISIBLE);
        if (tvStatusText != null)    tvStatusText.setText("就绪 - 滚动后截取");
        if (btnStart != null)        btnStart.setVisibility(View.GONE);

        // 拖拽
        View dragArea = floatingView.findViewById(R.id.floatingLayout);
        if (dragArea != null) {
            dragArea.setOnTouchListener(new DragTouchListener());
        }

        // 截取按钮 — 用 startService（不是 startForegroundService），
        // 因为 ScreenshotService 已经在 foreground 了
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "Capture");
                if (tvStatusText != null) tvStatusText.setText("截取中...");
                Intent i = new Intent(this, ScreenshotService.class);
                i.setAction(ScreenshotService.ACTION_CAPTURE);
                startService(i);
            });
        }

        // 保存按钮
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                Log.d(TAG, "Save");
                if (tvStatusText != null) tvStatusText.setText("保存中...");
                Intent i = new Intent(this, ScreenshotService.class);
                i.setAction(ScreenshotService.ACTION_STOP);
                startService(i);
            });
        }
    }

    // ===================== 拖拽（不拦截按钮点击） =====================

    private class DragTouchListener implements View.OnTouchListener {
        private int ix, iy;
        private float tx, ty;
        private boolean isDragging = false;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = params.x; iy = params.y;
                    tx = e.getRawX(); ty = e.getRawY();
                    isDragging = false;
                    return true;  // 消费 DOWN 事件
                case MotionEvent.ACTION_MOVE:
                    int dx = (int)(e.getRawX() - tx);
                    int dy = (int)(e.getRawY() - ty);
                    if (!isDragging && (Math.abs(dx) > 5 || Math.abs(dy) > 5)) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        params.x = ix + dx;
                        params.y = iy + dy;
                        wm.updateViewLayout(floatingView, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // 这是一次 tap，不消费让子 View 处理
                        return false;
                    }
                    return true;
            }
            return false;
        }
    }

    // ===================== 广播 =====================

    private void registerReceivers() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int h = intent.getIntExtra("height", 0);
                int n = intent.getIntExtra("frames", 0);
                if (tvHeight != null)     tvHeight.setText("高度: " + h + " px");
                if (tvSize != null)       tvSize.setText("帧数: " + n);
                if (tvFrames != null)     tvFrames.setText("帧数: " + n);
                if (tvStatusText != null) tvStatusText.setText("已截 " + n + " 帧");
            }
        };

        hideReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                Log.d(TAG, "Received HIDE broadcast");
                hide();
            }
        };

        IntentFilter updateFilter = new IntentFilter(ACTION_UPDATE);
        IntentFilter hideFilter   = new IntentFilter(ACTION_HIDE);

        // Android 13+ 需要声明 RECEIVER_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, updateFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(hideReceiver, hideFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateReceiver, updateFilter);
            registerReceiver(hideReceiver, hideFilter);
        }
    }

    private void unregisterReceivers() {
        if (updateReceiver != null) {
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            updateReceiver = null;
        }
        if (hideReceiver != null) {
            try { unregisterReceiver(hideReceiver); } catch (Exception ignored) {}
            hideReceiver = null;
        }
    }

    @Override
    public void onDestroy() {
        isShowing = false;
        if (floatingView != null) {
            try { wm.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
        }
        unregisterReceivers();
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
