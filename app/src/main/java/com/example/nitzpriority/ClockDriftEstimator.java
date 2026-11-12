package com.example.nitzpriority;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clock-drift estimation from offset history.
 *
 * <p>An offset sample is {@code referenceUnix - phoneUnix} at one
 * {@code elapsedRealtime} instant: positive means the phone lags the
 * reference. Samples come from two existing flows, never from a new
 * pipeline: GNSS trend points recorded by the diagnostics page and
 * NTSC NTP results (diagnostics page refresh and the periodic sync
 * tick). A linear regression over the samples estimates the crystal
 * drift rate in ppm.
 *
 * <p>The estimate is used for exactly two things, and nothing else:
 * (1) the diagnostics page shows "drift +X ppm, predicted current
 * error about +/-Y ms"; (2) when the predicted error at the next
 * scheduled sync exceeds the network threshold, the periodic sync
 * fires early. The predicted value is NEVER used to step the clock:
 * a regression can be wrong (bad samples, a temperature change, a
 * clock step) and a wrong prediction applied to the clock would move
 * the clock the wrong way with no reference to check against.
 *
 * <p>Acceptance is fail-closed. An estimate is only returned when:
 * at least 3 points, spanning at least 10 minutes, all within the
 * last hour, MAD-filtered and OLS-fitted with max residual 500 ms,
 * and |drift| at most 100 ppm (a phone crystal cannot drift faster
 * than that; anything larger means bad data or a clock step). A
 * sample that jumps more than 1000 ms plus the 100 ppm allowance over
 * its gap from the previous sample is treated as a clock step: the
 * history is cleared and the fit restarts from that sample, exactly
 * like NitzSampleMonitor's rebase on a carrier-clock discontinuity.
 */
final class ClockDriftEstimator {
    /** SharedPreferences file shared with the diagnostics page ("settings"). */
    static final String PREFS_NAME = "settings";
    static final String SAMPLES_KEY = "drift_samples";
    /** Periodic-sync switch, visible on the diagnostics page. Default on. */
    static final String KEY_PERIODIC_SYNC_ENABLED = "periodic_sync_enabled";
    static final String SOURCE_GNSS = "gnss";
    static final String SOURCE_NTP = "ntp";

    static final int MIN_POINTS = 3;
    static final long MIN_SPAN_MILLIS = 10L * 60 * 1000;
    /** Newest sample older than this: the estimate has expired. */
    static final long MAX_SAMPLE_AGE_MILLIS = 60L * 60 * 1000;
    /** Samples older than this are dropped from storage. */
    static final long HISTORY_WINDOW_MILLIS = 6L * 60 * 60 * 1000;
    static final int MAX_SAMPLES = 64;
    /** Minimum gap between stored samples; the diagnostics page samples
     * about once a minute and raw GNSS measurements arrive at 1 Hz. */
    static final long MIN_SAMPLE_INTERVAL_MILLIS = 55_000L;
    static final long MAX_PLAUSIBLE_OFFSET_MILLIS = 60_000L;
    /** A jump beyond this (plus the 100 ppm allowance over the gap) is a
     * clock step, not drift: clear the history and restart the fit.
     * 1000 ms sits between single-sample glitches (an NTP outlier is
     * normally well under a second and is left for the MAD filter) and
     * genuine steps (a force jump or a >2 s NITZ correction). */
    static final long STEP_CLEAR_MILLIS = 1_000L;
    static final double MAX_DRIFT_PPM = 100.0;
    static final long MAD_FLOOR_MILLIS = 500L;
    static final long MAX_RESIDUAL_MILLIS = 500L;

    private ClockDriftEstimator() {
    }

    /** One offset sample: reference minus phone, at an elapsed instant. */
    static final class Sample {
        final long elapsedRealtimeMillis;
        final long offsetMillis;
        final long uncertaintyMillis;
        final String source;

        Sample(long elapsedRealtimeMillis, long offsetMillis, long uncertaintyMillis,
                String source) {
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            this.offsetMillis = offsetMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.source = source;
        }
    }

    /** An accepted drift fit, or null when no trustworthy fit exists. */
    static final class Estimate {
        /** Slope of offset vs elapsed, in ppm. Positive: the phone clock
         * runs slow (offset grows); negative: it runs fast. */
        final double ppm;
        final long spanMillis;
        final int pointCount;
        final long lastElapsedRealtimeMillis;
        final long lastOffsetMillis;
        final long lastUncertaintyMillis;

        Estimate(double ppm, long spanMillis, int pointCount,
                long lastElapsedRealtimeMillis, long lastOffsetMillis,
                long lastUncertaintyMillis) {
            this.ppm = ppm;
            this.spanMillis = spanMillis;
            this.pointCount = pointCount;
            this.lastElapsedRealtimeMillis = lastElapsedRealtimeMillis;
            this.lastOffsetMillis = lastOffsetMillis;
            this.lastUncertaintyMillis = lastUncertaintyMillis;
        }
    }

    /** What the store holds, for "warming up" display when no fit exists. */
    static final class Summary {
        final int pointCount;
        final long spanMillis;
        /** Age of the newest sample, or -1 when the store is empty. */
        final long newestAgeMillis;

        Summary(int pointCount, long spanMillis, long newestAgeMillis) {
            this.pointCount = pointCount;
            this.spanMillis = spanMillis;
            this.newestAgeMillis = newestAgeMillis;
        }
    }

    static boolean isPeriodicSyncEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_PERIODIC_SYNC_ENABLED, true);
    }

    /**
     * Records one offset sample. Returns false when the sample was
     * rejected (implausible, non-monotonic, too soon after the last
     * one, or a step that cleared the history -- in the step case the
     * sample itself is kept as the first point of a fresh history).
     */
    static boolean recordSample(SharedPreferences prefs, long elapsedRealtimeMillis,
            long offsetMillis, long uncertaintyMillis, String source) {
        if (prefs == null || elapsedRealtimeMillis <= 0) return false;
        if (Math.abs(offsetMillis) > MAX_PLAUSIBLE_OFFSET_MILLIS) return false;
        if (uncertaintyMillis < 0) return false;
        if (!SOURCE_GNSS.equals(source) && !SOURCE_NTP.equals(source)) return false;
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (elapsedRealtimeMillis > nowElapsed + 2_000L) return false;

        List<Sample> samples = load(prefs);
        if (!samples.isEmpty()) {
            Sample last = samples.get(samples.size() - 1);
            if (elapsedRealtimeMillis < last.elapsedRealtimeMillis) {
                // elapsedRealtime reset after a reboot: the old history
                // belongs to a different boot and cannot be regressed.
                samples.clear();
            } else if (elapsedRealtimeMillis == last.elapsedRealtimeMillis) {
                return false;
            } else if (elapsedRealtimeMillis - last.elapsedRealtimeMillis
                    < MIN_SAMPLE_INTERVAL_MILLIS) {
                return false;
            } else {
                long gap = elapsedRealtimeMillis - last.elapsedRealtimeMillis;
                long stepAllowance = STEP_CLEAR_MILLIS
                    + (long) (MAX_DRIFT_PPM * gap / 1_000_000.0);
                if (Math.abs(offsetMillis - last.offsetMillis) > stepAllowance) {
                    // A clock step (force jump, auto correction larger than
                    // drift can explain, or a bad sample): rebase, do not
                    // let the discontinuity bend the fit.
                    samples.clear();
                }
            }
        }
        samples.add(new Sample(elapsedRealtimeMillis, offsetMillis,
            uncertaintyMillis, source));
        prune(samples, nowElapsed);
        save(prefs, samples);
        return true;
    }

    /** Current drift fit, or null when no trustworthy fit exists. */
    static Estimate estimate(SharedPreferences prefs, long nowElapsed) {
        if (prefs == null || nowElapsed < 0) return null;
        List<Sample> all = load(prefs);
        if (!all.isEmpty()
                && nowElapsed < all.get(all.size() - 1).elapsedRealtimeMillis) {
            // Reboot since the samples were recorded; they are unusable.
            prefs.edit().remove(SAMPLES_KEY).apply();
            return null;
        }
        // Only samples from the last hour take part: drift is a physical
        // property that can change with temperature, and a fit spanning a
        // clock step or a reboot must not survive.
        List<Sample> samples = new ArrayList<>();
        for (Sample sample : all) {
            if (nowElapsed - sample.elapsedRealtimeMillis <= MAX_SAMPLE_AGE_MILLIS) {
                samples.add(sample);
            }
        }
        if (samples.size() < MIN_POINTS) return null;
        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);
        long span = last.elapsedRealtimeMillis - first.elapsedRealtimeMillis;
        if (span < MIN_SPAN_MILLIS) return null;

        // Robust pre-filter, same pattern as NitzSampleMonitor: work on the
        // change in offset since the first sample and drop points beyond
        // 3 robust sigmas (MAD) around its median. Note the residuals here
        // are offset deltas, NOT (offset - elapsed): the samples already
        // carry offsets (reference - phone), so subtracting elapsed would
        // let the elapsed values dominate and the filter would never fire.
        // For a perfect clock the offset is constant, so the residuals are
        // just noise plus genuine drift (at most ~100 ppm * span).
        List<Long> residuals = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            residuals.add(sample.offsetMillis - first.offsetMillis);
        }
        long medianResidual = medianLong(residuals);
        List<Long> deviations = new ArrayList<>(residuals.size());
        for (long residual : residuals) deviations.add(Math.abs(residual - medianResidual));
        long mad = medianLong(deviations);
        long rejectThreshold = Math.max(MAD_FLOOR_MILLIS, (long) (3 * 1.4826 * mad));
        List<Sample> kept = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            if (Math.abs(residuals.get(i) - medianResidual) <= rejectThreshold) {
                kept.add(samples.get(i));
            }
        }
        if (kept.size() < MIN_POINTS) return null;
        samples = kept;
        first = samples.get(0);
        last = samples.get(samples.size() - 1);
        span = last.elapsedRealtimeMillis - first.elapsedRealtimeMillis;
        if (span < MIN_SPAN_MILLIS) return null;

        double meanX = 0;
        double meanY = 0;
        for (Sample sample : samples) {
            meanX += sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis;
            meanY += sample.offsetMillis - first.offsetMillis;
        }
        meanX /= samples.size();
        meanY /= samples.size();
        double covariance = 0;
        double variance = 0;
        for (Sample sample : samples) {
            double x = (sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis) - meanX;
            double y = (sample.offsetMillis - first.offsetMillis) - meanY;
            covariance += x * y;
            variance += x * x;
        }
        if (variance <= 0) return null;
        double slope = covariance / variance;
        double ppm = slope * 1_000_000.0;
        if (Double.isNaN(ppm) || Double.isInfinite(ppm)
                || Math.abs(ppm) > MAX_DRIFT_PPM) return null;

        // Spread check on the fitted line: a step or a wildly scattered
        // sample set must not produce a confident-looking ppm.
        double maxResidual = 0;
        for (Sample sample : samples) {
            double predicted = (sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis)
                * slope;
            double residual = Math.abs(
                (sample.offsetMillis - first.offsetMillis) - predicted);
            maxResidual = Math.max(maxResidual, residual);
        }
        if (maxResidual > MAX_RESIDUAL_MILLIS) return null;

        return new Estimate(ppm, span, samples.size(), last.elapsedRealtimeMillis,
            last.offsetMillis, last.uncertaintyMillis);
    }

    /** Predicted offset (reference minus phone) at one elapsed instant. */
    static long projectedOffsetMillis(Estimate estimate, long nowElapsed) {
        if (estimate == null) return 0L;
        double age = Math.max(0L, nowElapsed - estimate.lastElapsedRealtimeMillis);
        return estimate.lastOffsetMillis
            + Math.round(estimate.ppm * age / 1_000_000.0);
    }

    /**
     * Predicted current error budget: drift growth since the last sample
     * plus that sample's own uncertainty. This is the "+/-Y ms" shown
     * next to the ppm figure, and the quantity compared against a
     * source threshold to decide an early sync.
     */
    static long projectedErrorMillis(Estimate estimate, long nowElapsed) {
        if (estimate == null) return -1L;
        double age = Math.max(0L, nowElapsed - estimate.lastElapsedRealtimeMillis);
        return Math.round(Math.abs(estimate.ppm) * age / 1_000_000.0)
            + estimate.lastUncertaintyMillis;
    }

    /**
     * Predicted drift growth alone since the last sample: |ppm| * age,
     * WITHOUT that sample's measurement uncertainty. The periodic sync's
     * early-sync decision uses this, not {@link #projectedErrorMillis}:
     * uncertainty is measurement noise, not drift, and it alone — NTSC
     * RTT/2 is routinely 100-300 ms on device, raw GNSS 100 ms — already
     * exceeds the 50 ms network threshold, so comparing the full error
     * budget would fire early sync on almost every tick, collapsing the
     * 5-minute interval to 60 seconds and keeping GPS running nearly
     * non-stop indoors. projectedErrorMillis keeps its uncertainty term
     * for display (the "+/-Y ms" shown next to the ppm figure).
     */
    static long projectedDriftMillis(Estimate estimate, long nowElapsed) {
        if (estimate == null) return -1L;
        double age = Math.max(0L, nowElapsed - estimate.lastElapsedRealtimeMillis);
        return Math.round(Math.abs(estimate.ppm) * age / 1_000_000.0);
    }

    /** Store contents for the "warming up / expired" display. */
    static Summary summary(SharedPreferences prefs, long nowElapsed) {
        if (prefs == null || nowElapsed < 0) return new Summary(0, 0, -1L);
        List<Sample> samples = load(prefs);
        if (samples.isEmpty()) return new Summary(0, 0, -1L);
        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);
        if (nowElapsed < last.elapsedRealtimeMillis) return new Summary(0, 0, -1L);
        return new Summary(samples.size(),
            last.elapsedRealtimeMillis - first.elapsedRealtimeMillis,
            nowElapsed - last.elapsedRealtimeMillis);
    }

    private static void prune(List<Sample> samples, long nowElapsed) {
        long cutoff = nowElapsed - HISTORY_WINDOW_MILLIS;
        samples.removeIf(sample -> sample.elapsedRealtimeMillis < cutoff
            || sample.elapsedRealtimeMillis > nowElapsed);
        while (samples.size() > MAX_SAMPLES) samples.remove(0);
    }

    private static List<Sample> load(SharedPreferences prefs) {
        List<Sample> samples = new ArrayList<>();
        String encoded = prefs.getString(SAMPLES_KEY, "");
        if (encoded == null || encoded.isEmpty()) return samples;
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",");
            if (fields.length != 4) continue;
            try {
                samples.add(new Sample(Long.parseLong(fields[0]),
                    Long.parseLong(fields[1]), Long.parseLong(fields[2]), fields[3]));
            } catch (NumberFormatException ignored) {
                // Ignore damaged local entries, like the trend-point parser.
            }
        }
        return samples;
    }

    private static void save(SharedPreferences prefs, List<Sample> samples) {
        StringBuilder encoded = new StringBuilder();
        for (Sample sample : samples) {
            if (encoded.length() > 0) encoded.append(';');
            encoded.append(sample.elapsedRealtimeMillis).append(',')
                .append(sample.offsetMillis).append(',')
                .append(sample.uncertaintyMillis).append(',')
                .append(sample.source);
        }
        prefs.edit().putString(SAMPLES_KEY, encoded.toString()).apply();
    }

    private static long medianLong(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
