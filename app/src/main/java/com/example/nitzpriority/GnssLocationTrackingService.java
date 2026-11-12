package com.example.nitzpriority;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.util.Log;

import java.util.Locale;

/** User-started, time-limited foreground GPS tracking during GNSS calibration. */
public final class GnssLocationTrackingService extends Service {
    static final String ACTION_START = "com.example.nitzpriority.action.START_GNSS_TRACKING";
    /**
     * Periodic-sync burst, started by FloatingClockService every 5 minutes
     * (15 on low battery). Runs the same GPS tracking, but stops after the
     * first validated raw GnssClock offset or a 60 s timeout, and never
     * touches the manual-calibration state in SharedPreferences.
     */
    static final String ACTION_PERIODIC_GNSS_BURST =
        "com.example.nitzpriority.action.PERIODIC_GNSS_BURST";
    private static final String TAG = "NitzPriority";
    private static final long BURST_TIMEOUT_MILLIS = 60_000L;
    /**
     * Conservative ±uncertainty (ms) of a raw GnssClock offset when the chip
     * reports no usable time uncertainty. A chip that reports 0 (or nothing)
     * has not measured an uncertainty at all: 0 ms would claim a perfect
     * clock, so it is treated as unknown and replaced by this budget. The
     * value is the codebase's own bound for a raw offset, not an arbitrary
     * round number: the periodic-sync burst already records raw GNSS samples
     * into the drift store with a 100 ms uncertainty, and the measurement
     * callback itself discards any sample whose reported time uncertainty
     * exceeds 100 ms. The offset also pairs a hardware GNSS clock with the
     * phone wall clock across a Binder callback and a wall-clock read, so
     * sub-millisecond precision cannot be assumed even when the chip reports
     * a small number.
     */
    static final long RAW_UNCERTAINTY_FALLBACK_MILLIS = 100L;
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
    private int lastDiscontinuityCount = -1;
    private boolean tracking;
    /** True while serving a periodic-sync burst (see ACTION_PERIODIC_GNSS_BURST). */
    private boolean burstMode;
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
        if (ACTION_PERIODIC_GNSS_BURST.equals(action) && !tracking) {
            // A burst never pre-empts a manual calibration session: if one
            // is already tracking, its raw measurements already feed the
            // drift store through the same callback path.
            burstMode = true;
            sessionToken = "";
            startTracking();
        }
        return START_NOT_STICKY;
    }

    /**
     * Effective ±uncertainty (ms) of one raw GnssClock offset. Uses the
     * chip-reported time uncertainty when it is present and positive,
     * combined in quadrature with the reported bias uncertainty when that is
     * also present and positive; when the time uncertainty is missing or
     * zero it is treated as unknown and replaced by
     * {@link #RAW_UNCERTAINTY_FALLBACK_MILLIS}. Never returns 0: a zero
     * uncertainty must not reach a sync decision or an uncertainty display.
     */
    static long effectiveRawUncertaintyMillis(double timeUncertaintyNanos,
            double biasUncertaintyNanos) {
        double timeMs = timeUncertaintyNanos / 1_000_000.0;
        if (!(timeMs > 0)) return RAW_UNCERTAINTY_FALLBACK_MILLIS;
        double biasMs = biasUncertaintyNanos / 1_000_000.0;
        if (biasMs > 0) timeMs = Math.sqrt(timeMs * timeMs + biasMs * biasMs);
        return Math.max(1L, Math.round(timeMs));
    }

    /** Latest raw GnssClock measurement, as written by this service. */
    static final class RawGnssSample {
        /** Any raw measurement has been received since tracking started. */
        final boolean available;
        /**
         * The measurement passed every validity gate in the callback
         * (fresh within 1 s, leap second in range, no discontinuity, time
         * uncertainty under 100 ms, |offset| under 60 s) and was not
         * discarded: its UTC offset is usable as a fallback time source.
         */
        final boolean utcAvailable;
        /** GNSS UTC minus phone UTC at the sample instant, in ms. */
        final long offsetMillis;
        /** GNSS UTC at the sample instant, in ms since the Unix epoch. */
        final long gnssUtcMillis;
        /** Effective ±uncertainty (ms); see effectiveRawUncertaintyMillis. */
        final long uncertaintyMillis;
        /** True when the uncertainty is the fallback budget, not a chip value. */
        final boolean uncertaintyEstimated;
        /**
         * GnssClock timestamp of the sample, in elapsedRealtime millis.
         * Falls back to the callback receipt time when the clock carries no
         * elapsed timestamp; -1 when neither is known.
         */
        final long sampleElapsedMillis;
        final int measurementCount;
        final int visibleSatellites;
        final int usedSatellites;
        final long satelliteStatusElapsedMillis;
        final int fixCount;
        final long lastFixElapsedMillis;
        final String discardReason;

        RawGnssSample(boolean available, boolean utcAvailable, long offsetMillis,
                long gnssUtcMillis, long uncertaintyMillis, boolean uncertaintyEstimated,
                long sampleElapsedMillis, int measurementCount, int visibleSatellites,
                int usedSatellites, long satelliteStatusElapsedMillis, int fixCount,
                long lastFixElapsedMillis, String discardReason) {
            this.available = available;
            this.utcAvailable = utcAvailable;
            this.offsetMillis = offsetMillis;
            this.gnssUtcMillis = gnssUtcMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.uncertaintyEstimated = uncertaintyEstimated;
            this.sampleElapsedMillis = sampleElapsedMillis;
            this.measurementCount = measurementCount;
            this.visibleSatellites = visibleSatellites;
            this.usedSatellites = usedSatellites;
            this.satelliteStatusElapsedMillis = satelliteStatusElapsedMillis;
            this.fixCount = fixCount;
            this.lastFixElapsedMillis = lastFixElapsedMillis;
            this.discardReason = discardReason == null ? "" : discardReason;
        }

        /** Sample age at the given elapsedRealtime instant; -1 when unknown. */
        long ageMillis(long nowElapsedMillis) {
            if (sampleElapsedMillis < 0 || nowElapsedMillis < 0) return -1L;
            return Math.max(0L, nowElapsedMillis - sampleElapsedMillis);
        }

        static RawGnssSample fromPrefs(SharedPreferences prefs) {
            boolean available = prefs.getBoolean("raw_measurement_available", false);
            boolean utcAvailable = prefs.getBoolean("raw_clock_utc_available", false)
                && !prefs.getBoolean("raw_sample_discarded", false);
            double offset = parseDouble(prefs.getString("raw_offset_millis", "NaN"));
            long offsetMillis = Double.isFinite(offset) ? Math.round(offset)
                : Long.MIN_VALUE;
            double timeUncertaintyNanos =
                parseDouble(prefs.getString("raw_time_uncertainty_nanos", ""));
            double biasUncertaintyNanos =
                parseDouble(prefs.getString("raw_bias_uncertainty_nanos", ""));
            boolean uncertaintyEstimated = !(timeUncertaintyNanos > 0);
            long uncertaintyMillis = effectiveRawUncertaintyMillis(
                timeUncertaintyNanos, biasUncertaintyNanos);
            long clockElapsedNanos = prefs.getLong("raw_clock_elapsed_nanos", -1L);
            long callbackElapsedNanos = prefs.getLong("raw_callback_elapsed_nanos", -1L);
            long sampleElapsedMillis = clockElapsedNanos >= 0
                ? clockElapsedNanos / 1_000_000L
                : callbackElapsedNanos > 0 ? callbackElapsedNanos / 1_000_000L : -1L;
            return new RawGnssSample(available, utcAvailable, offsetMillis,
                prefs.getLong("raw_gnss_utc_millis", 0L), uncertaintyMillis,
                uncertaintyEstimated, sampleElapsedMillis,
                prefs.getInt("raw_measurement_count", 0),
                prefs.getInt("visible_satellites", -1),
                prefs.getInt("used_satellites", -1),
                prefs.getLong("satellite_status_elapsed", -1L),
                prefs.getInt("fix_count", 0),
                prefs.getLong("last_fix_elapsed", -1L),
                prefs.getString("raw_discard_reason", ""));
        }

        private static double parseDouble(String value) {
            if (value == null || value.isEmpty()) return Double.NaN;
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
    }

    /** Reads the latest raw GnssClock measurement from this service's store. */
    static RawGnssSample readRawSample(Context context) {
        return RawGnssSample.fromPrefs(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
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
            Notification notification = buildNotification(burstMode
                ? "定期同步：正在获取 GNSS 样本（最多 1 分钟）"
                : "正在启动 GPS 连续定位……");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            tracking = true;
            fixCount = 0;
            rawMeasurementCount = 0;
            if (!burstMode) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .clear()
                    .putString("token", sessionToken)
                    .putString("state", "RUNNING")
                    .putString("detail", "正在持续请求 GPS 位置")
                    .apply();
            }
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null || !tracking) return;
                    // A burst needs only the raw GnssClock measurements; the
                    // per-fix state writes belong to manual calibration and
                    // must not leave a stale RUNNING state behind.
                    if (burstMode) return;
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
            handler.postDelayed(timeoutRunnable,
                burstMode ? BURST_TIMEOUT_MILLIS : MAX_TRACKING_MILLIS);
            updateNotification(burstMode
                ? "定期同步：正在获取 GNSS 样本；最多 1 分钟"
                : "正在持续请求 GPS 位置并等待新的 GNSS 授时；最多 5 分钟");
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
                if (burstMode) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putBoolean("gnss_receiver_started", true)
                    .putLong("gnss_started_elapsed", SystemClock.elapsedRealtime())
                    .apply();
                updateNotification("GNSS 接收机已启动；正在等待卫星状态和时间样本");
            }

            @Override
            public void onStopped() {
                if (burstMode) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putBoolean("gnss_receiver_started", false)
                    .apply();
                updateNotification("GNSS 接收机已停止；等待系统重新启动定位");
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                if (burstMode) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("token", sessionToken)
                    .putInt("time_to_first_fix_ms", ttffMillis)
                    .putLong("first_fix_elapsed", SystemClock.elapsedRealtime())
                    .apply();
            }

            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                if (!tracking || status == null || burstMode) return;
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
                // Freshness window is 1 s, not 10 s: a 10 s old GnssClock paired with the
                // current wall clock can be off by seconds (receiver drift + scheduling).
                boolean clockFresh = clockElapsedNanos >= 0
                    && ageNanos >= -100_000_000L && ageNanos <= 1_000_000_000L;

                boolean hasFullBias = clock.hasFullBiasNanos();
                boolean hasBias = clock.hasBiasNanos();
                boolean hasLeapSecond = clock.hasLeapSecond();
                double biasNanos = hasBias ? clock.getBiasNanos() : 0.0;
                int leapSecond = hasLeapSecond ? clock.getLeapSecond() : -1;
                // GPS-UTC is currently 18 s; anything outside 10-30 s means the HAL
                // returned an uninitialised or bogus leap second (0 would shift UTC
                // by 18 s with no warning).
                boolean leapValid = leapSecond >= 10 && leapSecond <= 30;
                int discontinuityCount = clock.getHardwareClockDiscontinuityCount();
                boolean discontinuityChanged = lastDiscontinuityCount >= 0
                    && discontinuityCount != lastDiscontinuityCount;
                lastDiscontinuityCount = discontinuityCount;
                // A reported time uncertainty over 100 ms makes the sample diagnostic
                // only; it must not feed a millisecond offset display.
                boolean uncertaintyOk = !clock.hasTimeUncertaintyNanos()
                    || clock.getTimeUncertaintyNanos() < 100_000_000.0;
                // GPS time in long nanos: double loses integer precision above 2^53
                // (~1.4e18 ns here is ~256 ns steps) and must not carry the conversion.
                long gpsTimeNanos = hasFullBias
                    ? clock.getTimeNanos() - clock.getFullBiasNanos() - (long) biasNanos
                    : 0L;

                String discardReason = "";
                if (!clockFresh) discardReason = "stale_clock_over_1s";
                else if (!leapValid) discardReason = "leap_second_out_of_range";
                else if (discontinuityChanged) discardReason = "clock_discontinuity_changed";
                else if (!uncertaintyOk) discardReason = "time_uncertainty_over_100ms";
                boolean utcAvailable = hasFullBias && hasLeapSecond && clockFresh
                    && leapValid && !discontinuityChanged && uncertaintyOk;
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
                    long gpsSinceEpochMillis = gpsTimeNanos / 1_000_000L;
                    gnssUtcMillis = 315_964_800_000L + gpsSinceEpochMillis
                        - (long) leapSecond * 1_000L;
                    phoneUtcAtSampleMillis = wallPairMillis
                        + (clockElapsedNanos - wallPairElapsedNanos) / 1_000_000.0;
                    offsetMillis = gnssUtcMillis - phoneUtcAtSampleMillis;
                    // A GPS week rollover or uninitialised receiver clock can produce a
                    // wildly wrong offset; never display it as a real millisecond value.
                    if (Math.abs(offsetMillis) > 60_000.0) {
                        discardReason = "offset_over_60s";
                        utcAvailable = false;
                        gnssUtcMillis = 0L;
                        offsetMillis = Double.NaN;
                    }
                }
                boolean sampleDiscarded = !discardReason.isEmpty();

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
                        .putInt("raw_leap_second", leapSecond)
                        .putBoolean("raw_leap_second_valid", leapValid)
                        .putBoolean("raw_sample_discarded", sampleDiscarded)
                        .putString("raw_discard_reason", discardReason)
                        .putBoolean("raw_discontinuity_changed", discontinuityChanged)
                        .putString("raw_gps_time_nanos",
                            hasFullBias ? Long.toString(gpsTimeNanos) : "NaN")
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
                if (burstMode && utcAvailable && !sampleDiscarded) {
                    // First validated raw GnssClock offset of this burst: the
                    // callback already checked freshness (1 s), leap second,
                    // discontinuity and time uncertainty (< 100 ms), so this
                    // is a trustworthy GNSS offset sample. Record it into the
                    // shared drift store (the estimator's 55 s throttle keeps
                    // bursts from flooding it) and stop the burst.
                    long sampleElapsedMillis = clockElapsedNanos / 1_000_000L;
                    long gnssOffsetMillis = Math.round(offsetMillis);
                    ClockDriftEstimator.recordSample(
                        getSharedPreferences(ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE),
                        sampleElapsedMillis, gnssOffsetMillis, 100L,
                        ClockDriftEstimator.SOURCE_GNSS);
                    Log.i(TAG, "periodic sync: source=gnss offset=" + gnssOffsetMillis
                        + "ms uncertainty=100ms");
                    stopTracking("BURST_DONE");
                }
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
        // burstMode is cleared only after the block below: writeState() uses
        // it to decide whether this stop may touch the manual-calibration
        // state, and a burst stop (wasTracking == true) must not.
        if ((wasTracking || "STOPPED_BY_USER".equals(finalState)
                || "TIMED_OUT".equals(finalState) || "ERROR".equals(finalState))
                && !burstMode) {
            String detail;
            if ("STOPPED_BY_USER".equals(finalState)) detail = "用户从通知停止了持续定位";
            else if ("TIMED_OUT".equals(finalState)) detail = "持续定位达到 5 分钟上限，已自动停止";
            else if ("ERROR".equals(finalState)) detail = "定位服务启动失败";
            else detail = "本次 GNSS 校准流程已结束，持续定位已停止";
            writeState(finalState, detail);
        }
        burstMode = false;
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void writeState(String state, String detail) {
        if (burstMode) {
            // A periodic burst must not overwrite the manual-calibration
            // state the calibration page reads; its outcome is only a log
            // line and a drift sample.
            Log.i(TAG, "periodic gnss burst: " + state + " " + detail);
            return;
        }
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
            burstMode = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
