package com.example.nitzpriority;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
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
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
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
    private static final String CALIBRATION_MARKER = "__NITZPRIORITY_CALIBRATION__";
    private static final String HOOK_ACTIVE_MARKER = "__NITZPRIORITY_HOOK_ACTIVE__";
    private static final String GNSS_SERVICE_MARKER = "__NITZPRIORITY_GNSS_SERVICE__";
    private static final String NTP_SWITCH_MARKER = "__NITZPRIORITY_NTP_SWITCH__";
    private static final String NTP_STATUS_MARKER = "__NITZPRIORITY_NTP_STATUS__";
    private static final String NTP_SERVER_MARKER = "__NITZPRIORITY_NTP_SERVER__";
    private static final String NTP_DUMP_MARKER = "__NITZPRIORITY_NTP_DUMP__";
    // SharedPreferences key (in "settings") for the system-NTP switch.
    // Default OFF; the module reads debug.nitz.ntp_ntsc_enabled instead,
    // this pref only remembers what the user last chose.
    private static final String NTP_NTSC_ENABLED_PREF = "ntp_ntsc_enabled";
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
    // Calibrated NITZ starts at +/-200 ms instead of the raw +/-1500 ms: the
    // learned per-carrier bias removes the fixed part of the error, but
    // delivery jitter and staleness remain, so it never drops to 10 ms.
    private static final long NITZ_BASE_UNCERTAINTY_MILLIS = 1_500L;
    private static final long NITZ_CALIBRATED_BASE_UNCERTAINTY_MILLIS = 200L;
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
    // A "Set system clock confidence" line still carries the clock values read
    // just before and just after the set. On stock AOSP the confidence path
    // does not move the clock, so the two readings differ by at most the
    // execution gap between them (0-1 ms); on this build the confidence path
    // applies the correction as well (boot NITZ, 2026-11-12 22:25:21:
    // actual 1794493521075 -> new 1794493521292, +217 ms; an earlier boot:
    // +331 ms). Anything above this tolerance is a genuine clock step, not
    // read noise. Genuine corrections are at least the system threshold
    // (100 ms), so 10 ms sits an order of magnitude above the noise and
    // below any real step. The log line carries a single elapsed value for
    // both readings, so there is no separate elapsed delta to deduct; the
    // tolerance is the deduction.
    private static final long CLOCK_CHANGE_TOLERANCE_MILLIS = 10L;
    // Latest system-NTP server actually used, from the network suggestion
    // history in dumpsys time_detector: each entry carries
    // mNtpServerSocketAddress=<host>/<ip>:<port> after its UnixEpochTime.
    // The section is sliced first so a telephony elapsed value cannot pair
    // with a network address.
    private static final Pattern NTP_SERVER_ADDRESS_PATTERN = Pattern.compile(
        "mNtpServerSocketAddress=([A-Za-z0-9.\\-]+)(?::\\d+)?");
    private static final Pattern NTP_HISTORY_ELAPSED_PATTERN = Pattern.compile(
        "mElapsedRealtimeMillis=(\\d+)");
    // From `cmd network_time_update_service dump`: the server that actually
    // answered last, and the server list the system is configured with.
    private static final Pattern NTP_LAST_SUCCESS_PATTERN = Pattern.compile(
        "mLastSuccessfulNtpServerUri=([^\\s]+)");
    private static final Pattern NTP_CONFIG_LIST_PATTERN = Pattern.compile(
        "mServerUris=\\[([^\\]]*)\\]");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService forceWorker = Executors.newSingleThreadExecutor();
    private WebView webView;
    private Button refreshButton;
    private Button copyLogButton;
    private ProgressBar refreshProgress;
    private String lastShownHtml;
    private int pendingScrollY = -1;
    private Button forceButton;
    private Button clearHistoryButton;
    private Button applyThresholdButton;
    private EditText thresholdInput;
    private Switch ntpSwitch;
    // True while the switch is being set programmatically (refresh sync or
    // apply result): the listener must not re-run applyNtpSwitch then.
    private boolean ntpSwitchSyncing;
    // Latest refresh snapshot, kept so "copy diagnostic log" reuses the data
    // already on screen instead of running dumpsys a second time. Written on
    // the refresh worker thread, read on the UI thread.
    private volatile DiagnosticSnapshot lastDiagnosticSnapshot;
    // A copy/share tapped before the first refresh landed: run it when the
    // refresh that is already in flight (or just started) completes.
    private volatile boolean pendingCopyAfterRefresh;
    private volatile boolean pendingShareAfterRefresh;
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
        // One-tap diagnostic log: copies the full text log to the clipboard.
        // Long-press shares it instead (ACTION_SEND plain text).
        copyLogButton = new Button(this);
        copyLogButton.setText("复制日志");
        styleButton(copyLogButton, 0xFFE5EFF7, 0xFF365C78);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(8);
        actions.addView(copyLogButton, copyParams);
        Button calibrateButton = new Button(this);
        calibrateButton.setText("立即校准");
        styleButton(calibrateButton, 0xFF287FDB, Color.WHITE);
        LinearLayout.LayoutParams calibrateParams = new LinearLayout.LayoutParams(0, -2, 1);
        calibrateParams.leftMargin = dp(8);
        actions.addView(calibrateButton, calibrateParams);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        // Thin indeterminate bar shown while a refresh is running; the old page
        // stays visible underneath instead of flashing white.
        refreshProgress = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        refreshProgress.setIndeterminate(true);
        refreshProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams =
            new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.setMargins(dp(12), 0, dp(12), 0);
        root.addView(refreshProgress, progressParams);

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
        thresholdLabel.setText("自动改时门槛（全局回落值）");
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
        thresholdInput.setHint("100–2000");
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
        thresholdHint.setText("单位：毫秒（100–2000）。这里改的是全局回落值，仅用于未知来源与不新鲜的 GNSS 建议；GNSS 20ms、网络 50ms、基站 2000ms 各有自己的门槛。门槛只决定系统是否跳时，不会提高授时样本本身的精度。");
        thresholdHint.setTextSize(12);
        thresholdHint.setTextColor(Color.rgb(104, 117, 138));
        thresholdHint.setPadding(dp(4), dp(4), dp(4), dp(2));
        thresholdCard.addView(thresholdHint, new LinearLayout.LayoutParams(-1, -2));

        // Periodic sync switch: every 5 minutes (15 on low battery) while the
        // screen is on, refresh the NTSC reference and request one GNSS sample,
        // so the clock does not drift unchecked between manual refreshes.
        // The tick itself runs in FloatingClockService, so background sync only
        // happens while the floating clock is enabled; opening this page already
        // samples about once a minute through its own 60 s refresh.
        LinearLayout syncCard = new LinearLayout(this);
        syncCard.setOrientation(LinearLayout.VERTICAL);
        syncCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        syncCard.setBackground(glassCardBackground());
        syncCard.setElevation(dp(3));
        LinearLayout.LayoutParams syncCardParams = new LinearLayout.LayoutParams(-1, -2);
        syncCardParams.setMargins(dp(12), dp(8), dp(12), 0);
        root.addView(syncCard, syncCardParams);
        Switch syncSwitch = new Switch(this);
        syncSwitch.setText("定期同步（每 5 分钟）");
        syncSwitch.setTextSize(14);
        syncSwitch.setTypeface(null, android.graphics.Typeface.BOLD);
        syncSwitch.setTextColor(Color.rgb(37, 48, 66));
        syncSwitch.setChecked(getSharedPreferences(ClockDriftEstimator.PREFS_NAME,
            MODE_PRIVATE).getBoolean(ClockDriftEstimator.KEY_PERIODIC_SYNC_ENABLED, true));
        syncCard.addView(syncSwitch, new LinearLayout.LayoutParams(-1, -2));
        TextView syncHint = new TextView(this);
        syncHint.setText("亮屏时每 5 分钟查一次授时中心 NTP 并请求一次 GNSS 样本；低电量时降为 15 分钟。仅在悬浮时钟开启时后台运行。漂移估计随样本过期失效，关掉后不再定期同步。");
        syncHint.setTextSize(12);
        syncHint.setTextColor(Color.rgb(104, 117, 138));
        syncHint.setPadding(dp(4), dp(4), dp(4), dp(2));
        syncCard.addView(syncHint, new LinearLayout.LayoutParams(-1, -2));
        syncSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            getSharedPreferences(ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(ClockDriftEstimator.KEY_PERIODIC_SYNC_ENABLED, checked).apply();
            Toast.makeText(this, checked ? "定期同步已开启" : "定期同步已关闭",
                Toast.LENGTH_SHORT).show();
        });

        // System-NTP server switch: when on, the module makes the system
        // network time service try ntp.ntsc.ac.cn first and fall back to
        // time.android.com (the framework's own multi-server list, tried in
        // order in the same poll). Default OFF. The system only picks the
        // new server up at its next NTP poll (about every 18 h, or on a
        // network change), so flipping the switch needs no reboot but also
        // has no instant effect; the NTSC card below shows the server the
        // system actually hit, so a non-working hook is visible instead of
        // being claimed as success.
        LinearLayout ntpCard = new LinearLayout(this);
        ntpCard.setOrientation(LinearLayout.VERTICAL);
        ntpCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        ntpCard.setBackground(glassCardBackground());
        ntpCard.setElevation(dp(3));
        LinearLayout.LayoutParams ntpCardParams = new LinearLayout.LayoutParams(-1, -2);
        ntpCardParams.setMargins(dp(12), dp(8), dp(12), 0);
        root.addView(ntpCard, ntpCardParams);
        ntpSwitch = new Switch(this);
        ntpSwitch.setText("系统 NTP 走授时中心");
        ntpSwitch.setTextSize(14);
        ntpSwitch.setTypeface(null, android.graphics.Typeface.BOLD);
        ntpSwitch.setTextColor(Color.rgb(37, 48, 66));
        ntpSwitch.setChecked(getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean(NTP_NTSC_ENABLED_PREF, false));
        ntpCard.addView(ntpSwitch, new LinearLayout.LayoutParams(-1, -2));
        TextView ntpHint = new TextView(this);
        ntpHint.setText("开启后，系统自动授时（网络 NTP）优先用国家授时中心 ntp.ntsc.ac.cn，失败时回落到 time.android.com；关闭后下次轮询恢复设备默认服务器。需 Root 与 LSPosed 模块已启用；生效在下次系统轮询，切换飞行模式或重连网络可触发轮询。");
        ntpHint.setTextSize(12);
        ntpHint.setTextColor(Color.rgb(104, 117, 138));
        ntpHint.setPadding(dp(4), dp(4), dp(4), dp(2));
        ntpCard.addView(ntpHint, new LinearLayout.LayoutParams(-1, -2));
        ntpSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (ntpSwitchSyncing) return;
            applyNtpSwitch(checked);
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        // No JS is involved: this only restores the scroll position saved before a
        // periodic reload, so the page does not jump back to the top every 60 s.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingScrollY >= 0) {
                    int y = pendingScrollY;
                    pendingScrollY = -1;
                    view.scrollTo(0, y);
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        installSafeAreaInsets(root);

        refreshButton.setOnClickListener(view -> refresh(true));
        copyLogButton.setOnClickListener(view -> copyDiagnosticLog());
        copyLogButton.setOnLongClickListener(view -> {
            shareDiagnosticLog();
            return true;
        });
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
            Toast.makeText(this, "请输入 100–2000 之间的毫秒数", Toast.LENGTH_LONG).show();
            return;
        }
        if (threshold < NitzPriorityHook.MIN_CLOCK_UPDATE_THRESHOLD_MILLIS
                || threshold > NitzPriorityHook.MAX_CLOCK_UPDATE_THRESHOLD_MILLIS) {
            Toast.makeText(this, "门槛范围为 100–2000 毫秒", Toast.LENGTH_LONG).show();
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
                        ? "全局回落门槛已设为 " + threshold + " ms；GNSS 20ms / 网络 50ms / 基站 2000ms 不变，system_server 将在后续授时决策时应用"
                        : "设置失败，请检查 Root 授权与系统属性权限",
                    Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    // Writes the system-NTP switch to debug.nitz.ntp_ntsc_enabled via root
    // (same pattern as the threshold setting: setprop + read-back verify).
    // The module reads the property fresh on every system NTP refresh, so
    // no reboot is needed once the module itself is active; without the
    // module the property is simply inert. A failed write reverts the
    // switch to the last saved choice instead of leaving it showing a state
    // the system does not have.
    private void applyNtpSwitch(boolean checked) {
        if (!currentDiagnosticsHasRoot) {
            Toast.makeText(this, "请先授予 Root 并刷新诊断", Toast.LENGTH_LONG).show();
            setNtpSwitchChecked(!checked);
            return;
        }
        ntpSwitch.setEnabled(false);
        final String value = checked ? "1" : "0";
        final String command = "setprop " + NitzPriorityHook.NTP_SWITCH_PROPERTY + " "
            + value + " && test \"$(getprop "
            + NitzPriorityHook.NTP_SWITCH_PROPERTY + ")\" = \"" + value + "\"";
        worker.execute(() -> {
            RootCommand.Result result = RootCommand.run(command);
            boolean success = result.exitCode == 0;
            if (success) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean(NTP_NTSC_ENABLED_PREF, checked).apply();
            }
            runOnUiThread(() -> {
                ntpSwitch.setEnabled(currentDiagnosticsHasRoot);
                setNtpSwitchChecked(success ? checked
                    : getSharedPreferences("settings", MODE_PRIVATE)
                        .getBoolean(NTP_NTSC_ENABLED_PREF, false));
                Toast.makeText(this, success
                        ? (checked
                            ? "系统 NTP 已改为优先授时中心（ntp.ntsc.ac.cn，失败回落 time.android.com）；下次系统 NTP 轮询时生效，切换飞行模式或重连网络可触发轮询"
                            : "系统 NTP 已恢复设备默认服务器；下次系统 NTP 轮询时生效")
                        : "设置失败，请检查 Root 授权与系统属性权限",
                    Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    // Programmatic switch change that must not re-trigger applyNtpSwitch.
    private void setNtpSwitchChecked(boolean checked) {
        if (ntpSwitch == null) return;
        ntpSwitchSyncing = true;
        try {
            ntpSwitch.setChecked(checked);
        } finally {
            ntpSwitchSyncing = false;
        }
    }

    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean forceNtsc) {
        if (refreshInProgress || worker.isShutdown()) return;
        refreshInProgress = true;
        refreshButton.setEnabled(false);
        setRefreshing(true);
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
                + NitzPriorityHook.THRESHOLD_CONFIG_PROPERTY + "; echo "
                + CALIBRATION_MARKER + "; getprop "
                + NitzCalibrator.CALIBRATION_STATUS_PROPERTY + "; echo "
                + HOOK_ACTIVE_MARKER + "; getprop "
                + NitzPriorityHook.HOOK_ACTIVE_PROPERTY + "; echo "
                + GNSS_SERVICE_MARKER + "; getprop "
                + NitzPriorityHook.GNSS_SERVICE_PROPERTY + "; echo "
                + NTP_SWITCH_MARKER + "; getprop "
                + NitzPriorityHook.NTP_SWITCH_PROPERTY + "; echo "
                + NTP_STATUS_MARKER + "; getprop "
                + NitzPriorityHook.NTP_STATUS_PROPERTY + "; echo "
                + NTP_SERVER_MARKER + "; getprop "
                + NitzPriorityHook.NTP_SERVER_PROPERTY + "; echo "
                + NTP_DUMP_MARKER + "; cmd network_time_update_service dump";
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
            String[] calibrationParts = configParts.length > 1
                ? configParts[1].split(Pattern.quote(CALIBRATION_MARKER), 2)
                : new String[0];
            String thresholdConfig = calibrationParts.length > 0
                ? calibrationParts[0].trim() : "";
            // The hook-active and GNSS-service properties are read after the
            // calibration property, in the same root command. Empty means the
            // hook never wrote them (module not loaded in system_server, or
            // an older module build without this self-check). The NTP markers
            // follow the GNSS-service value in the same shell output, in the
            // same order the command above emits them.
            String[] hookParts = calibrationParts.length > 1
                ? calibrationParts[1].split(Pattern.quote(HOOK_ACTIVE_MARKER), 2)
                : new String[0];
            String nitzCalibrationStatus = hookParts.length > 0
                ? hookParts[0].trim() : "";
            String[] gnssServiceParts = hookParts.length > 1
                ? hookParts[1].split(Pattern.quote(GNSS_SERVICE_MARKER), 2)
                : new String[0];
            String hookActive = gnssServiceParts.length > 0
                ? gnssServiceParts[0].trim() : "";
            String[] ntpSwitchParts = gnssServiceParts.length > 1
                ? gnssServiceParts[1].split(Pattern.quote(NTP_SWITCH_MARKER), 2)
                : new String[0];
            String gnssService = ntpSwitchParts.length > 0
                ? ntpSwitchParts[0].trim() : "";
            String[] ntpStatusParts = ntpSwitchParts.length > 1
                ? ntpSwitchParts[1].split(Pattern.quote(NTP_STATUS_MARKER), 2)
                : new String[0];
            String ntpSwitch = ntpStatusParts.length > 0
                ? ntpStatusParts[0].trim() : "";
            String[] ntpServerParts = ntpStatusParts.length > 1
                ? ntpServerParts[1].split(Pattern.quote(NTP_SERVER_MARKER), 2)
                : new String[0];
            String ntpStatus = ntpServerParts.length > 0
                ? ntpServerParts[0].trim() : "";
            String[] ntpDumpParts = ntpServerParts.length > 1
                ? ntpServerParts[1].split(Pattern.quote(NTP_DUMP_MARKER), 2)
                : new String[0];
            String ntpServer = ntpDumpParts.length > 0
                ? ntpDumpParts[0].trim() : "";
            // The cmd dump is the tail of the output; empty when the
            // service does not exist (cmd prints "Can't find service" to
            // stderr, which the root helper merges into this output).
            String ntpDump = ntpDumpParts.length > 1 ? ntpDumpParts[1] : "";
            boolean hasRoot = result.exitCode == 0 && identity.contains("uid=0");
            // Drift samples come from the same refresh that draws the page:
            // the fresh GNSS trend point and the independent NTSC result are
            // both (reference - phone) offsets at an elapsed instant. The
            // estimator only displays drift and triggers an early sync; it
            // never steps the clock on a prediction.
            android.content.SharedPreferences settingsPrefs = getSharedPreferences(
                ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE);
            TrendPoint newTrendPoint = recordGnssTrendPoint(timeDump);
            long trendNowElapsed = parseLong(ELAPSED_PATTERN, timeDump, -1);
            if (newTrendPoint != null && trendNowElapsed >= 0) {
                long gnssAge = Math.max(0L,
                    trendNowElapsed - newTrendPoint.elapsedRealtimeMillis);
                ClockDriftEstimator.recordSample(settingsPrefs,
                    newTrendPoint.elapsedRealtimeMillis, newTrendPoint.offsetMillis,
                    uncertaintyFor("GNSS 卫星", gnssAge, -1),
                    ClockDriftEstimator.SOURCE_GNSS);
            }
            List<TrendPoint> trendPoints = loadGnssTrendPoints(trendNowElapsed);
            // Independent NTSC reference: plain UDP NTP on this worker thread,
            // never on the main thread. Does not need root and does not touch
            // the system clock, the detector, or the force-jump flow.
            NtscNtpClient.Result ntscResult = NtscNtpClient.query(forceNtsc);
            if (ntscResult != null && ntscResult.success) {
                ClockDriftEstimator.recordSample(settingsPrefs,
                    ntscResult.queryElapsedRealtimeMillis, ntscResult.offsetMillis,
                    ntscResult.uncertaintyMillis, ClockDriftEstimator.SOURCE_NTP);
            }
            // Keep this refresh for the copy-log button: it reuses exactly
            // these dumps and results instead of running dumpsys a second time.
            // The raw GnssClock sample is read here too, so the page and the
            // log show the same raw reading from this refresh.
            GnssLocationTrackingService.RawGnssSample rawGnss =
                GnssLocationTrackingService.readRawSample(this);
            SystemNtpInfo systemNtp = new SystemNtpInfo(
                ntpSwitch, ntpStatus, ntpServer, ntpDump);
            lastDiagnosticSnapshot = new DiagnosticSnapshot(hasRoot, identity,
                timeDump, gnssDump, thresholdStatus, thresholdConfig,
                nitzCalibrationStatus, calibrationState, calibrationDetail,
                calibrationTime, calibrationStartElapsed, forceState, forceDetail,
                forceTime, trendPoints, ntscResult, rawGnss, hookActive, gnssService,
                systemNtp,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
            String html = buildHtml(hasRoot, identity, timeDump, gnssDump,
                calibrationState, calibrationDetail, calibrationTime, calibrationStartElapsed,
                forceState, forceDetail, forceTime, thresholdStatus, thresholdConfig,
                nitzCalibrationStatus, trendPoints, ntscResult, rawGnss,
                hookActive, gnssService, systemNtp);
            runOnUiThread(() -> {
                refreshInProgress = false;
                setRefreshing(false);
                refreshButton.setEnabled(true);
                forceButton.setEnabled(currentDiagnosticsHasRoot && currentForceOptions.length > 0);
                applyThresholdButton.setEnabled(currentDiagnosticsHasRoot);
                if (hasRoot && !thresholdInput.hasFocus()) {
                    long displayedSetting = parseLongValue(thresholdConfig,
                        NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);
                    thresholdInput.setText(Long.toString(displayedSetting));
                }
                // The switch follows the property the module actually sees,
                // not just the saved pref: if the property was changed
                // outside the app, the switch shows the real state.
                if (hasRoot) {
                    setNtpSwitchChecked("1".equals(ntpSwitch));
                }
                showHtml(html);
                if (pendingCopyAfterRefresh) {
                    pendingCopyAfterRefresh = false;
                    copyDiagnosticLog();
                } else if (pendingShareAfterRefresh) {
                    pendingShareAfterRefresh = false;
                    shareDiagnosticLog();
                }
            });
        });
    }

    /** Thin indeterminate bar under the action row while a refresh is running. */
    private void setRefreshing(boolean refreshing) {
        if (refreshProgress != null) {
            refreshProgress.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        }
    }

    // ---- One-tap diagnostic log (copy to clipboard, long-press to share) ----
    //
    // The log is built from the latest refresh snapshot only: no second
    // dumpsys run, no second NTSC query. Ages and offsets are computed
    // against the snapshot's own collection instant, so the numbers in the
    // log are exactly the numbers that were on screen.

    private void copyDiagnosticLog() {
        DiagnosticSnapshot snapshot = lastDiagnosticSnapshot;
        if (snapshot == null) {
            // Entered the page and the first refresh has not landed yet:
            // refresh once, then copy when it completes. Never build a
            // log from empty data. refresh() is a no-op while one is
            // already running, so this is safe to call unconditionally.
            pendingCopyAfterRefresh = true;
            Toast.makeText(this, "正在刷新诊断，完成后自动复制",
                Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        String log = buildDiagnosticLog(snapshot);
        if (copyToClipboard(log)) {
            Toast.makeText(this, "已复制诊断日志，去粘贴发给我就行",
                Toast.LENGTH_LONG).show();
        } else {
            // Clipboard unavailable on this device (rare): fall back to
            // the share sheet instead of silently dropping the log.
            Toast.makeText(this, "剪贴板不可用，已打开分享",
                Toast.LENGTH_LONG).show();
            shareDiagnosticLog(log);
        }
    }

    private void shareDiagnosticLog() {
        DiagnosticSnapshot snapshot = lastDiagnosticSnapshot;
        if (snapshot == null) {
            pendingShareAfterRefresh = true;
            Toast.makeText(this, "正在刷新诊断，完成后自动打开分享",
                Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        shareDiagnosticLog(buildDiagnosticLog(snapshot));
    }

    private void shareDiagnosticLog(String log) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "卫星对时诊断日志");
        share.putExtra(Intent.EXTRA_TEXT, log);
        startActivity(Intent.createChooser(share, "分享诊断日志"));
    }

    /** Full text to the clipboard; false when the clipboard refuses it. */
    private boolean copyToClipboard(String text) {
        try {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return false;
            // The whole log, never silently truncated: dumpsys output is
            // already bounded by RootCommand's capture cap (~100 KB total).
            clipboard.setPrimaryClip(ClipData.newPlainText("卫星对时诊断日志", text));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static String formatWallTime(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timeMillis));
    }

    /**
     * Plain-text diagnostic log for copy/share. Sections, in order:
     * (1) header, (2) key status, (3) debug.nitz.* properties,
     * (4) NTSC result, (5) full dumpsys time_detector, (6) full dumpsys
     * gnss_time_update_service, (7) Time change log excerpt.
     *
     * <p>No passwords, tokens or keys are included anywhere in this log.
     * Deliberately NOT included: debug.nitz.force_time / force_result and
     * the GNSS trigger properties (debug.nitz.gnss*), because the force
     * result property carries the one-time force-jump token
     * ({@code <token>:OK/:FAIL}). Only the three properties the refresh
     * already reads (clock_threshold, clock_threshold_config, calibration,
     * hook_active, gnss_service) are reported here.
     */
    private String buildDiagnosticLog(DiagnosticSnapshot s) {
        StringBuilder log = new StringBuilder();
        log.append("卫星对时诊断日志\n");
        log.append("生成时间：").append(formatWallTime(System.currentTimeMillis()))
            .append("（elapsedRealtime=").append(SystemClock.elapsedRealtime())
            .append("ms）\n");
        log.append("诊断数据采集于：").append(formatWallTime(s.collectedWallMillis))
            .append("（elapsedRealtime=").append(s.collectedElapsedMillis).append("ms）\n");
        log.append("应用版本：").append(appVersion()).append("\n");
        log.append("本日志只含授时诊断数据，不含密码、口令或密钥。\n");

        long nowElapsed = s.collectedElapsedMillis;
        long systemUnix = parseLong(SYSTEM_TIME_PATTERN, s.timeDump, -1);
        String autoEnabled = parseString(AUTO_ENABLED_PATTERN, s.timeDump, "unknown");
        String priorities = parseString(PRIORITIES_PATTERN, s.timeDump, "unknown");
        long dumpedThreshold = parseLong(THRESHOLD_PATTERN, s.timeDump, 2_000);
        String[] thresholdStatusParts = s.thresholdStatus.split(":", 2);
        long configuredThreshold = parseLongValue(s.thresholdConfig,
            NitzPriorityHook.AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);
        configuredThreshold = Math.max(NitzPriorityHook.MIN_CLOCK_UPDATE_THRESHOLD_MILLIS,
            Math.min(NitzPriorityHook.MAX_CLOCK_UPDATE_THRESHOLD_MILLIS, configuredThreshold));
        long activeThreshold = thresholdStatusParts.length == 2
            ? parseLongValue(thresholdStatusParts[0], -1L) : -1L;
        boolean thresholdHookActive = activeThreshold == configuredThreshold
            && thresholdStatusParts.length == 2;
        long deviceOriginalThreshold = thresholdHookActive
            ? parseLongValue(thresholdStatusParts[1], dumpedThreshold) : dumpedThreshold;

        log.append("\n== 状态 ==\n");
        log.append("Root：").append(s.hasRoot ? "已授权" : "未授权").append("\n");
        if (!s.hasRoot) {
            log.append("检查结果：").append(s.identity.trim().isEmpty()
                ? "su 命令失败或未授权" : s.identity.trim()).append("\n");
        }
        log.append("自动授时：").append(autoEnabled).append("\n");
        log.append("优先级：").append(priorities).append("\n");
        log.append("自动改时门槛（分来源）：GNSS ")
            .append(NitzPriorityHook.GNSS_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append("ms / 网络 ")
            .append(NitzPriorityHook.NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append("ms / 基站 ")
            .append(NitzPriorityHook.TELEPHONY_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append("ms；全局回落值 ").append(configuredThreshold)
            .append("ms；模块已命中：").append(thresholdHookActive ? "是" : "否")
            .append("；dump 当前值 ").append(dumpedThreshold)
            .append("ms；设备原值 ").append(deviceOriginalThreshold).append("ms\n");
        log.append("模块生效状态：");
        if ("1".equals(s.hookActive)) {
            log.append("模块已生效");
            if ("absent".equals(s.gnssService)) {
                log.append("；系统 GNSS 服务在该 ROM 上不存在，系统建议不会来，原始时钟后备可用");
            }
            log.append("\n");
        } else {
            log.append("模块未生效（未检测到 debug.nitz.hook_active；请检查：已安装 LSPosed、模块已勾选、作用域 system_server、已重启）\n");
        }
        log.append("当前时钟：").append(systemUnix < 0 ? "未知"
            : formatWallTime(systemUnix) + "（" + systemUnix + "ms）").append("\n");

        Sample gnssService = findGnssServiceSample(s.gnssDump);
        appendLogSample(log, "GNSS 卫星", s.timeDump, "GnssTimeSuggestion", nowElapsed,
            systemUnix, -1, gnssService, null, false);
        NitzCalibrationDisplay nitzCalibration =
            parseNitzCalibrationStatus(s.nitzCalibrationStatus);
        boolean nitzCalibrated = nitzCalibration != null && nitzCalibration.calibrated;
        appendLogSample(log, "基站 NITZ", s.timeDump, "TelephonyTimeSuggestion",
            nowElapsed, systemUnix, -1, null,
            nitzCalibration == null ? "未发布校准状态"
                : nitzCalibration.displayText() + "（原始值：" + s.nitzCalibrationStatus + "）",
            nitzCalibrated);
        long networkUncertainty = findNetworkUncertainty(s.timeDump);
        appendLogSample(log, "网络 NTP", s.timeDump, "NetworkTimeSuggestion",
            nowElapsed, systemUnix, networkUncertainty, null, null, false);
        log.append("GNSS 服务样本：");
        if (gnssService == null) {
            log.append("暂无样本\n");
        } else {
            log.append("elapsed=").append(gnssService.elapsedRealtimeMillis)
                .append("，unix=").append(gnssService.unixEpochTimeMillis)
                .append("（").append(formatAge(
                    Math.max(0L, nowElapsed - gnssService.elapsedRealtimeMillis)))
                .append("前）\n");
        }
        log.append("原始 GNSS 时钟：");
        if (s.rawGnss == null || !s.rawGnss.available) {
            log.append("尚无原始测量记录\n");
        } else if (!s.rawGnss.utcAvailable || s.rawGnss.offsetMillis == Long.MIN_VALUE) {
            log.append("已有原始测量，无法换算 UTC 偏差");
            if (s.rawGnss.discardReason != null && !s.rawGnss.discardReason.isEmpty()) {
                log.append("（原因：").append(s.rawGnss.discardReason).append("）");
            }
            log.append("\n");
        } else if (s.rawGnss.ageMillis(nowElapsed) < 0 || systemUnix < 0) {
            log.append("样本参考时间无效\n");
        } else {
            long rawAge = s.rawGnss.ageMillis(nowElapsed);
            long rawOffset = s.rawGnss.gnssUtcMillis + rawAge - systemUnix;
            log.append("偏移=").append(rawOffset).append("ms（正值=本机落后），不确定度 ±")
                .append(s.rawGnss.uncertaintyMillis + driftBudgetMillis(rawAge))
                .append("ms（").append(s.rawGnss.uncertaintyEstimated
                    ? "芯片未上报有效值，按保守预算 ±"
                        + GnssLocationTrackingService.RAW_UNCERTAINTY_FALLBACK_MILLIS + " ms 计"
                    : "芯片上报")
                .append(" + 年龄漂移预算），样本 ")
                .append(formatAge(rawAge)).append("前");
            if (s.rawGnss.visibleSatellites >= 0 && s.rawGnss.usedSatellites >= 0
                    && s.rawGnss.satelliteStatusElapsedMillis > 0
                    && Math.abs(s.rawGnss.satelliteStatusElapsedMillis
                        - s.rawGnss.sampleElapsedMillis) <= 60_000L) {
                log.append("，可见 ").append(s.rawGnss.visibleSatellites)
                    .append(" 颗/参与定位 ").append(s.rawGnss.usedSatellites).append(" 颗");
            }
            log.append("（后备来源，不参与“已与卫星时间同步”判定）\n");
        }
        log.append("基站校准：")
            .append(nitzCalibration == null ? "未发布校准状态"
                : nitzCalibration.displayText())
            .append("\n");

        android.content.SharedPreferences settingsPrefs = getSharedPreferences(
            ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE);
        ClockDriftEstimator.Estimate estimate =
            ClockDriftEstimator.estimate(settingsPrefs, nowElapsed);
        log.append("漂移估计：");
        if (estimate == null) {
            ClockDriftEstimator.Summary summary =
                ClockDriftEstimator.summary(settingsPrefs, nowElapsed);
            if (summary.newestAgeMillis < 0) {
                log.append("暂无漂移样本\n");
            } else if (summary.newestAgeMillis > ClockDriftEstimator.MAX_SAMPLE_AGE_MILLIS) {
                log.append("已过期：最近样本在 ")
                    .append(formatAge(summary.newestAgeMillis)).append("前\n");
            } else {
                log.append("采集中：已收 ").append(summary.pointCount).append(" 个点，跨度 ")
                    .append(formatAge(summary.spanMillis)).append("\n");
            }
        } else {
            long predictedOffset =
                ClockDriftEstimator.projectedOffsetMillis(estimate, nowElapsed);
            long predictedError =
                ClockDriftEstimator.projectedErrorMillis(estimate, nowElapsed);
            log.append(String.format(Locale.US, "%+.1f", estimate.ppm)).append(" ppm（")
                .append(estimate.ppm > 0 ? "手机走慢" : estimate.ppm < 0 ? "手机走快" : "无方向")
                .append("），基于 ").append(estimate.pointCount).append(" 个点，跨度 ")
                .append(formatAge(estimate.spanMillis)).append("；按此推算当前误差约 ")
                .append(predictedOffset >= 0 ? "落后 " : "领先 ")
                .append(Math.abs(predictedOffset)).append(" 毫秒 ±")
                .append(predictedError).append(" 毫秒\n");
        }
        log.append("定期同步：")
            .append(ClockDriftEstimator.isPeriodicSyncEnabled(settingsPrefs)
                ? "开启（亮屏时每 5 分钟，低电量 15 分钟）" : "已关闭").append("\n");
        log.append("校准状态：").append(s.calibrationState)
            .append("（").append(s.calibrationDetail).append("）");
        if (s.calibrationTime > 0) {
            log.append("，时间：").append(formatWallTime(s.calibrationTime));
        }
        log.append("\n");
        log.append("强制跳改：").append(s.forceState)
            .append("（").append(s.forceDetail).append("）");
        if (s.forceTime > 0) {
            log.append("，时间：").append(formatWallTime(s.forceTime));
        }
        log.append("\n");
        log.append("GNSS 趋势点：")
            .append(s.trendPoints == null ? 0 : s.trendPoints.size()).append(" 个（最近 6 小时）\n");

        log.append("\n== 系统属性 debug.nitz.* ==\n");
        log.append("debug.nitz.hook_active=").append(s.hookActive).append("\n");
        log.append("debug.nitz.gnss_service=").append(s.gnssService).append("\n");
        log.append("debug.nitz.clock_threshold=").append(s.thresholdStatus).append("\n");
        log.append("debug.nitz.clock_threshold_config=").append(s.thresholdConfig).append("\n");
        log.append("debug.nitz.calibration=").append(s.nitzCalibrationStatus).append("\n");
        log.append("debug.nitz.ntp_ntsc_enabled=").append(s.systemNtp.switchEnabled).append("\n");
        log.append("debug.nitz.ntp_status=").append(s.systemNtp.status).append("\n");
        log.append("debug.nitz.ntp_server=").append(s.systemNtp.server).append("\n");
        log.append("（注：force_time / force_result 与 gnss 触发属性未列入，它们带有一次性跳改口令。）\n");

        log.append("\n== 国家授时中心 NTP 参考 ==\n");
        NtscNtpClient.Result ntsc = s.ntscResult;
        if (ntsc == null) {
            log.append("尚未查询授时中心时间。\n");
        } else if (!ntsc.success) {
            log.append("本次查询失败：").append(ntsc.error == null ? "未知原因" : ntsc.error)
                .append("\n");
            if (ntsc.lastGood != null) {
                log.append("上次成功结果（")
                    .append(formatAge(ntsc.lastGood.ageMillis(nowElapsed))).append("前，命中 ")
                    .append(ntsc.lastGood.server).append("）：偏移 ")
                    .append(ntsc.lastGood.offsetMillis).append("ms，不确定度 ±")
                    .append(ntsc.lastGood.uncertaintyMillis).append("ms\n");
            }
        } else {
            log.append("成功：命中 ").append(ntsc.server);
            if (ntsc.fallback) log.append("（已回落到 cn.pool.ntp.org，非授时中心直连）");
            if (ntsc.directIp) log.append("（授时中心 IP 直连）");
            log.append("\n偏移：").append(ntsc.offsetMillis)
                .append("ms（正值=本机落后），不确定度 ±").append(ntsc.uncertaintyMillis)
                .append("ms；往返延迟中位 ").append(ntsc.medianRttMillis)
                .append("ms；采样 ").append(ntsc.samplesAttempted).append(" 次，取 ")
                .append(ntsc.sampleCount).append(" 次中位数；样本 ")
                .append(formatAge(ntsc.ageMillis(nowElapsed))).append("前");
            if (ntsc.isExpired(nowElapsed)) log.append("（已超过 10 分钟）");
            log.append("\n");
        }

        log.append("\n== 系统 NTP 实际命中 ==\n");
        SystemNtpInfo ntp = s.systemNtp;
        log.append("开关：").append(ntp.switchOn() ? "已开启（优先授时中心）" : "未开启（设备默认服务器）").append("\n");
        log.append("模块 hook：");
        if (ntp.status.isEmpty()) {
            log.append("未确认（模块未启用或尚未发布状态）\n");
        } else {
            log.append(ntp.status).append("\n");
        }
        SystemNtpServer latestServer = findLatestSystemNtpServer(s.timeDump);
        if (latestServer == null) {
            log.append("最近一次系统 NTP 命中：尚无系统网络 NTP 建议记录\n");
        } else {
            log.append("最近一次系统 NTP 命中：").append(latestServer.host).append("（")
                .append(formatAge(Math.max(0L, nowElapsed - latestServer.elapsedRealtimeMillis)))
                .append("前）\n");
        }
        if (!ntp.server.isEmpty()) {
            log.append("模块报告上次命中：").append(ntp.server).append("\n");
        }
        String ntpLastSuccessful = parseNtpLastSuccessful(ntp.dump);
        if (!ntpLastSuccessful.isEmpty()) {
            log.append("network_time_update_service 上次命中：").append(ntpLastSuccessful).append("\n");
        }
        String ntpConfigured = parseNtpConfigList(ntp.dump);
        if (!ntpConfigured.isEmpty()) {
            log.append("系统 NTP 服务器列表：").append(ntpConfigured).append("\n");
        } else if (ntp.dump.contains("Can't find service")) {
            log.append("network_time_update_service 不存在：此系统未使用 AOSP 网络授时服务\n");
        }

        log.append("\n== dumpsys time_detector（全文） ==\n");
        log.append(s.timeDump == null || s.timeDump.isEmpty() ? "（无输出）\n" : s.timeDump);
        if (s.timeDump == null || !s.timeDump.endsWith("\n")) log.append("\n");
        log.append("\n== dumpsys gnss_time_update_service（全文） ==\n");
        log.append(s.gnssDump == null || s.gnssDump.isEmpty() ? "（无输出）\n" : s.gnssDump);
        if (s.gnssDump == null || !s.gnssDump.endsWith("\n")) log.append("\n");
        log.append("\n== cmd network_time_update_service dump（全文） ==\n");
        log.append(s.systemNtp.dump == null || s.systemNtp.dump.isEmpty() ? "（无输出）\n"
            : s.systemNtp.dump);
        if (s.systemNtp.dump == null || !s.systemNtp.dump.endsWith("\n")) log.append("\n");
        log.append("\n== Time change log（摘录） ==\n");
        log.append(extractTimeDetectorExcerpt(s.timeDump)).append("\n");
        return log.toString();
    }

    /** One source's latest sample line in the log, same parsers as the page. */
    private static void appendLogSample(StringBuilder log, String name, String timeDump,
            String className, long nowElapsed, long systemUnix, long reportedUncertainty,
            Sample gnssService, String extraNote, boolean nitzCalibrated) {
        ResolvedSample resolved = resolveSamples(timeDump, className, nowElapsed);
        Sample sample = resolved.sample;
        log.append(name).append("：");
        if (sample == null || nowElapsed < 0) {
            log.append("尚无时间建议记录\n");
            return;
        }
        long age = nowElapsed - sample.elapsedRealtimeMillis;
        if (age < 0 || systemUnix < 0) {
            log.append("样本参考时间无效（elapsed=")
                .append(sample.elapsedRealtimeMillis).append("，unix=")
                .append(sample.unixEpochTimeMillis).append("）\n");
            return;
        }
        long uncertainty;
        if ("基站 NITZ".equals(name)) {
            uncertainty = nitzUncertainty(age, nitzCalibrated);
        } else {
            uncertainty = uncertaintyFor(name, age, reportedUncertainty);
        }
        long projected = projectedMillis(sample, nowElapsed);
        log.append("elapsed=").append(sample.elapsedRealtimeMillis)
            .append("，unix=").append(sample.unixEpochTimeMillis)
            .append("，年龄=").append(formatAge(age))
            .append("，外推差值=").append(projected - systemUnix)
            .append("ms（正值=本机落后），不确定度 ±").append(uncertainty).append("ms");
        if (resolved.droppedOutlier) {
            log.append("（最新样本与中位数相差超过 2 秒，已按中位数显示）");
        }
        log.append("\n");
        if ("GNSS 卫星".equals(name)) {
            log.append("  GNSS 服务样本：")
                .append(gnssService == null ? "暂无样本"
                    : "最近样本 " + formatAge(
                        Math.max(0L, nowElapsed - gnssService.elapsedRealtimeMillis)) + "前")
                .append("\n");
        }
        if (extraNote != null) {
            log.append("  ").append(extraNote).append("\n");
        }
    }

    private String buildHtml(boolean hasRoot, String identity, String timeDump, String gnssDump,
            String calibrationState, String calibrationDetail, long calibrationTime,
            long calibrationStartElapsed,
            String forceState, String forceDetail, long forceTime, String thresholdStatus,
            String thresholdConfig, String nitzCalibrationStatus,
            List<TrendPoint> trendPoints,
            NtscNtpClient.Result ntscResult,
            GnssLocationTrackingService.RawGnssSample rawGnss,
            String hookActive, String gnssService,
            SystemNtpInfo systemNtp) {
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
            .append(".sync-title{font-size:18px;font-weight:800;display:flex;align-items:center;gap:8px}.sync-detail{font-size:13px;line-height:1.6;color:#536d83;margin-top:8px}")
            .append(".sync-title:before{content:'';width:10px;height:10px;border-radius:50%;background:currentColor;flex:none}")
            .append("@keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.45;transform:scale(1.35)}}")
            .append("@keyframes blink{0%,100%{opacity:1}50%{opacity:.25}}")
            .append(".sync-title.sync-good:before{animation:pulse 1.6s ease-in-out infinite}")
            .append(".sync-title.sync-warn:before{animation:blink 1.2s ease-in-out infinite}")
            .append(".card{transition:background-color .4s}.sync-title{transition:color .4s}.value{transition:color .4s}")
            .append("@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}")
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
            .append("details[open] summary ~ *{animation:fadeSlide .25s ease}")
            .append("@keyframes fadeSlide{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:translateY(0)}}")
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
                // The raw GnssClock reading needs location, not root, so it
                // is shown on the no-root page too.
                .append(rawGnssSourceCard(rawGnss, SystemClock.elapsedRealtime(),
                    System.currentTimeMillis()))
                .append(calibrationCard(calibrationState, calibrationDetail, calibrationTime))
                .append(forceCard(forceState, forceDetail, forceTime))
                .append(ntscTimeCard(ntscResult, null, timeDump,
                    SystemClock.elapsedRealtime()))
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
        // Raw GnssClock as a fallback force source. Same guardrails as the
        // system sources: the sample must be within 10 minutes, the jump is
        // capped at 60 s, and the one-time token / double confirmation /
        // post-jump clock re-check all apply unchanged. System suggestions
        // stay first in the list; the raw option only appears when a fresh,
        // valid raw measurement exists.
        if (rawGnss != null && rawGnss.utcAvailable
                && rawGnss.offsetMillis != Long.MIN_VALUE
                && rawGnss.sampleElapsedMillis >= 0 && rawGnss.gnssUtcMillis > 0) {
            addForceOption(options, "原始 GNSS 时钟",
                new Sample(rawGnss.sampleElapsedMillis, rawGnss.gnssUtcMillis),
                nowElapsed, systemUnix, rawGnss.uncertaintyMillis,
                rawGnss.uncertaintyEstimated);
        }
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
        // NITZ calibration state, published by the hook as debug.nitz.calibration:
        // "calibrated|<offset>|<samples>|<carrier>",
        // "uncalibrated|<samples>|<carrier>" while still learning, or the older
        // "uncalibrated|<carrier>" (no count). Empty means the hook has not
        // published anything yet (no NITZ sample seen since boot, or an older
        // module build without calibration).
        NitzCalibrationDisplay nitzCalibration =
            parseNitzCalibrationStatus(nitzCalibrationStatus);
        String nitzCalibrationNote = nitzCalibration == null ? null
            : nitzCalibration.displayText();
        if (nitzCalibrationNote != null) {
            telephonySlotNote = telephonySlotNote == null ? nitzCalibrationNote
                : telephonySlotNote + "；" + nitzCalibrationNote;
        }
        long gnssUncertainty = gnssDisplay != null && nowElapsed >= 0
            ? uncertaintyFor("GNSS 卫星",
                Math.max(0L, nowElapsed - gnssDisplay.elapsedRealtimeMillis), -1) : -1L;
        boolean nitzCalibrated = nitzCalibration != null && nitzCalibration.calibrated;
        long telephonyUncertainty = telephonyDisplay != null && nowElapsed >= 0
            ? nitzUncertainty(
                Math.max(0L, nowElapsed - telephonyDisplay.elapsedRealtimeMillis),
                nitzCalibrated) : -1L;
        long networkTotalUncertainty = networkDisplay != null && nowElapsed >= 0
            ? uncertaintyFor("网络 NTP",
                Math.max(0L, nowElapsed - networkDisplay.elapsedRealtimeMillis),
                networkUncertainty) : -1L;
        html.append("<div class='card root-ok'><div class='state'>Root 已授权 · 系统数据可读取</div></div>")
            .append(moduleStatusCard(hookActive, gnssService))
            .append(detectionWarningsCard(gnssResolved, telephonyResolved, networkResolved,
                gnssDisplay, networkDisplay, nowElapsed))
            .append(gnssSyncCard(gnssDisplay, nowElapsed, systemUnix, autoEnabled, priorities,
                timeDump, networkDisplay, calibrationState, calibrationStartElapsed))
            .append(gnssTrendCard(trendPoints, nowElapsed))
            .append(driftCard(nowElapsed))
            .append(beijingTimeCard(networkDisplay, networkUncertainty, nowElapsed, systemUnix))
            .append(ntscTimeCard(ntscResult, systemNtp, timeDump, nowElapsed))
            .append(calibrationCard(calibrationState, calibrationDetail, calibrationTime))
            .append(forceCard(forceState, forceDetail, forceTime));
        html.append("<div class='card'><div class='summary'>自动授时：<b>")
            .append(escape(autoEnabled)).append("</b>　优先级：<b>")
            .append(escape(priorities)).append("</b></div>")
            // Per-origin thresholds: a single number would lie now, because the
            // decision hook applies GNSS 20 ms / network 50 ms / telephony 2000 ms
            // and only falls back to the configured global value for unknown
            // origins and stale GNSS suggestions.
            .append("<div class='threshold'>自动改时门槛（分来源）：GNSS <b>")
            .append(NitzPriorityHook.GNSS_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append(" ms</b> · 网络 <b>")
            .append(NitzPriorityHook.NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append(" ms</b> · 基站 <b>")
            .append(NitzPriorityHook.TELEPHONY_CLOCK_UPDATE_THRESHOLD_MILLIS)
            .append(" ms</b>");
        if (thresholdHookActive) {
            html.append("（模块已命中；全局回落值 ").append(configuredThreshold)
                .append(" ms，设备原值 ").append(deviceOriginalThreshold).append(" ms）");
        } else {
            html.append("（模块设定值；system_server 生效待确认；dump 当前值 ")
                .append(dumpedThreshold).append(" ms）");
        }
        html.append(" · 当前时钟 ").append(systemUnix).append(" ms</div>")
            .append(sourceCard("GNSS 卫星", gnssDisplay, nowElapsed, systemUnix,
                NitzPriorityHook.GNSS_CLOCK_UPDATE_THRESHOLD_MILLIS,
                gnssService == null ? null : nowElapsed - gnssService.elapsedRealtimeMillis,
                gnssUncertainty, null))
            .append(sourceCard("基站 NITZ", telephonyDisplay, nowElapsed, systemUnix,
                NitzPriorityHook.TELEPHONY_CLOCK_UPDATE_THRESHOLD_MILLIS,
                null, telephonyUncertainty, telephonySlotNote))
            .append(sourceCard("网络 NTP", networkDisplay, nowElapsed, systemUnix,
                NitzPriorityHook.NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS,
                null, networkTotalUncertainty, null))
            .append(rawGnssSourceCard(rawGnss, nowElapsed, systemUnix));

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
     * Whether the LSPosed module is actually loaded in system_server, read
     * from the debug.nitz.hook_active property the hook writes as soon as
     * its entry runs. "1" means the hook is loaded; anything else (empty
     * included) means it never ran — LSPosed not installed, module not
     * checked, wrong scope, or no reboot yet — and the card says so
     * directly instead of leaving the user to infer it from "模块已命中：否".
     * When the hook is loaded but the GNSS service class does not exist on
     * this ROM (debug.nitz.gnss_service=absent), system GNSS suggestions
     * will never arrive and the raw GnssClock fallback is the way to get
     * GNSS time.
     */
    private static String moduleStatusCard(String hookActive, String gnssService) {
        if ("1".equals(hookActive)) {
            StringBuilder detail = new StringBuilder("LSPosed 模块已在 system_server 中加载，hook 正在工作。");
            if ("absent".equals(gnssService)) {
                detail.append("系统 GNSS 服务在该 ROM 上不存在（未找到 GnssTimeUpdateService 类），系统 GNSS 建议不会来；原始 GNSS 时钟后备可用。");
            }
            return "<div class='card'><div class='state good'>模块已生效</div><div class='sync-detail'>"
                + escape(detail.toString()) + "</div></div>";
        }
        return "<div class='card'><div class='state fail'>模块未生效</div><div class='sync-detail'>"
            + escape("没有检测到模块写入的状态属性（debug.nitz.hook_active）。请检查：已安装 LSPosed；在 LSPosed 里勾选本模块，作用域选择 system_server；然后重启手机。")
            + "</div></div>";
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
        String clockSetStatus = gnssClockSetStatus(timeDump, calibrationState,
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
    private static String ntscTimeCard(NtscNtpClient.Result result, SystemNtpInfo systemNtp,
            String timeDump, long nowElapsed) {
        StringBuilder html = new StringBuilder(
            "<div class='card'><div class='name'>国家授时中心 NTP 参考</div>")
            .append("<div class='meta'>独立 NTP 直查中国科学院国家授时中心（Stratum-1）；"
                + "只作显示，不参与自动改时与强制跳改。</div>")
            .append("<div class='divider'></div>");
        if (result == null) {
            html.append("<div class='action'>尚未查询授时中心时间，点“刷新”获取。</div>");
        } else if (!result.success) {
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
        } else {
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
            if (result.directIp) {
                html.append("（授时中心 IP 直连）");
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
        }
        html.append(systemNtpSection(systemNtp, timeDump, nowElapsed));
        return html.append("</div>").toString();
    }

    // System-NTP (automatic network time) subsection inside the NTSC card.
    // Shows the switch state, the hook state, and — the point of this
    // section — the server the system NTP actually hit, parsed from the
    // dumpsys network suggestion history (ground truth) and corroborated by
    // the hook's own last-hit property and the network_time_update_service
    // dump. The configured value is never shown as if it were the actual one.
    private static String systemNtpSection(SystemNtpInfo systemNtp, String timeDump,
            long nowElapsed) {
        StringBuilder html = new StringBuilder("<div class='divider'></div>")
            .append("<div class='name'>系统 NTP（自动授时）</div>");
        if (systemNtp == null) {
            return html.append("<div class='meta'>需 Root 才能查看系统 NTP 状态。</div>")
                .toString();
        }
        boolean switchOn = systemNtp.switchOn();
        html.append("<div class='sync-detail'>开关：")
            .append(switchOn ? "已开启（优先授时中心）" : "未开启（设备默认服务器）")
            .append("；模块 hook：");
        if (systemNtp.status.isEmpty()) {
            html.append("未确认（模块未启用或尚未发布状态）");
        } else if (systemNtp.status.startsWith("armed")) {
            html.append("已安装");
        } else if (systemNtp.status.startsWith("unavailable")) {
            html.append("未命中")
                .append(escape(systemNtp.status.substring("unavailable".length())));
        } else {
            html.append(escape(systemNtp.status));
        }
        html.append("。</div>");
        SystemNtpServer latest = findLatestSystemNtpServer(timeDump);
        if (latest == null) {
            html.append("<div class='sync-detail'>尚无系统网络 NTP 建议记录。</div>");
        } else {
            long age = Math.max(0L, nowElapsed - latest.elapsedRealtimeMillis);
            html.append("<div class='sync-detail'>最近一次系统 NTP 命中：")
                .append(escape(latest.host)).append("（")
                .append(escape(formatAge(age))).append("前）。</div>");
        }
        if (!systemNtp.server.isEmpty()) {
            html.append("<div class='sync-detail'>模块报告上次命中：")
                .append(escape(systemNtp.server)).append("。</div>");
        }
        String configured = parseNtpConfigList(systemNtp.dump);
        if (!configured.isEmpty()) {
            html.append("<div class='sync-detail'>系统 NTP 服务器列表：")
                .append(escape(configured)).append("。</div>");
        } else if (systemNtp.dump.contains("Can't find service")) {
            html.append("<div class='sync-detail'>network_time_update_service 不存在："
                + "此系统未使用 AOSP 网络授时服务，hook 无法生效。</div>");
        }
        html.append("<div class='sync-detail'>");
        if (!switchOn) {
            html.append("开关关闭，系统 NTP 使用设备默认服务器。");
        } else if (!systemNtp.status.isEmpty()
                && systemNtp.status.startsWith("unavailable")) {
            html.append("hook 未命中：此系统未使用 AOSP NTP 路径（或类名已改），开关无效。");
        } else if (latest != null
                && NitzPriorityHook.NTP_NTSC_HOST.equals(latest.host)) {
            html.append("系统 NTP 已命中授时中心。");
        } else if (latest != null) {
            html.append("开关已开，等待系统下次轮询（最近一次仍是 ")
                .append(escape(latest.host))
                .append("；切换飞行模式或重连网络可触发轮询）。");
        } else {
            html.append("开关已开，尚无系统 NTP 建议记录，等待下次轮询。");
        }
        html.append("</div>");
        html.append("<div class='small'>系统按框架自身的顺序尝试服务器列表，命中后会沿用该服务器直到其失败或重启；"
            + "授时中心不可达时同一次轮询内回落到 time.android.com。</div>");
        return html.toString();
    }

    // Latest server the system NTP actually used, from the network
    // suggestion history in dumpsys time_detector. Each entry has one
    // UnixEpochTime (mElapsedRealtimeMillis) and one mNtpServerSocketAddress;
    // entries are sliced apart so the two can never pair across entries.
    // Null when the history has no entry with a server address (older AOSP
    // does not print it, or no network suggestion yet).
    private static SystemNtpServer findLatestSystemNtpServer(String timeDump) {
        if (timeDump == null || timeDump.isEmpty()) return null;
        int sectionStart = timeDump.indexOf("Network suggestion history:");
        if (sectionStart < 0) return null;
        int sectionEnd = timeDump.indexOf("Gnss suggestion history:", sectionStart);
        String section = sectionEnd < 0 ? timeDump.substring(sectionStart)
            : timeDump.substring(sectionStart, sectionEnd);
        SystemNtpServer latest = null;
        for (String entry : section.split("NetworkTimeSuggestion\\{")) {
            Matcher elapsed = NTP_HISTORY_ELAPSED_PATTERN.matcher(entry);
            Matcher address = NTP_SERVER_ADDRESS_PATTERN.matcher(entry);
            if (!elapsed.find() || !address.find()) continue;
            long elapsedMillis;
            try {
                elapsedMillis = Long.parseLong(elapsed.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (latest == null || elapsedMillis > latest.elapsedRealtimeMillis) {
                latest = new SystemNtpServer(address.group(1), elapsedMillis);
            }
        }
        return latest;
    }

    // Host from `cmd network_time_update_service dump`'s
    // mLastSuccessfulNtpServerUri=ntp://<host> line ("" when absent).
    private static String parseNtpLastSuccessful(String ntpDump) {
        if (ntpDump == null || ntpDump.isEmpty()) return "";
        Matcher matcher = NTP_LAST_SUCCESS_PATTERN.matcher(ntpDump);
        String last = "";
        while (matcher.find()) last = matcher.group(1);
        if (last.startsWith("ntp://")) last = last.substring("ntp://".length());
        int slash = last.indexOf('/');
        if (slash >= 0) last = last.substring(0, slash);
        int colon = last.indexOf(':');
        if (colon >= 0) last = last.substring(0, colon);
        return last;
    }

    // Server list from `cmd network_time_update_service dump`'s
    // getNtpConfig()=NtpConnectionInfo{mServerUris=[...]} line ("" when
    // absent). Shows the list the system is actually configured with: the
    // hook's override list when the switch is on, the device default else.
    private static String parseNtpConfigList(String ntpDump) {
        if (ntpDump == null || ntpDump.isEmpty()) return "";
        Matcher matcher = NTP_CONFIG_LIST_PATTERN.matcher(ntpDump);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String gnssClockSetStatus(String timeDump,
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
        if ("SUCCESS_RAW".equals(calibrationState)) {
            return "未改时（本次以原始 GnssClock 偏移完成校准，仅测得手机与 GNSS 的偏差，未由系统实际改时）";
        }
        return "未发现本次操作后的 GNSS 实际改时记录";
    }

    private TrendPoint recordGnssTrendPoint(String timeDump) {
        long nowElapsed = parseLong(ELAPSED_PATTERN, timeDump, -1L);
        long systemUnix = parseLong(SYSTEM_TIME_PATTERN, timeDump, -1L);
        Sample gnss = findLatestSample(timeDump, "GnssTimeSuggestion");
        if (nowElapsed < 0 || systemUnix < 0 || gnss == null
                || nowElapsed < gnss.elapsedRealtimeMillis) return null;
        long age = nowElapsed - gnss.elapsedRealtimeMillis;
        if (age > GNSS_SYNC_MAX_SAMPLE_AGE_MILLIS) return null;
        long offset = gnss.unixEpochTimeMillis + age - systemUnix;

        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        List<TrendPoint> points = parseTrendPoints(prefs.getString(GNSS_TREND_PREF, ""));
        if (!points.isEmpty()) {
            long lastElapsed = points.get(points.size() - 1).elapsedRealtimeMillis;
            if (nowElapsed < lastElapsed) points.clear(); // elapsedRealtime reset after reboot.
            else if (nowElapsed - lastElapsed < GNSS_TREND_MIN_INTERVAL_MILLIS) return null;
        }
        TrendPoint point = new TrendPoint(nowElapsed, offset);
        points.add(point);
        pruneTrendPoints(points, nowElapsed);
        prefs.edit().putString(GNSS_TREND_PREF, serializeTrendPoints(points)).apply();
        return point;
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

    /**
     * Drift estimate card. Shows the fitted crystal drift in ppm and the
     * predicted current error, or why no fit exists yet. The prediction is
     * display only: nothing in this app steps the clock from a predicted
     * value (see ClockDriftEstimator).
     */
    private String driftCard(long nowElapsed) {
        android.content.SharedPreferences prefs = getSharedPreferences(
            ClockDriftEstimator.PREFS_NAME, MODE_PRIVATE);
        StringBuilder card = new StringBuilder("<div class='card'><div class='name'>时钟漂移估计</div>")
            .append("<div class='meta'>用最近 1 小时内的 GNSS / 授时中心偏移样本做线性回归；只用于显示与提前同步，不直接改时钟。</div>");
        boolean periodicOn = ClockDriftEstimator.isPeriodicSyncEnabled(prefs);
        card.append("<div class='small'>定期同步：")
            .append(periodicOn ? "开启（亮屏时每 5 分钟，低电量 15 分钟）" : "已关闭")
            .append("</div>");
        if (nowElapsed < 0) {
            return card.append("<div class='trend-empty'>暂无系统授时数据，无法估计漂移。</div></div>")
                .toString();
        }
        ClockDriftEstimator.Estimate estimate =
            ClockDriftEstimator.estimate(prefs, nowElapsed);
        if (estimate == null) {
            ClockDriftEstimator.Summary summary =
                ClockDriftEstimator.summary(prefs, nowElapsed);
            if (summary.newestAgeMillis < 0) {
                card.append("<div class='trend-empty'>暂无漂移样本；保持诊断页打开或开启定期同步，采集到 3 个点（跨度 10 分钟以上）后显示。</div>");
            } else if (summary.newestAgeMillis > ClockDriftEstimator.MAX_SAMPLE_AGE_MILLIS) {
                card.append("<div class='trend-empty'>漂移估计已过期：最近样本在 ")
                    .append(escape(formatAge(summary.newestAgeMillis)))
                    .append("前，超过 1 小时无新鲜样本。刷新一次或等定期同步采到新样本。</div>");
            } else {
                card.append("<div class='trend-empty'>采集中：已收 ")
                    .append(summary.pointCount).append(" 个点，跨度 ")
                    .append(escape(formatAge(summary.spanMillis)))
                    .append("；需要 3 个点且跨度 10 分钟以上。</div>");
            }
            return card.append("</div>").toString();
        }
        long predictedError = ClockDriftEstimator.projectedErrorMillis(estimate, nowElapsed);
        long predictedOffset = ClockDriftEstimator.projectedOffsetMillis(estimate, nowElapsed);
        String direction = estimate.ppm > 0 ? "（手机走慢）" : estimate.ppm < 0 ? "（手机走快）" : "";
        card.append("<div class='trend-latest'>漂移 ")
            .append(String.format(Locale.US, "%+.1f", estimate.ppm)).append(" ppm ")
            .append(escape(direction)).append("</div>")
            .append("<div class='sync-detail'>基于 ").append(estimate.pointCount)
            .append(" 个点，跨度 ").append(escape(formatAge(estimate.spanMillis)))
            .append("；按此推算，当前误差约 ")
            .append(predictedOffset >= 0 ? "落后 " : "领先 ")
            .append(Math.abs(predictedOffset)).append(" 毫秒 ±")
            .append(predictedError).append(" 毫秒。</div>")
            .append("<div class='small'>预测误差超过网络门槛（50ms）时，定期同步会提前触发一次；预测值本身不用于改时钟。</div>");
        return card.append("</div>").toString();
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
            boolean isSetLine = setMatcher.find();
            boolean isConfidenceLine = !isSetLine && confidenceMatcher.find();
            if (!isSetLine && !isConfidenceLine) continue;

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
                String origin = isSetLine ? setMatcher.group(1) : confidenceMatcher.group(1);
                long oldMillis = parseLong(OLD_CLOCK_MILLIS_PATTERN, line, -1L);
                long newMillis = parseLong(NEW_CLOCK_MILLIS_PATTERN, line, -1L);
                String cause = localizeChangeCause(
                    parseString(CHANGE_CAUSE_PATTERN, line, "").trim());
                // The line's wording alone does not decide whether the clock
                // moved: a confidence line can still carry a real step (see
                // CLOCK_CHANGE_TOLERANCE_MILLIS). Compare the clock values
                // the line itself reports instead of trusting the label.
                boolean clockChanged = isSetLine
                    || (oldMillis >= 0 && newMillis >= 0
                        && Math.abs(newMillis - oldMillis) > CLOCK_CHANGE_TOLERANCE_MILLIS);
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
        } else if ("SUCCESS_RAW".equals(state)) {
            label = "已用原始 GNSS 时钟完成校准";
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
        addForceOption(options, name, sample, nowElapsed, systemUnix, uncertainty, false);
    }

    private static void addForceOption(List<ForceOption> options, String name, Sample sample,
            long nowElapsed, long systemUnix, long uncertainty,
            boolean uncertaintyEstimated) {
        if (sample == null || nowElapsed < sample.elapsedRealtimeMillis || systemUnix < 0) return;
        // A raw sample read after the dumpsys snapshot can be a few seconds
        // newer than the dump's own elapsed clock; a negative age would only
        // produce a "reference time invalid" label in the chooser, so clamp
        // it — the drift over that gap is inside the uncertainty budget.
        long age = Math.max(0L, nowElapsed - sample.elapsedRealtimeMillis);
        if (age > maxForceSampleAgeMillis(name)) return;
        long target = sample.unixEpochTimeMillis + age;
        if (target < 1_577_836_800_000L || target > 4_102_444_800_000L) return;
        options.add(new ForceOption(name, target, target - systemUnix, age, uncertainty,
            nowElapsed, uncertaintyEstimated));
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
        } else if (option.uncertaintyEstimated) {
            message.append("（保守预算 ±").append(String.valueOf(option.uncertaintyMillis))
                .append(" ms；芯片未上报有效不确定度，样本越老不确定度越大）");
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
                .setMessage("没有符合年龄限制的授时样本。GNSS、基站 NITZ、网络 NTP 与原始 GNSS 时钟样本均需在 10 分钟内。请刷新或先获取新的 GNSS 样本。")
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
            option.ageMillis + elapsedDelta, option.uncertaintyMillis, nowElapsed,
            option.uncertaintyEstimated);
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

    /**
     * The raw GnssClock as a formal, fallback time source. It is measured
     * directly by the app's own GPS listener (same store the calibration
     * page reads) and never needs root or the system GnssTimeUpdateService.
     * It is display-only for the "phone clock is in sync with satellite
     * time" judgment: that judgment still reads the system GNSS suggestion
     * only (see gnssSyncCard). When no system suggestion exists, the same
     * reading backs manual calibration and the force-jump option.
     */
    private static String rawGnssSourceCard(
            GnssLocationTrackingService.RawGnssSample raw, long nowElapsed,
            long systemUnix) {
        StringBuilder card = new StringBuilder("<div class='source'><div><div class='name'>")
            .append("原始 GNSS 时钟</div>");
        if (raw == null || !raw.available) {
            card.append("<div class='meta'>尚无原始测量记录</div>")
                .append("<div class='small'>需开启定位后由校准页或定期同步采集；")
                .append("系统建议优先，原始时钟只作后备。</div>")
                .append("</div><div class='value none'>暂无</div></div>");
            return card.toString();
        }
        long age = raw.ageMillis(nowElapsed);
        if (!raw.utcAvailable || raw.offsetMillis == Long.MIN_VALUE) {
            card.append("<div class='meta'>已有原始测量，无法换算 UTC 偏差</div>");
            if (raw.discardReason != null && !raw.discardReason.isEmpty()) {
                card.append("<div class='small'>原因：")
                    .append(escape(raw.discardReason)).append("</div>");
            }
            card.append("<div class='small'>系统建议优先；原始时钟只作后备。</div>")
                .append("</div><div class='value none'>暂无</div></div>");
            return card.toString();
        }
        if (age < 0 || systemUnix < 0) {
            card.append("<div class='meta'>样本参考时间无效</div></div>")
                .append("<div class='value none'>—</div></div>");
            return card.toString();
        }
        // Same projection as the other source cards: the GNSS UTC at the
        // sample instant, extrapolated to now, compared with the phone clock.
        long projected = raw.gnssUtcMillis + age;
        long offset = projected - systemUnix;
        long uncertainty = raw.uncertaintyMillis + driftBudgetMillis(age);
        String valueClass = offset < 0 ? "lead" : offset > 0 ? "lag" : "equal";
        card.append("<div class='meta'>原始测量 · 样本 ")
            .append(escape(formatAge(age))).append("前</div>");
        if (raw.visibleSatellites >= 0 && raw.usedSatellites >= 0
                && raw.satelliteStatusElapsedMillis > 0
                && Math.abs(raw.satelliteStatusElapsedMillis
                    - raw.sampleElapsedMillis) <= 60_000L) {
            card.append("<div class='small'>可见 ").append(raw.visibleSatellites)
                .append(" 颗 · 参与定位 ").append(raw.usedSatellites).append(" 颗</div>");
        }
        card.append("<div class='small'>不确定度 ±").append(uncertainty)
            .append(" ms（").append(raw.uncertaintyEstimated
                ? "芯片未上报有效值，按保守预算 ±"
                    + GnssLocationTrackingService.RAW_UNCERTAINTY_FALLBACK_MILLIS
                    + " ms 计" : "芯片上报")
            .append(" + 年龄漂移预算）</div>")
            .append("<div class='small'>后备来源：系统建议优先；无系统建议时用于校准与强制跳改。</div>");
        if (age > DISPLAY_STALE_AGE_MILLIS) {
            // Over an hour old: the extrapolated value is not trustworthy enough to
            // show as a millisecond number at all.
            card.append("</div><div style='text-align:right'><div class='value none'>样本过旧</div>")
                .append("<div class='pill'>请重新采样</div></div></div>")
                .append("<div class='divider'></div>");
            return card.toString();
        }
        card.append("</div><div style='text-align:right'><div class='value ")
            .append(valueClass).append("'>").append(Math.abs(offset)).append(" 毫秒")
            .append(" ±").append(uncertainty).append(" 毫秒</div>")
            .append("<div class='pill ").append(valueClass).append("'>")
            .append(escape(phoneRelationLabel("原始 GNSS 时钟", offset)))
            .append("</div>");
        if (age > DISPLAY_REFERENCE_MAX_AGE_MILLIS) {
            card.append("<div class='pill'>样本超 10 分钟，仅供参考</div>");
        }
        card.append("</div></div>")
            .append("<div class='divider'></div>");
        return card.toString();
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

    /**
     * NITZ uncertainty with calibration taken into account. A calibrated NITZ
     * sample starts at +/-200 ms instead of +/-1500 ms: the learned
     * per-carrier bias removes the fixed part of the error, but delivery
     * jitter and staleness remain. The force-jump path deliberately keeps the
     * uncalibrated +/-1500 ms figure (see forceUncertaintyMillis): a forced
     * jump is a one-shot correction, not a place to trust a learned bias.
     */
    private static long nitzUncertainty(long ageMillis, boolean calibrated) {
        long base = calibrated
            ? NITZ_CALIBRATED_BASE_UNCERTAINTY_MILLIS : NITZ_BASE_UNCERTAINTY_MILLIS;
        return base + driftBudgetMillis(ageMillis);
    }

    /**
     * Parses the hook's debug.nitz.calibration property:
     * "calibrated|&lt;offsetMillis&gt;|&lt;samples&gt;|&lt;carrierKey&gt;",
     * "uncalibrated|&lt;samples&gt;|&lt;carrierKey&gt;" (still learning), or the
     * older "uncalibrated|&lt;carrierKey&gt;" (no count; treated as 0 samples).
     * Returns null when the property is empty or unparsable (hook not loaded,
     * or no NITZ sample seen since boot).
     */
    private static NitzCalibrationDisplay parseNitzCalibrationStatus(String status) {
        if (status == null || status.isEmpty()) return null;
        String[] parts = status.split("\\|");
        if (parts.length < 2) return null;
        if ("calibrated".equals(parts[0]) && parts.length >= 4) {
            try {
                long offset = Long.parseLong(parts[1]);
                int samples = Integer.parseInt(parts[2]);
                return new NitzCalibrationDisplay(true, offset, samples, parts[3]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if ("uncalibrated".equals(parts[0])) {
            if (parts.length >= 3) {
                try {
                    int samples = Integer.parseInt(parts[1]);
                    return new NitzCalibrationDisplay(false, 0L, samples, parts[2]);
                } catch (NumberFormatException ignored) {
                    // Not a count; fall through to the old two-part format.
                }
            }
            return new NitzCalibrationDisplay(false, 0L, 0, parts[1]);
        }
        return null;
    }

    private static final class NitzCalibrationDisplay {
        final boolean calibrated;
        final long offsetMillis;
        final int samples;
        final String carrierKey;

        NitzCalibrationDisplay(boolean calibrated, long offsetMillis, int samples,
                String carrierKey) {
            this.calibrated = calibrated;
            this.offsetMillis = offsetMillis;
            this.samples = samples;
            this.carrierKey = carrierKey;
        }

        String displayText() {
            if (calibrated) {
                String sign = offsetMillis >= 0 ? "+" : "";
                return "基站已校准，偏移 " + sign + offsetMillis + "ms（基于 " + samples + " 次学习）";
            }
            if (samples <= 0) return "基站未校准";
            if (samples < NitzCalibrator.MIN_SAMPLES_TO_APPLY) {
                return "基站学习中，已学 " + samples + "/"
                    + NitzCalibrator.MIN_SAMPLES_TO_APPLY + " 样本";
            }
            // Enough samples, but the median is still unusable (samples
            // disagree, the learning is stale, or the median is implausibly
            // large); the hook publishes uncalibrated in all three cases.
            return "基站学习中，已学 " + samples + " 个样本，暂未生效";
        }
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
        if ("原始 GNSS 时钟".equals(source)) return "GNSS 时间";
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
        if (webView == null) return;
        // Skip the reload entirely when nothing changed: a full loadDataWithBaseURL
        // every 60 s white-flashes and drops the scroll position for identical content.
        if (html.equals(lastShownHtml)) return;
        lastShownHtml = html;
        pendingScrollY = webView.getScrollY();
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
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

    /** One refresh's raw data, kept for the copy-log button. Immutable after
     * construction; the derived per-source lines in the log are recomputed
     * from these dumps with the same parsers the page itself uses. */
    private static final class DiagnosticSnapshot {
        final boolean hasRoot;
        final String identity;
        final String timeDump;
        final String gnssDump;
        final String thresholdStatus;
        final String thresholdConfig;
        final String nitzCalibrationStatus;
        final String calibrationState;
        final String calibrationDetail;
        final long calibrationTime;
        final long calibrationStartElapsed;
        final String forceState;
        final String forceDetail;
        final long forceTime;
        final List<TrendPoint> trendPoints;
        final NtscNtpClient.Result ntscResult;
        final GnssLocationTrackingService.RawGnssSample rawGnss;
        /** Raw debug.nitz.hook_active value: "1" when the hook entry ran in system_server. */
        final String hookActive;
        /** Raw debug.nitz.gnss_service value: "present" / "absent" / "" (not published). */
        final String gnssService;
        final SystemNtpInfo systemNtp;
        final long collectedWallMillis;
        final long collectedElapsedMillis;

        DiagnosticSnapshot(boolean hasRoot, String identity, String timeDump,
                String gnssDump, String thresholdStatus, String thresholdConfig,
                String nitzCalibrationStatus, String calibrationState,
                String calibrationDetail, long calibrationTime,
                long calibrationStartElapsed, String forceState, String forceDetail,
                long forceTime, List<TrendPoint> trendPoints,
                NtscNtpClient.Result ntscResult,
                GnssLocationTrackingService.RawGnssSample rawGnss,
                String hookActive, String gnssService,
                SystemNtpInfo systemNtp,
                long collectedWallMillis,
                long collectedElapsedMillis) {
            this.hasRoot = hasRoot;
            this.identity = identity;
            this.timeDump = timeDump;
            this.gnssDump = gnssDump;
            this.thresholdStatus = thresholdStatus;
            this.thresholdConfig = thresholdConfig;
            this.nitzCalibrationStatus = nitzCalibrationStatus;
            this.calibrationState = calibrationState;
            this.calibrationDetail = calibrationDetail;
            this.calibrationTime = calibrationTime;
            this.calibrationStartElapsed = calibrationStartElapsed;
            this.forceState = forceState;
            this.forceDetail = forceDetail;
            this.forceTime = forceTime;
            this.trendPoints = trendPoints;
            this.ntscResult = ntscResult;
            this.rawGnss = rawGnss;
            this.hookActive = hookActive;
            this.gnssService = gnssService;
            this.systemNtp = systemNtp == null ? SystemNtpInfo.empty() : systemNtp;
            this.collectedWallMillis = collectedWallMillis;
            this.collectedElapsedMillis = collectedElapsedMillis;
        }
    }

    /** System-NTP (network time) hook state, as read in one refresh. */
    private static final class SystemNtpInfo {
        // Raw property values; "" when unreadable (no root / older build).
        final String switchEnabled;
        final String status;
        final String server;
        // `cmd network_time_update_service dump` output; "" when unreadable.
        final String dump;

        SystemNtpInfo(String switchEnabled, String status, String server, String dump) {
            this.switchEnabled = switchEnabled == null ? "" : switchEnabled;
            this.status = status == null ? "" : status;
            this.server = server == null ? "" : server;
            this.dump = dump == null ? "" : dump;
        }

        static SystemNtpInfo empty() {
            return new SystemNtpInfo("", "", "", "");
        }

        boolean switchOn() {
            return "1".equals(switchEnabled);
        }
    }

    /** One system-NTP server actually used, with when it was used. */
    private static final class SystemNtpServer {
        final String host;
        final long elapsedRealtimeMillis;

        SystemNtpServer(String host, long elapsedRealtimeMillis) {
            this.host = host;
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        }
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
        /**
         * True when uncertaintyMillis is the conservative fallback budget,
         * not a value the source reported — currently only the raw GnssClock
         * option, whose chip may report no usable uncertainty. Shown as
         * "保守预算" in the confirmation, never as "来源上报".
         */
        final boolean uncertaintyEstimated;

        ForceOption(String source, long targetUnixMillis, long offsetMillis, long ageMillis,
                long uncertaintyMillis, long diagnosticElapsedMillis,
                boolean uncertaintyEstimated) {
            this.source = source;
            this.targetUnixMillis = targetUnixMillis;
            this.offsetMillis = offsetMillis;
            this.ageMillis = ageMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.diagnosticElapsedMillis = diagnosticElapsedMillis;
            this.uncertaintyEstimated = uncertaintyEstimated;
        }
    }
}
