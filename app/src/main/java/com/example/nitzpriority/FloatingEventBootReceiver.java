package com.example.nitzpriority;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores pending event reminders after reboot or exact-alarm permission changes. */
public final class FloatingEventBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("android.intent.action.BOOT_COMPLETED".equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
            FloatingEventStore.rescheduleAll(context);
        }
    }
}
