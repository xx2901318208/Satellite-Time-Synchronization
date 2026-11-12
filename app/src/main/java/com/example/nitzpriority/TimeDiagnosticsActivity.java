package com.example.nitzpriority;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeDiagnosticsActivity extends Activity {
    private static final String TIME_MARKER = "__NITZPRIORITY_TIME__";
    private static final String GNSS_MARKER = "__NITZPRIORITY_GNSS__";
    private static final String THRESHOLD_MARKER = "__NITZPRIORITY_THRESHOLD__";
    private static final String THRESHOLD_CONFIG_MARKER = "__NITZPRIORITY_THRESHOLD_CONFIG__";
    // Force-jump sources must all be fresh: GNSS, NITZ and NTP alike (was 24 h for
    // NITZ/NTP). NTP_REFERENCE_MAX_AGE_MILLIS below is display-only and unchanged.
    private static final long MAX_FORCE_SAMPLE_AGE_MILLIS = 600_000L;
    // Hard cap on a single forced jump, enforced on both sides of the request.
    private static final long MAX_FORCE_JUMP_MILLIS = 60_000L;
    // Jumps over this size get a red warning in the second confirmation.
    private static final long LARGE_FORCE_JUMP_MILLIS = 5_000L;
    // NITZ reports no uncertainty: baseline ±1500 ms, growing with sample age
    // because the rebase only shifts by elapsed time and corrects no drift.
    private static final long NITZ_FORCE_BASE_UNCERTAINTY_MILLIS = 1_500L;
    private static final long FORCE_UNCERTAINTY_GROWTH_PER_MINUTE_MILLIS = 100L;
    private static final long GNSS_SYNC_MAX_SAMPLE_AGE_MILLIS = 600_000L;
    private static final long NTP_REFERENCE_MAX_AGE_MILLIS = 86_400_000L;
    private static final long GNSS_SYNC_TOLERANCE_MILLIS = 100L;
    // Uncertainty budget for displayed time differences. A bare millisecond number
    // is misleading: NITZ is whole-second quantised, and every extrapolated sample
    // drifts with the phone crystal (100 ppm budget, ~4.3 s over 24 h at 50 ppm).
    // Displayed values are therefore always "difference +/- uncertainty".
    private static final long DRIFT_BUDGET_PPM = 100L;
    private static final long NITZ_BASE_UNCERTAINTY_MILLIS = 1_500L;
    private static final long GNSS_BASE_UNCERTAINTY_MILLIS = 500L;
    private static final long NETWORK_UNKNOWN_UNCERTAINTY_MILLIS = 1_000L;
    // Display-only staleness bands. These do NOT change force-jump age limits.
    private static final long DISPLAY_REFERENCE_MAX_AGE_MILLIS = 600_000L; // 10 min
    private static final long DISPLAY_STALE_AGE_MILLIS = 3_600_000L; // 1 hour
    private static final long CROSS_SOURCE_MISMATCH_MILLIS = 2_000L;
    private static final long MEDIAN_OUTLIER_MILLIS = 2_000L;
    private static final int MEDIAN_SAMPLE_COUNT = 3;
    private static final long GNSS_TREND_WINDOW_MILLIS = 6 * 60 * 60 * 1_000L;
    private static final long GNSS_TREND_MIN_INTERVAL_MILLIS = 55_000L;
    private static final int GNSS_TREND_MAX_POINTS = 360;
    private static final String GNSS_TREND_PREF = "gnss_trend_points";
    private static final String ACTION_LOG_CLEAR_ELAPSED_PREF = "action_log_clear_elapsed";
    private static final Pattern ELAPSED_PATTERN = Pattern.compile(
        "elapsedRealtimeMillis\\(\\)=.*?\\((\\d+)\\)");
    private static final Pattern SYSTEM_TIME_PATTERN = Pattern.compile(
        "systemClockMillis\\(\\)=.*?\\((\\d+)\\)");
    private static final Pattern AUTO_ENABLED_PATTERN = Pattern.compile(
        "mAutoDetectionEnabled=(true|false)");
    private static final Pattern PRIORITIES_PATTERN = Pattern.compile(
        "mOriginPriorities=\\[([^]]*)\\]");
    private static final Pattern THRESHOLD_PATTERN = Pattern.compile(
        "mSystemClockUpdateThresholdMillis=(\\d+)");
    // [^}]* instead of DOTALL .*?: a suggestion's fields must not be matched across
    // the closing brace of its own object, or the elapsed time of suggestion A can be
    // paired with the unix time of suggestion B. The uncertainty pattern is the one
    // exception: mUncertaintyMillis sits after the nested UnixEpochTime object, so it
    // is allowed to cross exactly one nested closing brace, and no more.
    private static final Pattern SUGGESTION_PATTERN = Pattern.compile(
        "(TelephonyTimeSuggestion|NetworkTimeSuggestion|GnssTimeSuggestion)\\{[^}]*?"
            + "(?:mUnixEpochTime=UnixEpochTime\\{mElapsedRealtimeMillis="
            + "|mUtcTime=TimestampedValue\\{mReferenceTimeMillis=)"
            + "(\\d+),\\s*m(?:UnixEpochTimeMillis|Value)=(\\d+)");
    private static final Pattern NETWORK_UNCERTAINTY_PATTERN = Pattern.compile(
        "NetworkTimeSuggestion\\{(?:[^}]*\\})?[^}]*mUncertaintyMillis=(\\d+)");
    private static final Pattern GNSS_SERVICE_SAMPLE_PATTERN = Pattern.compile(
        "mLastSuggestedGnssTime:[^}]*?mElapsedRealtimeMillis=(\\d+)[^}]*?"
            + "mUnixEpochTimeMillis=(\\d+)");
    // mSlotIndex is the first field of TelephonyTimeSuggestion in AOSP, before the
    // nested UnixEpochTime object, so it can be captured without crossing a brace.
    private static final Pattern TELEPHONY_SLOT_PATTERN = Pattern.compile(
        "TelephonyTimeSuggestion\\{[^}]*?mSlotIndex=(\\d+)[^}]*?"
            + "mUnixEpochTime=UnixEpochTime\\{mElapsedRealtimeMillis=(\\d+),"
            + "\\s*mUnixEpochTimeMillis=(\\d+)");
    private static final Pattern CLOCK_SET_ORIGIN_PATTERN = Pattern.compile(
        "(?i)Set system clock(?!\\s+confidence).*?origin=([a-z0-9_-]+)");
    private static final Pattern CONFIDENCE_ORIGIN_PATTERN = Pattern.compile(
        "(?i)Set system clock confidence.*?origin=([a-z0-9_-]+)");
    private static final Pattern CHANGE_LOG_ELAPSED_PATTERN = Pattern.compile(
        "^\\s*PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?\\s*/");
    private static final Pattern OLD_CLOCK_MILLIS_PATTERN = Pattern.compile(
        "actualSystemClockMillis=(\\d+)");
    private static final Pattern NEW_CLOCK_MILLIS_PATTERN = Pattern.compile(
        "newSystemClockMillis=(\\d+)");
    private static final Pattern CHANGE_CAUSE_PATTERN = Pattern.compile(
        "cause=([^,\\]]+)");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService forceWorker = Executors.newSingleThreadExecutor();
    private WebView webView;
    private Button refreshButton;
    private Button forceButton;
    private Button clearHistoryButton;
    private Button applyThresholdButton;
    private EditText thresholdInput;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean refreshInProgress;
    private volatile boolean forceJumpInProgress;
    private volatile boolean currentDiagnosticsHasRoot;
    private volatile ForceOption[] currentForceOptions = new ForceOption[0];
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                refresh();
                uiHandler.postDelayed(this, 60_000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(pageGlassBackground());
        styleSystemBars();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(12), dp(8), dp(12), dp(4));
        refreshButton = new Button(this);
        refreshButton.setText("刷新");
        styleButton(refreshButton, 0xFFDDF4FF, 0xFF165B78);
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button calibrateButton = new Button(this);
        calibrateButton.setText("立即校准");
        styleButton(calibrateButton, 0xFF287FDB, Color.WHITE);
        LinearLayout.LayoutParams calibrateParams = new LinearLayout.LayoutParams(0, -2, 1);
        calibrateParams.leftMargin = dp(8);
        actions.addView(calibrateButton, calibrateParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        forceButton = new Button(this);
        forceButton.setText("强制跳改（可选授时源）");
        styleButton(forceButton, 0xFFF8E5E8, 0xFF9F3548);
        forceButton.setEnabled(false);
        LinearLayout.LayoutParams forceParams = new LinearLayout.LayoutParams(-1, -2);
        forceParams.setMargins(dp(12), 0, dp(12), dp(4));
        root.addView(forceButton, forceParams);

        clearHistoryButton = new Button(this);
        clearHistoryButton.setText("清理历史记录和日志");
        styleButton(clearHistoryButton, 0xFFE5EFF7, 0xFF365C78);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-1, -2);
        clearParams.setMargins(dp(12), 0, dp(12), dp(4));
        root.addView(clearHistoryButton, clearParams);

        LinearLayout thresholdCard = new LinearLayout(this);
        thresholdCard.setOrientation(LinearLayout.VERTICAL);
        thresholdCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable cardBackground = glassCardBackground();
        thresholdCard.setBackground(cardBackground);
        thresholdCard.setElevation(dp(3));
        LinearLayout.LayoutParams thresholdCardParams =
            new LinearLayout.LayoutParams(-1, -2);
        thresholdCardParams.setMargins(dp(12), dp(2), dp(12), 0);
        root.addView(thresholdCard, thresholdCardParams);

        TextView thresholdLabel = new TextView(this);
        thresholdLabel.setText("自动改时门槛");
        thresholdLabel.setTextSize(14);
        thresholdLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        thresholdLabel.setTextColor(Color.rgb(37, 48, 66));
        thresholdCard.addView(thresholdLabel, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout thresholdRow = new LinearLayout(this);
        thresholdRow.setOrientation(LinearLayout.HORIZONTAL);
        thresholdRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        thresholdInput = new EditText(this);
        thresholdInput.setSingleLine(true);
        thresholdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        thresholdInput.setTextSize(18);
        thresholdInput.setMinHeight(dp(48));
        thresholdInput.setHint("1–2000");
        thresholdInput.setText(String.valueOf(getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("threshold_ms", NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS)));
        thresholdRow.addView(thresholdInput, new LinearLayout.LayoutParams(0, -2, 1));
        applyThresholdButton = new Button(this);
        applyThresholdButton.setText("应用");
        styleButton(applyThresholdButton, 0xFF1A9A9A, Color.WHITE);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(-2, -2);
        applyParams.leftMargin = dp(8);
        thresholdRow.addView(applyThresholdButton, applyParams);
        thresholdCard.addView(thresholdRow, new LinearLayout.LayoutParams(-1, -2));

        TextView thresholdHint = new TextView(this);
        thresholdHint.setText("单位：毫秒（1–2000）。门槛只决定系统是否跳时，不会提高授时样本本身的精度。");
        thresholdHint.setTextSize(12);
        thresholdHint.setTextColor(Color.rgb(104, 117, 138));
        thresholdHint.setPadding(dp(4), dp(4), dp(4), dp(2));
        thresholdCard.addView(thresholdHint, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        installSafeAreaInsets(root);

        refreshButton.setOnClickListener(view -> refresh(true));
        clearHistoryButton.setOnClickListener(view -> confirmClearHistory());
        applyThresholdButton.setOnClickListener(view -> applyThresholdSetting());
        calibrateButton.setOnClickListener(view ->
            startActivity(new Intent(this, GnssCalibrationActivity.class)));
        forceButton.setOnClickListener(view -> chooseForceSource());
        showHtml(loadingHtml());
    }

    private GradientDrawable pageGlassBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFEAF7FF, 0xFFF5FAFF, 0xFFEAF8F6});
    }

    private GradientDrawable glassCardBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xF2FFFFFF, 0xDDF0FAFF});
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), 0xDFFFFFFF);
        return drawable;
    }

    private void styleButton(Button button, int backgroundColor, int textColor) {
        button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setElevation(dp(2));
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

    private void confirmClearHistory() {
        if (refreshInProgress) {
            Toast.makeText(this, "状态刷新完成后再清理。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasActiveDiagnosticOperation()) {
            Toast.makeText(this, "GNSS 采集或强制跳改进行中，暂不能清理。", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("清理历史记录和日志？")
            .setMessage("将清除本应用保存的 GNSS 趋势、诊断快照、校准结果和强制跳改结果，并从系统动作时间线隐藏清理前事件；自动改时门槛会保留。Android 系统维护的 time_detector / Time change log 原始日志不会被清除，也不会修改系统时间。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理本地记录", (dialog, which) -> clearLocalHistory())
            .show();
    }

    private boolean hasActiveDiagnosticOperation() {
        if (forceJumpInProgress) return true;
        android.content.SharedPreferences status =
            getSharedPreferences("calibration_status", MODE_PRIVATE);
        long nowElapsed = SystemClock.elapsedRealtime();
        if ("RUNNING".equals(status.getString("state", ""))) {
            long started = status.getLong("operation_start_elapsed", -1L);
            if (started >= 0 && nowElapsed >= started
                    && nowElapsed - started < 5 * 60_000L) return true;
        }
        if ("RUNNING".equals(status.getString("force_state", ""))) {
            long age = System.currentTimeMillis() - status.getLong("force_time", 0L);
            if (age >= 0 && age < 30_000L) return true;
        }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service
                    : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.service != null && GnssLocationTrackingService.class.getName()
                        .equals(service.service.getClassName())) return true;
            }
        }
        return false;
    }

    private void clearLocalHistory() {
        if (refreshInProgress) {
            Toast.makeText(this, "状态刷新尚未完成，未清理。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasActiveDiagnosticOperation()) {
            Toast.makeText(this, "检测到采集或跳改任务仍在运行，未清理。", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .remove(GNSS_TREND_PREF)
            .putLong(ACTION_LOG_CLEAR_ELAPSED_PREF, SystemClock.elapsedRealtime())
            .apply();
        getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
            .remove("state").remove("detail").remove("time")
            .remove("operation_start_elapsed")
            .remove("force_state").remove("force_detail").remove("force_time")
            .apply();
        getSharedPreferences("gnss_location_tracking", MODE_PRIVATE).edit().clear().apply();
        Toast.makeText(this, "本地历史已清理；Android 系统授时日志仍由系统保留。",
            Toast.LENGTH_LONG).show();
        refresh();
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

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            refresh();
            uiHandler.removeCallbacks(periodicRefresh);
            uiHandler.postDelayed(periodicRefresh, 60_000L);
        }
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(periodicRefresh);
        super.onPause();
    }

    private void applyThresholdSetting() {
        if (!currentDiagnosticsHasRoot) {
            Toast.makeText(this, "请先授予 Root 并刷新诊断", Toast.LENGTH_LONG).show();
            return;
        }
        final int threshold;
        try {
            threshold = Integer.parseInt(thresholdInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入 1–2000 之间的毫秒数", Toast.LENGTH_LONG).show();
            return;
        }
        if (threshold < NitzPriorityHook.MIN_CLOCK_UPDATE_THRESHOLD_MILLIS
                || threshold > NitzPriorityHook.MAX_CLOCK_UPDATE_THRESHOLD_MILLIS) {
            Toast.makeText(this, "门槛范围为 1–2000 毫秒", Toast.LENGTH_LONG).show();
            return;
        }

        applyThresholdButton.setEnabled(false);
        final String command = "setprop " + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY + " "
            + threshold + " && test \"$(getprop "
            + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY + ")\" = \"" + threshold + "\"";
        worker.execute(() -> {
            RootCommand.Result result = RootCommand.run(command);
            boolean success = result.exitCode == 0;
            if (success) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putInt("threshold_ms", threshold).apply();
            }
            runOnUiThread(() -> {
                applyThresholdButton.setEnabled(currentDiagnosticsHasRoot);
                Toast.makeText(this, success
                        ? "门槛已设为 " + threshold + " ms；system_server 将在后续授时决策时应用"
                        : "设置失败，请检查 Root 授权与系统属性权限",
                    Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean forceNtsc) {
        if (refreshInProgress || worker.isShutdown()) return;
        refreshInProgress = true;
        refreshButton.setEnabled(false);
        forceButton.setEnabled(false);
        final android.content.SharedPreferences prefs = getSharedPreferences(
            "calibration_status", MODE_PRIVATE);
        final String calibrationState = prefs
            .getString("state", "NONE");
        final String calibrationDetail = prefs
            .getString("detail", "尚未执行手动校准");
        final long calibrationTime = prefs
            .getLong("time", 0L);
        final long calibrationStartElapsed = prefs.getLong("operation_start_elapsed", 0L);
        final String forceState = prefs.getString("force_state", "NONE");
        final String forceDetail = prefs.getString("force_detail", "尚未执行强制跳改");
        final long forceTime = prefs.getLong("force_time", 0L);
        final int savedThreshold = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("threshold_ms", NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);

        worker.execute(() -> {
            String command = "if [ -z \"$(getprop "
                + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY + ")\" ]; then setprop "
                + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY + " " + savedThreshold
                + "; fi; id; echo " + TIME_MARKER + "; dumpsys time_detector; echo "
                + GNSS_MARKER + "; dumpsys gnss_time_update_service; echo "
                + THRESHOLD_MARKER + "; getprop "
                + NitzPriorityHook.THRESHOLD_STATUS_PROPERTY + "; echo "
                + THRESHOLD_CONFIG_MARKER + "; getprop "
                + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY;
            RootCommand.Result result = RootCommand.run(command);
            String[] parts = result.output.split(Pattern.quote(TIME_MARKER), 2);
            String identity = parts.length > 0 ? parts[0] : "";
            String remainder = parts.length > 1 ? parts[1] : "";
            String[] sourceParts = remainder.split(Pattern.quote(GNSS_MARKER), 2);
            String timeDump = sourceParts.length > 0 ? sourceParts[0] : "";
            String[] thresholdParts = sourceParts.length > 1
                ? sourceParts[1].split(Pattern.quote(THRESHOLD_MARKER), 2) : new String[0];
            String gnssDump = thresholdParts.length > 0 ? thresholdParts[0] : "";
            String[] configParts = thresholdParts.length > 1
                ? thresholdParts[1].split(Pattern.quote(THRESHOLD_CONFIG_MARKER), 2)
                : new String[0];
            String thresholdStatus = configParts.length > 0 ? configParts[0].trim() : "";
            String thresholdConfig = configParts.length > 1 ? configParts[1].trim() : "";
            boolean hasRoot = result.exitCode == 0 && identity.contains("uid=0");
            recordGnssTrendPoint(timeDump);
            long trendNowElapsed = parseLong(ELAPSED_PATTERN, timeDump, -1);
            List<TrendPoint> trendPoints = loadGnssTrendPoints(trendNowElapsed);
            // Independent NTSC reference: plain UDP NTP on this worker thread,
            // never on the main thread. Does not need root and does not touch
            // the system clock, the detector, or the force-jump flow.
            NtscNtpClient.Result ntscResult = NtscNtpClient.query(forceNtsc);
            String html = buildHtml(hasRoot, identity, timeDump, gnssDump,
                calibrationState, calibrationDetail, calibrationTime, calibrationStartElapsed,
                forceState, forceDetail, forceTime, thresholdStatus, thresholdConfig, trendPoints,
                ntscResult);
            runOnUiThread(() -> {
                refreshInProgress = false;
                refreshButton.setEnabled(true);
                forceButton.setEnabled(currentDiagnosticsHasRoot && currentForceOptions.length > 0);
                applyThresholdButton.setEnabled(currentDiagnosticsHasRoot);
                if (hasRoot && !thresholdInput.hasFocus()) {
                    long displayedSetting = parseLongValue(thresholdConfig,
                        NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);
                    thresholdInput.setText(Long.toString(displayedSetting));
                }
                showHtml(html);
            });
        });
    }

    private String buildHtml(boolean hasRoot, String identity, String timeDump, String gnssDump,
            String calibrationState, String calibrationDetail, long calibrationTime,
            long calibrationStartElapsed,
            String forceState, String forceDetail, long forceTime, String thresholdStatus,
            String thresholdConfig, List<TrendPoint> trendPoints,
            NtscNtpClient.Result ntscResult) {
        currentDiagnosticsHasRoot = hasRoot;
        currentForceOptions = new ForceOption[0];
        StringBuilder html = new StringBuilder(8_000);
        html.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
            .append("<style>")
            .append("*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 8% 0%,rgba(120,211,255,.34),transparent 38%),radial-gradient(ellipse at 96% 30%,rgba(128,232,211,.24),transparent 35%),linear-gradient(145deg,#e8f6ff,#f5f9ff 52%,#eaf8f5);color:#18324d;"
                + "font-family:Roboto,'Noto Sans SC',Arial,sans-serif}.wrap{padding:18px 16px 30px;max-width:680px;margin:auto}")
            .append(".eyebrow{font-size:12px;letter-spacing:1.5px;color:#1682a4;font-weight:800;text-transform:uppercase}")
            .append("h1{font-size:26px;margin:5px 0 5px;color:#12324d;letter-spacing:-.3px}.sub{font-size:13px;color:#58718a;line-height:1.55;margin-bottom:15px}")
            .append(".card{background:linear-gradient(135deg,rgba(255,255,255,.86),rgba(238,249,255,.68));border:1px solid rgba(255,255,255,.94);border-radius:22px;padding:17px;margin:11px 0;"
                + "box-shadow:0 12px 30px rgba(42,91,130,.10),inset 0 1px 0 rgba(255,255,255,.9);backdrop-filter:blur(18px) saturate(145%);-webkit-backdrop-filter:blur(18px) saturate(145%)}")
            .append(".root-ok{background:linear-gradient(135deg,rgba(218,250,237,.9),rgba(231,255,249,.72));color:#14754d}.root-no{background:linear-gradient(135deg,rgba(255,245,222,.94),rgba(255,250,236,.75));color:#8a5a00}")
            .append(".state{font-size:14px;font-weight:700;display:flex;align-items:center;gap:8px}")
            .append(".state:before{content:'';width:8px;height:8px;border-radius:50%;background:currentColor;display:inline-block}")
            .append(".source{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}")
            .append(".name{font-size:16px;font-weight:750;color:#193b59}.meta{font-size:12px;color:#71869b;margin-top:5px;line-height:1.5}")
            .append(".sync-title{font-size:18px;font-weight:800}.sync-detail{font-size:13px;line-height:1.6;color:#536d83;margin-top:8px}")
            .append(".sync-good{color:#13774d}.sync-warn{color:#a15c00}.sync-unknown{color:#536c83}.sync-title.lead{color:#198754}.sync-title.lag{color:#c62828}.sync-title.equal{color:#536176}")
            .append(".value{font-size:27px;font-weight:800;letter-spacing:-.6px;white-space:nowrap;color:#1769d2}")
            .append(".value.lead{color:#198754}.value.lag{color:#c62828}.value.equal{color:#536176}")
            .append(".value.none{color:#9aa5b4;font-size:22px}")
            .append(".pill{display:inline-block;border-radius:999px;padding:5px 10px;background:rgba(218,239,250,.82);color:#47677d;font-size:11px;font-weight:750;margin-top:9px;border:1px solid rgba(255,255,255,.9)}")
            .append(".pill.lead{background:rgba(222,248,235,.9);color:#167343}.pill.lag{background:rgba(255,229,230,.92);color:#b42318}")
            .append(".note{font-size:12px;line-height:1.6;color:#5d748b;margin:14px 2px;padding:13px 15px;border:1px solid rgba(255,255,255,.8);border-radius:16px;background:rgba(255,255,255,.48)}")
            .append(".action{font-size:13px;line-height:1.6;color:#435168;white-space:pre-wrap}")
            .append(".event-row{padding:11px 0;border-bottom:1px solid rgba(102,147,177,.16)}.event-row:last-child{border-bottom:0}")
            .append(".event-title{font-size:14px;font-weight:750;color:#24445f;line-height:1.5}.event-meta{font-size:12px;color:#647e94;line-height:1.55;margin-top:3px}")
            .append(".event-title.actual{color:#167343}.event-title.confidence{color:#5b6575}")
            .append(".warn{color:#9b5e00}.good{color:#167343}.fail{color:#b42318}.running{color:#1769d2}")
            .append(".summary{font-size:13px;line-height:1.55;color:#526e84}.details{margin-top:16px}")
            .append(".details summary{font-size:13px;font-weight:750;color:#315f7a;padding:10px 0;cursor:pointer}")
            .append(".details pre{white-space:pre-wrap;word-break:break-word;font:11px/1.5 monospace;background:linear-gradient(145deg,#132b41,#1d4056);color:#d9eff9;"
                + "padding:13px;border:1px solid rgba(255,255,255,.18);border-radius:16px;max-height:360px;overflow:auto}")
            .append(".foot{font-size:11px;color:#6c8499;line-height:1.6;text-align:center;margin:18px 4px 8px}")
            .append(".divider{height:1px;background:rgba(102,147,177,.18);margin:13px 0}")
            .append(".threshold{font-size:12px;color:#647f94;margin:8px 2px 12px}")
            .append(".small{font-size:11px;color:#718ba0;margin-top:6px}")
            .append(".trend-svg{width:100%;height:178px;display:block;margin-top:10px;background:linear-gradient(135deg,rgba(255,255,255,.72),rgba(229,247,255,.75));border:1px solid rgba(255,255,255,.95);border-radius:16px}")
            .append(".trend-latest{font-size:14px;font-weight:750;color:#274a65;margin-top:8px}")
            .append(".trend-empty{font-size:13px;color:#647e94;background:rgba(255,255,255,.56);border:1px solid rgba(255,255,255,.9);border-radius:16px;padding:16px;margin-top:12px;line-height:1.5}")
            .append(".link{color:#1769d2;font-weight:750}")
            .append("</style></head><body><div class='wrap'>")
            .append("<div class='eyebrow'>卫星对时</div><h1>授时诊断</h1>")
            .append("<div class='sub'>优先查看手机是否与卫星时间同步，并显示基站和网络参考</div>");

        if (!hasRoot) {
            html.append("<div class='card root-no'><div class='state'>尚未取得 Root 权限</div>")
                .append("<div class='meta'>请在 Magisk / SuperSU 中允许“卫星对时”获取 root，然后点“刷新”。</div>")
                .append("<div class='small'>检查结果：").append(escape(identity.trim().isEmpty()
                    ? "su 命令失败或未授权" : identity.trim())).append("</div></div>")
                .append(calibrationCard(calibrationState, calibrationDetail, calibrationTime))
                .append(forceCard(forceState, forceDetail, forceTime))
                .append(ntscTimeCard(ntscResult))
                .append("<div class='foot'>没有 root 时无法读取系统授时历史，因此不会显示偏差数据。<br>作者：酷安 R1263599071</div>")
                .append("</div></body></html>");
            return html.toString();
        }

        long nowElapsed = parseLong(ELAPSED_PATTERN, timeDump, -1);
        long systemUnix = parseLong(SYSTEM_TIME_PATTERN, timeDump, -1);
        long dumpedThreshold = parseLong(THRESHOLD_PATTERN, timeDump, 2_000);
        String[] thresholdStatusParts = thresholdStatus.split(":", 2);
        long configuredThreshold = parseLongValue(thresholdConfig,
            NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);
        configuredThreshold = Math.max(NitzPriorityHook.MIN_CLOCK_UPDATE_THRESHOLD_MILLIS,
            Math.min(NitzPriorityHook.MAX_CLOCK_UPDATE_THRESHOLD_MILLIS, configuredThreshold));
        long activeThreshold = thresholdStatusParts.length == 2
            ? parseLongValue(thresholdStatusParts[0], -1L) : -1L;
        boolean thresholdHookActive = activeThreshold == configuredThreshold
            && thresholdStatusParts.length == 2;
        long deviceOriginalThreshold = thresholdHookActive
            ? parseLongValue(thresholdStatusParts[1], dumpedThreshold) : dumpedThreshold;
        long threshold = configuredThreshold;
        String autoEnabled = parseString(AUTO_ENABLED_PATTERN, timeDump, "unknown");
        String priorities = parseString(PRIORITIES_PATTERN, timeDump, "unknown");
        Sample gnss = findLatestSample(timeDump, "GnssTimeSuggestion");
        Sample telephony = findLatestSample(timeDump, "TelephonyTimeSuggestion");
        Sample network = findLatestSample(timeDump, "NetworkTimeSuggestion");
        long networkUncertainty = findNetworkUncertainty(timeDump);
        Sample gnssService = findGnssServiceSample(gnssDump);
        List<ForceOption> options = new ArrayList<>();
        addForceOption(options, "GNSS 卫星", gnss, nowElapsed, systemUnix, -1);
        addForceOption(options, "基站 NITZ", telephony, nowElapsed, systemUnix, -1);
        addForceOption(options, "网络 NTP", network, nowElapsed, systemUnix,
            networkUncertainty);
        currentForceOptions = options.toArray(new ForceOption[0]);
        // Display uses the median-resolved samples (outlier-resistant); force-jump
        // options above intentionally keep the latest sample and are untouched here.
        ResolvedSample gnssResolved =
            resolveSamples(timeDump, "GnssTimeSuggestion", nowElapsed);
        ResolvedSample telephonyResolved =
            resolveSamples(timeDump, "TelephonyTimeSuggestion", nowElapsed);
        ResolvedSample networkResolved =
            resolveSamples(timeDump, "NetworkTimeSuggestion", nowElapsed);
        Sample gnssDisplay = gnssResolved.sample;
        Sample telephonyDisplay = telephonyResolved.sample;
        Sample networkDisplay = networkResolved.sample;
        int telephonySlot = findTelephonySlotIndex(timeDump, telephonyDisplay);
        boolean multiSlot = countTelephonySlots(timeDump) > 1;
        String telephonySlotNote = telephonySlot >= 0
            ? "卡槽 " + telephonySlot + (multiSlot ? "（双卡：仅显示该卡槽样本）" : "")
            : (multiSlot ? "双卡：未能确认样本卡槽" : null);
        long gnssUncertainty = gnssDisplay != null && nowElapsed >= 0
            ? uncertaintyFor("GNSS 卫星",
                Math.max(0L, nowElapsed - gnssDisplay.elapsedRealtimeMillis), -1) : -1L;
        long telephonyUncertainty = telephonyDisplay != null && nowElapsed >= 0
            ? uncertaintyFor("基站 NITZ",
                Math.max(0L, nowElapsed - telephonyDisplay.elapsedRealtimeMillis), -1) : -1L;
        long networkTotalUncertainty = networkDisplay != null && nowElapsed >= 0
            ? uncertaintyFor("网络 NTP",
                Math.max(0L, nowElapsed - networkDisplay.elapsedRealtimeMillis),
                networkUncertainty) : -1L;
        html.append("<div class='card root-ok'><div class='state'>Root 已授权 · 系统数据可读取</div></div>")
            .append(detectionWarningsCard(gnssResolved, telephonyResolved, networkResolved,
                gnssDisplay, networkDisplay, nowElapsed))
            .append(gnssSyncCard(gnssDisplay, nowElapsed, systemUnix, autoEnabled, priorities,
                timeDump, networkDisplay, calibrationState, calibrationStartElapsed))
            .append(gnssTrendCard(trendPoints, nowElapsed))
            .append(beijingTimeCard(networkDisplay, networkUncertainty, nowElapsed, systemUnix))
            .append(ntscTimeCard(ntscResult))
            .append(calibrationCard(calibrationState, calibrationDetail, calibrationTime))
            .append(forceCard(forceState, forceDetail, forceTime));
        html.append("<div class='card'><div class='summary'>自动授时：<b>")
            .append(escape(autoEnabled)).append("</b>　优先级：<b>")
            .append(escape(priorities)).append("</b></div>")
            .append("<div class='threshold'>自动改时门槛 <b>").append(threshold)
            .append(" ms</b>");
        if (thresholdHookActive) {
            html.append("（模块已命中；设备原值 ").append(deviceOriginalThreshold).append(" ms）");
        } else {
            html.append("（模块设定值；system_server 生效待确认；dump 当前值 ")
                .append(dumpedThreshold).append(" ms）");
        }
        html.append(" · 当前时钟 ").append(systemUnix).append(" ms</div>")
            .append(sourceCard("GNSS 卫星", gnssDisplay, nowElapsed, systemUnix, threshold,
                gnssService == null ? null : nowElapsed - gnssService.elapsedRealtimeMillis,
                gnssUncertainty, null))
            .append(sourceCard("基站 NITZ", telephonyDisplay, nowElapsed, systemUnix, threshold,
                null, telephonyUncertainty, telephonySlotNote))
            .append(sourceCard("网络 NTP", networkDisplay, nowElapsed, systemUnix, threshold,
                null, networkTotalUncertainty, null));

        html.append("</div>")
            .append(clockActionCard(timeDump, nowElapsed))
            .append("<div class='note'>“手机时钟已与卫星时间同步”按最近 GNSS 样本（不超过 10 分钟）与手机时钟的外推差值判定，判定容差随样本年龄与不确定度动态计算，不再是固定 ±100 毫秒；仅收到新 GNSS 建议不等于时钟已调整。实际是否跳变会单独显示。所有毫秒差值均为“差值 ± 不确定度”的估算，不是实测绝对误差。强制跳改仅在选择来源、预览目标并二次确认后执行。</div>")
            .append("<details class='details'><summary>查看详细授时日志</summary><pre>")
            .append(escape(extractTimeDetectorExcerpt(timeDump) + "\n=== GNSS service ===\n" + gnssDump))
            .append("</pre></details><div class='foot'>授时诊断 · 数据由 Android 系统服务提供<br>作者：酷安 R1263599071</div>")
            .append("</div></body></html>");
        return html.toString();
    }

    /**
     * Warnings about the detection itself: a source whose newest sample was dropped
     * as a median outlier, or GNSS and NTP projections that disagree with each other.
     * A disagreement never silently picks one value; it tells the user to resample.
     */
    private static String detectionWarningsCard(ResolvedSample gnssResolved,
            ResolvedSample telephonyResolved, ResolvedSample networkResolved,
            Sample gnssDisplay, Sample networkDisplay, long nowElapsed) {
        StringBuilder warnings = new StringBuilder();
        if (gnssResolved.droppedOutlier) {
            warnings.append("GNSS 最新样本与最近 3 条样本中位数相差超过 2 秒，已按中位数显示；请重新采样确认。");
        }
        if (telephonyResolved.droppedOutlier) {
            if (warnings.length() > 0) warnings.append(" ");
            warnings.append("基站 NITZ 最新样本与最近 3 条样本中位数相差超过 2 秒，已按中位数显示；请重新采样确认。");
        }
        if (networkResolved.droppedOutlier) {
            if (warnings.length() > 0) warnings.append(" ");
            warnings.append("网络 NTP 最新样本与最近 3 条样本中位数相差超过 2 秒，已按中位数显示；请重新采样确认。");
        }
        if (gnssDisplay != null && networkDisplay != null && nowElapsed >= 0
                && nowElapsed >= gnssDisplay.elapsedRealtimeMillis
                && nowElapsed >= networkDisplay.elapsedRealtimeMillis) {
            long diff = Math.abs(projectedMillis(gnssDisplay, nowElapsed)
                - projectedMillis(networkDisplay, nowElapsed));
            if (diff > CROSS_SOURCE_MISMATCH_MILLIS) {
                if (warnings.length() > 0) warnings.append(" ");
                warnings.append("来源不一致：GNSS 与 NTP 外推值相差约 ").append(diff)
                    .append(" 毫秒（超过 2000 毫秒），请以 GNSS 为准并重新采样，不要直接采信任一数值。");
            }
        }
        if (warnings.length() == 0) return "";
        return "<div class='card'><div class='state warn'>检测提示</div><div class='sync-detail'>"
            + escape(warnings.toString()) + "</div></div>";
    }

    private static String gnssSyncCard(Sample gnss, long nowElapsed, long systemUnix,
            String autoEnabled, String priorities, String timeDump, Sample network,
            String calibrationState, long calibrationStartElapsed) {
        String css = "sync-unknown";
        String title;
        String detail;
        if (gnss == null) {
            title = "暂无法确认是否与卫星对时";
            detail = "系统没有可用的 GNSS 时间建议记录。请在定位开启且卫星信号可用时重新获取授时样本。";
        } else if (nowElapsed < gnss.elapsedRealtimeMillis || systemUnix < 0) {
            title = "暂无法确认是否与卫星对时";
            detail = "GNSS 样本或手机时钟参考无效，刷新诊断后再查看。";
        } else {
            long age = nowElapsed - gnss.elapsedRealtimeMillis;
            if (age > GNSS_SYNC_MAX_SAMPLE_AGE_MILLIS) {
                title = "暂无法确认是否与卫星对时";
                detail = "最近 GNSS 时间建议已是 " + formatAge(age)
                    + "前，超过 10 分钟的页面确认期限。请重新获取 GNSS 授时样本。";
            } else {
                long projected = gnss.unixEpochTimeMillis + age;
                long phoneOffset = projected - systemUnix;
                // Dynamic tolerance: base 100 ms + drift budget for the sample's age
                // + the source's own uncertainty. A 10-minute-old sample no longer
                // passes as "in sync" on the fixed 100 ms bar alone.
                long uncertainty = uncertaintyFor("GNSS 卫星", age, -1);
                long tolerance = syncToleranceMillis(age, uncertainty);
                String difference = phoneRelativePhrase("GNSS 卫星", phoneOffset)
                    + "（±" + uncertainty + " 毫秒）";
                if (Math.abs(phoneOffset) <= tolerance) {
                    css = "sync-good";
                    title = "手机时钟已与卫星时间同步";
                    detail = difference + "；GNSS 样本 " + formatAge(age)
                        + "前。本次判定容差 ±" + tolerance
                        + " 毫秒（100 毫秒基础 + 年龄漂移预算 + 来源不确定度）。";
                } else {
                    css = "sync-warn";
                    title = "手机时钟尚未与卫星时间同步";
                    detail = difference + "，超过本次判定容差 ±" + tolerance
                        + " 毫秒；GNSS 样本 " + formatAge(age) + "前。";
                }
            }
        }

        if ("false".equalsIgnoreCase(autoEnabled)) {
            detail += " 系统自动授时当前未开启，收到 GNSS 建议也不一定自动调整手机时钟。";
        } else if ("true".equalsIgnoreCase(autoEnabled) && !hasGnssPriority(priorities)) {
            detail += " 当前自动授时优先级未包含 GNSS，系统不会优先采用卫星建议。";
        } else if ("true".equalsIgnoreCase(autoEnabled)) {
            detail += " 自动授时已开启，GNSS 已列入授时来源。";
        } else {
            detail += " 未能确认系统自动授时开关状态。";
        }
        String clockSetStatus = gnssClockSetStatus(timeDump, network, calibrationState,
            calibrationStartElapsed, nowElapsed);
        return "<div class='card'><div class='sync-title " + css + "'>"
            + escape(title) + "</div><div class='sync-detail'>" + escape(detail)
            + "</div><div class='sync-detail'><b>本次 GNSS 实际校时：</b>"
            + escape(clockSetStatus)
            + "</div></div>";
    }

    private static String beijingTimeCard(Sample network, long uncertaintyMillis,
            long nowElapsed, long systemUnix) {
        StringBuilder html = new StringBuilder("<div class='card'><div class='name'>网络 NTP 参考误差</div>")
            .append("<div class='meta'>根据 Android 通用 NTP 样本外推估算；不是国家授时中心校验，也不代表北京时间绝对误差。</div>")
            .append("<div class='divider'></div>");
        if (network == null || nowElapsed < network.elapsedRealtimeMillis || systemUnix < 0) {
            html.append("<div class='action'>暂无可用的网络时间样本，暂时无法估算。</div>");
        } else {
            long age = nowElapsed - network.elapsedRealtimeMillis;
            if (age > NTP_REFERENCE_MAX_AGE_MILLIS) {
                html.append("<div class='action'>最近 NTP 样本已是 ")
                    .append(escape(formatAge(age))).append("前，超过 24 小时，暂不估算。</div>");
            } else if (age > DISPLAY_STALE_AGE_MILLIS) {
                html.append("<div class='action'>最近 NTP 样本已是 ")
                    .append(escape(formatAge(age)))
                    .append("前，样本过旧，不显示具体毫秒差，请重新采样。</div>");
            } else {
                long estimatedReference = network.unixEpochTimeMillis + age;
                long phoneMinusNetwork = systemUnix - estimatedReference;
                long magnitude = Math.abs(phoneMinusNetwork);
                // Total uncertainty: reported value plus the drift budget for the
                // sample's age, so old samples do not look as precise as fresh ones.
                long totalUncertainty = uncertaintyFor("网络 NTP", age, uncertaintyMillis);
                String relation;
                String color;
                if (phoneMinusNetwork < 0) {
                    relation = "本机比 NTP 参考慢约 ";
                    color = "lag";
                } else if (phoneMinusNetwork > 0) {
                    relation = "本机比 NTP 参考快约 ";
                    color = "lead";
                } else {
                    relation = "本机与 NTP 参考基本一致";
                    color = "equal";
                }
                html.append("<div class='sync-title ").append(color).append("'>")
                    .append(escape(relation));
                if (phoneMinusNetwork != 0) {
                    html.append(magnitude).append(" 毫秒 ±").append(totalUncertainty)
                        .append(" 毫秒");
                } else {
                    html.append(" ±").append(totalUncertainty).append(" 毫秒");
                }
                html.append("</div><div class='sync-detail'>参考时刻：")
                    .append(escape(formatReferenceTime(estimatedReference)))
                    .append("；NTP 样本 ").append(escape(formatAge(age))).append("前。");
                if (uncertaintyMillis >= 0) {
                    html.append("来源上报不确定度 ±").append(uncertaintyMillis)
                        .append(" ms，计入年龄漂移预算后总不确定度 ±")
                        .append(totalUncertainty).append(" ms。");
                }
                if (age > DISPLAY_REFERENCE_MAX_AGE_MILLIS) {
                    html.append("样本超过 10 分钟，结果仅作参考。");
                }
                html.append("</div>");
            }
        }
        return html.append("</div>").toString();
    }

    /**
     * Independent NTSC (National Time Service Center) NTP reference card.
     *
     * <p>The offset is shown in milliseconds because the user asked for
     * millisecond display, but that is display resolution, not 1&nbsp;ms
     * absolute accuracy: the uncertainty next to it (median round-trip / 2,
     * typically 10-30&nbsp;ms on the public internet) is part of the value.
     * This card is display only and is never offered as a force-jump source.
     */
    private static String ntscTimeCard(NtscNtpClient.Result result) {
        StringBuilder html = new StringBuilder(
            "<div class='card'><div class='name'>国家授时中心 NTP 参考</div>")
            .append("<div class='meta'>独立 NTP 直查中国科学院国家授时中心（Stratum-1）；"
                + "只作显示，不参与自动改时与强制跳改。</div>")
            .append("<div class='divider'></div>");
        if (result == null) {
            return html.append("<div class='action'>尚未查询授时中心时间，点“刷新”获取。</div></div>")
                .toString();
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        if (!result.success) {
            html.append("<div class='action'>本次查询失败：")
                .append(escape(result.error == null ? "未知原因" : result.error))
                .append("。</div>");
            if (result.lastGood != null && !result.lastGood.isExpired(nowElapsed)) {
                html.append("<div class='sync-detail'>上次成功结果（")
                    .append(escape(formatAge(result.lastGood.ageMillis(nowElapsed))))
                    .append("前，命中 ").append(escape(result.lastGood.server))
                    .append("）：")
                    .append(escape(phoneRelativePhrase("国家授时中心",
                        result.lastGood.offsetMillis)))
                    .append("，不确定度 ±").append(result.lastGood.uncertaintyMillis)
                    .append(" 毫秒。</div>");
            }
            return html.append("</div>").toString();
        }
        long age = result.ageMillis(nowElapsed);
        String relation = phoneRelativePhrase("国家授时中心", result.offsetMillis);
        String color = result.offsetMillis < 0 ? "lead"
            : result.offsetMillis > 0 ? "lag" : "equal";
        html.append("<div class='sync-title ").append(color).append("'>")
            .append(escape(relation))
            .append("，不确定度 ±").append(result.uncertaintyMillis).append(" 毫秒");
        html.append("</div><div class='sync-detail'>命中服务器：")
            .append(escape(result.server));
        if (result.fallback) {
            html.append("（授时中心全部无响应，已回落到 cn.pool.ntp.org，非授时中心直连）");
        }
        html.append("；参考时刻：")
            .append(escape(formatReferenceTime(
                System.currentTimeMillis() + result.offsetMillis)))
            .append("；往返延迟中位 ").append(result.medianRttMillis)
            .append(" 毫秒；采样 ").append(result.samplesAttempted)
            .append(" 次，取 ").append(result.sampleCount)
            .append(" 次中位数；样本 ").append(escape(formatAge(age))).append("前。");
        if (result.isExpired(nowElapsed)) {
            html.append("样本已超过 10 分钟，请点“刷新”重新查询。");
        }
        html.append("</div><div class='small'>毫秒是显示精度，不是 1 毫秒绝对精度；"
            + "公网 NTP 的典型不确定度为 10–30 毫秒，请以“差值 ± 不确定度”一起读。</div>");
        return html.append("</div>").toString();
    }

    private static String gnssClockSetStatus(String timeDump, Sample network,
            String calibrationState, long operationStartElapsed, long nowElapsed) {
        if (operationStartElapsed <= 0 || nowElapsed < operationStartElapsed) {
            return "无法确认（缺少本次操作时间基线，请重新执行一次校准）";
        }
        if ("RUNNING".equals(calibrationState)) return "校准进行中，等待系统结果";
        if (timeDump == null || !timeDump.contains("Time change log:")) {
            return "无法确认（系统未提供 Time change log）";
        }

        // The baseline is the operation start only. Raising it to the newest network
        // sample used to hide a real GNSS clock set that happened before that sample
        // arrived ("GNSS set, then NTP sample" was reported as "no GNSS set").
        long afterElapsed = operationStartElapsed;
        List<ClockSetEvent> events = findClockSetEvents(timeDump);
        ClockSetEvent latestEvent = null;
        for (ClockSetEvent event : events) {
            if (event.elapsedRealtimeMillis < afterElapsed
                    || event.elapsedRealtimeMillis > nowElapsed) continue;
            if (latestEvent == null
                    || event.elapsedRealtimeMillis > latestEvent.elapsedRealtimeMillis) {
                latestEvent = event;
            }
        }
        if (latestEvent != null) {
            if ("gnss".equalsIgnoreCase(latestEvent.origin)) {
                // A later network clock set does not erase the GNSS one; report it
                // separately instead of letting it filter the GNSS event out.
                boolean laterNetworkSet = false;
                for (ClockSetEvent event : events) {
                    if ("network".equalsIgnoreCase(event.origin)
                            && event.elapsedRealtimeMillis > latestEvent.elapsedRealtimeMillis
                            && event.elapsedRealtimeMillis <= nowElapsed) {
                        laterNetworkSet = true;
                        break;
                    }
                }
                return laterNetworkSet
                    ? "是（本次操作后，系统记录了 GNSS 实际改时；随后又有一次网络 NTP 改时）"
                    : "是（本次操作后，系统记录了 GNSS 实际改时）";
            }
            return "否（本次操作后实际改时来源为" + sourceName(latestEvent.origin) + "）";
        }
        if ("SUCCESS".equals(calibrationState)) {
            return "否（本次收到 GNSS 建议，但未记录到本次实际跳时；可能已足够接近而无需调整）";
        }
        return "未发现本次操作后的 GNSS 实际改时记录";
    }

    private void recordGnssTrendPoint(String timeDump) {
        long nowElapsed = parseLong(ELAPSED_PATTERN, timeDump, -1L);
        long systemUnix = parseLong(SYSTEM_TIME_PATTERN, timeDump, -1L);
        Sample gnss = findLatestSample(timeDump, "GnssTimeSuggestion");
        if (nowElapsed < 0 || systemUnix < 0 || gnss == null
                || nowElapsed < gnss.elapsedRealtimeMillis) return;
        long age = nowElapsed - gnss.elapsedRealtimeMillis;
        if (age > GNSS_SYNC_MAX_SAMPLE_AGE_MILLIS) return;
        long offset = gnss.unixEpochTimeMillis + age - systemUnix;

        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        List<TrendPoint> points = parseTrendPoints(prefs.getString(GNSS_TREND_PREF, ""));
        if (!points.isEmpty()) {
            long lastElapsed = points.get(points.size() - 1).elapsedRealtimeMillis;
            if (nowElapsed < lastElapsed) points.clear(); // elapsedRealtime reset after reboot.
            else if (nowElapsed - lastElapsed < GNSS_TREND_MIN_INTERVAL_MILLIS) return;
        }
        points.add(new TrendPoint(nowElapsed, offset));
        pruneTrendPoints(points, nowElapsed);
        prefs.edit().putString(GNSS_TREND_PREF, serializeTrendPoints(points)).apply();
    }

    private List<TrendPoint> loadGnssTrendPoints(long nowElapsed) {
        List<TrendPoint> points = parseTrendPoints(getSharedPreferences("settings", MODE_PRIVATE)
            .getString(GNSS_TREND_PREF, ""));
        if (nowElapsed < 0) return new ArrayList<>();
        if (!points.isEmpty() && nowElapsed < points.get(points.size() - 1).elapsedRealtimeMillis) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .remove(GNSS_TREND_PREF).apply();
            return new ArrayList<>();
        }
        pruneTrendPoints(points, nowElapsed);
        return points;
    }

    private static List<TrendPoint> parseTrendPoints(String encoded) {
        List<TrendPoint> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return points;
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", 2);
            if (fields.length != 2) continue;
            try {
                points.add(new TrendPoint(Long.parseLong(fields[0]),
                    Long.parseLong(fields[1])));
            } catch (NumberFormatException ignored) {
                // Ignore damaged local chart entries.
            }
        }
        return points;
    }

    private static void pruneTrendPoints(List<TrendPoint> points, long nowElapsed) {
        long cutoff = nowElapsed - GNSS_TREND_WINDOW_MILLIS;
        points.removeIf(point -> point.elapsedRealtimeMillis < cutoff
            || point.elapsedRealtimeMillis > nowElapsed);
        while (points.size() > GNSS_TREND_MAX_POINTS) points.remove(0);
    }

    private static String serializeTrendPoints(List<TrendPoint> points) {
        StringBuilder encoded = new StringBuilder();
        for (TrendPoint point : points) {
            if (encoded.length() > 0) encoded.append(';');
            encoded.append(point.elapsedRealtimeMillis).append(',').append(point.offsetMillis);
        }
        return encoded.toString();
    }

    private static String gnssTrendCard(List<TrendPoint> points, long nowElapsed) {
        StringBuilder card = new StringBuilder("<div class='card'><div class='name'>GNSS 偏差趋势</div>")
            .append("<div class='meta'>最近 6 小时的估算值；页面打开时每分钟采集一次。红色为本机落后，绿色为本机领先。</div>");
        if (points == null || points.isEmpty() || nowElapsed < 0) {
            return card.append("<div class='trend-empty'>暂无趋势数据；请先取得新鲜 GNSS 样本并保持诊断页打开一会儿。</div></div>")
                .toString();
        }

        long maxAbs = 0;
        String path = "";
        StringBuilder svg = new StringBuilder("<svg class='trend-svg' viewBox='0 0 360 178' preserveAspectRatio='none' role='img' aria-label='GNSS 与手机时钟偏差趋势'>");
        int left = 54, right = 348, top = 18, bottom = 144;
        long range = 10;
        for (TrendPoint point : points) maxAbs = Math.max(maxAbs, Math.abs(point.offsetMillis));
        range = Math.max(10L, ((maxAbs + 9L) / 10L) * 10L);
        int zeroY = (top + bottom) / 2;
        svg.append("<line x1='54' y1='").append(zeroY).append("' x2='348' y2='").append(zeroY)
            .append("' stroke='#9aa7b8' stroke-dasharray='4 4' stroke-width='1'/>")
            .append("<text x='3' y='29' fill='#b42318' font-size='11'>落后</text>")
            .append("<text x='3' y='141' fill='#167343' font-size='11'>领先</text>")
            .append("<text x='30' y='").append(zeroY + 4).append("' fill='#7b8798' font-size='10'>0</text>")
            .append("<line x1='54' y1='18' x2='54' y2='144' stroke='#d8e0ea'/>")
            .append("<line x1='54' y1='144' x2='348' y2='144' stroke='#d8e0ea'/>");

        long windowStart = nowElapsed - GNSS_TREND_WINDOW_MILLIS;
        double halfHeight = (bottom - top) / 2.0;
        StringBuilder polyline = new StringBuilder();
        for (TrendPoint point : points) {
            double fraction = (point.elapsedRealtimeMillis - windowStart)
                / (double) GNSS_TREND_WINDOW_MILLIS;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            double x = left + fraction * (right - left);
            double y = zeroY - (point.offsetMillis / (double) range) * halfHeight;
            y = Math.max(top, Math.min(bottom, y));
            if (polyline.length() > 0) polyline.append(' ');
            polyline.append(String.format(Locale.US, "%.1f,%.1f", x, y));
        }
        if (points.size() > 1) {
            svg.append("<polyline points='").append(polyline)
                .append("' fill='none' stroke='#64748b' stroke-width='2' stroke-linejoin='round' stroke-linecap='round'/>");
        }
        for (TrendPoint point : points) {
            double fraction = (point.elapsedRealtimeMillis - windowStart)
                / (double) GNSS_TREND_WINDOW_MILLIS;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            double x = left + fraction * (right - left);
            double y = zeroY - (point.offsetMillis / (double) range) * halfHeight;
            y = Math.max(top, Math.min(bottom, y));
            String color = point.offsetMillis > 0 ? "#c62828"
                : point.offsetMillis < 0 ? "#198754" : "#64748b";
            svg.append("<circle cx='").append(String.format(Locale.US, "%.1f", x))
                .append("' cy='").append(String.format(Locale.US, "%.1f", y))
                .append("' r='4' fill='").append(color).append("'><title>")
                .append(escape(trendOffsetPhrase(point.offsetMillis))).append("</title></circle>");
        }
        svg.append("<text x='54' y='166' fill='#7b8798' font-size='10'>6 小时前</text>")
            .append("<text x='320' y='166' fill='#7b8798' font-size='10'>现在</text></svg>");
        TrendPoint latest = points.get(points.size() - 1);
        long latestAge = Math.max(0L, nowElapsed - latest.elapsedRealtimeMillis);
        card.append(svg).append("<div class='trend-latest'>最近观测（")
            .append(escape(formatAge(latestAge))).append("前）：")
            .append(escape(trendOffsetPhrase(latest.offsetMillis))).append("</div>");
        if (points.size() < 2) {
            card.append("<div class='small'>当前 1 个观测点；后续自动采样后会连成趋势线。</div>");
        }
        return card.append("</div>").toString();
    }

    private static String trendOffsetPhrase(long offsetMillis) {
        long magnitude = Math.abs(offsetMillis);
        if (offsetMillis > 0) return "本机落后 GNSS 约 " + magnitude + " 毫秒";
        if (offsetMillis < 0) return "本机领先 GNSS 约 " + magnitude + " 毫秒";
        return "本机与 GNSS 基本一致";
    }

    private static List<ClockSetEvent> findClockSetEvents(String dump) {
        List<ClockSetEvent> events = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : (dump == null ? "" : dump).split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.contains("Time change log:")) {
                inSection = true;
                continue;
            }
            if (inSection && (line.startsWith("Telephony suggestion history:")
                    || line.startsWith("Network suggestion history:"))) break;
            if (!inSection) continue;

            Matcher originMatcher = CLOCK_SET_ORIGIN_PATTERN.matcher(line);
            if (!originMatcher.find()) continue;
            Matcher elapsedMatcher = CHANGE_LOG_ELAPSED_PATTERN.matcher(line);
            if (!elapsedMatcher.find()) continue;
            try {
                long hours = elapsedMatcher.group(1) == null
                    ? 0L : Long.parseLong(elapsedMatcher.group(1));
                long minutes = elapsedMatcher.group(2) == null
                    ? 0L : Long.parseLong(elapsedMatcher.group(2));
                double seconds = elapsedMatcher.group(3) == null
                    ? 0.0 : Double.parseDouble(elapsedMatcher.group(3));
                long elapsed = hours * 3_600_000L + minutes * 60_000L
                    + Math.round(seconds * 1_000.0);
                events.add(new ClockSetEvent(originMatcher.group(1), elapsed));
            } catch (NumberFormatException ignored) {
                // Ignore malformed or vendor-specific time-change records.
            }
        }
        return events;
    }

    private static List<ClockActionEvent> findClockActionEvents(String dump) {
        List<ClockActionEvent> events = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : (dump == null ? "" : dump).split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.contains("Time change log:")) {
                inSection = true;
                continue;
            }
            if (inSection && (line.startsWith("Telephony suggestion history:")
                    || line.startsWith("Network suggestion history:"))) break;
            if (!inSection) continue;

            Matcher setMatcher = CLOCK_SET_ORIGIN_PATTERN.matcher(line);
            Matcher confidenceMatcher = CONFIDENCE_ORIGIN_PATTERN.matcher(line);
            boolean clockChanged = setMatcher.find();
            boolean confidenceOnly = !clockChanged && confidenceMatcher.find();
            if (!clockChanged && !confidenceOnly) continue;

            Matcher elapsedMatcher = CHANGE_LOG_ELAPSED_PATTERN.matcher(line);
            if (!elapsedMatcher.find()) continue;
            try {
                long hours = elapsedMatcher.group(1) == null
                    ? 0L : Long.parseLong(elapsedMatcher.group(1));
                long minutes = elapsedMatcher.group(2) == null
                    ? 0L : Long.parseLong(elapsedMatcher.group(2));
                double seconds = elapsedMatcher.group(3) == null
                    ? 0.0 : Double.parseDouble(elapsedMatcher.group(3));
                long elapsed = hours * 3_600_000L + minutes * 60_000L
                    + Math.round(seconds * 1_000.0);
                String origin = clockChanged ? setMatcher.group(1) : confidenceMatcher.group(1);
                long oldMillis = parseLong(OLD_CLOCK_MILLIS_PATTERN, line, -1L);
                long newMillis = parseLong(NEW_CLOCK_MILLIS_PATTERN, line, -1L);
                String cause = localizeChangeCause(
                    parseString(CHANGE_CAUSE_PATTERN, line, "").trim());
                events.add(new ClockActionEvent(origin, elapsed, clockChanged,
                    oldMillis, newMillis, cause));
            } catch (NumberFormatException ignored) {
                // Ignore malformed or vendor-specific time-change records.
            }
        }
        return events;
    }

    private static boolean hasGnssPriority(String priorities) {
        if (priorities == null) return false;
        for (String origin : priorities.split(",")) {
            if ("gnss".equalsIgnoreCase(origin.trim())) return true;
        }
        return false;
    }

    private String calibrationCard(String state, String detail, long timeMillis) {
        String label;
        String css;
        if ("FAILED".equals(state) && detail != null
                && (detail.contains("系统已有 GNSS 建议")
                    || detail.contains("本次等待期间没有比操作前更新的建议"))) {
            state = "NO_NEW_SAMPLE";
        }
        if ("RUNNING".equals(state) && timeMillis > 0
                && System.currentTimeMillis() - timeMillis > 120_000) {
            state = "UNVERIFIED";
            detail = "上次操作中断，无法确认是否收到新 GNSS 建议；这不代表系统授时失败。";
        }
        if ("SUCCESS".equals(state)) {
            label = "本次已收到新的 GNSS 建议";
            css = "good";
        } else if ("RUNNING".equals(state)) {
            label = "正在校准 · 等待 GNSS 样本";
            css = "running";
        } else if ("NO_NEW_SAMPLE".equals(state)) {
            label = "本次未触发新 GNSS 建议";
            css = "warn";
        } else if ("NO_LOCATION_TIME".equals(state)) {
            label = "未获取到新的 GNSS 授时样本";
            css = "fail";
        } else if ("STALE_LOCATION_TIME".equals(state)) {
            label = "LocationTime 回调已到，但样本时间未更新";
            css = "fail";
        } else if ("UNVERIFIED".equals(state)) {
            label = "本次结果无法确认";
            css = "warn";
        } else if ("PARTIAL".equals(state)) {
            label = "GNSS 样本未完整送达";
            css = "warn";
        } else if ("FAILED".equals(state)) {
            label = "校准流程失败";
            css = "fail";
        } else {
            label = "尚未执行手动校准";
            css = "summary";
        }
        StringBuilder card = new StringBuilder("<div class='card'><div class='state ")
            .append(css).append("'>").append(escape(label)).append("</div>")
            .append("<div class='meta'>").append(escape(detail == null ? "" : detail)).append("</div>");
        if (timeMillis > 0) {
            card.append("<div class='small'>最近操作：").append(escape(
                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(timeMillis))))
                .append("</div>");
        }
        return card.append("</div>").toString();
    }

    private String forceCard(String state, String detail, long timeMillis) {
        String label;
        String css;
        if ("FAILED".equals(state) && detail != null
                && (detail.contains("timeout") || detail.contains("InterruptedException"))) {
            state = "UNVERIFIED";
            detail = "强制跳改等待期间未收到 system_server 回执，无法确认时钟是否已设置。"
                + "请检查 force_result 属性和 LSPosed 日志；确认前不要重复跳改。";
        }
        if ("RUNNING".equals(state) && timeMillis > 0
                && System.currentTimeMillis() - timeMillis > 120_000) {
            state = "UNVERIFIED";
            detail = "等待 system_server 回执超时，无法确认时钟是否已设置。请刷新授时日志后再决定是否重试。";
        }
        if ("SUCCESS".equals(state)) {
            label = "上次强制跳改成功";
            css = "good";
        } else if ("RUNNING".equals(state)) {
            label = "强制跳改进行中";
            css = "running";
        } else if ("FAILED".equals(state)) {
            label = "上次强制跳改失败";
            css = "fail";
        } else if ("UNVERIFIED".equals(state)) {
            label = "上次强制跳改无法确认";
            css = "warn";
        } else {
            label = "强制跳改仅在你确认后执行";
            css = "summary";
        }
        StringBuilder card = new StringBuilder("<div class='card'><div class='state ")
            .append(css).append("'>").append(escape(label)).append("</div>")
            .append("<div class='meta'>").append(escape(detail == null ? "" : detail)).append("</div>");
        if (timeMillis > 0) {
            card.append("<div class='small'>最近操作：").append(escape(
                java.text.DateFormat.getDateTimeInstance().format(new Date(timeMillis))))
                .append("</div>");
        }
        return card.append("</div>").toString();
    }

    private static void addForceOption(List<ForceOption> options, String name, Sample sample,
            long nowElapsed, long systemUnix, long uncertainty) {
        if (sample == null || nowElapsed < sample.elapsedRealtimeMillis || systemUnix < 0) return;
        long age = nowElapsed - sample.elapsedRealtimeMillis;
        if (age > maxForceSampleAgeMillis(name)) return;
        long target = sample.unixEpochTimeMillis + age;
        if (target < 1_577_836_800_000L || target > 4_102_444_800_000L) return;
        options.add(new ForceOption(name, target, target - systemUnix, age, uncertainty,
            nowElapsed));
    }

    private static long maxForceSampleAgeMillis(String source) {
        // All sources share the same 10-minute freshness limit; the parameter is
        // kept so call sites do not change if a per-source limit ever returns.
        return MAX_FORCE_SAMPLE_AGE_MILLIS;
    }

    private static String forceSampleAgeLimit(String source) {
        return "10 分钟";
    }

    /**
     * Effective ±uncertainty for a force-jump target. NITZ has no reported
     * uncertainty, so it uses a ±1500 ms baseline; every source grows by
     * 100 ms per minute of sample age (the rebase only shifts by elapsed
     * time, it does not correct drift, so older samples are less certain).
     * Returns -1 when the source reports nothing and has no baseline (GNSS,
     * or NTP without a reported value): unknown, not zero.
     */
    private static long forceUncertaintyMillis(ForceOption option) {
        long ageGrowth = Math.max(0L, option.ageMillis) / 60_000L
            * FORCE_UNCERTAINTY_GROWTH_PER_MINUTE_MILLIS;
        if ("基站 NITZ".equals(option.source)) {
            return NITZ_FORCE_BASE_UNCERTAINTY_MILLIS + ageGrowth;
        }
        if (option.uncertaintyMillis >= 0) {
            return option.uncertaintyMillis + ageGrowth;
        }
        return -1L;
    }

    /** Appends "目标系统时间：<UTC>（±X ms，即 <low> 至 <high>）" — or an honest
     *  "uncertainty unknown" note — never a bare point estimate. */
    private static void appendForceTarget(SpannableStringBuilder message, ForceOption option) {
        long uncertainty = forceUncertaintyMillis(option);
        message.append("目标系统时间：").append(formatUtc(option.targetUnixMillis));
        if (uncertainty >= 0) {
            message.append("（±").append(String.valueOf(uncertainty)).append(" ms，即 ")
                .append(formatUtc(option.targetUnixMillis - uncertainty)).append(" 至 ")
                .append(formatUtc(option.targetUnixMillis + uncertainty)).append("）");
        } else {
            message.append("（不确定度未知）");
        }
        message.append('\n');
    }

    private static void appendForceUncertaintyNote(SpannableStringBuilder message,
            ForceOption option, long uncertainty) {
        if (uncertainty < 0) {
            message.append("目标不确定度：未知（该来源未上报不确定度，目标为点估计）。");
            return;
        }
        message.append("目标不确定度：±").append(String.valueOf(uncertainty)).append(" ms");
        if ("基站 NITZ".equals(option.source)) {
            message.append("（基站 NITZ 基线 ±1500 ms，样本越老不确定度越大）");
        } else if (option.uncertaintyMillis >= 0) {
            message.append("（来源上报 ±").append(String.valueOf(option.uncertaintyMillis))
                .append(" ms，样本越老不确定度越大）");
        } else {
            message.append("（样本越老不确定度越大）");
        }
    }

    /** Red warning for the second confirmation when the jump exceeds 5 s. */
    private static CharSequence largeJumpWarning(long offsetMillis) {
        String text = "⚠ 大幅跳改：本次将把时钟"
            + (offsetMillis > 0 ? "前调" : "回拨") + "约 " + Math.abs(offsetMillis)
            + " 毫秒（超过 5 秒），请再次核对目标时间无误。";
        SpannableStringBuilder warning = new SpannableStringBuilder(text);
        warning.setSpan(new ForegroundColorSpan(Color.RED), 0, text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return warning;
    }

    private void chooseForceSource() {
        if (!currentDiagnosticsHasRoot) {
            new AlertDialog.Builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("请先在 Magisk / SuperSU 中允许“卫星对时”获取 root，再刷新授时诊断。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        final ForceOption[] options = currentForceOptions;
        if (options == null || options.length == 0) {
            new AlertDialog.Builder(this)
                .setTitle("没有可用的时间源")
                .setMessage("没有符合年龄限制的授时样本。GNSS、基站 NITZ 和网络 NTP 样本均需在 10 分钟内。请刷新或先获取新的 GNSS 样本。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            long uncertainty = forceUncertaintyMillis(options[i]);
            labels[i] = options[i].source + "　" + phoneRelativePhrase(options[i].source,
                options[i].offsetMillis)
                + "　·　" + formatAge(options[i].ageMillis) + "前"
                + (uncertainty >= 0 ? "　·　±" + uncertainty + " ms" : "　·　不确定度未知");
        }
        new AlertDialog.Builder(this)
            .setTitle("选择跳改依据")
            .setItems(labels, (dialog, which) -> showForcePreview(options[which]))
            .setNegativeButton("取消", null)
            .show();
    }

    private void showForcePreview(ForceOption option) {
        final ForceOption previewOption = rebaseForceOption(option);
        if (previewOption.ageMillis > maxForceSampleAgeMillis(previewOption.source)) {
            new AlertDialog.Builder(this).setTitle("授时样本已过期")
                .setMessage(previewOption.source + "样本已超过 "
                    + forceSampleAgeLimit(previewOption.source)
                    + "，不能用于强制跳改。请刷新诊断并获取新样本。")
                .setPositiveButton("知道了", null).show();
            return;
        }
        if (Math.abs(previewOption.offsetMillis) > MAX_FORCE_JUMP_MILLIS) {
            new AlertDialog.Builder(this).setTitle("跳变量过大，已拒绝")
                .setMessage("本次跳改需要把时钟"
                    + (previewOption.offsetMillis > 0 ? "前调" : "回拨") + "约 "
                    + Math.abs(previewOption.offsetMillis)
                    + " 毫秒，超过 60 秒单次上限。强制跳改只用于小幅校准；"
                    + "如时钟偏差确实很大，请先检查授时来源或手动校准。")
                .setPositiveButton("知道了", null).show();
            return;
        }
        long uncertainty = forceUncertaintyMillis(previewOption);
        SpannableStringBuilder message = new SpannableStringBuilder()
            .append("来源：").append(previewOption.source).append('\n')
            .append("样本年龄：").append(formatAge(previewOption.ageMillis)).append('\n')
            .append("当前设备时钟：").append(formatUtc(System.currentTimeMillis())).append('\n');
        appendForceTarget(message, previewOption);
        message.append("本机与来源时间：").append(phoneRelativePhrase(previewOption.source,
            previewOption.offsetMillis)).append('\n')
            .append("预计时钟变化：").append(clockAdjustmentDescription(previewOption.offsetMillis));
        if (uncertainty >= 0) {
            message.append("（±").append(String.valueOf(uncertainty)).append(" ms）");
        }
        message.append('\n');
        appendForceUncertaintyNote(message, previewOption, uncertainty);
        if (Math.abs(previewOption.offsetMillis) > LARGE_FORCE_JUMP_MILLIS) {
            message.append('\n').append(largeJumpWarning(previewOption.offsetMillis));
        }
        message.append("\n\n目标时间由所选来源样本按 elapsedRealtime 外推得到，不保证等于真实北京时间。继续后还会再次确认。");
        new AlertDialog.Builder(this)
            .setTitle("跳改预览")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步", (dialog, which) -> confirmForceJump(previewOption))
            .show();
    }

    private ForceOption rebaseForceOption(ForceOption option) {
        long nowElapsed = SystemClock.elapsedRealtime();
        long elapsedDelta = Math.max(0, nowElapsed - option.diagnosticElapsedMillis);
        long target = option.targetUnixMillis + elapsedDelta;
        return new ForceOption(option.source, target, target - System.currentTimeMillis(),
            option.ageMillis + elapsedDelta, option.uncertaintyMillis, nowElapsed);
    }

    private void confirmForceJump(ForceOption inputOption) {
        final ForceOption option = rebaseForceOption(inputOption);
        if (option.ageMillis > maxForceSampleAgeMillis(option.source)) {
            new AlertDialog.Builder(this).setTitle("授时样本已过期")
                .setMessage("最终确认前，" + option.source + "样本已超过 "
                    + forceSampleAgeLimit(option.source)
                    + "，本次跳改已取消。请刷新诊断并获取新样本。")
                .setPositiveButton("知道了", null).show();
            return;
        }
        if (Math.abs(option.offsetMillis) > MAX_FORCE_JUMP_MILLIS) {
            new AlertDialog.Builder(this).setTitle("跳变量过大，已拒绝")
                .setMessage("最终确认前目标已漂移：本次跳改需要把时钟"
                    + (option.offsetMillis > 0 ? "前调" : "回拨") + "约 "
                    + Math.abs(option.offsetMillis)
                    + " 毫秒，超过 60 秒单次上限，本次跳改已取消。")
                .setPositiveButton("知道了", null).show();
            return;
        }
        long uncertainty = forceUncertaintyMillis(option);
        SpannableStringBuilder message = new SpannableStringBuilder(
            "即将绕过 Android 自动改时阈值，立即将系统时钟设置为：\n\n");
        appendForceTarget(message, option);
        message.append("来源：").append(option.source)
            .append("\n本机与来源时间：").append(phoneRelativePhrase(option.source, option.offsetMillis))
            .append("\n预计时钟变化：").append(clockAdjustmentDescription(option.offsetMillis));
        if (uncertainty >= 0) {
            message.append("（±").append(String.valueOf(uncertainty)).append(" ms）");
        }
        message.append('\n');
        appendForceUncertaintyNote(message, option, uncertainty);
        if (Math.abs(option.offsetMillis) > LARGE_FORCE_JUMP_MILLIS) {
            message.append("\n\n").append(largeJumpWarning(option.offsetMillis));
        }
        message.append("\n\n这可能影响证书校验、定时任务和日志顺序；自动授时之后也可能再次调整。确定执行？");
        new AlertDialog.Builder(this)
            .setTitle("最后确认：强制跳改")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认跳改", (dialog, which) -> executeForceJump(option))
            .show();
    }

    private void executeForceJump(ForceOption option) {
        // Final send-time re-check: the target was rebased in confirmForceJump
        // and may have drifted past the cap since. The bridge enforces the
        // same 60 s cap, so a request that slipped through here is refused there.
        if (Math.abs(option.targetUnixMillis - System.currentTimeMillis())
                > MAX_FORCE_JUMP_MILLIS) {
            getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
                .putString("force_state", "FAILED")
                .putString("force_detail", "跳变量超过 60 秒上限，本次跳改已拒绝，未发送请求。")
                .putLong("force_time", System.currentTimeMillis()).apply();
            Toast.makeText(this, "跳变量超过 60 秒上限，已拒绝", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        // Unpredictable per-request token: a stale result from an earlier jump
        // can never match this request. Only an exact "<token>:OK" / ":FAIL"
        // reply inside the 10 x 1 s poll below is accepted as this request's
        // receipt; anything else ends UNVERIFIED, never SUCCESS.
        final String token = UUID.randomUUID().toString();
        final String request = token + ":" + option.targetUnixMillis;
        // Overwrite any stale result with this request's PENDING marker first,
        // so a leftover reply from a previous jump cannot be read as this one.
        // The write check accepts an empty property: the bridge consumes
        // (clears) the request once handled, which is also a valid outcome.
        final String command = "setprop " + GnssTriggerBridge.FORCE_RESULT_PROPERTY + " \""
            + token + ":PENDING\" || exit 10; setprop "
            + GnssTriggerBridge.FORCE_TIME_PROPERTY + " " + request
            + " || exit 10; r0=$(getprop " + GnssTriggerBridge.FORCE_TIME_PROPERTY
            + "); [ \"$r0\" = \"" + request + "\" ] || [ -z \"$r0\" ] || exit 11; i=0; while [ $i -lt 10 ]; do r=$(getprop "
            + GnssTriggerBridge.FORCE_RESULT_PROPERTY + "); if [ \"$r\" = \"" + token
            + ":OK\" ]; then echo \"$r\"; exit 0; fi; if [ \"$r\" = \"" + token
            + ":FAIL\" ]; then echo \"$r\"; exit 12; fi; sleep 1; i=$((i+1)); done; echo timeout; exit 13";
        getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
            .putString("force_state", "RUNNING")
            .putString("force_detail", "等待 system_server 执行时钟跳改。")
            .putLong("force_time", System.currentTimeMillis()).apply();
        forceButton.setEnabled(false);
        forceJumpInProgress = true;
        Toast.makeText(this, "强制跳改请求已发送", Toast.LENGTH_SHORT).show();
        forceWorker.execute(() -> {
            RootCommand.Result result = RootCommand.run(command);
            boolean success = result.exitCode == 0 && result.output.contains(token + ":OK");
            boolean interrupted = !success
                && result.output.contains("InterruptedException");
            boolean timedOut = !success && !interrupted
                && (result.exitCode == 13 || result.output.contains("timeout"));
            String resultState = success ? "SUCCESS"
                : (interrupted || timedOut) ? "UNVERIFIED" : "FAILED";
            String detail = success
                ? "来源 " + option.source + "；目标 " + formatUtc(option.targetUnixMillis)
                    + " UTC；" + clockAdjustmentDescription(option.offsetMillis) + "。"
                : interrupted
                    ? "等待 system_server 回执时任务被中断，无法确认是否已改时。请刷新 Time change log；确认前不要重复跳改。"
                    : timedOut
                    ? "强制跳改请求已发出，但 10 秒内未收到 system_server 回执。无法仅凭超时判断时钟是否已设置；请检查 force_result 属性和 LSPosed 日志，确认前不要重复跳改。"
                    : "system_server 未确认设置时钟。请检查 LSPosed Android 作用域和模块日志。 "
                    + limit(result.output.trim(), 180);
            getSharedPreferences("calibration_status", MODE_PRIVATE).edit()
                .putString("force_state", resultState)
                .putString("force_detail", detail)
                .putLong("force_time", System.currentTimeMillis()).apply();
            forceJumpInProgress = false;
            if (!isFinishing() && !isDestroyed()) {
                runOnUiThread(() -> {
                    Toast.makeText(this, success ? "强制跳改成功"
                            : (interrupted || timedOut) ? "强制跳改结果无法确认" : "强制跳改失败",
                        Toast.LENGTH_LONG).show();
                    refresh();
                });
            }
        });
    }

    private static String formatUtc(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'",
            Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMillis));
    }

    private static String formatReferenceTime(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'NTP 参考时刻'",
            Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timeMillis));
    }

    private static String sourceCard(String name, Sample sample, long nowElapsed, long systemUnix,
            long threshold, Long gnssServiceAge, long uncertainty, String slotNote) {
        StringBuilder card = new StringBuilder("<div class='source'><div><div class='name'>")
            .append(escape(name)).append("</div>");
        if (sample == null) {
            card.append("<div class='meta'>尚无时间建议记录</div>");
            if (slotNote != null) {
                card.append("<div class='small'>").append(escape(slotNote)).append("</div>");
            }
            if ("GNSS 卫星".equals(name)) {
                card.append("<div class='small'>GNSS 服务：")
                    .append(gnssServiceAge == null ? "暂无样本" : "最近样本已收到")
                    .append("</div>");
            }
            card.append("</div><div class='value none'>暂无</div></div>");
            return card.toString();
        }

        long age = nowElapsed - sample.elapsedRealtimeMillis;
        if (age < 0 || systemUnix < 0) {
            card.append("<div class='meta'>样本参考时间无效</div></div><div class='value none'>—</div></div>");
            return card.toString();
        }
        long projected = sample.unixEpochTimeMillis + age;
        long offset = projected - systemUnix;
        String valueClass = offset < 0 ? "lead" : offset > 0 ? "lag" : "equal";
        card.append("<div class='meta'>建议已收到 · 样本 ").append(escape(formatAge(age)))
            .append("前</div>");
        if (slotNote != null) {
            card.append("<div class='small'>").append(escape(slotNote)).append("</div>");
        }
        if ("GNSS 卫星".equals(name)) {
            card.append("<div class='small'>GNSS 服务样本：")
                .append(gnssServiceAge == null ? "无" : escape(formatAge(gnssServiceAge) + "前"))
                .append("</div>");
        }
        if (uncertainty >= 0) {
            card.append("<div class='small'>不确定度 ±")
                .append(uncertainty).append(" ms（来源基础不确定度 + 年龄漂移预算）</div>");
        }
        if (age > DISPLAY_STALE_AGE_MILLIS) {
            // Over an hour old: the extrapolated value is not trustworthy enough to
            // show as a millisecond number at all.
            card.append("</div><div style='text-align:right'><div class='value none'>样本过旧</div>")
                .append("<div class='pill'>请重新采样</div></div></div>")
                .append("<div class='divider'></div>");
            return card.toString();
        }
        card.append("</div><div style='text-align:right'><div class='value ")
            .append(valueClass).append("'>").append(Math.abs(offset)).append(" 毫秒");
        if (uncertainty >= 0) {
            card.append(" ±").append(uncertainty).append(" 毫秒");
        }
        card.append("</div>")
            .append("<div class='pill ").append(valueClass).append("'>")
            .append(escape(phoneRelationLabel(name, offset)))
            .append("</div>");
        if (age > DISPLAY_REFERENCE_MAX_AGE_MILLIS) {
            card.append("<div class='pill'>样本超 10 分钟，仅供参考</div>");
        }
        card.append("</div></div>")
            .append("<div class='divider'></div>");
        return card.toString();
    }

    /** Drift budget for extrapolating a sample of the given age: 100 ppm. */
    private static long driftBudgetMillis(long ageMillis) {
        if (ageMillis <= 0) return 0L;
        return ageMillis * DRIFT_BUDGET_PPM / 1_000_000L;
    }

    /**
     * Total uncertainty of a displayed difference for one source: its base
     * uncertainty plus the drift budget for the sample's age. NITZ has no reported
     * uncertainty, so it starts at +/-1500 ms (whole-second quantisation plus
     * carrier transmission delay); NTP uses its reported value when present.
     */
    private static long uncertaintyFor(String source, long ageMillis, long reportedMillis) {
        long base;
        if ("基站 NITZ".equals(source)) {
            base = NITZ_BASE_UNCERTAINTY_MILLIS;
        } else if ("网络 NTP".equals(source)) {
            base = reportedMillis >= 0 ? reportedMillis : NETWORK_UNKNOWN_UNCERTAINTY_MILLIS;
        } else {
            base = GNSS_BASE_UNCERTAINTY_MILLIS;
        }
        return base + driftBudgetMillis(ageMillis);
    }

    /** Sync tolerance grows with sample age and uncertainty; it is not a fixed 100 ms. */
    private static long syncToleranceMillis(long ageMillis, long uncertaintyMillis) {
        return GNSS_SYNC_TOLERANCE_MILLIS + driftBudgetMillis(ageMillis) + uncertaintyMillis;
    }

    private static long projectedMillis(Sample sample, long nowElapsed) {
        return sample.unixEpochTimeMillis + (nowElapsed - sample.elapsedRealtimeMillis);
    }

    /**
     * Median of the most recent samples from one source. Takes up to the 3 newest
     * samples with strictly increasing elapsed time, projects each to now and uses
     * the median projection. If the newest sample's projection is more than 2 s
     * from the median it is dropped as an outlier and the median sample is used.
     * With fewer than 3 samples the newest sample is used unchanged.
     */
    private static ResolvedSample resolveSamples(String dump, String className,
            long nowElapsed) {
        List<Sample> recent = findRecentSamples(dump, className, MEDIAN_SAMPLE_COUNT);
        if (recent.isEmpty()) return new ResolvedSample(null, 0, false, -1L);
        if (recent.size() < MEDIAN_SAMPLE_COUNT || nowElapsed < 0) {
            return new ResolvedSample(recent.get(0), recent.size(), false, -1L);
        }
        List<Long> projected = new ArrayList<>(recent.size());
        for (Sample sample : recent) projected.add(projectedMillis(sample, nowElapsed));
        List<Long> sorted = new ArrayList<>(projected);
        Collections.sort(sorted);
        long medianProjected = sorted.get(sorted.size() / 2);
        Sample newest = recent.get(0);
        if (Math.abs(projectedMillis(newest, nowElapsed) - medianProjected)
                > MEDIAN_OUTLIER_MILLIS) {
            // The median sample is the one whose projection is the median value.
            for (int i = 0; i < recent.size(); i++) {
                if (projected.get(i) == medianProjected) {
                    return new ResolvedSample(recent.get(i), recent.size(), true,
                        medianProjected);
                }
            }
        }
        return new ResolvedSample(newest, recent.size(), false, medianProjected);
    }

    /** Newest samples of one source, most recent first, strictly increasing elapsed. */
    private static List<Sample> findRecentSamples(String dump, String className, int max) {
        Matcher matcher = SUGGESTION_PATTERN.matcher(dump == null ? "" : dump);
        List<Sample> all = new ArrayList<>();
        while (matcher.find()) {
            if (!className.equals(matcher.group(1))) continue;
            try {
                all.add(new Sample(Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3))));
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump entries.
            }
        }
        Collections.sort(all, (a, b) ->
            Long.compare(b.elapsedRealtimeMillis, a.elapsedRealtimeMillis));
        List<Sample> recent = new ArrayList<>();
        long lastElapsed = Long.MAX_VALUE;
        for (Sample sample : all) {
            if (sample.elapsedRealtimeMillis >= lastElapsed) continue;
            recent.add(sample);
            lastElapsed = sample.elapsedRealtimeMillis;
            if (recent.size() >= max) break;
        }
        return recent;
    }

    /**
     * The SIM slot that produced a given telephony sample, matched on its exact
     * elapsed/unix pair so two carriers are never mixed into one reading.
     * Returns -1 when the dump does not carry a slot index for this sample.
     */
    private static int findTelephonySlotIndex(String dump, Sample sample) {
        if (sample == null) return -1;
        Matcher matcher = TELEPHONY_SLOT_PATTERN.matcher(dump == null ? "" : dump);
        while (matcher.find()) {
            try {
                long elapsed = Long.parseLong(matcher.group(2));
                long unix = Long.parseLong(matcher.group(3));
                if (elapsed == sample.elapsedRealtimeMillis
                        && unix == sample.unixEpochTimeMillis) {
                    return Integer.parseInt(matcher.group(1));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump entries.
            }
        }
        return -1;
    }

    /** Number of distinct SIM slots with a telephony suggestion in this dump. */
    private static int countTelephonySlots(String dump) {
        Matcher matcher = TELEPHONY_SLOT_PATTERN.matcher(dump == null ? "" : dump);
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        while (matcher.find()) {
            try {
                slots.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump entries.
            }
        }
        return slots.size();
    }

    private static Sample findLatestSample(String dump, String className) {
        Matcher matcher = SUGGESTION_PATTERN.matcher(dump == null ? "" : dump);
        Sample latest = null;
        while (matcher.find()) {
            if (!className.equals(matcher.group(1))) continue;
            try {
                long elapsed = Long.parseLong(matcher.group(2));
                long unix = Long.parseLong(matcher.group(3));
                if (latest == null || elapsed > latest.elapsedRealtimeMillis) {
                    latest = new Sample(elapsed, unix);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump entries.
            }
        }
        return latest;
    }

    private static Sample findGnssServiceSample(String dump) {
        // Take the newest match, like findLatestSample: the first match in the dump
        // may be a stale history entry, not the current service sample.
        Matcher matcher = GNSS_SERVICE_SAMPLE_PATTERN.matcher(dump == null ? "" : dump);
        Sample latest = null;
        while (matcher.find()) {
            try {
                Sample sample = new Sample(Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)));
                if (latest == null
                        || sample.elapsedRealtimeMillis > latest.elapsedRealtimeMillis) {
                    latest = sample;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump entries.
            }
        }
        return latest;
    }

    private static long findNetworkUncertainty(String dump) {
        Matcher matcher = NETWORK_UNCERTAINTY_PATTERN.matcher(dump == null ? "" : dump);
        long value = -1;
        while (matcher.find()) {
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Ignore malformed dump values.
            }
        }
        return value;
    }

    private String clockActionCard(String dump, long nowElapsed) {
        android.content.SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        long clearedBefore = settings.getLong(ACTION_LOG_CLEAR_ELAPSED_PREF, -1L);
        if (nowElapsed >= 0 && clearedBefore > nowElapsed) {
            settings.edit().remove(ACTION_LOG_CLEAR_ELAPSED_PREF).apply();
            clearedBefore = -1L;
        }
        List<ClockActionEvent> events = new ArrayList<>();
        for (ClockActionEvent event : findClockActionEvents(dump)) {
            if (clearedBefore < 0 || event.elapsedRealtimeMillis > clearedBefore) {
                events.add(event);
            }
        }
        StringBuilder html = new StringBuilder(1_200);
        html.append("<div class='card'><div class='name'>实际改时来源与系统动作</div>")
            .append("<div class='meta'>以 Time change log 的 origin 字段为准；收到某来源建议不等于它实际改了时钟。</div>")
            .append("<div class='divider'></div>");
        if (events.isEmpty()) {
            if (clearedBefore >= 0) {
                html.append("<div class='action'>清理后暂无新的系统改时记录。清理前事件已从本时间线隐藏；Android 原始系统日志仍保留在下方详细日志中。</div>");
            } else {
                html.append("<div class='action'>当前系统日志中没有可识别的改时/置信度动作记录。授时建议历史只能说明样本已收到，不能据此认定系统已跳时。</div>");
            }
        } else {
            int first = Math.max(0, events.size() - 6);
            for (int i = events.size() - 1; i >= first; i--) {
                ClockActionEvent event = events.get(i);
                String title = event.clockChanged
                    ? "实际设置系统时钟 · " + sourceName(event.origin)
                    : "仅更新时间置信度 · " + sourceName(event.origin);
                long age = nowElapsed >= event.elapsedRealtimeMillis
                    ? nowElapsed - event.elapsedRealtimeMillis : -1L;
                StringBuilder meta = new StringBuilder();
                meta.append("系统记录：").append(age < 0 ? "时间参考无效" : formatAge(age) + "前");
                if (event.clockChanged) {
                    meta.append("；").append(clockDeltaDescription(event.oldClockMillis,
                        event.newClockMillis));
                } else {
                    meta.append("；置信度变化不代表时钟跳变");
                }
                if (!event.cause.isEmpty()) meta.append("；原因：").append(event.cause);
                html.append("<div class='event-row'><div class='event-title ")
                    .append(event.clockChanged ? "actual" : "confidence").append("'>")
                    .append(escape(title)).append("</div><div class='event-meta'>")
                    .append(escape(meta.toString())).append("</div></div>");
            }
        }
        return html.append("</div>").toString();
    }

    private static String clockDeltaDescription(long oldMillis, long newMillis) {
        if (oldMillis < 0 || newMillis < 0) return "系统未提供调整量";
        long delta = newMillis - oldMillis;
        if (delta > 0) return "时钟前调约 " + delta + " 毫秒";
        if (delta < 0) return "时钟回拨约 " + Math.abs(delta) + " 毫秒";
        return "记录的时钟值未变化";
    }

    private static String localizeChangeCause(String cause) {
        String lower = cause.toLowerCase(Locale.ROOT);
        if (lower.contains("good gnss suggestion")) return "系统采纳了有效 GNSS 时间建议";
        if (lower.contains("good telephony suggestion")) return "系统采纳了有效基站 NITZ 时间建议";
        if (lower.contains("good network suggestion")) return "系统采纳了有效网络 NTP 时间建议";
        return cause;
    }

    private static String sourceName(String source) {
        if ("gnss".equalsIgnoreCase(source)) return "GNSS 卫星";
        if ("telephony".equalsIgnoreCase(source)) return "基站 NITZ";
        if ("network".equalsIgnoreCase(source)) return "网络 NTP";
        return source;
    }

    private static String extractTimeDetectorExcerpt(String dump) {
        if (dump == null || dump.trim().isEmpty()) return "无 time_detector 输出";
        StringBuilder out = new StringBuilder();
        boolean keep = false;
        for (String line : dump.split("\\r?\\n")) {
            if (line.contains("Time change log:")) keep = true;
            if (keep) out.append(line).append('\n');
            if (keep && line.startsWith("External suggestion history:")) break;
            if (out.length() > 12_000) {
                out.append("\n[日志摘录已截断]\n");
                break;
            }
        }
        return out.length() == 0 ? limit(dump, 12_000) : out.toString();
    }

    private static long parseLong(Pattern pattern, String text, long fallback) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) return fallback;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongValue(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String parseString(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static String formatAge(long ageMillis) {
        if (ageMillis < 0) return "参考时间无效";
        long seconds = ageMillis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + "小时" + minutes + "分";
        if (minutes > 0) return minutes + "分" + remainder + "秒";
        return remainder + "秒";
    }

    private static String sourceTimeName(String source) {
        if ("GNSS 卫星".equals(source)) return "卫星时间";
        if ("基站 NITZ".equals(source)) return "基站时间";
        if ("网络 NTP".equals(source)) return "网络时间";
        return source + "时间";
    }

    private static String phoneRelationLabel(String source, long phoneOffsetMillis) {
        String sourceTime = sourceTimeName(source);
        if (phoneOffsetMillis > 0) return "本机落后" + sourceTime;
        if (phoneOffsetMillis < 0) return "本机领先" + sourceTime;
        return "本机与" + sourceTime + "基本一致";
    }

    private static String phoneRelativePhrase(String source, long phoneOffsetMillis) {
        String sourceTime = sourceTimeName(source);
        long magnitude = Math.abs(phoneOffsetMillis);
        if (phoneOffsetMillis > 0) return "本机落后" + sourceTime + "约 " + magnitude + " 毫秒";
        if (phoneOffsetMillis < 0) return "本机领先" + sourceTime + "约 " + magnitude + " 毫秒";
        return "本机与" + sourceTime + "基本一致（约 0 毫秒差）";
    }

    private static String clockAdjustmentDescription(long offsetMillis) {
        long magnitude = Math.abs(offsetMillis);
        if (offsetMillis > 0) return "手机时钟前调约 " + magnitude + " 毫秒";
        if (offsetMillis < 0) return "手机时钟回拨约 " + magnitude + " 毫秒";
        return "预计无需跳变（差约 0 毫秒）";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value == null ? "" : value;
        return value.substring(0, maxChars) + "…";
    }

    private String loadingHtml() {
        return "<html><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<body style='font:16px sans-serif;background:radial-gradient(ellipse at 10% 0%,#bfeaff,transparent 45%),linear-gradient(145deg,#eaf7ff,#eff8f5);padding:24px;color:#24445f'>"
            + "正在读取授时状态…<div style='font-size:12px;color:#6b8498;margin-top:18px'>作者：酷安 R1263599071</div></body></html>";
    }

    private void showHtml(String html) {
        if (webView != null) webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(periodicRefresh);
        worker.shutdownNow();
        forceWorker.shutdown();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private static final class ClockSetEvent {
        final String origin;
        final long elapsedRealtimeMillis;

        ClockSetEvent(String origin, long elapsedRealtimeMillis) {
            this.origin = origin;
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        }
    }

    private static final class ClockActionEvent {
        final String origin;
        final long elapsedRealtimeMillis;
        final boolean clockChanged;
        final long oldClockMillis;
        final long newClockMillis;
        final String cause;

        ClockActionEvent(String origin, long elapsedRealtimeMillis, boolean clockChanged,
                long oldClockMillis, long newClockMillis, String cause) {
            this.origin = origin;
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            this.clockChanged = clockChanged;
            this.oldClockMillis = oldClockMillis;
            this.newClockMillis = newClockMillis;
            this.cause = cause;
        }
    }

    private static final class Sample {
        final long elapsedRealtimeMillis;
        final long unixEpochTimeMillis;

        Sample(long elapsedRealtimeMillis, long unixEpochTimeMillis) {
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            this.unixEpochTimeMillis = unixEpochTimeMillis;
        }
    }

    /** One source's median-resolved sample for display. */
    private static final class ResolvedSample {
        final Sample sample;
        final int sampleCount;
        final boolean droppedOutlier;
        final long medianProjectedMillis;

        ResolvedSample(Sample sample, int sampleCount, boolean droppedOutlier,
                long medianProjectedMillis) {
            this.sample = sample;
            this.sampleCount = sampleCount;
            this.droppedOutlier = droppedOutlier;
            this.medianProjectedMillis = medianProjectedMillis;
        }
    }

    private static final class TrendPoint {
        final long elapsedRealtimeMillis;
        final long offsetMillis;

        TrendPoint(long elapsedRealtimeMillis, long offsetMillis) {
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            this.offsetMillis = offsetMillis;
        }
    }

    private static final class ForceOption {
        final String source;
        final long targetUnixMillis;
        final long offsetMillis;
        final long ageMillis;
        final long uncertaintyMillis;
        final long diagnosticElapsedMillis;

        ForceOption(String source, long targetUnixMillis, long offsetMillis, long ageMillis,
                long uncertaintyMillis, long diagnosticElapsedMillis) {
            this.source = source;
            this.targetUnixMillis = targetUnixMillis;
            this.offsetMillis = offsetMillis;
            this.ageMillis = ageMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.diagnosticElapsedMillis = diagnosticElapsedMillis;
        }
    }
}
