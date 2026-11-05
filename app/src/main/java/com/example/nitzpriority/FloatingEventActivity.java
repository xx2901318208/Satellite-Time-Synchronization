package com.example.nitzpriority;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Event list, event editor, and reminder configuration for the floating clock. */
public final class FloatingEventActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 8301;
    private static final int[] REMINDER_MINUTES = {0, 1, 5, 10, 15, 30};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout upcomingList;
    private LinearLayout finishedList;
    private TextView nextEventView;
    private TextView alarmPermissionView;
    private TextView notificationPermissionView;
    private Spinner reminderSpinner;
    private Button notificationButton;
    private boolean updatingReminderSpinner;
    private long nextCategoryRefreshAt = Long.MAX_VALUE;

    private final Runnable nextEventTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (System.currentTimeMillis() >= nextCategoryRefreshAt) refreshEvents();
            updateNextEventCountdown();
            handler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        styleSystemBars();
        FloatingEventAlarmReceiver.ensureNotificationChannel(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFE8F6FF, 0xFFF5FAFF, 0xFFEAF8F5}));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        page.addView(text("定时事件", 27, 0xFF12324D, true), matchWrap());
        TextView intro = text("创建开售、预约或提醒事件；到点通知可点开目标链接。", 14,
            0xFF58718A, false);
        intro.setPadding(0, dp(5), 0, dp(12));
        page.addView(intro, matchWrap());

        LinearLayout permissionCard = card();
        page.addView(permissionCard, marginMatch(dp(2), dp(8)));
        alarmPermissionView = text("正在检查闹钟权限…", 13, 0xFF35546E, false);
        permissionCard.addView(alarmPermissionView, matchWrap());
        notificationPermissionView = text("正在检查通知权限…", 13, 0xFF35546E, false);
        permissionCard.addView(notificationPermissionView, marginMatch(dp(3), 0));
        Button exactButton = button("设置精确提醒权限", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams exactParams = marginMatch(dp(5), 0);
        permissionCard.addView(exactButton, exactParams);
        exactButton.setOnClickListener(view -> openExactAlarmSettings());
        if (Build.VERSION.SDK_INT < 31) exactButton.setVisibility(View.GONE);

        notificationButton = button("开启事件通知权限", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams notificationParams = marginMatch(dp(5), 0);
        permissionCard.addView(notificationButton, notificationParams);
        notificationButton.setOnClickListener(view -> {
            requestOrOpenNotificationSettings();
        });
        Button testNotificationButton = button("测试提醒声音与震动", 0xFFFFF1D8, 0xFF805000);
        LinearLayout.LayoutParams testNotificationParams = marginMatch(dp(5), 0);
        permissionCard.addView(testNotificationButton, testNotificationParams);
        testNotificationButton.setOnClickListener(view -> sendTestNotification());
        Button testAlarmButton = button("12 秒后测试真实闹钟", 0xFFE8E6FF, 0xFF51428A);
        permissionCard.addView(testAlarmButton, marginMatch(dp(5), 0));
        testAlarmButton.setOnClickListener(view -> scheduleTestAlarm());
        Button stopAlertButton = button("停止当前提醒", 0xFFFFE5E1, 0xFF9A3529);
        permissionCard.addView(stopAlertButton, marginMatch(dp(5), 0));
        stopAlertButton.setOnClickListener(view -> {
            FloatingEventAlarmReceiver.stopAllActiveAlerts(this);
            Toast.makeText(this, "已停止当前铃声、振动和事件提醒通知。", Toast.LENGTH_SHORT).show();
        });
        Switch soundAlertSwitch = new Switch(this);
        soundAlertSwitch.setText("事件提醒播放系统默认通知铃声");
        soundAlertSwitch.setTextSize(14);
        soundAlertSwitch.setTextColor(0xFF35546E);
        soundAlertSwitch.setChecked(FloatingClockStyle.preferences(this)
            .getBoolean(FloatingEventStore.ALERT_SOUND_ENABLED_KEY, true));
        permissionCard.addView(soundAlertSwitch, marginMatch(dp(5), 0));
        soundAlertSwitch.setOnCheckedChangeListener((buttonView, checked) ->
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingEventStore.ALERT_SOUND_ENABLED_KEY, checked).apply());
        Switch vibrationAlertSwitch = new Switch(this);
        vibrationAlertSwitch.setText("事件提醒时振动");
        vibrationAlertSwitch.setTextSize(14);
        vibrationAlertSwitch.setTextColor(0xFF35546E);
        vibrationAlertSwitch.setChecked(FloatingClockStyle.preferences(this)
            .getBoolean(FloatingEventStore.ALERT_VIBRATION_ENABLED_KEY, true));
        permissionCard.addView(vibrationAlertSwitch, marginMatch(dp(2), 0));
        vibrationAlertSwitch.setOnCheckedChangeListener((buttonView, checked) ->
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingEventStore.ALERT_VIBRATION_ENABLED_KEY, checked).apply());

        LinearLayout defaultCard = card();
        page.addView(defaultCard, marginMatch(dp(6), dp(8)));
        defaultCard.addView(text("默认提前提醒", 15, 0xFF193B59, true), matchWrap());
        reminderSpinner = new Spinner(this);
        String[] reminderLabels = {"不提醒", "提前 1 分钟", "提前 5 分钟", "提前 10 分钟",
            "提前 15 分钟", "提前 30 分钟"};
        ArrayAdapter<String> reminderAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, reminderLabels);
        reminderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reminderSpinner.setAdapter(reminderAdapter);
        defaultCard.addView(reminderSpinner, matchWrap());
        reminderSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (updatingReminderSpinner) return;
                FloatingClockStyle.preferences(FloatingEventActivity.this).edit()
                    .putInt(FloatingEventStore.DEFAULT_REMINDER_KEY,
                        REMINDER_MINUTES[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        nextEventView = text("下一事件：暂无待开始事件", 15, 0xFF1769D2, true);
        nextEventView.setPadding(dp(15), dp(14), dp(15), dp(14));
        nextEventView.setBackground(cardBackground());
        page.addView(nextEventView, marginMatch(dp(8), dp(5)));

        Button addButton = button("＋ 添加定时事件", 0xFF287FDB, 0xFFFFFFFF);
        page.addView(addButton, marginMatch(dp(6), dp(8)));
        addButton.setOnClickListener(view -> showEditor(null));

        page.addView(text("待开始", 18, 0xFF193B59, true), marginMatch(dp(12), dp(4)));
        upcomingList = new LinearLayout(this);
        upcomingList.setOrientation(LinearLayout.VERTICAL);
        page.addView(upcomingList, matchWrap());
        page.addView(text("已结束", 18, 0xFF69849A, true), marginMatch(dp(14), dp(4)));
        finishedList = new LinearLayout(this);
        finishedList.setOrientation(LinearLayout.VERTICAL);
        page.addView(finishedList, matchWrap());

        TextView note = text("提醒使用系统闹钟调度，到提前提醒和目标时间时播放系统默认声音并振动。Android 12+ 必须允许“精确闹钟”才能准时唤醒；未授权时系统可能延迟。请检查通知权限、铃声音量、设备振动和勿扰模式。点按提醒才会打开链接。", 12,
            0xFF69849A, false);
        note.setPadding(dp(3), dp(16), dp(3), dp(5));
        page.addView(note, matchWrap());
        TextView author = text("作者：酷安 R1263599071", 12, 0xFF69849A, false);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(14), 0, dp(4));
        page.addView(author, matchWrap());

        setContentView(scroll);
        loadDefaultReminder();
        refreshEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        refreshEvents();
        handler.removeCallbacks(nextEventTicker);
        handler.post(nextEventTicker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(nextEventTicker);
        super.onPause();
    }

    private void loadDefaultReminder() {
        int minutes = FloatingClockStyle.preferences(this)
            .getInt(FloatingEventStore.DEFAULT_REMINDER_KEY,
                FloatingEventStore.DEFAULT_REMINDER_MINUTES);
        int selected = 2;
        for (int i = 0; i < REMINDER_MINUTES.length; i++) {
            if (REMINDER_MINUTES[i] == minutes) selected = i;
        }
        updatingReminderSpinner = true;
        reminderSpinner.setSelection(selected);
        updatingReminderSpinner = false;
    }

    private void updatePermissionStatus() {
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            exact = manager != null && manager.canScheduleExactAlarms();
        }
        if (exact) {
            alarmPermissionView.setText("精确闹钟已授权：提前和到点提醒使用系统闹钟唤醒。");
            alarmPermissionView.setTextColor(0xFF13774D);
        } else {
            alarmPermissionView.setText("精确闹钟未授权：系统可能延迟提醒；点按下方按钮开启。");
            alarmPermissionView.setTextColor(0xFF9B5E00);
        }
        boolean runtimeGranted = Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        boolean appNotificationsEnabled = notificationManager == null
            || notificationManager.areNotificationsEnabled();
        NotificationChannel eventChannel = Build.VERSION.SDK_INT >= 26
            && notificationManager != null
            ? notificationManager.getNotificationChannel(FloatingEventStore.NOTIFICATION_CHANNEL_ID)
            : null;
        if (!runtimeGranted) {
            notificationPermissionView.setText("通知权限未开启；提前提醒和到点通知可能不可见。");
            notificationPermissionView.setTextColor(0xFF9B5E00);
            notificationButton.setText("开启事件通知权限");
        } else if (!appNotificationsEnabled) {
            notificationPermissionView.setText("应用通知总开关已关闭；请在系统设置中重新开启。");
            notificationPermissionView.setTextColor(0xFF9B5E00);
            notificationButton.setText("打开通知设置");
        } else if (eventChannel != null
                && eventChannel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            notificationPermissionView.setText("事件通知渠道已在系统设置中关闭；请重新允许通知。");
            notificationPermissionView.setTextColor(0xFF9B5E00);
            notificationButton.setText("打开事件通知设置");
        } else {
            notificationPermissionView.setText(Build.VERSION.SDK_INT < 33
                ? "通知已启用；铃声和振动由本页开关直接控制。"
                : "通知权限已开启；铃声和振动由本页开关直接控制。");
            notificationPermissionView.setTextColor(0xFF13774D);
            notificationButton.setText("事件通知卡片设置");
        }
    }

    private void requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            boolean askedBefore = FloatingClockStyle.preferences(this)
                .getBoolean("notification_permission_requested", false);
            if (askedBefore && !shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS)) {
                openAppNotificationSettings();
            } else {
                FloatingClockStyle.preferences(this).edit()
                    .putBoolean("notification_permission_requested", true).apply();
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            }
            return;
        }
        openEventChannelSettings();
    }

    private void sendTestNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestOrOpenNotificationSettings();
            return;
        }
        FloatingEventAlarmReceiver.postTestNotification(this);
        Toast.makeText(this, "测试提醒已发送；若无声音或振动，请检查本页开关、通知音量、设备振动和勿扰模式。",
            Toast.LENGTH_LONG).show();
    }

    private void openEventChannelSettings() {
        FloatingEventAlarmReceiver.ensureNotificationChannel(this);
        try {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, FloatingEventStore.NOTIFICATION_CHANNEL_ID);
            startActivity(intent);
        } catch (RuntimeException exception) {
            openAppNotificationSettings();
        }
    }

    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (RuntimeException exception) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
            startActivity(fallback);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            updatePermissionStatus();
            Toast.makeText(this, grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    ? "事件通知权限已开启。" : "通知权限未开启；可在系统设置中允许。",
                Toast.LENGTH_LONG).show();
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "请到系统应用权限中允许精确闹钟。", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshEvents() {
        if (upcomingList == null || finishedList == null) return;
        long now = System.currentTimeMillis();
        List<FloatingEventStore.Event> upcoming = new ArrayList<>();
        List<FloatingEventStore.Event> finished = new ArrayList<>();
        for (FloatingEventStore.Event event : FloatingEventStore.load(this)) {
            if (event.targetTimeMillis > now) upcoming.add(event); else finished.add(event);
        }
        Collections.sort(upcoming, Comparator.comparingLong(e -> e.targetTimeMillis));
        Collections.sort(finished, (left, right) ->
            Long.compare(right.targetTimeMillis, left.targetTimeMillis));
        nextCategoryRefreshAt = upcoming.isEmpty()
            ? Long.MAX_VALUE : upcoming.get(0).targetTimeMillis;
        upcomingList.removeAllViews();
        finishedList.removeAllViews();
        if (upcoming.isEmpty()) upcomingList.addView(text("暂无待开始事件。", 13, 0xFF69849A, false));
        else for (FloatingEventStore.Event event : upcoming) addEventCard(upcomingList, event, true);
        if (finished.isEmpty()) finishedList.addView(text("暂无已结束事件。", 13, 0xFF8A9BAD, false));
        else for (FloatingEventStore.Event event : finished) addEventCard(finishedList, event, false);
        updateNextEventCountdown();
        FloatingEventStore.rescheduleAll(this);
    }

    private void scheduleTestAlarm() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestOrOpenNotificationSettings();
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = Build.VERSION.SDK_INT >= 26 && manager != null
            ? manager.getNotificationChannel(FloatingEventStore.NOTIFICATION_CHANNEL_ID) : null;
        if (manager == null || !manager.areNotificationsEnabled()
                || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            Toast.makeText(this, "请先开启应用与事件通知权限，再测试闹钟声振。",
                Toast.LENGTH_LONG).show();
            openEventChannelSettings();
            return;
        }
        if (!FloatingEventStore.canScheduleExactAlarms(this)) {
            new AlertDialog.Builder(this)
                .setTitle("先开启精确闹钟权限")
                .setMessage("Android 当前未允许“卫星对时”设置精确闹钟。未授权时，系统可能延迟提前提醒和到点提醒。请先开启权限，再用 12 秒测试确认完整闹钟链路。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往授权", (dialog, which) -> openExactAlarmSettings())
                .show();
            return;
        }
        if (FloatingEventStore.scheduleTestAlarm(this)) {
            Toast.makeText(this, "测试闹钟已设定，约 12 秒后响铃并振动。", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "测试闹钟设置失败；请检查精确闹钟权限。", Toast.LENGTH_LONG).show();
        }
    }

    private void addEventCard(LinearLayout parent, FloatingEventStore.Event event, boolean isUpcoming) {
        LinearLayout item = card();
        LinearLayout.LayoutParams itemParams = marginMatch(dp(4), dp(5));
        parent.addView(item, itemParams);
        item.addView(text(event.title, 16, 0xFF193B59, true), matchWrap());
        item.addView(text(FloatingEventStore.formatTarget(event.targetTimeMillis), 12,
            0xFF69849A, false), marginMatch(dp(3), 0));
        String status = isUpcoming ? "剩余 " + formatDuration(event.targetTimeMillis - System.currentTimeMillis())
            : "已结束";
        item.addView(text(status, 13, isUpcoming ? 0xFF1769D2 : 0xFF8A9BAD, true),
            marginMatch(dp(5), 0));
        if (!event.url.isEmpty()) {
            item.addView(text(event.url, 12, 0xFF287FDB, false), marginMatch(dp(3), 0));
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = marginMatch(dp(5), 0);
        item.addView(buttons, rowParams);
        if (FloatingEventStore.isWebUrl(event.url)) {
            Button open = smallButton("打开链接", 0xFFDDF4FF, 0xFF165B78);
            buttons.addView(open, new LinearLayout.LayoutParams(0, dp(42), 1));
            open.setOnClickListener(view -> openEventLink(event.url));
        }
        Button edit = smallButton("编辑", 0xFFE9F1F8, 0xFF365C78);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        editParams.leftMargin = dp(5);
        buttons.addView(edit, editParams);
        edit.setOnClickListener(view -> showEditor(event));
        Button delete = smallButton("删除", 0xFFF8E5E8, 0xFF9F3548);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        deleteParams.leftMargin = dp(5);
        buttons.addView(delete, deleteParams);
        delete.setOnClickListener(view -> confirmDelete(event));
    }

    private void updateNextEventCountdown() {
        if (nextEventView == null) return;
        long now = System.currentTimeMillis();
        FloatingEventStore.Event next = null;
        for (FloatingEventStore.Event event : FloatingEventStore.load(this)) {
            if (event.targetTimeMillis > now && (next == null
                    || event.targetTimeMillis < next.targetTimeMillis)) next = event;
        }
        if (next == null) {
            nextEventView.setText("下一事件：暂无待开始事件");
        } else {
            nextEventView.setText("下一事件：" + next.title + "\n倒计时 "
                + formatDuration(next.targetTimeMillis - now));
        }
    }

    private String formatDuration(long millis) {
        long remaining = Math.max(0L, millis);
        long hours = remaining / 3_600_000L;
        long minutes = (remaining / 60_000L) % 60L;
        long seconds = (remaining / 1_000L) % 60L;
        int ms = (int) (remaining % 1_000L);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d",
            hours, minutes, seconds, ms);
    }

    private void showEditor(FloatingEventStore.Event existing) {
        Calendar calendar = Calendar.getInstance();
        if (existing != null) calendar.setTimeInMillis(existing.targetTimeMillis);
        else {
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
        }
        final long[] targetTime = {calendar.getTimeInMillis()};
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(6), dp(20), dp(4));
        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("事件名称，例如：演唱会开票");
        titleInput.setText(existing == null ? "" : existing.title);
        fields.addView(titleInput, matchWrap());
        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setHint("目标链接（可选，http/https）");
        urlInput.setText(existing == null ? "" : existing.url);
        fields.addView(urlInput, matchWrap());
        TextView dateTimeLabel = text("目标时间：" + FloatingEventStore.formatTarget(targetTime[0]),
            13, 0xFF35546E, true);
        LinearLayout.LayoutParams dateTimeParams = marginMatch(dp(6), dp(2));
        fields.addView(dateTimeLabel, dateTimeParams);
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        fields.addView(dateRow, marginMatch(dp(2), 0));
        Button dateButton = smallButton("选择日期", 0xFFDDF4FF, 0xFF165B78);
        dateRow.addView(dateButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button timeButton = smallButton("选择时间", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams timeButtonParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        timeButtonParams.leftMargin = dp(7);
        dateRow.addView(timeButton, timeButtonParams);
        dateButton.setOnClickListener(view -> {
            Calendar selected = Calendar.getInstance();
            selected.setTimeInMillis(targetTime[0]);
            new DatePickerDialog(this, (picker, year, month, day) -> {
                Calendar updated = Calendar.getInstance();
                updated.setTimeInMillis(targetTime[0]);
                updated.set(Calendar.YEAR, year);
                updated.set(Calendar.MONTH, month);
                updated.set(Calendar.DAY_OF_MONTH, day);
                targetTime[0] = updated.getTimeInMillis();
                dateTimeLabel.setText("目标时间：" + FloatingEventStore.formatTarget(targetTime[0]));
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)).show();
        });
        timeButton.setOnClickListener(view -> {
            Calendar selected = Calendar.getInstance();
            selected.setTimeInMillis(targetTime[0]);
            new TimePickerDialog(this, (picker, hour, minute) -> {
                Calendar updated = Calendar.getInstance();
                updated.setTimeInMillis(targetTime[0]);
                updated.set(Calendar.HOUR_OF_DAY, hour);
                updated.set(Calendar.MINUTE, minute);
                updated.set(Calendar.SECOND, 0);
                updated.set(Calendar.MILLISECOND, 0);
                targetTime[0] = updated.getTimeInMillis();
                dateTimeLabel.setText("目标时间：" + FloatingEventStore.formatTarget(targetTime[0]));
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show();
        });

        TextView reminderLabel = text("提前提醒", 13, 0xFF35546E, true);
        fields.addView(reminderLabel, marginMatch(dp(7), 0));
        Spinner reminderChoice = new Spinner(this);
        String[] labels = {"不提醒", "提前 1 分钟", "提前 5 分钟", "提前 10 分钟",
            "提前 15 分钟", "提前 30 分钟"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reminderChoice.setAdapter(adapter);
        fields.addView(reminderChoice, matchWrap());
        int defaultReminder = FloatingClockStyle.preferences(this)
            .getInt(FloatingEventStore.DEFAULT_REMINDER_KEY, FloatingEventStore.DEFAULT_REMINDER_MINUTES);
        int selectedReminder = existing == null ? defaultReminder : existing.reminderMinutes;
        int selectedIndex = 2;
        for (int i = 0; i < REMINDER_MINUTES.length; i++) {
            if (REMINDER_MINUTES[i] == selectedReminder) selectedIndex = i;
        }
        reminderChoice.setSelection(selectedIndex);

        ScrollView container = new ScrollView(this);
        container.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "添加事件" : "编辑事件")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String title = titleInput.getText().toString().trim();
                String url = urlInput.getText().toString().trim();
                if (title.isEmpty()) {
                    titleInput.setError("请输入事件名称");
                    return;
                }
                if (!url.isEmpty() && !FloatingEventStore.isWebUrl(url)) {
                    urlInput.setError("链接需以 http:// 或 https:// 开头");
                    return;
                }
                if (targetTime[0] <= System.currentTimeMillis()) {
                    Toast.makeText(this, "目标时间需设置为未来时间。", Toast.LENGTH_SHORT).show();
                    return;
                }
                int reminder = REMINDER_MINUTES[reminderChoice.getSelectedItemPosition()];
                List<FloatingEventStore.Event> events = FloatingEventStore.load(this);
                String id = existing == null
                    ? Long.toString(System.currentTimeMillis()) + "-" + android.os.SystemClock.elapsedRealtime()
                    : existing.id;
                FloatingEventStore.Event saved = new FloatingEventStore.Event(id, title,
                    targetTime[0], url, reminder);
                if (existing != null) FloatingEventAlarmReceiver.stopEventAlert(this, existing.id);
                boolean replaced = false;
                for (int i = 0; i < events.size(); i++) {
                    if (events.get(i).id.equals(id)) {
                        events.set(i, saved);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) events.add(saved);
                FloatingEventStore.save(this, events);
                String selectedEvent = FloatingClockStyle.preferences(this)
                    .getString("selected_event_id", "");
                if (selectedEvent.isEmpty()) {
                    FloatingClockStyle.preferences(this).edit()
                        .putString("selected_event_id", id).apply();
                }
                refreshEvents();
                dialog.dismiss();
                if (!FloatingEventStore.canScheduleExactAlarms(this)) {
                    new AlertDialog.Builder(this)
                        .setTitle("事件已保存，但还不能保证准时提醒")
                        .setMessage("Android 尚未授予精确闹钟权限。请开启后，提前提醒和到点提醒才能按设定时间唤醒；否则系统可能延迟。")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("现在开启", (permissionDialog, which) ->
                            openExactAlarmSettings())
                        .show();
                }
            }));
        dialog.show();
    }

    private void confirmDelete(FloatingEventStore.Event event) {
        new AlertDialog.Builder(this).setTitle("删除事件？")
            .setMessage("将删除“" + event.title + "”，取消未触发的闹钟，并停止该事件当前正在播放的铃声、振动和通知。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (dialog, which) -> {
                List<FloatingEventStore.Event> events = FloatingEventStore.load(this);
                List<FloatingEventStore.Event> remaining = new ArrayList<>();
                for (FloatingEventStore.Event item : events) {
                    if (!item.id.equals(event.id)) remaining.add(item);
                }
                FloatingEventAlarmReceiver.stopEventAlert(this, event.id);
                FloatingEventStore.cancelAll(this, event);
                FloatingEventStore.save(this, remaining);
                if (event.id.equals(FloatingClockStyle.preferences(this)
                        .getString("selected_event_id", ""))) {
                    FloatingClockStyle.preferences(this).edit().putString("selected_event_id", "").apply();
                }
                refreshEvents();
            }).show();
    }

    private void openEventLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "没有应用可以打开此链接。", Toast.LENGTH_LONG).show();
        }
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button smallButton(String label, int background, int foreground) {
        Button button = button(label, background, foreground);
        button.setTextSize(12);
        button.setMinHeight(dp(40));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(cardBackground());
        card.setElevation(dp(3));
        return card;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xF2FFFFFF, 0xDDF0FAFF});
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), 0xDFFFFFFF);
        return background;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams marginMatch(int top, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
}
