package com.example.nitzpriority;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GnssCalibrationActivity extends Activity {
    private static final int REQUEST_FINE_LOCATION = 41;
    private static final int REQUEST_POST_NOTIFICATIONS = 42;
    private static final long GNSS_OPERATION_TIMEOUT_MILLIS = 5 * 60_000L;
    private static final long GNSS_POLL_INTERVAL_MILLIS = 2_000L;
    /**
     * Grace window after the first valid raw GnssClock offset before a
     * calibration may complete on the raw measurement. When the system
     * GnssTimeUpdateService is running, its suggestion normally follows a
     * GPS fix within seconds, so 30 s is a generous grace that keeps the
     * system suggestion primary; only when it still has not arrived does
     * the raw offset finish the run (labelled as raw-sourced).
     */
    private static final long RAW_FALLBACK_GRACE_MILLIS = 30_000L;
    private static final String TIME_DETECTOR_MARKER = "__NITZPRIORITY_TIME_DETECTOR__";
    private static final String GNSS_RESULT_MARKER = "__NITZPRIORITY_GNSS_RESULT__";
    private static final Pattern GNSS_ELAPSED_PATTERN =
        Pattern.compile("mElapsedRealtimeMillis=(\\d+)");
    private static final Pattern GNSS_SUGGESTION_ELAPSED_PATTERN = Pattern.compile(
        "GnssTimeSuggestion\\{.*?mElapsedRealtimeMillis=(\\d+)", Pattern.DOTALL);
    private static final Pattern GNSS_LOCATION_TIME_NANOS_PATTERN = Pattern.compile(
        "LocationTime\\{[^}]*mElapsedRealtimeNanos=[^\\(]*\\((\\d+)\\)", Pattern.DOTALL);
    private static final Pattern PASSIVE_LOCATION_RECEIPT_PATTERN = Pattern.compile(
        "(?m)^[ \\t]*PT(?:(\\d+)H)?(?:(\\d+)M)?([0-9]+(?:\\.[0-9]+)?)S"
            + "[ \\t]+/[^\\n]* - Passive location time received: LocationTime\\{");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout contentLayout;
    private TextView statusView;
    private android.graphics.drawable.GradientDrawable statusBackground;
    private ProgressBar calibrationProgress;
    private TextView satelliteView;
    private TextView satelliteDetailsView;
    private TextView rawClockView;
    private TextView rawClockDetailsView;
    private int lastVisibleSatellites = -1;
    private int lastUsedSatellites = -1;
    private boolean satelliteDetailsExpanded;
    private boolean rawClockDetailsExpanded;
    private Button triggerButton;
    private Button stopButton;
    private LocationManager locationManager;
    private long operationStartElapsedMillis = -1;
    private long operationDeadlineElapsedMillis = -1;
    private long firstValidRawElapsedMillis = -1;
    private volatile String triggerToken = "";
    private String lastGpsAccuracy = "未知";
    private boolean operationRunning;
    private boolean triggerSignalSent;
    private volatile boolean recoveryCancelSent;
    private volatile boolean activityDestroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setClipToPadding(false);
        page.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFE8F6FF, 0xFFF4F9FF, 0xFFEAF8F5}));
        styleSystemBars();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(26), dp(22), dp(28));
        page.addView(layout, new ScrollView.LayoutParams(-1, -2));
        contentLayout = layout;

        TextView title = new TextView(this);
        title.setText("卫星对时");
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF172033);
        layout.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("GNSS 授时优先 · 基站 NITZ 后备");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF68758A);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        layout.addView(subtitle, subtitleParams);

        TextView description = new TextView(this);
        description.setText("点击开始后，模块会持续请求 GPS 定位，并等待新的 GNSS 时间样本，最长 5 分钟。建议在室外开阔处操作；持续定位会增加耗电。定位成功不一定代表授时样本已刷新。系统建议始终优先；若系统建议一直不到，将在测得原始 GnssClock 偏移后以其完成本次校准（会明确标注来源是原始时钟）。");
        description.setTextSize(14);
        description.setTextColor(0xFF354258);
        description.setLineSpacing(dp(3), 1f);
        description.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable descriptionBackground =
            glassCardBackground(0xF2FFFFFF, 0xDDF1FAFF);
        description.setBackground(descriptionBackground);
        description.setElevation(dp(4));
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(24);
        layout.addView(description, descriptionParams);

        triggerButton = new Button(this);
        triggerButton.setText("触发一次 GNSS 授时校准");
        triggerButton.setTextSize(16);
        triggerButton.setMinHeight(dp(56));
        styleButton(triggerButton, 0xFF287FDB, 0xFFFFFFFF);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(30);
        layout.addView(triggerButton, buttonParams);

        stopButton = new Button(this);
        stopButton.setText("停止本次后台定位");
        stopButton.setTextSize(15);
        stopButton.setMinHeight(dp(48));
        styleButton(stopButton, 0xFFF8E5E8, 0xFF9F3548);
        stopButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2);
        stopParams.topMargin = dp(8);
        layout.addView(stopButton, stopParams);
        stopButton.setOnClickListener(view -> finishWithResult("CANCELLED",
            "已手动停止持续 GPS 定位；本次不再等待新的 GNSS 时间样本。"));

        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("查看授时状态与误差");
        diagnosticsButton.setTextSize(16);
        diagnosticsButton.setMinHeight(dp(54));
        styleButton(diagnosticsButton, 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(-1, -2);
        diagnosticsParams.topMargin = dp(12);
        layout.addView(diagnosticsButton, diagnosticsParams);
        diagnosticsButton.setOnClickListener(view ->
            startActivity(new Intent(this, TimeDiagnosticsActivity.class)));

        Button floatingClockButton = new Button(this);
        floatingClockButton.setText("悬浮时钟设置");
        floatingClockButton.setTextSize(15);
        floatingClockButton.setMinHeight(dp(50));
        styleButton(floatingClockButton, 0xFFE7F7F2, 0xFF176B62);
        LinearLayout.LayoutParams floatingClockParams = new LinearLayout.LayoutParams(-1, -2);
        floatingClockParams.topMargin = dp(8);
        layout.addView(floatingClockButton, floatingClockParams);
        floatingClockButton.setOnClickListener(view ->
            startActivity(new Intent(this, FloatingClockSettingsActivity.class)));

        // Determinate 0-300 s bar for the 5-minute wait: only setProgress() moves it,
        // the status text below keeps changing on its own. Hidden until a run starts.
        calibrationProgress = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        calibrationProgress.setMax(300);
        calibrationProgress.setProgress(0);
        calibrationProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
        progressParams.topMargin = dp(24);
        layout.addView(calibrationProgress, progressParams);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF354258);
        statusView.setLineSpacing(dp(3), 1f);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusBackground = glassCardBackground(0xE8E3F4FF, 0xD5E7F2FF);
        statusView.setBackground(statusBackground);
        statusView.setElevation(dp(4));
        statusView.setText("准备就绪\n开启系统定位后，点击上方按钮获取新的 GNSS 时间样本。");
        statusView.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        layout.addView(statusView, statusParams);

        satelliteView = new TextView(this);
        satelliteView.setTextSize(13);
        satelliteView.setTextColor(0xFF354258);
        satelliteView.setLineSpacing(dp(3), 1f);
        satelliteView.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable satelliteBackground =
            glassCardBackground(0xF2FFFFFF, 0xDDEBFAFF);
        satelliteView.setBackground(satelliteBackground);
        satelliteView.setElevation(dp(3));
        satelliteView.setText("GNSS 卫星状态\n等待开始定位后接收 GnssStatus 回报。");
        satelliteView.setTextIsSelectable(true);
        satelliteView.setOnClickListener(view -> toggleSatelliteDetails());
        LinearLayout.LayoutParams satelliteParams = new LinearLayout.LayoutParams(-1, -2);
        satelliteParams.topMargin = dp(12);
        layout.addView(satelliteView, satelliteParams);

        satelliteDetailsView = createDetailsView();
        satelliteDetailsView.setText("卫星详情暂不可用。");
        layout.addView(satelliteDetailsView, detailsLayoutParams());

        rawClockView = new TextView(this);
        rawClockView.setTextSize(13);
        rawClockView.setTextColor(0xFF354258);
        rawClockView.setLineSpacing(dp(3), 1f);
        rawClockView.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable rawClockBackground =
            glassCardBackground(0xF2FFFFFF, 0xDDF0FBF8);
        rawClockView.setBackground(rawClockBackground);
        rawClockView.setElevation(dp(3));
        rawClockView.setText("原始 GNSS 时钟对齐\n等待 GnssClock 测量回调。");
        rawClockView.setTextIsSelectable(true);
        rawClockView.setOnClickListener(view -> toggleRawClockDetails());
        LinearLayout.LayoutParams rawClockParams = new LinearLayout.LayoutParams(-1, -2);
        rawClockParams.topMargin = dp(12);
        layout.addView(rawClockView, rawClockParams);

        rawClockDetailsView = createDetailsView();
        rawClockDetailsView.setText("原始时钟字段暂不可用。");
        layout.addView(rawClockDetailsView, detailsLayoutParams());

        TextView authorView = new TextView(this);
        authorView.setText("作者：酷安 R1263599071");
        authorView.setTextSize(12);
        authorView.setTextColor(0xFF69849A);
        authorView.setGravity(android.view.Gravity.CENTER);
        authorView.setPadding(0, dp(18), 0, dp(8));
        layout.addView(authorView, new LinearLayout.LayoutParams(-1, -2));

        setContentView(page);
        installSafeAreaInsets(page);
        triggerButton.setOnClickListener(view -> beginTrigger());
    }

    private android.graphics.drawable.GradientDrawable glassCardBackground(int topColor,
            int bottomColor) {
        android.graphics.drawable.GradientDrawable drawable =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[] {topColor, bottomColor});
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), 0xDFFFFFFF);
        return drawable;
    }

    private void styleButton(Button button, int backgroundColor, int textColor) {
        FloatingClockStyle.styleButton(this, button, backgroundColor, textColor, 2);
    }

    private void styleSystemBars() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFFEAF7FF);
            getWindow().setNavigationBarColor(0xFFF2F8FC);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private TextView createDetailsView() {
        TextView view = new TextView(this);
        view.setTextSize(12);
        view.setTextColor(0xFF66748A);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(dp(14), dp(10), dp(14), dp(12));
        view.setBackground(glassCardBackground(0xBFFFFFFF, 0xCDEFF8FC));
        view.setVisibility(View.GONE);
        view.setTextIsSelectable(true);
        return view;
    }

    private LinearLayout.LayoutParams detailsLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.leftMargin = dp(8);
        params.rightMargin = dp(8);
        return params;
    }

    private void toggleSatelliteDetails() {
        satelliteDetailsExpanded = !satelliteDetailsExpanded;
        // Height animation for the expand/collapse; skipped when animations are off.
        if (contentLayout != null && FloatingClockStyle.animationsEnabled(this)) {
            TransitionManager.beginDelayedTransition(contentLayout, new AutoTransition());
        }
        satelliteDetailsView.setVisibility(satelliteDetailsExpanded ? View.VISIBLE : View.GONE);
        updateSatelliteDiagnosticsView();
    }

    private void toggleRawClockDetails() {
        rawClockDetailsExpanded = !rawClockDetailsExpanded;
        if (contentLayout != null && FloatingClockStyle.animationsEnabled(this)) {
            TransitionManager.beginDelayedTransition(contentLayout, new AutoTransition());
        }
        rawClockDetailsView.setVisibility(rawClockDetailsExpanded ? View.VISIBLE : View.GONE);
        updateRawClockDiagnosticsView();
    }

    private void installSafeAreaInsets(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safeInsets = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private void beginTrigger() {
        if (operationRunning) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            String message = "此手动流程需要 Android 11 或更高版本的 GNSS 定位 API。";
            saveCalibrationResult("FAILED", message);
            setStatus("校准失败：" + message);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            saveCalibrationResult("FAILED", "请授予精确定位权限后重试。");
            requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_FINE_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("calibration_status", MODE_PRIVATE)
                    .getBoolean("notification_permission_prompted", false)) {
            getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
                .putBoolean("notification_permission_prompted", true).apply();
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQUEST_POST_NOTIFICATIONS);
            return;
        }
        if (locationManager == null
                || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            String message = "请打开系统定位开关，并确认 GPS provider 可用后重试。";
            saveCalibrationResult("FAILED", message);
            setStatus("校准失败：" + message);
            return;
        }

        operationRunning = true;
        triggerSignalSent = false;
        recoveryCancelSent = false;
        firstValidRawElapsedMillis = -1;
        operationStartElapsedMillis = SystemClock.elapsedRealtime();
        operationDeadlineElapsedMillis = operationStartElapsedMillis
            + GNSS_OPERATION_TIMEOUT_MILLIS;
        lastGpsAccuracy = "未知";
        triggerToken = Long.toString(SystemClock.elapsedRealtimeNanos());
        triggerButton.setEnabled(false);
        if (calibrationProgress != null) {
            calibrationProgress.setProgress(0);
            calibrationProgress.setVisibility(View.VISIBLE);
        }
        // Fresh run: drop the previous result's green/red tint.
        if (statusBackground != null) statusBackground.setColorFilter(null);
        saveCalibrationResult("RUNNING", "本次校准已重置应用内旧结果；只统计点击后到达的新 GNSS 建议。");
        stopButton.setVisibility(View.VISIBLE);
        setStatus("正在启动 GNSS 监听…最多等待 5 分钟。");
        if (!startContinuousGpsTracking()) return;

        executeWorker(() -> {
            final String token = triggerToken;
            String propertyCommand = "setprop " + GnssTriggerBridge.GNSS_CANCEL_PROPERTY
                + " '' && setprop " + GnssTriggerBridge.TRIGGER_PROPERTY + " "
                + token + " && test \"$(getprop " + GnssTriggerBridge.TRIGGER_PROPERTY
                + ")\" = \"" + token + "\"";
            RootCommand.Result signal = RootCommand.run(propertyCommand);

            mainHandler.post(() -> {
                triggerSignalSent = signal.exitCode == 0;
                if (activityDestroyed || isFinishing() || isDestroyed() || !operationRunning) {
                    recoveryCancelSent = false;
                    signalRecoveryCancellation();
                    return;
                }
                if (signal.exitCode == 0) {
                    saveCalibrationResult("RUNNING", "已取得 root；正在等待新的 GNSS 授时样本。");
                    setStatus("监听请求已发送，正在等待 GNSS 时间样本…");
                } else {
                    String detail = "未取得 root 权限或模块触发失败。请在 Magisk/SuperSU 授权“卫星对时”。";
                    saveCalibrationResult("RUNNING", detail);
                    setStatus(detail + " GPS 定位仍会继续；等待结束后会显示结果。");
                }
                mainHandler.postDelayed(() -> {
                    if (!operationRunning) return;
                    pollGnssSuggestion();
                }, 800);
            });
        });
    }

    private boolean startContinuousGpsTracking() {
        if (activityDestroyed || isFinishing() || isDestroyed() || !operationRunning) return false;
        try {
            Intent intent = new Intent(this, GnssLocationTrackingService.class)
                .setAction(GnssLocationTrackingService.ACTION_START)
                .putExtra("session_token", triggerToken);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            String notificationNote = Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                ? "通知权限未授予；可在本页使用停止按钮，系统也可能仅在活动应用列表显示服务。"
                : "可从常驻通知或本页停止。";
            setStatus("正在请求 GPS 定位并等待新的 GNSS 时间样本，最多 5 分钟。"
                + (notificationNote.startsWith("可从") ? "可从通知或本页停止。" : ""));
            return true;
        } catch (SecurityException e) {
            finishWithMessage("无法启动后台 GPS 前台服务；请确认精确定位权限和定位开关：" + e.getMessage());
        } catch (Throwable t) {
            finishWithMessage("启动持续 GPS 定位失败：" + t);
        }
        return false;
    }

    /**
     * The latest raw GnssClock measurement belonging to this run: same
     * session token, sampled after this run started, and passing every
     * validity gate in the tracking service. Returns null when there is no
     * such measurement — a burst-mode or earlier-run sample must never
     * complete this run's calibration.
     */
    private GnssLocationTrackingService.RawGnssSample readCurrentRawSample() {
        android.content.SharedPreferences prefs =
            getSharedPreferences("gnss_location_tracking", MODE_PRIVATE);
        if (!triggerToken.equals(prefs.getString("token", ""))) return null;
        GnssLocationTrackingService.RawGnssSample raw =
            GnssLocationTrackingService.RawGnssSample.fromPrefs(prefs);
        if (!raw.utcAvailable || raw.offsetMillis == Long.MIN_VALUE
                || raw.sampleElapsedMillis <= operationStartElapsedMillis) return null;
        return raw;
    }

    /**
     * Completes this run on the raw GnssClock offset — the fallback path,
     * used only when no system suggestion arrived. The offset must be fresh
     * (sampled within the last 10 s); the result state is SUCCESS_RAW so
     * the diagnostics page can label it as raw-sourced instead of a system
     * suggestion. Returns false when there is no usable raw sample, so the
     * caller keeps waiting or falls through to the timeout branches.
     * Completing on raw never sets the system clock: it only records the
     * measured offset as this run's calibration result.
     */
    private boolean finishWithRawCalibration() {
        GnssLocationTrackingService.RawGnssSample raw = readCurrentRawSample();
        if (raw == null) return false;
        long ageMillis = SystemClock.elapsedRealtime() - raw.sampleElapsedMillis;
        if (ageMillis < 0 || ageMillis > 10_000L) return false;
        long offset = raw.offsetMillis;
        String direction = offset > 0 ? "落后" : offset < 0 ? "领先" : "与 GNSS 基本一致";
        String detail = "已用原始 GnssClock 偏移完成本次校准（来源：原始 GNSS 时钟，非系统建议）：本机"
            + direction + " GNSS"
            + (offset == 0 ? "" : "约 " + Math.abs(offset) + " 毫秒")
            + "（±" + raw.uncertaintyMillis + " 毫秒），样本 "
            + Math.max(0L, ageMillis / 1_000L) + " 秒前。系统未产生新建议；"
            + "待 LSPosed 生效后，系统建议将优先用于校准。";
        finishWithResult("SUCCESS_RAW", detail);
        return true;
    }

    private void pollGnssSuggestion() {
        if (activityDestroyed || isFinishing() || isDestroyed() || !operationRunning) return;
        executeWorker(() -> {
            String dumpCommand = "dumpsys gnss_time_update_service; echo "
                + TIME_DETECTOR_MARKER + "; dumpsys time_detector; echo "
                + GNSS_RESULT_MARKER + "; getprop "
                + GnssTriggerBridge.GNSS_RESULT_PROPERTY;
            RootCommand.Result result = RootCommand.run(dumpCommand);
            GnssSnapshot current = parseGnssSnapshot(result.output, triggerToken);
            mainHandler.post(() -> {
                if (activityDestroyed || !operationRunning) return;
                String trackingToken = getSharedPreferences("gnss_location_tracking", MODE_PRIVATE)
                    .getString("token", "");
                String trackingState = getSharedPreferences("gnss_location_tracking", MODE_PRIVATE)
                    .getString("state", "");
                String trackingDetail = getSharedPreferences("gnss_location_tracking", MODE_PRIVATE)
                    .getString("detail", "");
                boolean currentTrackingSession = triggerToken.equals(trackingToken);
                if (current.detectorElapsedMillis > operationStartElapsedMillis) {
            finishWithResult("SUCCESS", "已收到新的 GNSS 时间建议。手机时钟是否实际调整，请查看诊断页的“本次 GNSS 实际校时”。");
                } else if (currentTrackingSession && "ERROR".equals(trackingState)) {
                    finishWithResult("FAILED", "持续 GPS 定位服务启动失败：" + trackingDetail);
                } else if (currentTrackingSession && "STOPPED".equals(trackingState)) {
                    finishWithResult("FAILED", "持续 GPS 定位服务意外结束：" + trackingDetail);
                } else if (currentTrackingSession && "STOPPED_BY_USER".equals(trackingState)) {
                    finishWithResult("CANCELLED", "已从持续定位通知停止本次任务；未观察到新 GNSS 时间建议。" );
                } else if (result.exitCode != 0 && current.detectorElapsedMillis < 0
                        && current.serviceElapsedMillis < 0
                        && current.locationTimeElapsedMillis < 0
                        && current.locationReceiptElapsedMillis < 0) {
                    finishWithResult("UNVERIFIED",
                        "无法读取本次 GNSS 状态；请检查 root 授权及 dumpsys 输出。" + brief(result.output));
                } else if (SystemClock.elapsedRealtime() < operationDeadlineElapsedMillis) {
                    // Dual path: the system suggestion stays primary. Once a
                    // valid raw offset exists, keep waiting for the system
                    // suggestion through the grace window; only when the grace
                    // expires with no system suggestion does the raw offset
                    // finish this run. A raw sample that later turns invalid
                    // resets the grace clock.
                    GnssLocationTrackingService.RawGnssSample raw = readCurrentRawSample();
                    if (raw == null) {
                        firstValidRawElapsedMillis = -1;
                    } else if (firstValidRawElapsedMillis < 0) {
                        firstValidRawElapsedMillis = SystemClock.elapsedRealtime();
                    } else if (SystemClock.elapsedRealtime() - firstValidRawElapsedMillis
                            >= RAW_FALLBACK_GRACE_MILLIS) {
                        if (finishWithRawCalibration()) return;
                    }
                    updateGnssProgress(current);
                    long remaining = operationDeadlineElapsedMillis - SystemClock.elapsedRealtime();
                    mainHandler.postDelayed(this::pollGnssSuggestion,
                        Math.min(GNSS_POLL_INTERVAL_MILLIS, remaining));
                } else if (finishWithRawCalibration()) {
                    // Deadline reached with a valid raw offset but no system
                    // suggestion: complete on raw instead of NO_LOCATION_TIME.
                } else if (current.locationTimeElapsedMillis > operationStartElapsedMillis) {
                    finishWithResult("PARTIAL",
                        "已收到新的 GNSS 时间样本，但 Android 尚未记录新建议。请查看详细日志。");
                } else if (current.serviceElapsedMillis > operationStartElapsedMillis) {
                    finishWithResult("PARTIAL",
                        "GNSS 服务样本已更新，但未确认新时间建议进入 Android 授时服务。");
                } else if (current.locationReceiptElapsedMillis > operationStartElapsedMillis) {
                    String recoveryDetail = current.bridgeResult.startsWith(
                            "stale_sample_rearmed_")
                        ? "已自动重新挂接监听（" + current.bridgeResult.substring(
                            "stale_sample_rearmed_".length()) + " 次）。"
                        : "";
                    finishWithResult("STALE_LOCATION_TIME",
                        "系统回调到达，但时间样本未更新。"
                            + recoveryDetail + "请稍后在开阔处重试。");
                } else {
                    String nextStep;
                    if ("passive_listener_armed".equals(current.bridgeResult)
                            || "existing_listener_restarted_and_rearmed".equals(current.bridgeResult)) {
                        nextStep = "GNSS 监听已启动。请在室外开阔处保持定位后重试。";
                    } else if (current.bridgeResult.isEmpty()) {
                        nextStep = "请检查 LSPosed 系统框架作用域并重启设备。";
                    } else {
                        nextStep = "请检查 LSPosed 日志和系统定位状态。";
                    }
                    finishWithResult("NO_LOCATION_TIME",
                        "5 分钟内未收到新的 GNSS 时间样本。\n" + trackingProgress()
                            + (triggerSignalSent ? "\n" : "\nGNSS 触发请求未确认。\n")
                            + nextStep);
                }
            });
        });
    }

    private void updateGnssProgress(GnssSnapshot snapshot) {
        updateSatelliteDiagnosticsView();
        updateRawClockDiagnosticsView();
        // Only the bar moves here; the status text is updated below.
        if (calibrationProgress != null) {
            long elapsedSeconds = Math.max(0L,
                (SystemClock.elapsedRealtime() - operationStartElapsedMillis) / 1_000L);
            calibrationProgress.setProgress((int) Math.min(300L, elapsedSeconds));
        }
        long remaining = Math.max(0L,
            operationDeadlineElapsedMillis - SystemClock.elapsedRealtime());
        long remainingSeconds = (remaining + 999L) / 1_000L;
        String locationStatus = trackingProgress();
        String current;
        if (snapshot.locationTimeElapsedMillis > operationStartElapsedMillis) {
            current = "已收到新的 GNSS 时间样本，正在确认 Android 是否采纳。";
        } else if (snapshot.serviceElapsedMillis > operationStartElapsedMillis) {
            current = "GNSS 服务有更新，正在等待授时建议。";
        } else if (snapshot.locationReceiptElapsedMillis > operationStartElapsedMillis) {
            current = "收到系统回调，但样本仍旧；正在继续等待并重新挂接监听。";
        } else {
            current = "正在等待新的 GNSS 时间样本。";
        }
        // Tell the user a raw offset is already in hand and the run will
        // finish on it once the grace window expires — the system suggestion
        // is still being waited for in the meantime.
        String rawNote = "";
        GnssLocationTrackingService.RawGnssSample raw = readCurrentRawSample();
        if (raw != null && raw.offsetMillis != Long.MIN_VALUE) {
            long graceLeftSeconds = firstValidRawElapsedMillis < 0
                ? RAW_FALLBACK_GRACE_MILLIS / 1_000L
                : Math.max(0L, (RAW_FALLBACK_GRACE_MILLIS
                    - (SystemClock.elapsedRealtime() - firstValidRawElapsedMillis)) / 1_000L);
            rawNote = "已测得原始 GNSS 时钟偏移（本机"
                + (raw.offsetMillis > 0 ? "落后" : raw.offsetMillis < 0 ? "领先"
                    : "与 GNSS 基本一致")
                + (raw.offsetMillis == 0 ? "" : "约 " + Math.abs(raw.offsetMillis) + " 毫秒")
                + "，±" + raw.uncertaintyMillis + " 毫秒）；仍在等系统建议，"
                + graceLeftSeconds + " 秒后以原始时钟完成本次校准。\n";
        }
        setStatus(current + "\n" + rawNote + locationStatus + "剩余约 " + remainingSeconds + " 秒。");
    }

    private String trackingProgress() {
        android.content.SharedPreferences prefs =
            getSharedPreferences("gnss_location_tracking", MODE_PRIVATE);
        if (!triggerToken.equals(prefs.getString("token", ""))) {
            return "持续 GPS 服务正在启动。";
        }
        int count = prefs.getInt("fix_count", 0);
        String accuracy = prefs.getString("last_accuracy", "未知");
        lastGpsAccuracy = accuracy;
        if (count <= 0) return "GPS 连续定位已启动，尚未收到位置。";
        long sampleElapsed = prefs.getLong("last_location_elapsed", 0L);
        long ageSeconds = sampleElapsed > 0
            ? Math.max(0L, (SystemClock.elapsedRealtime() - sampleElapsed) / 1_000L) : -1L;
        String age = ageSeconds >= 0 ? "，位置样本约 " + ageSeconds + " 秒前" : "";
        return "GPS 已连续收到 " + count + " 次位置，最近精度约 " + accuracy + age + "。";
    }

    private void updateSatelliteDiagnosticsView() {
        if (satelliteView == null || satelliteDetailsView == null) return;
        android.content.SharedPreferences prefs =
            getSharedPreferences("gnss_location_tracking", MODE_PRIVATE);
        if (!triggerToken.equals(prefs.getString("token", ""))) {
            satelliteView.setText("GNSS 卫星状态\n等待定位数据 · 点按查看详情");
            satelliteDetailsView.setText("等待本次定位服务启动并接收卫星状态回报。");
            return;
        }
        boolean registered = prefs.getBoolean("satellite_callback_registered", false);
        boolean available = prefs.getBoolean("satellite_status_available", false);
        boolean receiverStarted = prefs.getBoolean("gnss_receiver_started", false);
        StringBuilder summary = new StringBuilder("GNSS 卫星状态\n");
        StringBuilder details = new StringBuilder();
        if (!registered) {
            String error = prefs.getString("satellite_callback_error", "");
            summary.append("卫星状态暂不可用");
            details.append("系统尚未注册 GnssStatus 回调");
            if (!error.isEmpty()) details.append("：").append(error);
        } else if (!available) {
            summary.append("等待卫星列表");
            details.append("已注册卫星状态回调，暂未收到列表。请开启定位并在室外开阔处等待。");
        } else {
            int visible = prefs.getInt("visible_satellites", 0);
            int used = prefs.getInt("used_satellites", 0);
            // Small 150 ms bounce when the satellite count itself changes; the
            // text is rewritten every poll, so only a real count change animates.
            if ((visible != lastVisibleSatellites || used != lastUsedSatellites)
                    && FloatingClockStyle.animationsEnabled(this)) {
                satelliteView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(75)
                    .withEndAction(() -> satelliteView.animate().scaleX(1f).scaleY(1f)
                        .setDuration(75).start())
                    .start();
            }
            lastVisibleSatellites = visible;
            lastUsedSatellites = used;
            summary.append("可见 ").append(visible).append(" 颗 · 参与定位 ")
                .append(used).append(" 颗");
            String[] keys = {"gps", "beidou", "glonass", "galileo", "qzss", "sbas", "irnss", "unknown"};
            String[] names = {"GPS", "北斗", "GLONASS", "Galileo", "QZSS", "SBAS", "NavIC", "未知"};
            StringBuilder constellations = new StringBuilder("星座分布：");
            boolean any = false;
            for (int i = 0; i < keys.length; i++) {
                int constellationVisible = prefs.getInt("visible_" + keys[i], 0);
                int constellationUsed = prefs.getInt("used_" + keys[i], 0);
                if (constellationVisible <= 0 && constellationUsed <= 0) continue;
                if (any) constellations.append(" · ");
                constellations.append(names[i]).append(' ')
                    .append(constellationVisible).append('/')
                    .append(constellationUsed);
                any = true;
            }
            details.append(constellations.append(any ? "（可见/参与定位）\n" : "暂无\n"));
            long lastStatusElapsed = prefs.getLong("satellite_status_elapsed", 0L);
            if (lastStatusElapsed > 0) {
                long ageSeconds = Math.max(0L,
                    (SystemClock.elapsedRealtime() - lastStatusElapsed) / 1_000L);
                details.append("最近卫星状态：").append(ageSeconds).append(" 秒前\n");
            }
            int ttffMillis = prefs.getInt("time_to_first_fix_ms", -1);
            if (ttffMillis >= 0) {
                details.append("首次定位耗时：约 ")
                    .append(String.format(java.util.Locale.US, "%.1f", ttffMillis / 1_000.0))
                    .append(" 秒\n");
            }
        }
        details.append("GNSS 接收机：")
            .append(receiverStarted ? "系统已报告启动" : "未报告启动/当前未运行")
            .append("\n说明：卫星参与定位不代表已产生新的系统授时样本。");
        summary.append("\n点按").append(satelliteDetailsExpanded ? "收起" : "查看")
            .append("卫星详情");
        satelliteView.setText(summary.toString());
        satelliteDetailsView.setText(details.toString());
        satelliteDetailsView.setVisibility(satelliteDetailsExpanded ? View.VISIBLE : View.GONE);
    }

    private void updateRawClockDiagnosticsView() {
        if (rawClockView == null || rawClockDetailsView == null) return;
        android.content.SharedPreferences prefs =
            getSharedPreferences("gnss_location_tracking", MODE_PRIVATE);
        if (!triggerToken.equals(prefs.getString("token", ""))) {
            rawClockView.setText("GNSS 硬件时钟\n等待测量数据 · 点按查看技术详情");
            rawClockView.setTextColor(0xFF354258);
            rawClockDetailsView.setText("等待本次定位服务启动并注册原始测量回调。");
            return;
        }
        boolean registered = prefs.getBoolean("raw_callback_registered", false);
        boolean hasMeasurement = prefs.getBoolean("raw_measurement_available", false);
        StringBuilder summary = new StringBuilder("GNSS 硬件时钟\n");
        StringBuilder details = new StringBuilder("原始测量详情\n");
        double offset = Double.NaN;
        if (!registered) {
            String error = prefs.getString("raw_callback_error", "");
            summary.append("原始测量不可用");
            details.append("原始测量回调未注册");
            if (!error.isEmpty()) details.append("：").append(error);
        } else if (!hasMeasurement) {
            summary.append("等待测量");
            details.append("回调已注册，尚未收到 GnssClock 测量。该 API 受设备能力及系统前后台状态影响。");
        } else {
            long callbackElapsed = prefs.getLong("raw_callback_elapsed_nanos", 0L);
            int measurementCount = prefs.getInt("raw_measurement_count", 0);
            details.append("测量回调次数：").append(measurementCount).append('\n');
            long ageMillis = -1L;
            if (callbackElapsed > 0) {
                ageMillis = Math.max(0L,
                    (SystemClock.elapsedRealtimeNanos() - callbackElapsed) / 1_000_000L);
                details.append("最近测量：").append(ageMillis).append(" 毫秒前\n");
            }
            boolean hasElapsed = prefs.getBoolean("raw_clock_has_elapsed", false);
            boolean fresh = prefs.getBoolean("raw_clock_fresh", false);
            long clockElapsed = prefs.getLong("raw_clock_elapsed_nanos", -1L);
            if (hasElapsed && clockElapsed >= 0) {
                long sampleAgeMillis = prefs.getLong("raw_clock_age_nanos", -1L) / 1_000_000L;
                details.append("GnssClock 与手机单调时钟配对：");
                if (sampleAgeMillis >= 0) details.append("约 ").append(sampleAgeMillis).append(" 毫秒前");
                else details.append("样本时间戳超前手机单调时钟");
                details.append('\n');
            } else {
                details.append("设备未提供有效 elapsedRealtime 时间戳，无法可靠配对。\n");
            }

            boolean hasFullBias = prefs.getBoolean("raw_clock_has_full_bias", false);
            boolean hasLeapSecond = prefs.getBoolean("raw_clock_has_leap_second", false);
            boolean utcAvailable = prefs.getBoolean("raw_clock_utc_available", false);
            if (!hasFullBias) {
                details.append("缺少 FullBiasNanos，无法换算 GPS 时间。\n");
            } else {
                details.append("FullBiasNanos：")
                    .append(prefs.getLong("raw_full_bias_nanos", 0L));
                if (prefs.getBoolean("raw_clock_has_bias", false)) {
                    details.append("；BiasNanos：")
                        .append(prefs.getString("raw_bias_nanos", "未知"));
                }
                details.append('\n');
            }
            details.append("接收机 TimeNanos：")
                .append(prefs.getLong("raw_time_nanos", 0L)).append(" ns\n");
            if (!hasLeapSecond) {
                details.append("缺少有效 GPS−UTC 闰秒字段，无法换算 UTC。\n");
            }
            if (hasFullBias && hasLeapSecond && !fresh) {
                details.append("样本时间戳过旧或异常，本次不用于偏差比较。\n");
            }
            if (utcAvailable) {
                offset = readPreferenceDouble(prefs, "raw_offset_millis");
                if (Double.isFinite(offset)) {
                    details.append("同一时刻 GNSS UTC − 手机 UTC = ")
                        .append(String.format(java.util.Locale.US, "%+.0f ms", offset))
                        .append(offset > 0.5 ? "（本机落后）" : offset < -0.5
                            ? "（本机领先）" : "（约 0 ms）")
                        .append('\n');
                } else {
                    details.append("无法计算 GNSS 与手机 UTC 的同刻偏差。\n");
                }
                int leapSeconds = prefs.getInt("raw_leap_second", -1);
                details.append("本样本 GPS−UTC 闰秒：").append(leapSeconds).append(" 秒\n");
            }
            String timeUncertainty = prefs.getString("raw_time_uncertainty_nanos", "");
            String biasUncertainty = prefs.getString("raw_bias_uncertainty_nanos", "");
            // Effective uncertainty: the chip-reported values when usable,
            // otherwise the conservative fallback budget (see
            // GnssLocationTrackingService.effectiveRawUncertaintyMillis). A
            // chip reporting 0.000 ms has not measured an uncertainty, so 0
            // is never shown as the effective value.
            double timeUncertaintyNanos =
                readPreferenceDouble(prefs, "raw_time_uncertainty_nanos");
            double biasUncertaintyNanos =
                readPreferenceDouble(prefs, "raw_bias_uncertainty_nanos");
            long effectiveUncertainty =
                GnssLocationTrackingService.effectiveRawUncertaintyMillis(
                    timeUncertaintyNanos, biasUncertaintyNanos);
            details.append("不确定度（1-sigma，有效值）：±").append(effectiveUncertainty)
                .append(" ms");
            if (timeUncertaintyNanos > 0) {
                details.append("（芯片上报时间 ")
                    .append(formatNanosAsMillis(timeUncertainty)).append(" ms");
                if (!biasUncertainty.isEmpty()) {
                    details.append("，偏差 ").append(formatNanosAsMillis(biasUncertainty))
                        .append(" ms；已按平方和合成");
                }
                details.append("）\n");
            } else {
                details.append("（芯片未上报有效时间不确定度，按保守预算 ±")
                    .append(GnssLocationTrackingService.RAW_UNCERTAINTY_FALLBACK_MILLIS)
                    .append(" ms 计；0.000 ms 不代表真实 UTC 绝对误差为零）\n");
            }
            String elapsedUncertainty = prefs.getString("raw_elapsed_uncertainty_nanos", "");
            if (!elapsedUncertainty.isEmpty()) {
                details.append("elapsedRealtime 对齐不确定度：")
                    .append(formatNanosAsMillis(elapsedUncertainty)).append(" ms\n");
            }
            String drift = prefs.getString("raw_drift_nanos_per_second", "");
            if (!drift.isEmpty()) {
                details.append("接收机时钟漂移：").append(drift).append(" ns/s\n");
            }
            int discontinuityCount = prefs.getInt("raw_hardware_clock_discontinuity_count", -1);
            details.append("硬件时钟不连续计数：").append(discontinuityCount);
            if (discontinuityCount > 0) {
                details.append("（计数值不是误差毫秒数）");
            }
            details.append("\n换算公式：GPS 时间 = TimeNanos − (FullBiasNanos + BiasNanos)；UTC = GPS 时间 − GPS−UTC 闰秒。此值仅供诊断，不会直接改系统时间。");
            if (Double.isFinite(offset)) {
                double magnitude = Math.abs(offset);
                if (magnitude <= 0.5) summary.append("本机与 GNSS 基本一致");
                else summary.append(offset > 0 ? "本机落后 GNSS 约 " : "本机领先 GNSS 约 ")
                    .append(String.format(java.util.Locale.US, "%.0f ms", magnitude));
            } else {
                summary.append(registered && hasMeasurement
                    ? "已有原始测量，无法换算 UTC 偏差" : "等待有效 GNSS 时间数据");
            }
            if (ageMillis >= 0) summary.append("\n最近测量 ").append(ageMillis).append(" ms 前");
        }
        summary.append("\n点按").append(rawClockDetailsExpanded ? "收起" : "查看")
            .append("技术详情");
        rawClockView.setText(summary.toString());
        rawClockDetailsView.setText(details.toString());
        rawClockDetailsView.setVisibility(rawClockDetailsExpanded ? View.VISIBLE : View.GONE);
        if (Double.isFinite(offset) && offset > 0.5) rawClockView.setTextColor(0xFFC0392B);
        else if (Double.isFinite(offset) && offset < -0.5) rawClockView.setTextColor(0xFF16834A);
        else rawClockView.setTextColor(0xFF354258);
    }

    private static double readPreferenceDouble(
            android.content.SharedPreferences prefs, String key) {
        try {
            return Double.parseDouble(prefs.getString(key, "NaN"));
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private static String formatNanosAsMillis(String value) {
        try {
            return String.format(java.util.Locale.US, "%.3f",
                Double.parseDouble(value) / 1_000_000.0);
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private void finishWithMessage(String message) {
        finishWithResult("FAILED", message);
    }

    private void finishWithResult(String state, String message) {
        if (activityDestroyed) return;
        updateSatelliteDiagnosticsView();
        updateRawClockDiagnosticsView();
        signalRecoveryCancellation();
        operationRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        stopService(new Intent(this, GnssLocationTrackingService.class));
        if (triggerButton != null) triggerButton.setEnabled(true);
        if (stopButton != null) stopButton.setVisibility(View.GONE);
        if (calibrationProgress != null) calibrationProgress.setVisibility(View.GONE);
        animateStatusResult(state);
        saveCalibrationResult(state, message);
        String prefix;
        if ("SUCCESS".equals(state)) prefix = "授时请求完成：";
        else if ("SUCCESS_RAW".equals(state)) prefix = "原始 GNSS 时钟校准完成：";
        else if ("NO_NEW_SAMPLE".equals(state)) prefix = "本次未触发新 GNSS 建议：";
        else if ("NO_LOCATION_TIME".equals(state)) prefix = "GNSS 授时样本获取失败：";
        else if ("STALE_LOCATION_TIME".equals(state)) prefix = "GNSS 时间样本未更新：";
        else if ("UNVERIFIED".equals(state)) prefix = "本次结果无法确认：";
        else if ("PARTIAL".equals(state)) prefix = "GNSS 样本未完整送达：";
        else if ("CANCELLED".equals(state)) prefix = "操作已停止：";
        else prefix = "校准流程失败：";
        setStatus(prefix + message);
    }

    private void setStatus(String message) {
        if (!activityDestroyed && statusView != null) statusView.setText(message);
    }

    /**
     * Result tint for the status card: fades a green tint in on success, a red
     * tint on failure, and clears the tint for partial/cancelled results.
     * Implemented as an animated SRC_ATOP color filter over the card gradient,
     * so the gradient itself is never rebuilt per frame.
     */
    private void animateStatusResult(String state) {
        if (statusView == null || statusBackground == null) return;
        int target;
        if ("SUCCESS".equals(state) || "SUCCESS_RAW".equals(state)) {
            target = 0x5522C55E;
        } else if ("FAILED".equals(state) || "NO_LOCATION_TIME".equals(state)
                || "STALE_LOCATION_TIME".equals(state) || "UNVERIFIED".equals(state)) {
            target = 0x55DC2626;
        } else {
            statusBackground.setColorFilter(null);
            return;
        }
        if (!FloatingClockStyle.animationsEnabled(this)) {
            statusBackground.setColorFilter(target, PorterDuff.Mode.SRC_ATOP);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofArgb(0x00000000, target);
        animator.setDuration(300);
        animator.addUpdateListener(animation -> statusBackground.setColorFilter(
            (int) animation.getAnimatedValue(), PorterDuff.Mode.SRC_ATOP));
        animator.start();
    }

    private void signalRecoveryCancellation() {
        final String token = triggerToken;
        if (!triggerSignalSent || token == null || token.isEmpty() || recoveryCancelSent) return;
        recoveryCancelSent = true;
        new Thread(() -> {
            try {
                RootCommand.run("setprop " + GnssTriggerBridge.GNSS_CANCEL_PROPERTY + " " + token
                    + " && test \"$(getprop " + GnssTriggerBridge.GNSS_CANCEL_PROPERTY
                    + ")\" = \"" + token + "\"");
            } catch (Throwable ignored) {
                // The server-side recovery session has a hard elapsedRealtime deadline as fallback.
            }
        }, "gnss-recovery-cancel").start();
    }

    private void executeWorker(Runnable task) {
        if (activityDestroyed || worker.isShutdown()) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            if (!activityDestroyed && operationRunning) {
                mainHandler.post(() -> {
                    if (!activityDestroyed && operationRunning) {
                        finishWithMessage("校准任务已结束，请重新打开页面后重试。");
                    }
                });
            }
        }
    }

    private static GnssSnapshot parseGnssSnapshot(String output, String requestToken) {
        String[] parts = (output == null ? "" : output)
            .split(Pattern.quote(TIME_DETECTOR_MARKER), 2);
        String serviceDump = parts.length > 0 ? parts[0] : "";
        String detectorAndResult = parts.length > 1 ? parts[1] : "";
        String[] resultParts = detectorAndResult.split(Pattern.quote(GNSS_RESULT_MARKER), 2);
        String timeDetectorDump = resultParts.length > 0 ? resultParts[0] : "";
        String bridgeResult = resultParts.length > 1 ? resultParts[1].trim() : "";
        long serviceElapsed = latestElapsed(GNSS_ELAPSED_PATTERN, serviceDump);
        long locationTimeNanos = latestElapsed(GNSS_LOCATION_TIME_NANOS_PATTERN, serviceDump);
        long locationTimeElapsed = locationTimeNanos < 0 ? -1L : locationTimeNanos / 1_000_000L;
        long locationReceiptElapsed = latestPassiveReceiptElapsed(serviceDump);
        long detectorElapsed = latestElapsed(GNSS_SUGGESTION_ELAPSED_PATTERN,
            timeDetectorDump);
        String expectedPrefix = requestToken == null ? "" : requestToken + ":";
        if (!expectedPrefix.isEmpty() && !bridgeResult.startsWith(expectedPrefix)) {
            bridgeResult = "";
        } else if (!expectedPrefix.isEmpty()) {
            bridgeResult = bridgeResult.substring(expectedPrefix.length());
        }
        return new GnssSnapshot(serviceElapsed, locationTimeElapsed,
            locationReceiptElapsed, detectorElapsed, bridgeResult);
    }

    private static long latestElapsed(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        long latest = -1;
        while (matcher.find()) {
            try {
                latest = Math.max(latest, Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Continue: dumps can contain vendor-specific malformed diagnostic fragments.
            }
        }
        return latest;
    }

    private static long latestPassiveReceiptElapsed(String text) {
        Matcher matcher = PASSIVE_LOCATION_RECEIPT_PATTERN.matcher(text == null ? "" : text);
        long latest = -1L;
        while (matcher.find()) {
            try {
                long hours = matcher.group(1) == null ? 0L : Long.parseLong(matcher.group(1));
                long minutes = matcher.group(2) == null ? 0L : Long.parseLong(matcher.group(2));
                double seconds = Double.parseDouble(matcher.group(3));
                long elapsed = hours * 3_600_000L + minutes * 60_000L
                    + Math.round(seconds * 1_000.0);
                latest = Math.max(latest, elapsed);
            } catch (NumberFormatException ignored) {
                // Ignore malformed vendor-specific dump lines.
            }
        }
        return latest;
    }

    private static String brief(String text) {
        if (text == null || text.trim().isEmpty()) return "无详细输出";
        String value = text.trim().replace('\n', ' ');
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FINE_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                beginTrigger();
            } else {
                String message = "需要授予精确位置权限，才能通过 GPS provider 请求实际 GNSS 定位。";
                saveCalibrationResult("FAILED", message);
                setStatus("校准失败：" + message);
            }
        } else if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            beginTrigger();
        }
    }

    private void saveCalibrationResult(String state, String detail) {
        getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
            .putString("state", state)
            .putString("detail", detail)
            .putLong("time", System.currentTimeMillis())
            .putLong("operation_start_elapsed", Math.max(0L, operationStartElapsedMillis))
            .apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 切后台会中断本次校准：定位服务随即停止（复用 onDestroy 的停服务
        // 调用），轮询在 2 秒内看到服务写下的 STOPPED 状态后以 FAILED 收尾；
        // 返回本页需重开校准。没有 onPause 时，按 Home 切后台后 GPS 会
        // 一直跑到 5 分钟上限。只在本次操作进行中才停服务，以免打断定期
        // 同步的 GNSS burst。
        if (operationRunning) {
            stopService(new Intent(this, GnssLocationTrackingService.class));
        }
    }

    @Override
    protected void onDestroy() {
        if (operationRunning) signalRecoveryCancellation();
        activityDestroyed = true;
        if (operationRunning) {
            saveCalibrationResult("FAILED", "校准页面已关闭，待处理的 GNSS 请求已取消。");
        }
        operationRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        stopService(new Intent(this, GnssLocationTrackingService.class));
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class GnssSnapshot {
        final long serviceElapsedMillis;
        final long locationTimeElapsedMillis;
        final long locationReceiptElapsedMillis;
        final long detectorElapsedMillis;
        final String bridgeResult;

        GnssSnapshot(long serviceElapsedMillis, long locationTimeElapsedMillis,
                long locationReceiptElapsedMillis, long detectorElapsedMillis,
                String bridgeResult) {
            this.serviceElapsedMillis = serviceElapsedMillis;
            this.locationTimeElapsedMillis = locationTimeElapsedMillis;
            this.locationReceiptElapsedMillis = locationReceiptElapsedMillis;
            this.detectorElapsedMillis = detectorElapsedMillis;
            this.bridgeResult = bridgeResult == null ? "" : bridgeResult;
        }
    }
}
