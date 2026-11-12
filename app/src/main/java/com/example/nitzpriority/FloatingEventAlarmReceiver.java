package com.example.nitzpriority;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Delivers event notifications and direct system-default sound / vibration alerts. */
public final class FloatingEventAlarmReceiver extends BroadcastReceiver {
    private static final int TEST_NOTIFICATION_ID = 83_501;
    private static final long MAX_NOTIFICATION_RING_MILLIS = 5_000L;
    private static final long MAX_ALARM_RING_MILLIS = 60_000L;
    private static final Handler RINGTONE_HANDLER = new Handler(Looper.getMainLooper());
    // Kept separate from RINGTONE_HANDLER so stopping one alert's playback does not cancel
    // other notifications' scheduled soften tasks.
    private static final Handler SOFTEN_HANDLER = new Handler(Looper.getMainLooper());
    private static Ringtone activeRingtone;
    private static Vibrator activeVibrator;
    private static final Set<Integer> ACTIVE_ALERT_NOTIFICATIONS =
        Collections.synchronizedSet(new HashSet<>());
    private static final String PREF_ACTIVE_ALERTS_KEY = "active_alert_notification_ids";

    /** Active (pinned) alert ids, persisted so stop-all works across processes. */
    private static Set<Integer> persistedActiveAlerts(Context context) {
        Set<Integer> ids = new HashSet<>();
        String raw = FloatingClockStyle.preferences(context)
            .getString(PREF_ACTIVE_ALERTS_KEY, "");
        if (raw == null || raw.isEmpty()) return ids;
        for (String part : raw.split(",")) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore one bad entry in the persisted id list.
            }
        }
        return ids;
    }

    private static void writePersistedActiveAlerts(Context context, Set<Integer> ids) {
        StringBuilder raw = new StringBuilder();
        for (int id : ids) {
            if (raw.length() > 0) raw.append(',');
            raw.append(id);
        }
        // commit(): the record must be on disk before this process can be killed.
        FloatingClockStyle.preferences(context).edit()
            .putString(PREF_ACTIVE_ALERTS_KEY, raw.toString()).commit();
    }

    private static void addActiveAlert(Context context, int notificationId) {
        ACTIVE_ALERT_NOTIFICATIONS.add(notificationId);
        Set<Integer> ids = persistedActiveAlerts(context);
        if (ids.add(notificationId)) writePersistedActiveAlerts(context, ids);
    }

    private static void removeActiveAlert(Context context, int notificationId) {
        ACTIVE_ALERT_NOTIFICATIONS.remove(notificationId);
        Set<Integer> ids = persistedActiveAlerts(context);
        if (ids.remove(notificationId)) writePersistedActiveAlerts(context, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String eventId = intent.getStringExtra("event_id");
        String kind = intent.getStringExtra("event_kind");
        if ("stop_alert".equals(kind)) {
            stopActiveAlert(context, intent.getIntExtra("notification_id", TEST_NOTIFICATION_ID));
            return;
        }
        if ("test_alarm".equals(kind)) {
            deliverTestAlarm(context);
            return;
        }
        FloatingEventStore.Event event = FloatingEventStore.find(context, eventId);
        if (event == null || kind == null) return;
        long now = System.currentTimeMillis();

        if (kind.startsWith("tick_")) {
            if (now < event.targetTimeMillis && event.targetTimeMillis - now <= 4_000L
                    && isAlertDeliveryEnabled(context)
                    && FloatingClockStyle.preferences(context).getBoolean(
                        FloatingEventStore.ALERT_VIBRATION_ENABLED_KEY, true)) {
                vibrate(context, 100L, false);
            }
            return;
        }
        if ("reminder".equals(kind) && now >= event.targetTimeMillis) return;

        ensureNotificationChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        boolean alarmMode = "due".equals(kind);
        performAlerts(context, alarmMode);

        String title;
        String text;
        if ("reminder".equals(kind)) {
            title = "即将开始：" + event.title;
            text = "目标时间 " + FloatingEventStore.formatTarget(event.targetTimeMillis)
                + " · 点按通知打开事件链接";
        } else {
            title = "事件开始：" + event.title;
            text = "目标时间 " + FloatingEventStore.formatTarget(event.targetTimeMillis)
                + " · 点按通知打开事件链接";
        }

        Intent open;
        if (FloatingEventStore.isWebUrl(event.url)) {
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(event.url));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(context, FloatingEventActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(context,
            (event.id + kind).hashCode(), open, flags);
        int notificationId = (event.id + kind).hashCode();
        PendingIntent stopIntent = stopIntent(context, notificationId);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, FloatingEventStore.NOTIFICATION_CHANNEL_ID)
            : new Notification.Builder(context);
        if (Build.VERSION.SDK_INT >= 26) {
            // System-side timeout: the pinned alert becomes dismissible even if
            // this process is killed before the in-memory soften task runs.
            // SOFTEN_HANDLER below is kept only as a fallback.
            builder.setTimeoutAfter(alarmMode ? MAX_ALARM_RING_MILLIS : MAX_NOTIFICATION_RING_MILLIS);
        }
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "停止提醒", stopIntent).build())
            .build();
        addActiveAlert(context, notificationId);
        manager.notify(notificationId, notification);
        scheduleSoften(context, notificationId, title, text, contentIntent,
            alarmMode ? MAX_ALARM_RING_MILLIS : MAX_NOTIFICATION_RING_MILLIS);
    }

    /** Re-posts the alert without pinning once the sound window ends, so it can be dismissed. */
    private static void scheduleSoften(Context context, int notificationId, String title,
            String text, PendingIntent contentIntent, long afterMillis) {
        // The broadcast context is only valid inside onReceive; keep the application context.
        Context appContext = context.getApplicationContext();
        SOFTEN_HANDLER.postDelayed(() -> {
            synchronized (FloatingEventAlarmReceiver.class) {
                if (!ACTIVE_ALERT_NOTIFICATIONS.contains(notificationId)) return;
            }
            NotificationManager manager = appContext.getSystemService(NotificationManager.class);
            if (manager == null) return;
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(appContext, FloatingEventStore.NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(appContext);
            Notification softened = builder
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                    "停止提醒", stopIntent(appContext, notificationId)).build())
                .build();
            manager.notify(notificationId, softened);
            // The alert is dismissible now; it no longer needs the pinned record.
            removeActiveAlert(appContext, notificationId);
        }, afterMillis);
    }

    static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
            FloatingEventStore.NOTIFICATION_CHANNEL_ID,
            "事件提醒通知", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("事件通知卡片；声音和振动由应用按本页开关调用系统默认提示");
        // Sound and haptics are emitted directly by this app to avoid duplicate channel alerts.
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    static void postTestNotification(Context context) {
        ensureNotificationChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        performAlerts(context, false);
        Intent open = new Intent(context, FloatingEventActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(context, TEST_NOTIFICATION_ID,
            open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, FloatingEventStore.NOTIFICATION_CHANNEL_ID)
            : new Notification.Builder(context);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("提醒功能测试")
            .setContentText("已按本页开关测试系统默认通知铃声和振动。")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setPriority(Notification.PRIORITY_HIGH)
            .build();
        manager.notify(TEST_NOTIFICATION_ID, notification);
    }

    private static void deliverTestAlarm(Context context) {
        ensureNotificationChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        int notificationId = TEST_NOTIFICATION_ID + 1;
        performAlerts(context, true);
        Intent open = new Intent(context, FloatingEventActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(context, notificationId,
            open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, FloatingEventStore.NOTIFICATION_CHANNEL_ID)
            : new Notification.Builder(context);
        if (Build.VERSION.SDK_INT >= 26) {
            // System-side timeout; the in-memory soften task is only a fallback.
            builder.setTimeoutAfter(MAX_ALARM_RING_MILLIS);
        }
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("测试闹钟已触发")
            .setContentText("如果听到闹铃并感到振动，事件闹钟链路正常。")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "停止提醒", stopIntent(context, notificationId)).build())
            .build();
        addActiveAlert(context, notificationId);
        manager.notify(notificationId, notification);
        scheduleSoften(context, notificationId, "测试闹钟已触发",
            "如果听到闹铃并感到振动，事件闹钟链路正常。", contentIntent, MAX_ALARM_RING_MILLIS);
    }

    private static PendingIntent stopIntent(Context context, int notificationId) {
        Intent stop = new Intent(context, FloatingEventAlarmReceiver.class)
            .setAction("com.example.nitzpriority.event.stop_alert." + notificationId)
            .putExtra("event_kind", "stop_alert")
            .putExtra("notification_id", notificationId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, notificationId ^ 0x5A17, stop, flags);
    }

    private static void stopActiveAlert(Context context, int notificationId) {
        removeActiveAlert(context, notificationId);
        boolean moreAlertsRemain;
        synchronized (ACTIVE_ALERT_NOTIFICATIONS) {
            moreAlertsRemain = !ACTIVE_ALERT_NOTIFICATIONS.isEmpty();
        }
        // Ringtone and vibrator are shared; only silence them when no other alert is active.
        if (!moreAlertsRemain) stopPlayback();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(notificationId);
    }

    static void stopEventAlert(Context context, String eventId) {
        if (eventId == null || eventId.isEmpty()) return;
        stopPlayback();
        int dueId = (eventId + "due").hashCode();
        int reminderId = (eventId + "reminder").hashCode();
        removeActiveAlert(context, dueId);
        removeActiveAlert(context, reminderId);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(dueId);
            manager.cancel(reminderId);
        }
    }

    static void stopAllActiveAlerts(Context context) {
        stopPlayback();
        Set<Integer> ids = new HashSet<>();
        synchronized (ACTIVE_ALERT_NOTIFICATIONS) {
            ids.addAll(ACTIVE_ALERT_NOTIFICATIONS);
            ACTIVE_ALERT_NOTIFICATIONS.clear();
        }
        // Include ids persisted by a previous process that has since died.
        ids.addAll(persistedActiveAlerts(context));
        writePersistedActiveAlerts(context, new HashSet<>());
        ids.add(TEST_NOTIFICATION_ID + 1);
        for (FloatingEventStore.Event event : FloatingEventStore.load(context)) {
            ids.add((event.id + "due").hashCode());
            ids.add((event.id + "reminder").hashCode());
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            for (int id : ids) manager.cancel(id);
            if (Build.VERSION.SDK_INT >= 23) {
                for (StatusBarNotification status : manager.getActiveNotifications()) {
                    Notification notification = status.getNotification();
                    boolean eventAlert = Notification.CATEGORY_ALARM.equals(notification.category)
                        || (Build.VERSION.SDK_INT >= 26
                            && FloatingEventStore.NOTIFICATION_CHANNEL_ID.equals(notification.getChannelId()));
                    if (eventAlert) manager.cancel(status.getId());
                }
            }
        }
    }

    private static void stopPlayback() {
        RINGTONE_HANDLER.removeCallbacksAndMessages(null);
        synchronized (FloatingEventAlarmReceiver.class) {
            try {
                if (activeRingtone != null && activeRingtone.isPlaying()) activeRingtone.stop();
            } catch (RuntimeException ignored) { }
            activeRingtone = null;
            try {
                if (activeVibrator != null) activeVibrator.cancel();
            } catch (RuntimeException ignored) { }
            activeVibrator = null;
        }
    }

    private static void performAlerts(Context context, boolean alarmMode) {
        if (!isAlertDeliveryEnabled(context)) return;
        android.content.SharedPreferences preferences = FloatingClockStyle.preferences(context);
        if (preferences.getBoolean(FloatingEventStore.ALERT_SOUND_ENABLED_KEY, true)) {
            playSystemDefaultTone(context, alarmMode);
        }
        if (preferences.getBoolean(FloatingEventStore.ALERT_VIBRATION_ENABLED_KEY, true)) {
            vibrate(context, alarmMode ? 60_000L : 500L, alarmMode);
        }
    }

    private static boolean isAlertDeliveryEnabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(
                FloatingEventStore.NOTIFICATION_CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private static void playSystemDefaultTone(Context context, boolean alarmMode) {
        Uri soundUri = RingtoneManager.getActualDefaultRingtoneUri(context,
            alarmMode ? RingtoneManager.TYPE_ALARM : RingtoneManager.TYPE_NOTIFICATION);
        boolean useAlarmStream = alarmMode;
        if (soundUri == null) {
            soundUri = RingtoneManager.getActualDefaultRingtoneUri(context,
                alarmMode ? RingtoneManager.TYPE_NOTIFICATION : RingtoneManager.TYPE_ALARM);
            useAlarmStream = !alarmMode;
        }
        if (soundUri == null) return;
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context, soundUri);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(useAlarmStream ? AudioAttributes.USAGE_ALARM
                    : AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            if (Build.VERSION.SDK_INT >= 28 && alarmMode) ringtone.setLooping(true);
            synchronized (FloatingEventAlarmReceiver.class) {
                if (activeRingtone != null && activeRingtone.isPlaying()) activeRingtone.stop();
                activeRingtone = ringtone;
            }
            ringtone.play();
            RINGTONE_HANDLER.postDelayed(() -> {
                synchronized (FloatingEventAlarmReceiver.class) {
                    if (activeRingtone == ringtone) {
                        try {
                            if (ringtone.isPlaying()) ringtone.stop();
                        } catch (RuntimeException ignored) { }
                        activeRingtone = null;
                    }
                }
            }, alarmMode ? MAX_ALARM_RING_MILLIS : MAX_NOTIFICATION_RING_MILLIS);
        } catch (RuntimeException ignored) {
            // The notification remains visible if the device has no usable default tone.
        }
    }

    /**
     * Alarm vibration as a finite waveform (~60 s total) instead of an infinitely repeating
     * pattern, so playback ends even if the process is killed before a Handler cancellation.
     */
    private static long[] alarmVibrationPattern() {
        long[] cycle = {0, 650, 350, 650, 350, 900}; // 3400 ms per cycle
        int cycles = 18; // 18 * 3400 ms = 61.2 s, matching MAX_ALARM_RING_MILLIS
        long[] pattern = new long[cycle.length * cycles];
        for (int i = 0; i < cycles; i++) {
            System.arraycopy(cycle, 0, pattern, i * cycle.length, cycle.length);
        }
        return pattern;
    }

    private static void vibrate(Context context, long durationMillis, boolean alarmMode) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                VibrationEffect effect = alarmMode
                    ? VibrationEffect.createWaveform(alarmVibrationPattern(), -1)
                    : VibrationEffect.createWaveform(new long[] {0, 250, 180, 250}, -1);
                synchronized (FloatingEventAlarmReceiver.class) {
                    if (activeVibrator != null && activeVibrator != vibrator) {
                        activeVibrator.cancel();
                    }
                    activeVibrator = vibrator;
                }
                vibrator.vibrate(effect);
                if (alarmMode) {
                    RINGTONE_HANDLER.postDelayed(() -> {
                        synchronized (FloatingEventAlarmReceiver.class) {
                            if (activeVibrator == vibrator) {
                                try { vibrator.cancel(); } catch (RuntimeException ignored) { }
                                activeVibrator = null;
                            }
                        }
                    }, durationMillis);
                }
            } else {
                vibrator.vibrate(durationMillis);
            }
        } catch (SecurityException ignored) {
            // The system notification remains available if direct haptics are restricted.
        }
    }
}
