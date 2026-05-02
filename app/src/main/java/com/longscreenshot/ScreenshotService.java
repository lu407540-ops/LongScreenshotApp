package com.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
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
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import android.graphics.Bitmap.CompressFormat;

public class ScreenshotService extends Service {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_CAPTURE = "ACTION_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_DATA = "EXTRA_DATA";

    private static final String TAG = "ScreenshotService";

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private Handler handler;
    private HandlerThread handlerThread;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private Bitmap longScreenshotBitmap;
    private Canvas canvas;
    private int currentHeight = 0;

    private boolean isCapturing = false;
    private boolean isFirstCapture = true;

    private static final String CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFY_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建通知渠道（Android 8.0+ 必须）
        createNotificationChannel();

        // 立即设为前台服务，必须在 onCreate 完成前调用
        startForeground(NOTIFY_ID, buildNotification("长截图服务", "正在准备..."));

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // 初始化HandlerThread
        handlerThread = new HandlerThread("ScreenshotThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "长截图服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("长截图后台运行通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startCapture(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopCapture();
        } else if (ACTION_CAPTURE.equals(action)) {
            captureOnce();
        }

        return START_STICKY;
    }

    private void startCapture(Intent intent) {
        // 注意：RESULT_OK == -1，默认值用 Integer.MIN_VALUE 避免冲突
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Integer.MIN_VALUE);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);

        if (resultCode == Integer.MIN_VALUE || data == null) {
            Log.e(TAG, "startCapture: 无效的 resultCode 或 data 为空");
            return;
        }

        // 创建MediaProjection
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, data);

        // 创建ImageReader
        imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);

        // 创建长截图Bitmap（动态高度，初始为屏幕高度）
        longScreenshotBitmap = Bitmap.createBitmap(screenWidth, screenHeight * 10, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(longScreenshotBitmap);
        currentHeight = 0;
        isFirstCapture = true;
        isCapturing = true;

        // 开始定时截图
        handler.postDelayed(this::captureAndStitch, 500);

        Log.d(TAG, "Screenshot started");
    }

    private void captureOnce() {
        if (mediaProjection == null || imageReader == null || !isCapturing) return;

        // 创建虚拟显示器
        android.hardware.display.VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                screenWidth, screenHeight, screenDensity,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        // 等待截图完成
        handler.postDelayed(() -> {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                processImage(image);
                image.close();
            }
            virtualDisplay.release();
        }, 100);
    }

    private void captureAndStitch() {
        if (!isCapturing || mediaProjection == null) return;

        android.hardware.display.VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                screenWidth, screenHeight, screenDensity,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        handler.postDelayed(() -> {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                Bitmap bitmap = imageToBitmap(image);

                if (bitmap != null) {
                    // 拼接图片
                    if (isFirstCapture) {
                        // 第一次，直接绘制
                        canvas.drawBitmap(bitmap, 0, 0, null);
                        currentHeight = bitmap.getHeight();
                        isFirstCapture = false;
                    } else {
                        // 后续，拼接到底部
                        canvas.drawBitmap(bitmap, 0, currentHeight, null);
                        currentHeight += bitmap.getHeight();

                        // 如果超过预分配高度，扩展Bitmap
                        if (currentHeight > longScreenshotBitmap.getHeight() - screenHeight) {
                            expandBitmap();
                        }
                    }

                    // 发送广播更新悬浮窗
                    updateFloatingWindow();

                    bitmap.recycle();
                }

                image.close();
            }
            virtualDisplay.release();

            // 继续下一次截图
            if (isCapturing) {
                handler.postDelayed(this::captureAndStitch, 800);
            }
        }, 150);
    }

    private void processImage(Image image) {
        Bitmap bitmap = imageToBitmap(image);
        if (bitmap != null) {
            if (isFirstCapture) {
                canvas.drawBitmap(bitmap, 0, 0, null);
                currentHeight = bitmap.getHeight();
                isFirstCapture = false;
            } else {
                canvas.drawBitmap(bitmap, 0, currentHeight, null);
                currentHeight += bitmap.getHeight();
                if (currentHeight > longScreenshotBitmap.getHeight() - screenHeight) {
                    expandBitmap();
                }
            }
            updateFloatingWindow();
            bitmap.recycle();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        // 裁剪掉padding
        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            return cropped;
        }

        return bitmap;
    }

    private void expandBitmap() {
        Bitmap newBitmap = Bitmap.createBitmap(screenWidth,
                longScreenshotBitmap.getHeight() + screenHeight * 10,
                Bitmap.Config.ARGB_8888);
        Canvas newCanvas = new Canvas(newBitmap);
        newCanvas.drawBitmap(longScreenshotBitmap, 0, 0, null);
        longScreenshotBitmap.recycle();
        longScreenshotBitmap = newBitmap;
        canvas = new Canvas(longScreenshotBitmap);
    }

    private void updateFloatingWindow() {
        // 计算文件大小估算
        int estimatedSize = estimateFileSize();

        // 发送广播给悬浮窗服务
        Intent intent = new Intent(FloatingWindowService.ACTION_UPDATE);
        intent.putExtra("width", screenWidth);
        intent.putExtra("height", currentHeight);
        intent.putExtra("size", estimatedSize);
        sendBroadcast(new Intent(intent));
    }

    private int estimateFileSize() {
        // 估算PNG文件大小（KB）
        long pixels = (long) screenWidth * currentHeight;
        long bytes = pixels * 4; // ARGB_8888 = 4 bytes per pixel
        // PNG压缩率约50%
        long compressedBytes = bytes / 2;
        return (int) (compressedBytes / 1024); // 转换为KB
    }

    private void stopCapture() {
        isCapturing = false;

        // 保存最终截图
        saveScreenshot();

        // 清理
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        // 隐藏悬浮窗
        Intent intent = new Intent(this, FloatingWindowService.class);
        intent.setAction(FloatingWindowService.ACTION_HIDE);
        startService(intent);

        Log.d(TAG, "Screenshot stopped");
    }

    private void saveScreenshot() {
        if (longScreenshotBitmap == null || currentHeight == 0) return;

        // 裁剪有效区域
        Bitmap finalBitmap = Bitmap.createBitmap(longScreenshotBitmap, 0, 0, screenWidth, currentHeight);

        // 保存到DCIM目录
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "LongScreenshot");
        if (!dir.exists()) dir.mkdirs();

        String fileName = "long_screenshot_" + System.currentTimeMillis() + ".png";
        File file = new File(dir, fileName);

        try {
            FileOutputStream fos = new FileOutputStream(file);
            finalBitmap.compress(CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Log.d(TAG, "Screenshot saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save screenshot", e);
        }

        finalBitmap.recycle();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }
}
