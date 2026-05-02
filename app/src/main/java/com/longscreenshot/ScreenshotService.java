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

    private static final String TAG = "ScreenshotSvc";
    public static final String ACTION_START   = "ACTION_START";
    public static final String ACTION_STOP    = "ACTION_STOP";
    public static final String ACTION_CAPTURE = "ACTION_CAPTURE";

    private static final String CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFY_ID = 1002;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Handler bgHandler;
    private HandlerThread bgThread;

    private int screenWidth, screenHeight, screenDensity;
    private final List<Bitmap> frames = new ArrayList<>();
    private volatile boolean isProjectionReady = false;

    // 去重参数
    private static final int MATCH_THRESHOLD = 30;
    private static final float OVERLAP_SEARCH_RATIO = 0.5f;
    private static final int SEARCH_STEP = 2;

    // ===================== 生命周期 =====================

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        bgThread = new HandlerThread("ScreenshotBg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        readScreenSize();
        Log.d(TAG, "onCreate | screen=" + screenWidth + "x" + screenHeight);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 12+：每次 startForegroundService 后必须尽快 startForeground
        startForeground(NOTIFY_ID, buildNotif("正在运行..."));

        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand action=" + action + " ready=" + isProjectionReady);

        if (ACTION_START.equals(action)) {
            initProjection();
        } else if (ACTION_CAPTURE.equals(action)) {
            captureFrame();
        } else if (ACTION_STOP.equals(action)) {
            stopAndSave();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (bgThread != null) bgThread.quitSafely();
        ProjectionHolder.clear();
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // ===================== 初始化 MediaProjection =====================

    private void initProjection() {
        if (isProjectionReady) {
            Log.d(TAG, "Already ready, skip");
            return;
        }

        if (ProjectionHolder.resultCode == -1 || ProjectionHolder.data == null) {
            Log.e(TAG, "ProjectionHolder empty!");
            updateNotif("授权数据缺失，请重新打开App");
            return;
        }

        try {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mgr.getMediaProjection(
                    ProjectionHolder.resultCode, ProjectionHolder.data);
            ProjectionHolder.clear();

            imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "LongScreenshot",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    imageReader.getSurface(), null, bgHandler);

            isProjectionReady = true;
            frames.clear();
            updateNotif("就绪 - 滚动页面后点悬浮窗截取");
            Log.d(TAG, "Projection READY");
        } catch (Exception e) {
            Log.e(TAG, "initProjection failed", e);
            updateNotif("启动失败: " + e.getMessage());
        }
    }

    // ===================== 截取 =====================

    private void captureFrame() {
        if (!isProjectionReady || imageReader == null) {
            Log.w(TAG, "capture: not ready");
            updateNotif("服务未就绪，请重新打开App授权");
            return;
        }

        bgHandler.postDelayed(() -> {
            try {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    Log.w(TAG, "No image");
                    updateNotif("未获取到画面，请稍后重试");
                    return;
                }

                Bitmap bmp = imageToBitmap(image);
                image.close();

                if (bmp != null) {
                    frames.add(bmp);
                    String msg = "已截 " + frames.size() + " 帧";
                    Log.d(TAG, msg);
                    updateNotif(msg);
                    notifyFloatingWindow();
                }
            } catch (Exception e) {
                Log.e(TAG, "captureFrame err", e);
            }
        }, 300);
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int ps = planes[0].getPixelStride();
            int rs = planes[0].getRowStride();
            int pad = (rs - ps * screenWidth) / ps;

            Bitmap src = Bitmap.createBitmap(
                    screenWidth + pad, screenHeight, Bitmap.Config.ARGB_8888);
            src.copyPixelsFromBuffer(buf);
            return Bitmap.createBitmap(src, 0, 0, screenWidth, screenHeight);
        } catch (Exception e) {
            Log.e(TAG, "imageToBitmap err", e);
            return null;
        }
    }

    // ===================== 保存 =====================

    private void stopAndSave() {
        if (frames.isEmpty()) {
            updateNotif("没有截取任何帧");
            return;
        }

        isProjectionReady = false;
        updateNotif("正在拼接保存...");

        bgHandler.postDelayed(() -> {
            try {
                Bitmap result = stitchFrames();
                String path = saveBitmap(result);
                if (path != null) {
                    updateNotif("已保存到: " + path);
                    Log.d(TAG, "Saved: " + path);
                } else {
                    updateNotif("保存失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "save err", e);
                updateNotif("保存出错: " + e.getMessage());
            }

            // 通知悬浮窗关闭（用 sendBroadcast 不依赖 Context 引用）
            sendBroadcast(new Intent(FloatingWindowService.ACTION_HIDE));

            // 清理资源
            cleanup();
            stopSelf();
        }, 500);
    }

    // ===================== 帧拼接 =====================

    private Bitmap stitchFrames() {
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0);

        int totalH = 0;
        List<Bitmap> uniq = new ArrayList<>();
        Bitmap prev = frames.get(0);
        uniq.add(prev);
        totalH += prev.getHeight();

        for (int i = 1; i < frames.size(); i++) {
            Bitmap cur = frames.get(i);
            int ov = findOverlap(prev, cur);
            if (ov > 0) {
                Bitmap crop = Bitmap.createBitmap(cur, 0, ov, cur.getWidth(), cur.getHeight() - ov);
                uniq.add(crop);
                totalH += crop.getHeight();
            } else {
                uniq.add(cur);
                totalH += cur.getHeight();
            }
            prev = cur;
        }

        Bitmap out = Bitmap.createBitmap(screenWidth, totalH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int y = 0;
        for (Bitmap b : uniq) { c.drawBitmap(b, 0, y, null); y += b.getHeight(); }
        Log.d(TAG, "Stitched " + frames.size() + " -> " + screenWidth + "x" + totalH);
        return out;
    }

    private int findOverlap(Bitmap prev, Bitmap cur) {
        int w = prev.getWidth(), pH = prev.getHeight();
        int start = (int)(pH * OVERLAP_SEARCH_RATIO);
        for (int off = start; off < pH - 10; off += SEARCH_STEP) {
            int ok = 0, rows = Math.min(5, pH - off);
            for (int r = 0; r < rows; r++) {
                int py = pH - off + r, cy = r;
                if (py >= pH || cy >= cur.getHeight()) break;
                if (isRowSimilar(prev, cur, py, cy, w)) ok++;
            }
            if (ok >= rows * 0.8) return off;
        }
        return 0;
    }

    private boolean isRowSimilar(Bitmap a, Bitmap b, int y1, int y2, int w) {
        int step = Math.max(1, w / 50), ok = 0;
        for (int x = 0; x < w; x += step) {
            int c1 = a.getPixel(x, y1), c2 = b.getPixel(x, y2);
            if (Math.abs(Color.red(c1)-Color.red(c2))
              + Math.abs(Color.green(c1)-Color.green(c2))
              + Math.abs(Color.blue(c1)-Color.blue(c2)) < MATCH_THRESHOLD) ok++;
        }
        return ok > (w / step) * 0.7;
    }

    private String saveBitmap(Bitmap bmp) {
        if (bmp == null) return null;
        try {
            File dir = new File(getExternalFilesDir(null), "LongScreenshot");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "screenshot_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(CompressFormat.PNG, 100, fos);
            fos.flush(); fos.close();
            return f.getAbsolutePath();
        } catch (Exception e) { Log.e(TAG, "save err", e); return null; }
    }

    // ===================== 通知 =====================

    private void readScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        screenWidth = m.widthPixels;
        screenHeight = m.heightPixels;
        screenDensity = m.densityDpi;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "截图服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("长截图后台运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("长截图")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotif(text));
    }

    private void notifyFloatingWindow() {
        int h = 0; for (Bitmap f : frames) h += f.getHeight();
        Intent i = new Intent(FloatingWindowService.ACTION_UPDATE);
        i.putExtra("height", h);
        i.putExtra("frames", frames.size());
        sendBroadcast(i);
    }

    // ===================== 清理 =====================

    private void cleanup() {
        isProjectionReady = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        for (Bitmap f : frames) { if (!f.isRecycled()) f.recycle(); }
        frames.clear();
    }
}
