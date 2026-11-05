package com.example.nitzpriority;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssClock;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;

/** User-started, time-limited foreground GPS tracking during GNSS calibration. */
public final class GnssLocationTrackingService extends Service {
    static final String ACTION_START = "com.example.nitzpriority.action.START_GNSS_TRACKING";
    private static final String ACTION_STOP_BY_USER =
        "com.example.nitzpriority.action.STOP_GNSS_TRACKING_BY_USER";
    private static final String EXTRA_TOKEN = "session_token";
    private static final String PREFS = "gnss_location_tracking";
    private static final String CHANNEL_ID = "gnss_calibration_location";
    private static final int NOTIFICATION_ID = 8127;
    private static final long MAX_TRACKING_MILLIS = 5 * 60_000L;
    private static final long REQUEST_INTERVAL_MILLIS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private LocationListener locationListener;
    private GnssStatus.Callback gnssStatusCallback;
    private GnssMeasurementsEvent.Callback gnssMeasurementsCallback;
    private String sessionToken = "";
    private int fixCount;
    private int rawMeasurementCount;
    private boolean tracking;
    private final Runnable timeoutRunnable = () -> stopTracking("TIMED_OUT");

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP_BY_USER.equals(action)) {
            stopTracking("STOPPED_BY_USER");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !tracking) {
            sessionToken = intent.getStringExtra(EXTRA_TOKEN);
            if (sessionToken == null) sessionToken = "";
            startTracking();
        }
        return START_NOT_STICKY;
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            writeState("ERROR", "缺少精确定位权限");
            stopSelf();
            return;
        }
        if (locationManager == null
                || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            writeState("ERROR", "GPS Provider 不可用或系统定位已关闭");
            stopSelf();
            return;
        }

        try {
            Notification notification = buildNotification("正在启动 GPS 连续定位……");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            tracking = true;
            fixCount = 0;
            rawMeasurementCount = 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .clear()
                .putString("token", sessionToken)
                .putString("state", "RUNNING")
                .putString("detail", "正在持续请求 GPS 位置")
                .apply();
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null || !tracking) return;
                    fixCount++;
                    long sampleElapsed = location.getElapsedRealtimeNanos() / 1_000_000L;
                    long receiptElapsed = SystemClock.elapsedRealtime();
                    String accuracy = location.hasAccuracy()
                        ? String.format(Locale.US, "%.1f m", location.getAccuracy()) : "未知";
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("token", sessionToken)
                        .putString("state", "RUNNING")
                        .putString("detail", "持续定位中")
                        .putInt("fix_count", fixCount)
                        .putString("last_accuracy", accuracy)
                        .putLong("last_fix_elapsed", receiptElapsed)
                        .putLong("last_location_elapsed", sampleElapsed)
                        .apply();
                    updateNotification("已收到 " + fixCount + " 次 GPS 位置，最近精度 "
                        + accuracy + "；等待本次新 GNSS 授时");
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
                    // Deprecated by Android, retained for older platform compatibility.
                }

                @Override
                public void onProviderEnabled(String provider) {
                    if (LocationManager.GPS_PROVIDER.equals(provider)) {
                        updateNotification("GPS 已开启，持续请求位置并等待新授时");
                    }
                }

                @Override
                public void onProviderDisabled(String provider) {
                    if (LocationManager.GPS_PROVIDER.equals(provider)) {
                        updateNotification("GPS 已关闭；请开启定位，或从通知停止本次任务");
                    }
                }
            };
            registerSatelliteStatusCallback();
            registerRawMeasurementsCallback();
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                REQUEST_INTERVAL_MILLIS, 0f, locationListener, handler.getLooper());
            handler.postDelayed(timeoutRunnable, MAX_TRACKING_MILLIS);
            updateNotification("正在持续请求 GPS 位置并等待新的 GNSS 授时；最多 5 分钟");
        } catch (SecurityException e) {
            writeState("ERROR", "启动 GPS 定位服务被拒绝：" + e.getMessage());
            stopTracking("ERROR");
        } catch (Throwable t) {
            writeState("ERROR", "启动 GPS 连续定位失败：" + t);
            stopTracking("ERROR");
        }
    }

    private void registerSatelliteStatusCallback() {
        gnssStatusCallback = new GnssStatus.Callback() {
            @Override
            public void onStarted() {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putBoolean("gnss_receiver_started", true)
                    .putLong("gnss_started_elapsed", SystemClock.elapsedRealtime())
                    .apply();
                updateNotification("GNSS 接收机已启动；正在等待卫星状态和时间样本");
            }

            @Override
            public void onStopped() {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putBoolean("gnss_receiver_started", false)
                    .apply();
                updateNotification("GNSS 接收机已停止；等待系统重新启动定位");
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putInt("time_to_first_fix_ms", ttffMillis)
                    .putLong("first_fix_elapsed", SystemClock.elapsedRealtime())
                    .apply();
            }

            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                if (!tracking || status == null) return;
                int total = status.getSatelliteCount();
                int usedTotal = 0;
                int[] visible = new int[8];
                int[] used = new int[8];
                for (int i = 0; i < total; i++) {
                    int constellation = status.getConstellationType(i);
                    int bucket = constellationBucket(constellation);
                    if (bucket < 0) continue;
                    visible[bucket]++;
                    if (status.usedInFix(i)) {
                        used[bucket]++;
                        usedTotal++;
                    }
                }
                long receivedElapsed = SystemClock.elapsedRealtime();
                android.content.SharedPreferences.Editor editor =
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("token", sessionToken)
                        .putBoolean("satellite_status_available", true)
                        .putLong("satellite_status_elapsed", receivedElapsed)
                        .putInt("visible_satellites", total)
                        .putInt("used_satellites", usedTotal);
                String[] keys = {"unknown", "gps", "sbas", "glonass", "qzss", "beidou", "galileo", "irnss"};
                for (int i = 0; i < keys.length; i++) {
                    editor.putInt("visible_" + keys[i], visible[i]);
                    editor.putInt("used_" + keys[i], used[i]);
                }
                editor.apply();
                updateNotification("卫星状态：列表 " + total + " 颗，参与定位解算 "
                    + usedTotal + " 颗；等待新的 GNSS 授时样本");
            }
        };
        try {
            boolean registered = locationManager.registerGnssStatusCallback(
                gnssStatusCallback, handler);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("token", sessionToken)
                .putBoolean("satellite_callback_registered", registered)
                .putString("satellite_callback_error", registered ? "" : "系统未注册卫星状态回调")
                .apply();
        } catch (Throwable t) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("token", sessionToken)
                .putBoolean("satellite_callback_registered", false)
                .putString("satellite_callback_error", t.toString())
                .apply();
        }
    }

    private static int constellationBucket(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return 1;
            case GnssStatus.CONSTELLATION_SBAS: return 2;
            case GnssStatus.CONSTELLATION_GLONASS: return 3;
            case GnssStatus.CONSTELLATION_QZSS: return 4;
            case GnssStatus.CONSTELLATION_BEIDOU: return 5;
            case GnssStatus.CONSTELLATION_GALILEO: return 6;
            case GnssStatus.CONSTELLATION_IRNSS: return 7;
            default: return 0;
        }
    }

    private void registerRawMeasurementsCallback() {
        gnssMeasurementsCallback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                if (!tracking || event == null || event.getClock() == null) return;
                GnssClock clock = event.getClock();
                rawMeasurementCount++;
                long callbackElapsedNanos = SystemClock.elapsedRealtimeNanos();
                boolean hasClockElapsed = Build.VERSION.SDK_INT >= 29
                    && clock.hasElapsedRealtimeNanos();
                long clockElapsedNanos = hasClockElapsed
                    ? clock.getElapsedRealtimeNanos() : -1L;
                long ageNanos = clockElapsedNanos >= 0
                    ? callbackElapsedNanos - clockElapsedNanos : Long.MAX_VALUE;
                boolean clockFresh = clockElapsedNanos >= 0
                    && ageNanos >= -100_000_000L && ageNanos <= 10_000_000_000L;

                boolean hasFullBias = clock.hasFullBiasNanos();
                boolean hasBias = clock.hasBiasNanos();
                boolean hasLeapSecond = clock.hasLeapSecond();
                double biasNanos = hasBias ? clock.getBiasNanos() : 0.0;
                double gpsTimeNanos = hasFullBias
                    ? (double) clock.getTimeNanos()
                        - ((double) clock.getFullBiasNanos() + biasNanos)
                    : Double.NaN;

                boolean utcAvailable = hasFullBias && hasLeapSecond && clockFresh;
                long gnssUtcMillis = 0L;
                double phoneUtcAtSampleMillis = Double.NaN;
                double offsetMillis = Double.NaN;
                long wallPairElapsedNanos = -1L;
                long wallPairMillis = 0L;
                long wallPairElapsedBefore = SystemClock.elapsedRealtimeNanos();
                long wallMillis = System.currentTimeMillis();
                long wallPairElapsedAfter = SystemClock.elapsedRealtimeNanos();
                wallPairElapsedNanos = wallPairElapsedBefore
                    + (wallPairElapsedAfter - wallPairElapsedBefore) / 2L;
                wallPairMillis = wallMillis;
                if (utcAvailable) {
                    long gpsSinceEpochMillis = Math.round(gpsTimeNanos / 1_000_000.0);
                    gnssUtcMillis = 315_964_800_000L + gpsSinceEpochMillis
                        - clock.getLeapSecond() * 1_000L;
                    phoneUtcAtSampleMillis = wallPairMillis
                        + (clockElapsedNanos - wallPairElapsedNanos) / 1_000_000.0;
                    offsetMillis = gnssUtcMillis - phoneUtcAtSampleMillis;
                }

                android.content.SharedPreferences.Editor editor =
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("token", sessionToken)
                        .putBoolean("raw_measurement_available", true)
                        .putInt("raw_measurement_count", rawMeasurementCount)
                        .putBoolean("raw_clock_has_elapsed", clockElapsedNanos >= 0)
                        .putBoolean("raw_clock_fresh", clockFresh)
                        .putBoolean("raw_clock_has_full_bias", hasFullBias)
                        .putBoolean("raw_clock_has_bias", hasBias)
                        .putBoolean("raw_clock_has_leap_second", hasLeapSecond)
                        .putBoolean("raw_clock_utc_available", utcAvailable)
                        .putLong("raw_callback_elapsed_nanos", callbackElapsedNanos)
                        .putLong("raw_clock_elapsed_nanos", clockElapsedNanos)
                        .putLong("raw_clock_age_nanos", ageNanos == Long.MAX_VALUE ? -1L : ageNanos)
                        .putLong("raw_time_nanos", clock.getTimeNanos())
                        .putLong("raw_full_bias_nanos", hasFullBias ? clock.getFullBiasNanos() : 0L)
                        .putString("raw_bias_nanos", Double.toString(biasNanos))
                        .putInt("raw_leap_second", hasLeapSecond ? clock.getLeapSecond() : -1)
                        .putString("raw_gps_time_nanos", Double.toString(gpsTimeNanos))
                        .putLong("raw_gnss_utc_millis", gnssUtcMillis)
                        .putString("raw_phone_utc_at_sample_millis",
                            Double.toString(phoneUtcAtSampleMillis))
                        .putString("raw_offset_millis", Double.toString(offsetMillis))
                        .putLong("raw_wall_pair_elapsed_nanos", wallPairElapsedNanos)
                        .putLong("raw_wall_pair_millis", wallPairMillis)
                        .putInt("raw_hardware_clock_discontinuity_count",
                            clock.getHardwareClockDiscontinuityCount());
                editor.putString("raw_time_uncertainty_nanos",
                    clock.hasTimeUncertaintyNanos()
                        ? Double.toString(clock.getTimeUncertaintyNanos()) : "");
                editor.putString("raw_bias_uncertainty_nanos",
                    clock.hasBiasUncertaintyNanos()
                        ? Double.toString(clock.getBiasUncertaintyNanos()) : "");
                boolean hasElapsedUncertainty = Build.VERSION.SDK_INT >= 29
                    && clock.hasElapsedRealtimeUncertaintyNanos();
                editor.putString("raw_elapsed_uncertainty_nanos",
                    hasElapsedUncertainty
                        ? Double.toString(clock.getElapsedRealtimeUncertaintyNanos()) : "");
                editor.putString("raw_drift_nanos_per_second",
                    clock.hasDriftNanosPerSecond()
                        ? Double.toString(clock.getDriftNanosPerSecond()) : "");
                editor.apply();
            }
        };
        try {
            boolean registered;
            if (Build.VERSION.SDK_INT >= 30) {
                registered = locationManager.registerGnssMeasurementsCallback(
                    gnssMeasurementsCallback, handler);
            } else {
                registered = locationManager.registerGnssMeasurementsCallback(
                    gnssMeasurementsCallback);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("token", sessionToken)
                .putBoolean("raw_callback_registered", registered)
                .putString("raw_callback_error", registered ? "" : "系统未注册原始 GNSS 测量回调")
                .apply();
        } catch (Throwable t) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("token", sessionToken)
                .putBoolean("raw_callback_registered", false)
                .putString("raw_callback_error", t.toString())
                .apply();
        }
    }

    private void stopTracking(String finalState) {
        handler.removeCallbacks(timeoutRunnable);
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Throwable ignored) {
                // Provider may already be shut down.
            }
        }
        if (locationManager != null && gnssStatusCallback != null) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (Throwable ignored) {
                // Provider may already be shut down.
            }
        }
        if (locationManager != null && gnssMeasurementsCallback != null) {
            try {
                locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback);
            } catch (Throwable ignored) {
                // Provider may already be shut down.
            }
        }
        locationListener = null;
        gnssStatusCallback = null;
        gnssMeasurementsCallback = null;
        boolean wasTracking = tracking;
        tracking = false;
        if (wasTracking || "STOPPED_BY_USER".equals(finalState)
                || "TIMED_OUT".equals(finalState) || "ERROR".equals(finalState)) {
            String detail;
            if ("STOPPED_BY_USER".equals(finalState)) detail = "用户从通知停止了持续定位";
            else if ("TIMED_OUT".equals(finalState)) detail = "持续定位达到 5 分钟上限，已自动停止";
            else if ("ERROR".equals(finalState)) detail = "定位服务启动失败";
            else detail = "本次 GNSS 校准流程已结束，持续定位已停止";
            writeState(finalState, detail);
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void writeState(String state, String detail) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("token", sessionToken)
            .putString("state", state)
            .putString("detail", detail)
            .apply();
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, GnssCalibrationActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 8127, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, GnssLocationTrackingService.class)
            .setAction(ACTION_STOP_BY_USER);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 8128, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("卫星对时：正在获取 GNSS 样本")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "停止", stopPendingIntent).build());
        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && tracking) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "GNSS 校准定位", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用户手动启动、限时运行的 GNSS 定位校准状态");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        if (tracking) {
            handler.removeCallbacks(timeoutRunnable);
            if (locationManager != null && locationListener != null) {
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (Throwable ignored) {
                    // Best-effort cleanup.
                }
            }
            if (locationManager != null && gnssStatusCallback != null) {
                try {
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
                } catch (Throwable ignored) {
                    // Best-effort cleanup.
                }
            }
            if (locationManager != null && gnssMeasurementsCallback != null) {
                try {
                    locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback);
                } catch (Throwable ignored) {
                    // Best-effort cleanup.
                }
            }
            tracking = false;
            writeState("STOPPED", "持续定位服务已结束");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
