package com.longscreenshot;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
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

    private static final String TAG = "FloatingWindowService";
    public static final String ACTION_SHOW = "ACTION_SHOW";
    public static final String ACTION_HIDE = "ACTION_HIDE";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";

    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private LinearLayout layoutStandby, layoutCapturing;
    private Button btnStart, btnCapture, btnSave;
    private TextView tvStatusText, tvHeight, tvSize, tvFrames;

    private boolean isShowing = false;
    private BroadcastReceiver updateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
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
        if (isShowing) return;
        isShowing = true;

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 200;

        wm.addView(floatingView, params);
        bindFloatingView();
        registerUpdateReceiver();
        Log.d(TAG, "Floating window shown");
    }

    private void hideFloatingWindow() {
        if (!isShowing) return;
        isShowing = false;
        if (floatingView != null) {
            wm.removeView(floatingView);
            floatingView = null;
        }
        if (updateReceiver != null) {
            unregisterReceiver(updateReceiver);
            updateReceiver = null;
        }
        Log.d(TAG, "Floating window hidden");
    }

    private void bindFloatingView() {
        if (floatingView == null) return;

        layoutStandby  = floatingView.findViewById(R.id.layoutStandby);
        layoutCapturing = floatingView.findViewById(R.id.layoutCapturing);
        btnStart     = floatingView.findViewById(R.id.btnStart);
        btnCapture   = floatingView.findViewById(R.id.btnCapture);
        btnSave      = floatingView.findViewById(R.id.btnSave);
        tvStatusText = floatingView.findViewById(R.id.tvStatusText);
        tvHeight     = floatingView.findViewById(R.id.tvHeight);
        tvSize       = floatingView.findViewById(R.id.tvSize);
        tvFrames     = floatingView.findViewById(R.id.tvFrames);

        // 拖拽区域 = 整个悬浮窗
        View dragArea = floatingView.findViewById(R.id.floatingLayout);
        if (dragArea != null) {
            dragArea.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = e.getRawX();
                        initialTouchY = e.getRawY();
                        return true;
                    } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                        params.x = initialX + (int)(e.getRawX() - initialTouchX);
                        params.y = initialY + (int)(e.getRawY() - initialTouchY);
                        wm.updateViewLayout(floatingView, params);
                        return true;
                    }
                    return false;
                }
            });
        }

        // 「▶ 开始」按钮：切换到截图模式，启动截图服务
        btnStart.setOnClickListener(v -> {
            Log.d(TAG, "Start clicked");
            layoutStandby.setVisibility(View.GONE);
            layoutCapturing.setVisibility(View.VISIBLE);
            tvStatusText.setText("截图中...");
            startService(new Intent(this, ScreenshotService.class)
                    .setAction(ScreenshotService.ACTION_START));
        });

        // 「截取」按钮：通知截图服务截取当前帧
        btnCapture.setOnClickListener(v -> {
            Log.d(TAG, "Capture clicked");
            tvStatusText.setText("截取中...");
            startService(new Intent(this, ScreenshotService.class)
                    .setAction(ScreenshotService.ACTION_CAPTURE));
        });

        // 「保存」按钮：停止并保存
        btnSave.setOnClickListener(v -> {
            Log.d(TAG, "Save clicked");
            tvStatusText.setText("正在保存...");
            startService(new Intent(this, ScreenshotService.class)
                    .setAction(ScreenshotService.ACTION_STOP));
            // 延迟关闭悬浮窗，等保存完成
            new android.os.Handler().postDelayed(() -> hideFloatingWindow(), 1500);
        });
    }

    private void registerUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_UPDATE.equals(intent.getAction())) return;
                int height = intent.getIntExtra("height", 0);
                int size   = intent.getIntExtra("size", 0);
                int fCount = intent.getIntExtra("frames", 0);
                if (tvHeight != null)  tvHeight.setText("高度: " + height + " px");
                if (tvSize != null)    tvSize.setText("大小: " + size + " KB");
                if (tvFrames != null)  tvFrames.setText("帧数: " + fCount);
                if (tvStatusText != null) tvStatusText.setText("截图成功");
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        hideFloatingWindow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
