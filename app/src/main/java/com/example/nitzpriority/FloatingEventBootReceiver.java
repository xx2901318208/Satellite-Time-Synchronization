package com.example.nitzpriority;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * Restores pending event reminders after reboot. Also posts a tap-to-restore
 * notification for the floating clock: Android 12+ forbids starting a
 * foreground service from the background (BOOT_COMPLETED), so the clock is
 * restarted from the foreground when the user taps the notification and the
 * settings page self-heals in onResume.
 */
public final class FloatingEventBootReceiver extends BroadcastReceiver {
    private static final String RESTORE_CHANNEL_ID = "floating_clock_restore";
    private static final int RESTORE_NOTIFICATION_ID = 8141;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            FloatingEventStore.rescheduleAll(context);
            postClockRestoreNotification(context);
        }
    }

    private static void postClockRestoreNotification(Context context) {
        boolean enabled = FloatingClockStyle.preferences(context)
            .getBoolean(FloatingClockStyle.KEY_ENABLED, false);
        if (!enabled || !Settings.canDrawOverlays(context)) return;
        try {
            NotificationManager manager =
                context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(RESTORE_CHANNEL_ID,
                    "悬浮时钟恢复提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("重启后点按通知回到设置页，恢复悬浮时钟");
                manager.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, FloatingClockSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent contentIntent = PendingIntent.getActivity(
                context, RESTORE_NOTIFICATION_ID, open, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, RESTORE_CHANNEL_ID)
                : new Notification.Builder(context);
            Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("悬浮时钟待恢复")
                .setContentText("重启后悬浮时钟未运行，点按回到设置页自动恢复")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
            manager.notify(RESTORE_NOTIFICATION_ID, notification);
        } catch (RuntimeException ignored) {
            // A missing notification permission must not break boot restore.
        }
    }
}
