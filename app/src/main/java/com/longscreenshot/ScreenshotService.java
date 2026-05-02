package com.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.graphics.Bitmap.CompressFormat;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_CAPTURE = "ACTION_CAPTURE";

    private static final String PREFS_NAME = "LongScreenshotPrefs";
    private static final String KEY_RESULT_CODE = "result_code";
    private static final String NOTIFICATION_CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFICATION_ID = 1002;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private Handler handler;
    private HandlerThread handlerThread;

    private int screenWidth, screenHeight, screenDensity;

    // 帧存储
    private List<Bitmap> frames = new ArrayList<>();
    private VirtualDisplay virtualDisplay;
    private boolean isRunning = false;

    // 去重参数
    private static final int MATCH_THRESHOLD = 30;  // 像素匹配阈值（容差）
    private static final int SEARCH_START = (int) (0.5); // 从帧底部 50% 开始搜索重叠
    private static final int SEARCH_STEP = 2;        // 每隔2行搜索一次

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        handlerThread = new HandlerThread("ScreenshotThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startProjection();
        } else if (ACTION_STOP.equals(action)) {
            stopAndSave();
        } else if (ACTION_CAPTURE.equals(action)) {
            captureFrame();
        }

        return START_STICKY;
    }

    // ===================== MediaProjection 初始化 =====================

    private void startProjection() {
        if (isRunning) return;

        // 从 SharedPreferences 读取授权信息
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int resultCode = prefs.getInt(KEY_RESULT_CODE, -1);
        if (resultCode == -1) {
            Log.e(TAG, "No valid screen capture permission");
            stopSelf();
            return;
        }

        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode,
                    new Intent()); // Intent 已消费，传空 Intent

            imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                    PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "LongScreenshot",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);

            isRunning = true;
            frames.clear();

            // 启动为前台服务
            startForeground(NOTIFICATION_ID, buildNotification("长截图服务运行中"));

            Log.d(TAG, "Projection started, ready to capture frames");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start projection", e);
            cleanup();
        }
    }

    // ===================== 截取单帧 =====================

    private void captureFrame() {
        if (!isRunning || imageReader == null) {
            Log.w(TAG, "Not ready to capture");
            return;
        }

        // 在 ImageReader 的 Surface 上获取最新帧
        // 先短暂延迟确保 VirtualDisplay 渲染最新画面
        handler.postDelayed(() -> {
            try {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    Log.w(TAG, "No image available");
                    return;
                }

                Bitmap bitmap = imageToBitmap(image);
                image.close();

                if (bitmap != null) {
                    frames.add(bitmap);
                    Log.d(TAG, "Frame captured: #" + frames.size() +
                            " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");

                    // 更新悬浮窗信息
                    updateFloatingWindow();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error capturing frame", e);
            }
        }, 200); // 延迟 200ms 确保画面渲染
    }

    // ===================== 停止并保存 =====================

    private void stopAndSave() {
        if (!isRunning) return;

        isRunning = false;

        if (frames.isEmpty()) {
            Log.w(TAG, "No frames to save");
            cleanup();
            return;
        }

        handler.post(() -> {
            try {
                // 拼接所有帧
                Bitmap result = stitchFrames(frames);

                // 保存文件
                String path = saveBitmap(result);

                // 通知悬浮窗保存完成
                Intent intent = new Intent(FloatingWindowService.ACTION_SERVICE_STATUS);
                intent.putExtra(FloatingWindowService.EXTRA_STATUS, "saved");
                intent.putExtra("path", path);
                sendBroadcast(intent);

                // 回收帧内存
                for (Bitmap frame : frames) {
                    if (!frame.isRecycled()) frame.recycle();
                }
                frames.clear();
                if (result != null && !result.isRecycled()) result.recycle();

                Log.d(TAG, "Saved: " + path);

            } catch (Exception e) {
                Log.e(TAG, "Error saving", e);
            }

            cleanup();
            stopSelf();
        });
    }

    // ===================== 帧拼接（带去重） =====================

    private Bitmap stitchFrames(List<Bitmap> frameList) {
        if (frameList.isEmpty()) return null;
        if (frameList.size() == 1) return frameList.get(0);

        Log.d(TAG, "Stitching " + frameList.size() + " frames...");

        // 计算总高度（估算，后续可能调整）
        int estimatedHeight = screenHeight + (frameList.size() - 1) * (screenHeight / 2);

        // 创建结果 Bitmap
        Bitmap result = Bitmap.createBitmap(screenWidth, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();

        // 绘制第一帧
        canvas.drawBitmap(frameList.get(0), 0, 0, paint);
        int currentY = frameList.get(0).getHeight();

        // 逐帧拼接
        for (int i = 1; i < frameList.size(); i++) {
            Bitmap prevFrame = frameList.get(i - 1);
            Bitmap currFrame = frameList.get(i);

            // 找重叠区域
            int overlap = findOverlap(prevFrame, currFrame);
            int newContentStart = overlap;
            int newContentHeight = currFrame.getHeight() - overlap;

            if (newContentHeight <= 0) {
                Log.w(TAG, "Frame #" + i + " is fully overlapped, skipping");
                continue;
            }

            // 如果超出预分配高度，扩展 Bitmap
            if (currentY + newContentHeight > result.getHeight()) {
                Bitmap newResult = Bitmap.createBitmap(screenWidth,
                        result.getHeight() + screenHeight, Bitmap.Config.ARGB_8888);
                Canvas newCanvas = new Canvas(newResult);
                newCanvas.drawBitmap(result, 0, 0, paint);
                result.recycle();
                result = newResult;
                canvas = new Canvas(result);
            }

            // 裁剪新帧的非重叠部分，绘制到结果上
            Bitmap cropped = Bitmap.createBitmap(currFrame, 0, newContentStart,
                    screenWidth, newContentHeight);
            canvas.drawBitmap(cropped, 0, currentY, paint);
            currentY += newContentHeight;
            cropped.recycle();

            Log.d(TAG, "Frame #" + i + ": overlap=" + overlap +
                    "px, new=" + newContentHeight + "px, total=" + currentY + "px");
        }

        // 裁剪到实际大小
        if (currentY < result.getHeight()) {
            Bitmap finalBitmap = Bitmap.createBitmap(result, 0, 0, screenWidth, currentY);
            result.recycle();
            return finalBitmap;
        }

        return result;
    }

    /**
     * 查找两帧之间的重叠行数
     * 原理：取前一帧底部区域，在新帧中搜索匹配位置
     */
    private int findOverlap(Bitmap prev, Bitmap curr) {
        int searchRows = (int) (screenHeight * SEARCH_START); // 搜索前帧底部 50%

        // 取前一帧底部 searchRows 行的中间一行作为样本
        int sampleRowInPrev = screenHeight - searchRows / 2;
        int[] samplePixels = new int[screenWidth];
        prev.getPixels(samplePixels, 0, screenWidth, 0, sampleRowInPrev, screenWidth, 1);

        // 在新帧中搜索匹配行（从顶部开始搜索）
        int maxSearch = screenHeight - 10; // 最多搜索到帧底部 10px 处
        for (int y = 0; y < maxSearch; y += SEARCH_STEP) {
            int[] currPixels = new int[screenWidth];
            curr.getPixels(currPixels, 0, screenWidth, 0, y, screenWidth, 1);

            if (compareRows(samplePixels, currPixels)) {
                // 找到匹配行，计算重叠量
                int overlap = screenHeight - sampleRowInPrev + y;
                if (overlap > 0 && overlap < screenHeight) {
                    return overlap;
                }
            }
        }

        // 没找到匹配，假设最小重叠（屏幕 10%）
        return screenHeight / 10;
    }

    /**
     * 比较两行像素是否相似
     */
    private boolean compareRows(int[] row1, int[] row2) {
        int matchCount = 0;
        int sampleInterval = Math.max(1, row1.length / 50); // 采样 50 个点

        for (int i = 0; i < row1.length; i += sampleInterval) {
            int diff = Math.abs((row1[i] & 0xFF) - (row2[i] & 0xFF))
                     + Math.abs(((row1[i] >> 8) & 0xFF) - ((row2[i] >> 8) & 0xFF))
                     + Math.abs(((row1[i] >> 16) & 0xFF) - ((row2[i] >> 16) & 0xFF));

            if (diff < MATCH_THRESHOLD * 3) {
                matchCount++;
            }
        }

        return matchCount > (row1.length / sampleInterval) * 0.6;
    }

    // ===================== 工具方法 =====================

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            return cropped;
        }

        return bitmap;
    }

    private String saveBitmap(Bitmap bitmap) {
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "LongScreenshot");
        if (!dir.exists()) dir.mkdirs();

        String fileName = "long_screenshot_" + System.currentTimeMillis() + ".png";
        File file = new File(dir, fileName);

        try {
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            // 通知系统媒体库扫描新文件
            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scanIntent.setData(android.net.Uri.fromFile(file));
            sendBroadcast(scanIntent);

            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save", e);
            return null;
        }
    }

    private void updateFloatingWindow() {
        int totalHeight = frames.size() * screenHeight;
        int sizeKB = (int) ((long) screenWidth * totalHeight * 4 / 2 / 1024);

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
                    NOTIFICATION_CHANNEL_ID,
                    "截图服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("长截图后台运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
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

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        for (Bitmap frame : frames) {
            if (!frame.isRecycled()) frame.recycle();
        }
        frames.clear();
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
