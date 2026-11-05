package com.example.nitzpriority;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared appearance preferences and rendering helpers for the floating clock. */
final class FloatingClockStyle {
    static final String PREFS = "floating_clock";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_TEXT_SIZE = "text_size_sp";
    static final String KEY_TEXT_COLOR = "text_color";
    static final String KEY_BACKGROUND_TRANSPARENCY = "background_transparency";
    static final String KEY_X = "overlay_x";
    static final String KEY_Y = "overlay_y";
    static final String KEY_RUNNING = "running";
    static final String KEY_LAST_ERROR = "last_error";

    static final String[] COLOR_NAMES = {"绿色", "白色", "青色", "黄色", "橙色", "黑色"};
    static final int[] TEXT_COLORS = {
        0xFF22E06B, Color.WHITE, 0xFF67E8F9, 0xFFFFD166, 0xFFFF8A65, 0xFF172033
    };
    static final int DEFAULT_TEXT_SIZE_SP = 38;
    static final int DEFAULT_TEXT_COLOR = 0xFF22E06B;
    static final int DEFAULT_BACKGROUND_TRANSPARENCY = 100;

    // SimpleDateFormat is not thread-safe; each caller thread gets its own instance.
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA));
    private static final ThreadLocal<SimpleDateFormat> CLOCK_FORMAT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.CHINA));

    private FloatingClockStyle() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int textSize(Context context) {
        return clamp(preferences(context).getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP), 16, 72);
    }

    static int textColor(Context context) {
        return preferences(context).getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR);
    }

    static int backgroundTransparency(Context context) {
        return clamp(preferences(context).getInt(KEY_BACKGROUND_TRANSPARENCY,
            DEFAULT_BACKGROUND_TRANSPARENCY), 0, 100);
    }

    static void apply(Context context, TextView view) {
        int color = textColor(context);
        int transparency = backgroundTransparency(context);
        view.setTextSize(textSize(context));
        view.setTextColor(color);
        view.setGravity(android.view.Gravity.CENTER);
        int padding = dp(context, 13);
        view.setPadding(padding, dp(context, 8), padding, dp(context, 8));

        int backgroundRgb = isDark(color) ? 0xFFF4F8FC : 0xFF10243A;
        int alpha = Math.round(255f * (100 - transparency) / 100f);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(alpha, Color.red(backgroundRgb),
            Color.green(backgroundRgb), Color.blue(backgroundRgb)));
        background.setCornerRadius(dp(context, 18));
        background.setStroke(dp(context, 1), Color.argb(Math.min(220, alpha),
            isDark(color) ? 120 : 230, isDark(color) ? 155 : 245, isDark(color) ? 185 : 255));
        view.setBackground(background);
        view.setElevation(dp(context, 8));
    }

    static String formatTime(long timeMillis) {
        return TIME_FORMAT.get().format(new Date(timeMillis));
    }

    static String formatClock(long timeMillis, int millisecondDigits) {
        String base = CLOCK_FORMAT.get().format(new Date(timeMillis));
        return base + fraction(Math.floorMod(timeMillis, 1_000L), millisecondDigits);
    }

    private static String fraction(long millis, int digits) {
        int safeDigits = clamp(digits, 0, 3);
        if (safeDigits == 0) return "";
        String value = String.format(Locale.US, "%03d", millis);
        return "." + value.substring(0, safeDigits);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static boolean isDark(int color) {
        double luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color)
            + 0.114 * Color.blue(color);
        return luminance < 128;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
