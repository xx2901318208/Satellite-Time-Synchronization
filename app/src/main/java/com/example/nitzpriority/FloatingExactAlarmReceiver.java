package com.example.nitzpriority;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-schedules event reminders when the exact-alarm permission changes.
 * Kept separate from {@link FloatingEventBootReceiver} on purpose: the boot
 * receiver requires RECEIVE_BOOT_COMPLETED, and that permission restriction
 * would also gate this system broadcast, which is not sent by a holder of
 * RECEIVE_BOOT_COMPLETED.
 */
public final class FloatingExactAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if ("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
                .equals(intent.getAction())) {
            FloatingEventStore.rescheduleAll(context);
        }
    }
}
