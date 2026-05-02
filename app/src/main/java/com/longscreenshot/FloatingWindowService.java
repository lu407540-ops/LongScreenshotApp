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
    public static final String ACTION_SHOW   = "ACTION_SHOW";
    public static final String ACTION_HIDE   = "ACTION_HIDE";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";

    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private LinearLayout layoutStandby, layoutCapturing;
    private Button btnCapture, btnSave;
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
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            updateReceiver = null;
        }
        stopSelf();
        Log.d(TAG, "Floating window hidden");
    }

    private void bindFloatingView() {
        if (floatingView == null) return;

        layoutStandby  = floatingView.findViewById(R.id.layoutStandby);
        layoutCapturing = floatingView.findViewById(R.id.layoutCapturing);
        Button btnStart   = floatingView.findViewById(R.id.btnStart);
        btnCapture   = floatingView.findViewById(R.id.btnCapture);
        btnSave      = floatingView.findViewById(R.id.btnSave);
        tvStatusText = floatingView.findViewById(R.id.tvStatusText);
        tvHeight     = floatingView.findViewById(R.id.tvHeight);
        tvSize       = floatingView.findViewById(R.id.tvSize);
        tvFrames     = floatingView.findViewById(R.id.tvFrames);

        // MediaProjection 已就绪，直接进入截图模式
        if (layoutStandby != null)  layoutStandby.setVisibility(View.GONE);
        if (layoutCapturing != null) layoutCapturing.setVisibility(View.VISIBLE);
        if (tvStatusText != null)   tvStatusText.setText("就绪 - 滚动后点击截取");
        if (btnStart != null)       btnStart.setVisibility(View.GONE);

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

        // 「截取」按钮
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> {
                Log.d(TAG, "Capture clicked");
                if (tvStatusText != null) tvStatusText.setText("截取中...");
                // 用 startForegroundService 确保前台服务能收到
                Intent intent = new Intent(this, ScreenshotService.class);
                intent.setAction(ScreenshotService.ACTION_CAPTURE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            });
        }

        // 「保存」按钮
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                Log.d(TAG, "Save clicked");
                if (tvStatusText != null) tvStatusText.setText("正在保存...");
                Intent intent = new Intent(this, ScreenshotService.class);
                intent.setAction(ScreenshotService.ACTION_STOP);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            });
        }
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
                if (tvStatusText != null) tvStatusText.setText("已截 " + fCount + " 帧");
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_UPDATE);

        // 同时监听 ScreenshotService 发来的 ACTION_HIDE 广播
        IntentFilter hideFilter = new IntentFilter(ACTION_HIDE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED);
            registerReceiver(hideReceiver, hideFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
            registerReceiver(hideReceiver, hideFilter);
        }
    }

    // 监听保存完成后的关闭指令
    private BroadcastReceiver hideReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_HIDE.equals(intent.getAction())) {
                hideFloatingWindow();
            }
        }
    };

    @Override
    public void onDestroy() {
        isShowing = false;
        if (floatingView != null) {
            try { wm.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
        }
        if (updateReceiver != null) {
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            updateReceiver = null;
        }
        if (hideReceiver != null) {
            try { unregisterReceiver(hideReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
