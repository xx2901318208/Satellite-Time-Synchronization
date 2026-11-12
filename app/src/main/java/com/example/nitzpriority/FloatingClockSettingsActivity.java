package com.example.nitzpriority;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** User-facing settings and live preview for the always-on-top clock. */
public final class FloatingClockSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> colorChips = new ArrayList<>();
    private Switch overlaySwitch;
    private TextView permissionStatus;
    private TextView sizeLabel;
    private TextView transparencyLabel;
    private TextView digitsLabel;
    private TextView preview;
    private boolean updatingSwitch;
    private boolean awaitingPermissionForEnable;

    private final Runnable previewTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed() || preview == null) return;
            int digits = FloatingClockStyle.preferences(FloatingClockSettingsActivity.this)
                .getInt("millisecond_digits", 3);
            preview.setText(FloatingClockStyle.formatClock(System.currentTimeMillis(), digits));
            handler.postDelayed(this, 50L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        styleSystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFE8F6FF, 0xFFF5FAFF, 0xFFEAF8F5}));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("悬浮时间", 27, 0xFF12324D, true);
        page.addView(title, matchWrap());
        TextView intro = text("全局悬浮显示系统时间；可拖动悬浮窗，设置会实时预览。", 14,
            0xFF58718A, false);
        intro.setPadding(0, dp(5), 0, dp(14));
        page.addView(intro, matchWrap());
        TextView rebootHint = text("提示：重启设备后悬浮时钟不会自动启动，点按开机后弹出的恢复通知，或回到本页即可自动恢复。", 13,
            0xFF69849A, false);
        rebootHint.setPadding(0, 0, 0, dp(4));
        page.addView(rebootHint, matchWrap());

        LinearLayout permissionCard = card();
        page.addView(permissionCard, marginMatch(dp(4), dp(8)));
        permissionStatus = text("正在检查悬浮窗权限…", 14, 0xFF24445F, true);
        permissionCard.addView(permissionStatus, matchWrap());
        Button permissionButton = button("前往系统设置授权悬浮窗", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams permissionButtonParams = new LinearLayout.LayoutParams(-1, -2);
        permissionButtonParams.topMargin = dp(8);
        permissionCard.addView(permissionButton, permissionButtonParams);
        permissionButton.setOnClickListener(view -> openOverlayPermissionSettings());

        LinearLayout toggleCard = card();
        page.addView(toggleCard, marginMatch(dp(6), dp(8)));
        overlaySwitch = new Switch(this);
        overlaySwitch.setText("开启全局悬浮时钟");
        overlaySwitch.setTextSize(16);
        overlaySwitch.setTextColor(0xFF193B59);
        toggleCard.addView(overlaySwitch, matchWrap());
        overlaySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitch) return;
            if (checked) enableOverlay(); else disableOverlay();
        });

        TextView appearanceLabel = text("显示样式", 17, 0xFF193B59, true);
        LinearLayout.LayoutParams appearanceParams = marginMatch(dp(12), 0);
        page.addView(appearanceLabel, appearanceParams);

        Button manageEvents = button("管理定时事件", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams manageParams = marginMatch(dp(5), 0);
        page.addView(manageEvents, manageParams);
        manageEvents.setOnClickListener(view ->
            startActivity(new Intent(this, FloatingEventActivity.class)));

        sizeLabel = text("字号：38 sp", 14, 0xFF35546E, true);
        LinearLayout.LayoutParams sizeLabelParams = marginMatch(dp(15), 0);
        page.addView(sizeLabel, sizeLabelParams);
        SeekBar sizeSeek = new SeekBar(this);
        sizeSeek.setMax(56);
        sizeSeek.setProgress(FloatingClockStyle.textSize(this) - 16);
        page.addView(sizeSeek, matchWrap());
        sizeSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 16;
                sizeLabel.setText("字号：" + value + " sp");
                FloatingClockStyle.preferences(FloatingClockSettingsActivity.this).edit()
                    .putInt(FloatingClockStyle.KEY_TEXT_SIZE, value).apply();
                applyPreviewStyle();
                notifyRunningService();
            }
        });

        TextView colorLabel = text("文字颜色", 14, 0xFF35546E, true);
        LinearLayout.LayoutParams colorLabelParams = marginMatch(dp(10), 0);
        page.addView(colorLabel, colorLabelParams);
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        palette.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(palette, marginMatch(dp(2), dp(2)));
        buildColorPalette(palette);

        LinearLayout customColorRow = new LinearLayout(this);
        customColorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams customColorParams = marginMatch(dp(2), 0);
        page.addView(customColorRow, customColorParams);
        EditText customColorInput = new EditText(this);
        customColorInput.setSingleLine(true);
        customColorInput.setHint("自定义颜色，如 #33B5E5");
        customColorInput.setTextSize(13);
        customColorRow.addView(customColorInput,
            new LinearLayout.LayoutParams(0, dp(48), 1));
        Button applyCustomColor = button("应用", 0xFFDDF4FF, 0xFF165B78);
        LinearLayout.LayoutParams applyCustomParams = new LinearLayout.LayoutParams(dp(76), dp(48));
        applyCustomParams.leftMargin = dp(7);
        customColorRow.addView(applyCustomColor, applyCustomParams);
        applyCustomColor.setOnClickListener(view -> {
            try {
                int parsedColor = Color.parseColor(customColorInput.getText().toString().trim());
                int opaqueColor = Color.rgb(Color.red(parsedColor), Color.green(parsedColor),
                    Color.blue(parsedColor));
                FloatingClockStyle.preferences(this).edit()
                    .putInt(FloatingClockStyle.KEY_TEXT_COLOR, opaqueColor).apply();
                updateColorChipSelection(opaqueColor);
                applyPreviewStyle();
                notifyRunningService();
            } catch (IllegalArgumentException exception) {
                customColorInput.setError("请输入有效颜色，例如 #33B5E5");
            }
        });

        transparencyLabel = text("背景透明度：24%", 14, 0xFF35546E, true);
        LinearLayout.LayoutParams transparencyParams = marginMatch(dp(10), 0);
        page.addView(transparencyLabel, transparencyParams);
        SeekBar transparencySeek = new SeekBar(this);
        transparencySeek.setMax(100);
        transparencySeek.setProgress(FloatingClockStyle.backgroundTransparency(this));
        page.addView(transparencySeek, matchWrap());
        transparencySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transparencyLabel.setText("背景透明度：" + progress + "%");
                FloatingClockStyle.preferences(FloatingClockSettingsActivity.this).edit()
                    .putInt(FloatingClockStyle.KEY_BACKGROUND_TRANSPARENCY, progress).apply();
                applyPreviewStyle();
                notifyRunningService();
            }
        });

        digitsLabel = text("毫秒显示位数：3 位", 14, 0xFF35546E, true);
        LinearLayout.LayoutParams digitsParams = marginMatch(dp(10), 0);
        page.addView(digitsLabel, digitsParams);
        SeekBar digitsSeek = new SeekBar(this);
        digitsSeek.setMax(3);
        digitsSeek.setProgress(FloatingClockStyle.preferences(this).getInt("millisecond_digits", 3));
        page.addView(digitsSeek, matchWrap());
        digitsSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                digitsLabel.setText("毫秒显示位数：" + progress + " 位");
                FloatingClockStyle.preferences(FloatingClockSettingsActivity.this).edit()
                    .putInt("millisecond_digits", progress).apply();
                notifyRunningService();
            }
        });

        TextView previewHeading = text("实时预览", 17, 0xFF193B59, true);
        LinearLayout.LayoutParams previewHeadingParams = marginMatch(dp(13), dp(6));
        page.addView(previewHeading, previewHeadingParams);
        LinearLayout previewCard = card();
        previewCard.setGravity(Gravity.CENTER);
        page.addView(previewCard, marginMatch(dp(2), dp(8)));
        preview = new TextView(this);
        preview.setSingleLine(true);
        preview.setIncludeFontPadding(false);
        preview.setText("00:00:00.000");
        previewCard.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        applyPreviewStyle();
        TextView previewHint = text("预览显示本机系统时间；悬浮窗轻点打开此页，拖动可调整位置。授时精度请以诊断页的来源样本和偏差为准。", 12,
            0xFF69849A, false);
        previewHint.setPadding(dp(3), dp(9), dp(3), dp(3));
        page.addView(previewHint, matchWrap());

        TextView author = text("作者：酷安 R1263599071", 12, 0xFF69849A, false);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(18), 0, dp(4));
        page.addView(author, matchWrap());

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        handler.removeCallbacks(previewTicker);
        handler.post(previewTicker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(previewTicker);
        super.onPause();
    }

    private void updatePermissionStatus() {
        boolean granted = Settings.canDrawOverlays(this);
        permissionStatus.setText(granted
            ? "悬浮窗权限已开启"
            : "尚未授权：开启后悬浮时钟才能显示在其他应用上层");
        permissionStatus.setTextColor(granted ? 0xFF13774D : 0xFF9B5E00);
        if (awaitingPermissionForEnable) {
            awaitingPermissionForEnable = false;
            if (granted) {
                setSwitchChecked(true);
                startOverlayService();
            } else {
                setSwitchChecked(false);
            }
            return;
        }
        boolean enabled = FloatingClockStyle.preferences(this)
            .getBoolean(FloatingClockStyle.KEY_ENABLED, false);
        if (enabled && !granted) {
            // Keep KEY_ENABLED: losing the permission is not the user turning
            // the clock off. Stop the service; it restarts once permission returns.
            stopService(new Intent(this, FloatingClockService.class));
        } else if (enabled && granted) {
            // Self-heal after reboot / process death / service crash: restart
            // when the user still wants the clock but it is not running.
            boolean running = FloatingClockStyle.preferences(this)
                .getBoolean(FloatingClockStyle.KEY_RUNNING, false);
            if (!running) startOverlayService();
        }
        setSwitchChecked(enabled);
    }

    private void setSwitchChecked(boolean checked) {
        if (overlaySwitch == null) return;
        updatingSwitch = true;
        overlaySwitch.setChecked(checked);
        updatingSwitch = false;
    }

    private void enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            awaitingPermissionForEnable = true;
            setSwitchChecked(false);
            openOverlayPermissionSettings();
            return;
        }
        FloatingClockStyle.preferences(this).edit()
            .putBoolean(FloatingClockStyle.KEY_ENABLED, true).apply();
        startOverlayService();
    }

    private void startOverlayService() {
        try {
            Intent intent = new Intent(this, FloatingClockService.class)
                .setAction(FloatingClockService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            Toast.makeText(this, "悬浮时钟已开启，可在其他应用上层查看。", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException exception) {
            // Keep KEY_ENABLED and the switch state: a start failure is not
            // the user turning the clock off; onResume will retry.
            FloatingClockStyle.preferences(this).edit()
                .putBoolean(FloatingClockStyle.KEY_RUNNING, false)
                .putString(FloatingClockStyle.KEY_LAST_ERROR,
                    "启动服务失败：" + exception.getClass().getSimpleName()).apply();
            Toast.makeText(this, "启动失败，请检查通知和悬浮窗权限。", Toast.LENGTH_LONG).show();
        }
    }

    private void disableOverlay() {
        FloatingClockStyle.preferences(this).edit()
            .putBoolean(FloatingClockStyle.KEY_ENABLED, false).apply();
        stopService(new Intent(this, FloatingClockService.class));
        Toast.makeText(this, "悬浮时钟已关闭。", Toast.LENGTH_SHORT).show();
    }

    private void openOverlayPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开悬浮窗设置，请到系统设置中手动授权。",
                Toast.LENGTH_LONG).show();
        }
    }

    private void buildColorPalette(LinearLayout palette) {
        int selected = FloatingClockStyle.textColor(this);
        for (int i = 0; i < FloatingClockStyle.TEXT_COLORS.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText("Aa");
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(16);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setTextColor(FloatingClockStyle.TEXT_COLORS[i]);
            chip.setContentDescription(FloatingClockStyle.COLOR_NAMES[i] + "文字颜色");
            chip.setMinimumWidth(dp(48));
            chip.setMinimumHeight(dp(42));
            chip.setPadding(dp(5), 0, dp(5), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            palette.addView(chip, params);
            colorChips.add(chip);
            chip.setOnClickListener(view -> {
                FloatingClockStyle.preferences(this).edit()
                    .putInt(FloatingClockStyle.KEY_TEXT_COLOR,
                        FloatingClockStyle.TEXT_COLORS[index]).apply();
                updateColorChipSelection(FloatingClockStyle.TEXT_COLORS[index]);
                applyPreviewStyle();
                notifyRunningService();
            });
        }
        updateColorChipSelection(selected);
    }

    private void updateColorChipSelection(int selectedColor) {
        for (int i = 0; i < colorChips.size(); i++) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xDFFFFFFF);
            background.setCornerRadius(dp(13));
            background.setStroke(dp(selectedColor == FloatingClockStyle.TEXT_COLORS[i] ? 2 : 1),
                selectedColor == FloatingClockStyle.TEXT_COLORS[i] ? 0xFF287FDB : 0xFFD8E5EE);
            colorChips.get(i).setBackground(background);
        }
    }

    private void applyPreviewStyle() {
        if (preview != null) FloatingClockStyle.apply(this, preview);
    }

    private void notifyRunningService() {
        // The foreground service observes the persisted settings and applies them on its next tick.
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xF2FFFFFF, 0xDDF0FAFF});
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), 0xDFFFFFFF);
        card.setBackground(background);
        card.setElevation(dp(3));
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextColor(foreground);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        return view;
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

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
