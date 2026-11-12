package com.example.nitzpriority;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-enabled global overlay clock, kept alive by a visible foreground-service notification. */
public final class FloatingClockService extends Service {
    private static final String TAG = "NitzPriority";
    public static final String ACTION_START = "com.example.nitzpriority.action.START_FLOATING_CLOCK";
    private static final String ACTION_STOP = "com.example.nitzpriority.action.STOP_FLOATING_CLOCK";
    private static final String CHANNEL_ID = "floating_clock_service";
    private static final int NOTIFICATION_ID = 8140;
    private static final long FRAME_MILLIS = 50L;
    /** Periodic sync: NTSC NTP + one GNSS burst, screen-on only. */
    private static final long PERIODIC_SYNC_INTERVAL_MILLIS = 5L * 60 * 1000;
    private static final long LOW_BATTERY_SYNC_INTERVAL_MILLIS = 15L * 60 * 1000;
    private static final int LOW_BATTERY_CAPACITY_PERCENT = 15;
    /** When the fitted drift already predicts an error beyond the network
     * threshold by the next scheduled tick, come back this soon instead. */
    private static final long EARLY_SYNC_DELAY_MILLIS = 60_000L;
    private static final long SCREEN_ON_SYNC_DELAY_MILLIS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LinearLayout clockContainer;
    private TextView secondsView;
    private TextView millisView;
    private String lastSecondsText = "";
    private String lastMillisText = "";
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
    private boolean screenOn = true;
    private boolean snapPending;
    private int snapTargetX;
    private int snapTargetY;
    private boolean overlayRemoved;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // The overlay is invisible with the screen off; stop waking the
                // main thread every 50 ms until the screen comes back.
                screenOn = false;
                handler.removeCallbacks(ticker);
                // Periodic sync also stops with the screen off; it is
                // re-posted on SCREEN_ON below.
                handler.removeCallbacks(periodicSync);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOn = true;
                handler.removeCallbacks(ticker);
                handler.post(ticker);
                handler.removeCallbacks(periodicSync);
                handler.postDelayed(periodicSync, SCREEN_ON_SYNC_DELAY_MILLIS);
            }
        }
    };

    private final ExecutorService syncWorker = Executors.newSingleThreadExecutor();

    /**
     * Periodic sync tick, hosted on this service's Handler because it is the
     * only always-on app component (no WorkManager, no new service). Each
     * tick refreshes the NTSC NTP reference on a worker thread and asks the
     * location foreground service for one short GNSS burst; both results
     * land in the shared drift store as offset samples. The tick never
     * steps the clock and never runs with the screen off.
     */
    private final Runnable periodicSync = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!screenOn) return;
            android.content.SharedPreferences prefs = getSharedPreferences(
                ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE);
            long interval = isLowBattery()
                ? LOW_BATTERY_SYNC_INTERVAL_MILLIS : PERIODIC_SYNC_INTERVAL_MILLIS;
            if (!ClockDriftEstimator.isPeriodicSyncEnabled(prefs)) {
                // Switch off: keep re-checking on schedule so turning it back
                // on takes effect without restarting the service.
                handler.postDelayed(this, interval);
                return;
            }
            // Normal next run is scheduled first; the worker may pull it
            // forward to EARLY_SYNC_DELAY_MILLIS if the drift fit predicts
            // an error beyond the network threshold by then.
            handler.postDelayed(this, interval);
            syncWorker.execute(() -> {
                NtscNtpClient.Result result = NtscNtpClient.query(false);
                if (result != null && result.success) {
                    ClockDriftEstimator.recordSample(prefs,
                        result.queryElapsedRealtimeMillis, result.offsetMillis,
                        result.uncertaintyMillis, ClockDriftEstimator.SOURCE_NTP);
                    Log.i(TAG, "periodic sync: source=ntp offset=" + result.offsetMillis
                        + "ms uncertainty=" + result.uncertaintyMillis
                        + "ms server=" + result.server);
                } else {
                    Log.i(TAG, "periodic sync: ntp query failed: "
                        + (result == null ? "null" : result.error));
                }
                // One short GNSS burst. The burst service is a location
                // foreground service; starting it from this foreground
                // service is allowed, and it stops itself after the first
                // validated raw GnssClock offset or a 60 s timeout.
                try {
                    Intent burst = new Intent(FloatingClockService.this,
                        GnssLocationTrackingService.class)
                        .setAction(GnssLocationTrackingService.ACTION_PERIODIC_GNSS_BURST);
                    startForegroundService(burst);
                } catch (Throwable t) {
                    Log.i(TAG, "periodic sync: gnss burst start failed: " + t);
                }
                ClockDriftEstimator.Estimate estimate = ClockDriftEstimator.estimate(
                    prefs, SystemClock.elapsedRealtime());
                if (estimate != null) {
                    long predicted = ClockDriftEstimator.projectedErrorMillis(estimate,
                        SystemClock.elapsedRealtime() + interval);
                    if (predicted
                            > NitzPriorityHook.NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS) {
                        handler.removeCallbacks(periodicSync);
                        handler.postDelayed(periodicSync, EARLY_SYNC_DELAY_MILLIS);
                        Log.i(TAG, "periodic sync: early sync, predicted error " + predicted
                            + "ms exceeds 50ms network threshold");
                    }
                }
            });
        }
    };

    private boolean isLowBattery() {
        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (batteryManager == null) return false;
        int capacity = batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY);
        // Unknown capacity reports Integer.MIN_VALUE; that is not low battery.
        return capacity > 0 && capacity <= LOW_BATTERY_CAPACITY_PERCENT;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (clockContainer == null || !screenOn) return;
            long elapsedNow = android.os.SystemClock.elapsedRealtime();
            boolean checkOverlayPermission =
                elapsedNow - lastOverlayPermissionCheckElapsed >= 1_000L;
            if (checkOverlayPermission) {
                lastOverlayPermissionCheckElapsed = elapsedNow;
                if (!Settings.canDrawOverlays(FloatingClockService.this)) {
                    // Keep KEY_ENABLED: losing the permission is not the user
                    // turning the clock off; it restarts once permission returns.
                    FloatingClockStyle.preferences(FloatingClockService.this).edit()
                        .putBoolean(FloatingClockStyle.KEY_RUNNING, false)
                        .putString(FloatingClockStyle.KEY_LAST_ERROR,
                            "悬浮窗权限已关闭；重新授权后回到悬浮时间设置页可自动恢复")
                        .apply();
                    stopSelf();
                    return;
                }
            }
            long now = System.currentTimeMillis();
            applyAppearanceIfChanged();
            int digits = millisecondDigits();
            updateClockText(now, digits);
            // Without millisecond digits there is nothing to show between seconds; wake up
            // slightly after the next second boundary instead of redrawing 20 times a second.
            // In millis mode the delay is aligned to the 50 ms frame boundary so the
            // tick does not drift fast/slow against the wall clock.
            long delay = digits == 0
                ? 1_000L - (now % 1_000L) + 20L
                : FRAME_MILLIS - (now % FRAME_MILLIS);
            handler.postDelayed(this, delay);
        }
    };

    /** Seconds and millis live in separate TextViews so the millis part can fade/slide on its own instead of replacing the whole string every frame. */
    private void updateClockText(long now, int digits) {
        String full = FloatingClockStyle.formatClock(now, digits);
        String seconds = digits == 0 ? full : full.substring(0, full.length() - digits - 1);
        String millis = digits == 0 ? "" : full.substring(full.length() - digits - 1);
        if (!seconds.equals(lastSecondsText)) {
            lastSecondsText = seconds;
            secondsView.setText(seconds);
        }
        if (digits == 0) {
            if (millisView.getVisibility() != View.GONE) millisView.setVisibility(View.GONE);
            lastMillisText = "";
            return;
        }
        if (millisView.getVisibility() != View.VISIBLE) millisView.setVisibility(View.VISIBLE);
        if (!millis.equals(lastMillisText)) {
            lastMillisText = millis;
            millisView.setText(millis);
            animateMillisChange();
        }
    }

    /**
     * Millis changes every frame, so this is a short soft fade/slide, not a discrete roll:
     * the 90 ms animation restarts each tick and the digit reads as gently pulsing
     * instead of hard-cutting. Skipped entirely when animations are off.
     */
    private void animateMillisChange() {
        if (!FloatingClockStyle.animationsEnabled(this)) return;
        millisView.animate().cancel();
        millisView.setAlpha(0.5f);
        millisView.setTranslationY(FloatingClockStyle.dp(this, 3));
        millisView.animate().alpha(1f).translationY(0f).setDuration(90)
            .setInterpolator(new DecelerateInterpolator()).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = powerManager == null || powerManager.isInteractive();
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);
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
            // Keep KEY_ENABLED (user intent); only record that it is not running.
            FloatingClockStyle.preferences(this).edit()
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
            if (clockContainer == null) addOverlay();
            applyAppearanceIfChanged();
            updateClockText(System.currentTimeMillis(), millisecondDigits());
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingClockStyle.KEY_RUNNING, true)
                .remove(FloatingClockStyle.KEY_LAST_ERROR)
                .apply();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
            handler.removeCallbacks(periodicSync);
            handler.postDelayed(periodicSync, SCREEN_ON_SYNC_DELAY_MILLIS);
            return START_STICKY;
        } catch (RuntimeException exception) {
            // Keep KEY_ENABLED: a start failure is not the user turning it off.
            FloatingClockStyle.preferences(this).edit()
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
        clockContainer = new LinearLayout(this);
        clockContainer.setOrientation(LinearLayout.HORIZONTAL);
        clockContainer.setGravity(Gravity.CENTER_VERTICAL);
        secondsView = new TextView(this);
        secondsView.setSingleLine(true);
        secondsView.setIncludeFontPadding(false);
        millisView = new TextView(this);
        millisView.setSingleLine(true);
        millisView.setIncludeFontPadding(false);
        clockContainer.addView(secondsView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clockContainer.addView(millisView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clockContainer.setContentDescription("悬浮时间；轻点打开设置，拖动可移动位置");
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
        windowManager.addView(clockContainer, layoutParams);
        // Saved coordinates may be off-screen after rotation or a display change.
        clampPosition(false);
        overlayRemoved = false;
        // Fade in on appear (200 ms); skipped when animations are off.
        if (FloatingClockStyle.animationsEnabled(this)) {
            clockContainer.setAlpha(0f);
            clockContainer.animate().alpha(1f).setDuration(200).start();
        }
        clockContainer.setOnTouchListener(this::handleOverlayTouch);
    }

    private int[] displaySize() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new int[] {bounds.width(), bounds.height()};
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[] {metrics.widthPixels, metrics.heightPixels};
    }

    /**
     * Clamps {@link #layoutParams} to the display and pushes it to the window manager.
     * When {@code forceUpdate} is false, the layout is updated only if clamping changed
     * the coordinates (used at startup); drag passes {@code true} so the view follows
     * the finger even while already in bounds.
     */
    private void clampPosition(boolean forceUpdate) {
        if (layoutParams == null || windowManager == null || clockContainer == null) return;
        int[] size = displaySize();
        int maxX = Math.max(0, size[0] - FloatingClockStyle.dp(this, 40));
        int maxY = Math.max(0, size[1] - FloatingClockStyle.dp(this, 40));
        int clampedX = Math.max(0, Math.min(layoutParams.x, maxX));
        int clampedY = Math.max(0, Math.min(layoutParams.y, maxY));
        boolean changed = clampedX != layoutParams.x || clampedY != layoutParams.y;
        layoutParams.x = clampedX;
        layoutParams.y = clampedY;
        if (!changed && !forceUpdate) return;
        try {
            windowManager.updateViewLayout(clockContainer, layoutParams);
        } catch (RuntimeException ignored) {
            // The system may remove an overlay while permissions are being changed.
        }
    }

    private boolean handleOverlayTouch(View view, MotionEvent event) {
        if (layoutParams == null || windowManager == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A snap animation may still be running: finish it instantly so the
                // drag starts from the real position, not from a mid-animation offset.
                finishPendingSnap();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = layoutParams.x;
                startY = layoutParams.y;
                moved = false;
                pressFeedback(true);
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
                pressFeedback(false);
                if (moved) {
                    snapToEdge();
                } else {
                    Intent settings = new Intent(this, FloatingClockSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(settings);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressFeedback(false);
                if (moved) snapToEdge();
                return true;
            default:
                return false;
        }
    }

    /** Press: 1.05x + deeper shadow, 100 ms. Released: back to 1.0. Skipped when animations are off. */
    private void pressFeedback(boolean pressed) {
        if (clockContainer == null) return;
        if (!FloatingClockStyle.animationsEnabled(this)) {
            clockContainer.setScaleX(1f);
            clockContainer.setScaleY(1f);
            clockContainer.setElevation(FloatingClockStyle.dp(this, 8));
            return;
        }
        clockContainer.animate().cancel();
        clockContainer.animate().scaleX(pressed ? 1.05f : 1f).scaleY(pressed ? 1.05f : 1f)
            .setDuration(100).start();
        clockContainer.setElevation(FloatingClockStyle.dp(this, pressed ? 14 : 8));
    }

    /**
     * Snap to the nearest left/right edge (14 dp margin) over 200 ms. Only the container's
     * translation is animated (GPU compositing, no IPC); the real layout params are
     * written once at the end.
     */
    private void snapToEdge() {
        if (clockContainer == null || layoutParams == null) return;
        int[] size = displaySize();
        int margin = FloatingClockStyle.dp(this, 14);
        int width = clockContainer.getWidth();
        int targetX = layoutParams.x + width / 2 < size[0] / 2
            ? margin : Math.max(margin, size[0] - width - margin);
        int maxY = Math.max(0, size[1] - FloatingClockStyle.dp(this, 40));
        int targetY = Math.max(0, Math.min(layoutParams.y, maxY));
        int dx = targetX - layoutParams.x;
        int dy = targetY - layoutParams.y;
        if (dx == 0 && dy == 0) {
            saveOverlayPosition();
            return;
        }
        if (!FloatingClockStyle.animationsEnabled(this)) {
            layoutParams.x = targetX;
            layoutParams.y = targetY;
            clampPosition(true);
            saveOverlayPosition();
            return;
        }
        snapPending = true;
        snapTargetX = targetX;
        snapTargetY = targetY;
        // Do NOT cancel the animator here: the press-release scale animation may
        // still be running, and cancelling it would freeze the scale mid-flight.
        // Adding translation to the same animator keeps the scale gliding to 1.0.
        clockContainer.setTranslationX(0f);
        clockContainer.setTranslationY(0f);
        clockContainer.animate().translationX(dx).translationY(dy).setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(this::finishPendingSnap).start();
    }

    private void finishPendingSnap() {
        if (!snapPending || clockContainer == null) return;
        snapPending = false;
        clockContainer.animate().cancel();
        clockContainer.setTranslationX(0f);
        clockContainer.setTranslationY(0f);
        // The cancel above can freeze a concurrent scale animation mid-flight.
        clockContainer.setScaleX(1f);
        clockContainer.setScaleY(1f);
        layoutParams.x = snapTargetX;
        layoutParams.y = snapTargetY;
        clampPosition(true);
        saveOverlayPosition();
    }

    private void saveOverlayPosition() {
        FloatingClockStyle.preferences(this).edit()
            .putInt(FloatingClockStyle.KEY_X, layoutParams.x)
            .putInt(FloatingClockStyle.KEY_Y, layoutParams.y)
            .apply();
    }

    private void applyAppearanceIfChanged() {
        if (clockContainer == null) return;
        int size = FloatingClockStyle.textSize(this);
        int color = FloatingClockStyle.textColor(this);
        int transparency = FloatingClockStyle.backgroundTransparency(this);
        if (size == appliedTextSize && color == appliedTextColor
                && transparency == appliedTransparency) return;
        FloatingClockStyle.applyBackground(this, clockContainer);
        FloatingClockStyle.applyText(this, secondsView);
        FloatingClockStyle.applyText(this, millisView);
        appliedTextSize = size;
        appliedTextColor = color;
        appliedTransparency = transparency;
    }

    private int millisecondDigits() {
        int digits = FloatingClockStyle.preferences(this)
            .getInt("millisecond_digits", 3);
        return Math.max(0, Math.min(3, digits));
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
        handler.removeCallbacks(periodicSync);
        syncWorker.shutdownNow();
        try {
            unregisterReceiver(screenReceiver);
        } catch (RuntimeException ignored) {
            // Already unregistered, or registration never completed.
        }
        if (clockContainer != null && windowManager != null) {
            final WindowManager wm = windowManager;
            final View view = clockContainer;
            if (FloatingClockStyle.animationsEnabled(this)) {
                // Fade out (150 ms) and only then remove the view. The delayed
                // fallback covers the case where the animator never fires its
                // end action; both paths are guarded by overlayRemoved.
                Runnable removeOverlay = () -> {
                    if (overlayRemoved) return;
                    overlayRemoved = true;
                    try {
                        wm.removeView(view);
                    } catch (RuntimeException ignored) {
                        // It may already have been removed by the system.
                    }
                };
                view.animate().alpha(0f).setDuration(150)
                    .withEndAction(removeOverlay).start();
                handler.postDelayed(removeOverlay, 200);
            } else {
                try {
                    wm.removeView(view);
                } catch (RuntimeException ignored) {
                    // It may already have been removed by the system.
                }
                overlayRemoved = true;
            }
            clockContainer = null;
            secondsView = null;
            millisView = null;
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
