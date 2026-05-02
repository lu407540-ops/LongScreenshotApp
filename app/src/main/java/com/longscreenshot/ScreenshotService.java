package com.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    public static final String ACTION_START  = "ACTION_START";
    public static final String ACTION_STOP   = "ACTION_STOP";
    public static final String ACTION_CAPTURE = "ACTION_CAPTURE";

    public static final String PREFS_NAME     = "LongScreenshotPrefs";
    public static final String KEY_RESULT_CODE = "result_code";

    private static final String NOTIFICATION_CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFICATION_ID = 1002;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Handler handler;
    private HandlerThread handlerThread;

    private int screenWidth, screenHeight, screenDensity;
    private List<Bitmap> frames = new ArrayList<>();
    private boolean isRunning = false;

    // 权限数据（从 MainActivity 通过 Intent 传递）
    private int cachedResultCode = -1;
    private Intent cachedData = null;

    // 去重参数
    private static final int MATCH_THRESHOLD = 30;
    private static final float OVERLAP_SEARCH_RATIO = 0.5f;
    private static final int SEARCH_STEP = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handlerThread = new HandlerThread("ScreenshotThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        Log.d(TAG, "Screen: " + screenWidth + "x" + screenHeight);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        // 接收权限数据（首次授权时由 MainActivity 传入）
        if (intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            cachedResultCode = intent.getIntExtra("resultCode", -1);
            cachedData = intent.getParcelableExtra("data");
            // 持久化 resultCode（data 无法持久化，每次需重新授权）
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_RESULT_CODE, cachedResultCode)
                    .apply();
        }

        Log.d(TAG, "onStartCommand: " + action);
        if (ACTION_START.equals(action)) {
            startProjection();
        } else if (ACTION_CAPTURE.equals(action)) {
            captureFrame();
        } else if (ACTION_STOP.equals(action)) {
            stopProjectionAndSave();
        }
        return START_STICKY;
    }

    // ===================== 启动 / 停止 =====================

    private void startProjection() {
        if (isRunning) return;
        if (cachedResultCode == -1) {
            Log.e(TAG, "No valid screen capture permission");
            return;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(cachedResultCode, cachedData);

            imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                    PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "LongScreenshot",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);

            isRunning = true;
            frames.clear();
            startForeground(NOTIFICATION_ID, buildNotification("长截图服务运行中，点击悬浮窗截图"));
            Log.d(TAG, "Projection started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start projection", e);
        }
    }

    private void stopProjectionAndSave() {
        Log.d(TAG, "Stopping and saving...");
        isRunning = false;
        handler.postDelayed(() -> {
            Bitmap result = stitchFrames();
            String path = saveBitmap(result);
            Log.d(TAG, "Saved to: " + path);
            stopSelf();
        }, 500);
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (handlerThread != null) handlerThread.quitSafely();
        super.onDestroy();
    }

    // ===================== 截取单帧 =====================

    private void captureFrame() {
        if (!isRunning || imageReader == null) {
            Log.w(TAG, "Not ready to capture");
            return;
        }
        handler.postDelayed(() -> {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.w(TAG, "No image available");
                return;
            }
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap != null) {
                frames.add(bitmap);
                Log.d(TAG, "Frame captured: #" + frames.size()
                        + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                updateFloatingWindow();
            }
        }, 100);
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
        } catch (Exception e) {
            Log.e(TAG, "imageToBitmap failed", e);
            return null;
        }
    }

    // ===================== 帧去重拼接 =====================

    private Bitmap stitchFrames() {
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0);

        int totalH = 0;
        List<Bitmap> uniqueFrames = new ArrayList<>();
        Bitmap prev = frames.get(0);
        uniqueFrames.add(prev);
        totalH += prev.getHeight();

        for (int i = 1; i < frames.size(); i++) {
            Bitmap current = frames.get(i);
            int overlap = findOverlap(prev, current);
            if (overlap > 0) {
                Bitmap cropped = Bitmap.createBitmap(current, 0, overlap,
                        current.getWidth(), current.getHeight() - overlap);
                uniqueFrames.add(cropped);
                totalH += cropped.getHeight();
            } else {
                uniqueFrames.add(current);
                totalH += current.getHeight();
            }
            prev = current;
        }

        Bitmap result = Bitmap.createBitmap(screenWidth, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        int y = 0;
        for (Bitmap b : uniqueFrames) {
            canvas.drawBitmap(b, 0, y, null);
            y += b.getHeight();
        }
        Log.d(TAG, "Stitched: " + frames.size() + " frames -> " + screenWidth + "x" + totalH);
        return result;
    }

    private int findOverlap(Bitmap prev, Bitmap current) {
        int w = prev.getWidth();
        int prevH = prev.getHeight();
        int searchStart = (int) (prevH * OVERLAP_SEARCH_RATIO);

        for (int offset = searchStart; offset < prevH - 10; offset += SEARCH_STEP) {
            int matchCount = 0;
            int sampleRows = Math.min(5, prevH - offset);
            for (int r = 0; r < sampleRows; r++) {
                int py = prevH - offset + r;
                int cy = r;
                if (py >= prevH || cy >= current.getHeight()) break;
                if (isRowSimilar(prev, current, py, cy, w)) matchCount++;
            }
            if (matchCount >= sampleRows * 0.8) return offset;
        }
        return 0;
    }

    private boolean isRowSimilar(Bitmap b1, Bitmap b2, int y1, int y2, int w) {
        int step = Math.max(1, w / 50);
        int similar = 0;
        for (int x = 0; x < w; x += step) {
            int c1 = b1.getPixel(x, y1);
            int c2 = b2.getPixel(x, y2);
            int dr = Math.abs(Color.red(c1) - Color.red(c2));
            int dg = Math.abs(Color.green(c1) - Color.green(c2));
            int db = Math.abs(Color.blue(c1) - Color.blue(c2));
            if (dr + dg + db < MATCH_THRESHOLD) similar++;
        }
        return similar > (w / step) * 0.7;
    }

    // ===================== 保存 =====================

    private String saveBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            File dir = new File(getExternalFilesDir(null), "LongScreenshot");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "long_screenshot_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save", e);
            return null;
        }
    }

    private void updateFloatingWindow() {
        int totalHeight = 0;
        for (Bitmap f : frames) totalHeight += f.getHeight();
        int sizeKB = (int) (totalHeight * screenWidth * 4L / 1024);

        Intent intent = new Intent(FloatingWindowService.ACTION_UPDATE);
        intent.putExtra("height", totalHeight);
        intent.putExtra("size", sizeKB);
        intent.putExtra("frames", frames.size());
        sendBroadcast(intent);
    }

    // ===================== 通知 =====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "截图服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("长截图后台运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("长截图工具")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    // ===================== 清理 =====================

    private void cleanup() {
        isRunning = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        for (Bitmap f : frames) {
            if (!f.isRecycled()) f.recycle();
        }
        frames.clear();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
