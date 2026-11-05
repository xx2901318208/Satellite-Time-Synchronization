package com.example.nitzpriority;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Local event storage and notification alarm scheduling for the floating clock. */
final class FloatingEventStore {
    static final String EVENTS_KEY = "floating_events_json";
    static final String DEFAULT_REMINDER_KEY = "default_reminder_minutes";
    static final String ALERT_SOUND_ENABLED_KEY = "event_alert_sound_enabled";
    static final String ALERT_VIBRATION_ENABLED_KEY = "event_alert_vibration_enabled";
    static final String NOTIFICATION_CHANNEL_ID = "floating_event_reminders_v3";
    static final int DEFAULT_REMINDER_MINUTES = 5;

    static final class Event {
        final String id;
        final String title;
        final long targetTimeMillis;
        final String url;
        final int reminderMinutes;

        Event(String id, String title, long targetTimeMillis, String url, int reminderMinutes) {
            this.id = id;
            this.title = title;
            this.targetTimeMillis = targetTimeMillis;
            this.url = url;
            this.reminderMinutes = reminderMinutes;
        }
    }

    private FloatingEventStore() { }

    static List<Event> load(Context context) {
        List<Event> events = new ArrayList<>();
        String encoded = FloatingClockStyle.preferences(context).getString(EVENTS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String id = object.optString("id", "");
                String title = object.optString("title", "").trim();
                long target = object.optLong("target", 0L);
                String url = object.optString("url", "").trim();
                int reminder = Math.max(0, Math.min(1440, object.optInt("reminder", 0)));
                if (!id.isEmpty() && !title.isEmpty() && target > 0) {
                    events.add(new Event(id, title, target, url, reminder));
                }
            }
        } catch (Exception ignored) {
            // A malformed local preference should not prevent the app from opening.
        }
        return events;
    }

    static Event find(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Event event : load(context)) {
            if (id.equals(event.id)) return event;
        }
        return null;
    }

    static void save(Context context, List<Event> events) {
        for (Event oldEvent : load(context)) cancelAll(context, oldEvent);
        JSONArray array = new JSONArray();
        for (Event event : events) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", event.id);
                object.put("title", event.title);
                object.put("target", event.targetTimeMillis);
                object.put("url", event.url);
                object.put("reminder", event.reminderMinutes);
                array.put(object);
            } catch (Exception ignored) {
                // Skip a single bad event rather than losing other local events.
            }
        }
        FloatingClockStyle.preferences(context).edit()
            .putString(EVENTS_KEY, array.toString()).apply();
        rescheduleAll(context);
    }

    static void rescheduleAll(Context context) {
        List<Event> events = load(context);
        long now = System.currentTimeMillis();
        for (Event event : events) {
            cancelAll(context, event);
            if (event.targetTimeMillis <= now) continue;
            if (event.reminderMinutes > 0) {
                long reminderAt = event.targetTimeMillis - event.reminderMinutes * 60_000L;
                if (reminderAt > now) schedule(context, event, "reminder", reminderAt);
            }
            if (event.targetTimeMillis - 3_000L > now) {
                schedule(context, event, "tick_3", event.targetTimeMillis - 3_000L);
            }
            if (event.targetTimeMillis - 2_000L > now) {
                schedule(context, event, "tick_2", event.targetTimeMillis - 2_000L);
            }
            if (event.targetTimeMillis - 1_000L > now) {
                schedule(context, event, "tick_1", event.targetTimeMillis - 1_000L);
            }
            schedule(context, event, "due", event.targetTimeMillis);
        }
    }

    static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    static boolean scheduleTestAlarm(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null || !canScheduleExactAlarms(context)) return false;
        long triggerAt = System.currentTimeMillis() + 12_000L;
        Intent intent = new Intent(context, FloatingEventAlarmReceiver.class)
            .setAction("com.example.nitzpriority.event.test_alarm")
            .putExtra("event_id", "alarm_test")
            .putExtra("event_kind", "test_alarm");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent operation = PendingIntent.getBroadcast(context, 83_502, intent, flags);
        PendingIntent showIntent = PendingIntent.getActivity(context, 83_503,
            new Intent(context, FloatingEventActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), flags);
        try {
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void cancelAll(Context context, Event event) {
        cancel(context, event, "reminder");
        cancel(context, event, "due");
        cancel(context, event, "tick_3");
        cancel(context, event, "tick_2");
        cancel(context, event, "tick_1");
    }

    static String formatTarget(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(timeMillis));
    }

    static boolean isWebUrl(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static void schedule(Context context, Event event, String kind, long when) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pendingIntent = pendingIntent(context, event, kind,
            PendingIntent.FLAG_UPDATE_CURRENT);
        if (pendingIntent == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pendingIntent);
            } else if ("reminder".equals(kind) || "due".equals(kind)) {
                PendingIntent showIntent = alarmShowIntent(context, event);
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showIntent),
                    pendingIntent);
            } else if (Build.VERSION.SDK_INT >= 23) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pendingIntent);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, when, pendingIntent);
            }
        } catch (SecurityException ignored) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pendingIntent);
        }
    }

    private static PendingIntent alarmShowIntent(Context context, Event event) {
        Intent intent = new Intent(context, FloatingEventActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("alarm_event_id", event.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, (event.id + "alarm_show").hashCode(), intent,
            flags);
    }

    private static void cancel(Context context, Event event, String kind) {
        PendingIntent pendingIntent = pendingIntent(context, event, kind, PendingIntent.FLAG_NO_CREATE);
        if (pendingIntent == null) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    private static PendingIntent pendingIntent(Context context, Event event, String kind, int flag) {
        Intent intent = new Intent(context, FloatingEventAlarmReceiver.class);
        intent.setAction("com.example.nitzpriority.event." + kind + "." + event.id);
        intent.putExtra("event_id", event.id);
        intent.putExtra("event_kind", kind);
        int flags = flag;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, (event.id + kind).hashCode(), intent, flags);
    }
}
