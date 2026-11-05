package com.example.nitzpriority;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Passive diagnostics for telephony time suggestions.
 *
 * The interpolated values are diagnostic only: this class never edits a suggestion or sets the
 * system clock. Android remains responsible for validating and applying all time suggestions.
 */
final class NitzSampleMonitor {
    private static final String TAG = "NitzPriority";
    private static final long MAX_SAMPLE_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private static final long FUTURE_TOLERANCE_MILLIS = 2_000;
    private static final long MIN_EPOCH_MILLIS = 1_577_836_800_000L; // 2020-01-01 UTC
    private static final long MAX_EPOCH_MILLIS = 4_102_444_800_000L; // 2100-01-01 UTC
    private static final long MAX_REGRESSION_STEP_ERROR_MILLIS = 120_000;
    private static final long MIN_DRIFT_SPAN_MILLIS = 10L * 60 * 1000;
    private static final int MAX_HISTORY_SIZE = 64;

    private final Map<Integer, SlotState> states = new HashMap<>();

    synchronized String observe(Object suggestion) throws Exception {
        int slotIndex = readInt(suggestion, "getSlotIndex");
        Object unixEpochTime = invoke(suggestion, "getUnixEpochTime");
        if (unixEpochTime == null) return "slot=" + slotIndex + " ignored=null_time";

        long sampleElapsed = readLong(unixEpochTime, "getElapsedRealtimeMillis");
        long sampleUnix = readLong(unixEpochTime, "getUnixEpochTimeMillis");
        long nowElapsed = SystemClock.elapsedRealtime();
        long ageMillis = nowElapsed - sampleElapsed;

        String invalidReason = validate(sampleElapsed, sampleUnix, ageMillis, nowElapsed);
        if (invalidReason != null) {
            return "slot=" + slotIndex + " ignored=" + invalidReason
                + " sampleElapsed=" + sampleElapsed + " ageMs=" + ageMillis
                + " sampleUnixMs=" + sampleUnix;
        }

        long predictedNow = sampleUnix + ageMillis;
        SlotState state = states.get(slotIndex);
        if (state == null) {
            state = new SlotState();
            states.put(slotIndex, state);
        }

        Sample sample = new Sample(sampleElapsed, sampleUnix);
        if (state.lastSeen != null
                && state.lastSeen.elapsedRealtimeMillis == sampleElapsed
                && state.lastSeen.unixEpochTimeMillis == sampleUnix) {
            return summary(slotIndex, sample, ageMillis, predictedNow, state, "duplicate");
        }

        String status = acceptForDiagnostics(state, sample);
        state.lastSeen = sample;
        return summary(slotIndex, sample, ageMillis, predictedNow, state, status);
    }

    private static String validate(long sampleElapsed, long sampleUnix, long ageMillis,
            long nowElapsed) {
        if (sampleElapsed < 0) return "negative_elapsed";
        if (sampleUnix < MIN_EPOCH_MILLIS || sampleUnix >= MAX_EPOCH_MILLIS) {
            return "implausible_epoch";
        }
        if (ageMillis < -FUTURE_TOLERANCE_MILLIS) return "future_elapsed";
        if (ageMillis > MAX_SAMPLE_AGE_MILLIS) return "stale_over_24h";
        if (sampleElapsed > nowElapsed + FUTURE_TOLERANCE_MILLIS) return "future_elapsed";
        return null;
    }

    private static String acceptForDiagnostics(SlotState state, Sample sample) {
        Sample previous = state.lastAccepted;
        if (previous == null) {
            state.history.addLast(sample);
            state.lastAccepted = sample;
            return "accepted_first_sample";
        }

        long elapsedDelta = sample.elapsedRealtimeMillis - previous.elapsedRealtimeMillis;
        if (elapsedDelta <= 0) {
            state.rejectedNonMonotonic++;
            return "filtered_non_monotonic_elapsed";
        }

        long unixDelta = sample.unixEpochTimeMillis - previous.unixEpochTimeMillis;
        long stepError = unixDelta - elapsedDelta;
        if (Math.abs(stepError) > MAX_REGRESSION_STEP_ERROR_MILLIS) {
            // A carrier clock may legitimately be corrected. Keep the newest valid suggestion as
            // the interpolation anchor, but start a fresh history so the discontinuity cannot
            // distort a drift estimate. The platform suggestion itself is never rejected.
            state.discontinuities++;
            state.history.clear();
            state.history.addLast(sample);
            state.lastAccepted = sample;
            return "accepted_rebased_stepErrorMs=" + stepError;
        }

        state.lastStepErrorMillis = stepError;
        if (elapsedDelta >= 1_000) {
            state.history.addLast(sample);
            while (state.history.size() > MAX_HISTORY_SIZE) state.history.removeFirst();
        }
        state.lastAccepted = sample;
        state.acceptedCount++;
        return elapsedDelta < 1_000 ? "accepted_anchor_only_short_interval" : "accepted";
    }

    private static String summary(int slotIndex, Sample sample, long ageMillis, long predictedNow,
            SlotState state, String status) {
        Drift drift = estimateDrift(state.history);
        String driftText = drift == null
            ? "driftPpm=warming_up"
            : "driftPpm=" + String.format(java.util.Locale.US, "%.1f", drift.ppm)
                + " driftResolutionApproxPpm=±"
                + String.format(java.util.Locale.US, "%.1f", drift.quantizationPpm)
                + " driftConfidence=" + (drift.spanMillis >= 6L * 60 * 60 * 1000
                    ? "better_after_6h_span" : "low_short_span");
        return "slot=" + slotIndex + " sample=" + status
            + " sourceElapsedMs=" + sample.elapsedRealtimeMillis
            + " sourceUnixMs=" + sample.unixEpochTimeMillis
            + " ageMs=" + ageMillis
            + " interpolatedNowUnixMs=" + predictedNow
            + " sourceResolution=" + (sample.unixEpochTimeMillis % 1000 == 0
                ? "whole_second_or_coarser" : "subsecond")
            + " lastStepErrorMs=" + state.lastStepErrorMillis
            + " accepted=" + state.acceptedCount
            + " discontinuities=" + state.discontinuities
            + " nonMonotonicFiltered=" + state.rejectedNonMonotonic
            + " history=" + state.history.size()
            + " " + driftText;
    }

    private static Drift estimateDrift(Deque<Sample> history) {
        if (history.size() < 3) return null;
        List<Sample> samples = new ArrayList<>(history);
        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);
        long span = last.elapsedRealtimeMillis - first.elapsedRealtimeMillis;
        if (span < MIN_DRIFT_SPAN_MILLIS) return null;

        double meanX = 0;
        double meanY = 0;
        for (Sample sample : samples) {
            meanX += sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis;
            meanY += sample.unixEpochTimeMillis - first.unixEpochTimeMillis;
        }
        meanX /= samples.size();
        meanY /= samples.size();

        double covariance = 0;
        double variance = 0;
        for (Sample sample : samples) {
            double x = (sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis) - meanX;
            double y = (sample.unixEpochTimeMillis - first.unixEpochTimeMillis) - meanY;
            covariance += x * y;
            variance += x * x;
        }
        if (variance <= 0) return null;

        double rate = covariance / variance;
        double ppm = (rate - 1.0) * 1_000_000.0;
        // NITZ commonly carries whole-second timestamps. This is only a quantization floor;
        // changing carrier transmission delay can make the true uncertainty larger.
        double quantizationPpm = 2_000_000_000.0 / span;
        return new Drift(ppm, quantizationPpm, span);
    }

    private static int readInt(Object target, String methodName) throws Exception {
        Object value = invoke(target, methodName);
        if (!(value instanceof Number)) throw new IllegalStateException(methodName + " not numeric");
        return ((Number) value).intValue();
    }

    private static long readLong(Object target, String methodName) throws Exception {
        Object value = invoke(target, methodName);
        if (!(value instanceof Number)) throw new IllegalStateException(methodName + " not numeric");
        return ((Number) value).longValue();
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static final class SlotState {
        final Deque<Sample> history = new ArrayDeque<>();
        Sample lastAccepted;
        Sample lastSeen;
        long lastStepErrorMillis;
        int acceptedCount;
        int discontinuities;
        int rejectedNonMonotonic;
    }

    private static final class Sample {
        final long elapsedRealtimeMillis;
        final long unixEpochTimeMillis;

        Sample(long elapsedRealtimeMillis, long unixEpochTimeMillis) {
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            this.unixEpochTimeMillis = unixEpochTimeMillis;
        }
    }

    private static final class Drift {
        final double ppm;
        final double quantizationPpm;
        final long spanMillis;

        Drift(double ppm, double quantizationPpm, long spanMillis) {
            this.ppm = ppm;
            this.quantizationPpm = quantizationPpm;
            this.spanMillis = spanMillis;
        }
    }
}
