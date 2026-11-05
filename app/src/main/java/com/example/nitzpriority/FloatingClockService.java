package com.example.nitzpriority;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

/** User-enabled global overlay clock, kept alive by a visible foreground-service notification. */
public final class FloatingClockService extends Service {
    public static final String ACTION_START = "com.example.nitzpriority.action.START_FLOATING_CLOCK";
    private static final String ACTION_STOP = "com.example.nitzpriority.action.STOP_FLOATING_CLOCK";
    private static final String CHANNEL_ID = "floating_clock_service";
    private static final int NOTIFICATION_ID = 8140;
    private static final long FRAME_MILLIS = 50L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView clockView;
    private WindowManager.LayoutParams layoutParams;
    private int appliedTextSize = -1;
    private int appliedTextColor = 0;
    private int appliedTransparency = -1;
    private long lastOverlayPermissionCheckElapsed;
    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private boolean moved;
    private int touchSlop;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (clockView == null) return;
            long elapsedNow = android.os.SystemClock.elapsedRealtime();
            boolean checkOverlayPermission =
                elapsedNow - lastOverlayPermissionCheckElapsed >= 1_000L;
            if (checkOverlayPermission) {
                lastOverlayPermissionCheckElapsed = elapsedNow;
                if (!Settings.canDrawOverlays(FloatingClockService.this)) {
                    FloatingClockStyle.preferences(FloatingClockService.this).edit()
                        .putBoolean(FloatingClockStyle.KEY_ENABLED, false)
                        .putBoolean(FloatingClockStyle.KEY_RUNNING, false)
                        .putString(FloatingClockStyle.KEY_LAST_ERROR, "悬浮窗权限已关闭")
                        .apply();
                    stopSelf();
                    return;
                }
            }
            long now = System.currentTimeMillis();
            applyAppearanceIfChanged();
            int digits = millisecondDigits();
            clockView.setText(renderTime(now, digits));
            // Without millisecond digits there is nothing to show between seconds; wake up
            // slightly after the next second boundary instead of redrawing 20 times a second.
            long delay = digits == 0
                ? 1_000L - (now % 1_000L) + 20L
                : FRAME_MILLIS;
            handler.postDelayed(this, delay);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (windowManager == null || !Settings.canDrawOverlays(this)) {
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingClockStyle.KEY_ENABLED, false)
                .putBoolean(FloatingClockStyle.KEY_RUNNING, false)
                .putString(FloatingClockStyle.KEY_LAST_ERROR, "请先授予悬浮窗权限")
                .apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            if (clockView == null) addOverlay();
            applyAppearanceIfChanged();
            clockView.setText(renderTime(System.currentTimeMillis(), millisecondDigits()));
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingClockStyle.KEY_RUNNING, true)
                .remove(FloatingClockStyle.KEY_LAST_ERROR)
                .apply();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
            return START_STICKY;
        } catch (RuntimeException exception) {
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingClockStyle.KEY_ENABLED, false)
                .putBoolean(FloatingClockStyle.KEY_RUNNING, false)
                .putString(FloatingClockStyle.KEY_LAST_ERROR,
                    "悬浮窗启动失败：" + exception.getClass().getSimpleName())
                .apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void addOverlay() {
        clockView = new TextView(this);
        clockView.setSingleLine(true);
        clockView.setIncludeFontPadding(false);
        clockView.setContentDescription("悬浮时间；轻点打开设置，拖动可移动位置");
        applyAppearanceIfChanged();

        android.content.SharedPreferences prefs = FloatingClockStyle.preferences(this);
        int savedX = prefs.getInt(FloatingClockStyle.KEY_X, -1);
        int savedY = prefs.getInt(FloatingClockStyle.KEY_Y, -1);
        layoutParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutParams.x = savedX < 0 ? FloatingClockStyle.dp(this, 14) : savedX;
        layoutParams.y = savedY < 0 ? FloatingClockStyle.dp(this, 130) : savedY;
        windowManager.addView(clockView, layoutParams);
        // Saved coordinates may be off-screen after rotation or a display change.
        clampPosition(false);
        clockView.setOnTouchListener(this::handleOverlayTouch);
    }

    /**
     * Clamps {@link #layoutParams} to the display and pushes it to the window manager.
     * When {@code forceUpdate} is false, the layout is updated only if clamping changed
     * the coordinates (used at startup); drag passes {@code true} so the view follows
     * the finger even while already in bounds.
     */
    private void clampPosition(boolean forceUpdate) {
        if (layoutParams == null || windowManager == null || clockView == null) return;
        int screenWidth;
        int screenHeight;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        }
        int maxX = Math.max(0, screenWidth - FloatingClockStyle.dp(this, 40));
        int maxY = Math.max(0, screenHeight - FloatingClockStyle.dp(this, 40));
        int clampedX = Math.max(0, Math.min(layoutParams.x, maxX));
        int clampedY = Math.max(0, Math.min(layoutParams.y, maxY));
        boolean changed = clampedX != layoutParams.x || clampedY != layoutParams.y;
        layoutParams.x = clampedX;
        layoutParams.y = clampedY;
        if (!changed && !forceUpdate) return;
        try {
            windowManager.updateViewLayout(clockView, layoutParams);
        } catch (RuntimeException ignored) {
            // The system may remove an overlay while permissions are being changed.
        }
    }

    private boolean handleOverlayTouch(View view, MotionEvent event) {
        if (layoutParams == null || windowManager == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = layoutParams.x;
                startY = layoutParams.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
                if (moved) {
                    layoutParams.x = startX + dx;
                    layoutParams.y = startY + dy;
                    clampPosition(true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (moved) {
                    FloatingClockStyle.preferences(this).edit()
                        .putInt(FloatingClockStyle.KEY_X, layoutParams.x)
                        .putInt(FloatingClockStyle.KEY_Y, layoutParams.y)
                        .apply();
                } else {
                    Intent settings = new Intent(this, FloatingClockSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(settings);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void applyAppearanceIfChanged() {
        if (clockView == null) return;
        int size = FloatingClockStyle.textSize(this);
        int color = FloatingClockStyle.textColor(this);
        int transparency = FloatingClockStyle.backgroundTransparency(this);
        if (size == appliedTextSize && color == appliedTextColor
                && transparency == appliedTransparency) return;
        FloatingClockStyle.apply(this, clockView);
        appliedTextSize = size;
        appliedTextColor = color;
        appliedTransparency = transparency;
    }

    private int millisecondDigits() {
        int digits = FloatingClockStyle.preferences(this)
            .getInt("millisecond_digits", 3);
        return Math.max(0, Math.min(3, digits));
    }

    private String renderTime(long nowMillis, int digits) {
        return FloatingClockStyle.formatClock(nowMillis, digits);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, FloatingClockSettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 8140, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("悬浮时间已开启")
            .setContentText("轻点悬浮时钟可调整显示或关闭")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "悬浮时间服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持用户主动开启的全局悬浮时钟运行");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (clockView != null && windowManager != null) {
            try {
                windowManager.removeView(clockView);
            } catch (RuntimeException ignored) {
                // It may already have been removed by the system.
            }
            clockView = null;
        }
        FloatingClockStyle.preferences(this).edit()
            .putBoolean(FloatingClockStyle.KEY_RUNNING, false).apply();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
