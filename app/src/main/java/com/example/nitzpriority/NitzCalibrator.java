package com.example.nitzpriority;

import android.os.SystemClock;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NITZ calibration: learns each carrier's fixed NITZ bias from fresh GNSS / NTP
 * references, and applies the learned bias when only NITZ is available.
 *
 * Why this is needed: NITZ carries whole-second timestamps only, and the phone
 * cannot measure how long a NITZ message spent in transit, so a raw NITZ sample
 * is only good to about +/-500 ms at best. But the carrier's error is mostly a
 * *fixed* bias (its clock offset plus the phase at which it sends NITZ plus the
 * usual transit delay). When a fresh GNSS or NTP reference is available at the
 * same moment, that bias can be measured; the median of many such measurements
 * is a stable per-carrier correction.
 *
 * Sign convention (read carefully, it is easy to get backwards): the midpoint
 * estimate of a raw NITZ value U is U + 500 ms. A learning sample stores
 * delta = referenceTime - (U + 500), i.e. how far the midpoint estimate was
 * from the truth. The corrected value is U + 500 + median(delta). Defining the
 * offset the other way round and then *adding* it to the bare value U would
 * double-count the 500 ms midpoint and could be wrong by up to ~1 s, so the
 * midpoint is always carried through the correction.
 *
 * Accuracy ceiling, stated plainly: after calibration the NITZ residual is
 * about +/-100-200 ms, never 10 ms. Delivery jitter, the carrier's own clock
 * error, and the learned offset going stale all remain. A calibrated NITZ
 * suggestion must never override a fresh GNSS / NTP suggestion: the origin
 * priority order is untouched, only the NITZ suggestion itself is corrected,
 * and the per-origin telephony threshold (2000 ms) still applies to it.
 *
 * Second-flip phase tracking: a genuine phase anchor would need two NITZ
 * samples exactly one second apart, i.e. observing the instant the carrier
 * second flips. NITZ is only pushed on registration / location updates, so
 * that is essentially never observable; the phase information is already
 * fully captured by the median learning (the median *is* the estimate of the
 * carrier's send phase plus transit delay). Consecutive-second flips are
 * therefore only detected and logged as anchors for future work, never used
 * to adjust a correction.
 *
 * All failures are fail-closed: any reflection failure, unparsable suggestion,
 * or failed suggestion rebuild leaves the original NITZ suggestion untouched,
 * exactly as if calibration did not exist. Calibration must never interfere
 * with system_server time detection.
 */
final class NitzCalibrator {
    private static final String TAG = "NitzPriority";
    static final String CALIBRATION_STATUS_PROPERTY = "debug.nitz.calibration";

    // A reference only teaches when it is close in time to the NITZ sample, so
    // extrapolation error stays small (100 ppm * 60 s = 6 ms).
    private static final long REFERENCE_MAX_AGE_MILLIS = 60_000L;
    // A reference whose own uncertainty exceeds this does not teach.
    private static final long REFERENCE_MAX_UNCERTAINTY_MILLIS = 50L;
    // Base uncertainty assumed for a reference when the suggestion carries no
    // reported uncertainty (GNSS suggestions carry none; network suggestions on
    // some builds report mUncertaintyMillis). GNSS is tens of ms, the NTP
    // median is +/-10-30 ms per the project's NTSC findings; both stay under
    // the 50 ms gate once the small drift budget is added.
    private static final long GNSS_REFERENCE_BASE_UNCERTAINTY_MILLIS = 20L;
    private static final long NETWORK_REFERENCE_BASE_UNCERTAINTY_MILLIS = 30L;
    private static final long DRIFT_BUDGET_PPM = 100L;
    private static final long HALF_SECOND_MILLIS = 500L;
    // A single learning sample outside this range is a carrier clock step or a
    // bad reference, not a bias: ignore it.
    private static final long MAX_OFFSET_SAMPLE_MILLIS = 2_000L;
    // A learned median outside this range is never applied.
    private static final long MAX_APPLY_OFFSET_MILLIS = 1_500L;
    // Learning samples must agree with each other this tightly to be trusted.
    private static final long MAX_SPREAD_MILLIS = 200L;
    private static final int MAX_SAMPLES_PER_CARRIER = 20;
    private static final int MIN_SAMPLES_TO_APPLY = 5;
    private static final long MAX_LEARN_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private static final long MIN_EPOCH_MILLIS = 1_577_836_800_000L; // 2020-01-01 UTC
    private static final long MAX_EPOCH_MILLIS = 4_102_444_800_000L; // 2100-01-01 UTC
    private static final long FUTURE_TOLERANCE_MILLIS = 2_000L;
    private static final int MAX_CARRIER_STATES = 16;

    // Latest reference per origin, fed by NitzPriorityHook from the GNSS /
    // network suggestion hooks. The reference's unix time is valid at its
    // elapsed time and is extrapolated to the NITZ sample's elapsed time.
    private static volatile long lastGnssRefElapsedMillis = -1L;
    private static volatile long lastGnssRefUnixMillis = -1L;
    private static volatile long lastNetworkRefElapsedMillis = -1L;
    private static volatile long lastNetworkRefUnixMillis = -1L;
    // Reported uncertainty of the latest network reference, if the suggestion
    // carried it; -1 when unknown.
    private static volatile long lastNetworkRefUncertaintyMillis = -1L;

    private final Map<String, CarrierState> states = new HashMap<>();
    private volatile String lastPublishedStatus = "";
    private volatile boolean flipAnchorLogged;

    /**
     * Records a GNSS / network suggestion as a potential learning reference.
     * Called from NitzPriorityHook for ORIGIN_GNSS and ORIGIN_NETWORK only.
     * Telephony suggestions are never references.
     */
    static void recordReference(int origin, Object suggestion) {
        long[] times = suggestionTimes(suggestion);
        if (times == null) return;
        long elapsed = times[0];
        long unix = times[1];
        if (elapsed <= 0 || unix < MIN_EPOCH_MILLIS || unix >= MAX_EPOCH_MILLIS) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (elapsed > nowElapsed + FUTURE_TOLERANCE_MILLIS) return;
        if (origin == NitzPriorityHook.ORIGIN_GNSS) {
            lastGnssRefElapsedMillis = elapsed;
            lastGnssRefUnixMillis = unix;
        } else if (origin == NitzPriorityHook.ORIGIN_NETWORK) {
            lastNetworkRefElapsedMillis = elapsed;
            lastNetworkRefUnixMillis = unix;
            lastNetworkRefUncertaintyMillis = suggestionUncertaintyMillis(suggestion);
        }
    }

    /**
     * Learn from, or apply a learned correction to, one telephony suggestion.
     * Called from the suggestTelephonyTime before-hook with the raw suggestion.
     * When a fresh reference is available the sample is used for learning only
     * and the raw suggestion is passed on unchanged; the learned correction is
     * applied only when no fresh reference exists. The suggestion's elapsed
     * time is never changed, so the decision hook's per-origin matching (by
     * elapsed) and the 2000 ms telephony threshold are unaffected.
     *
     * @return a replacement suggestion carrying the corrected unix time, or
     *         null when the original suggestion should be passed on unchanged.
     *         The caller swaps param.args[0] for a non-null return value.
     */
    Object onTelephonySuggestion(XC_LoadPackage.LoadPackageParam lpparam, Object suggestion) {
        try {
            long[] times = suggestionTimes(suggestion);
            if (times == null) return null;
            long nitzElapsed = times[0];
            long nitzUnix = times[1];
            if (nitzUnix < MIN_EPOCH_MILLIS || nitzUnix >= MAX_EPOCH_MILLIS) return null;
            int slot = suggestionSlot(suggestion);
            String key = carrierKey(lpparam, slot);

            synchronized (states) {
                CarrierState state = stateFor(key);
                recordSecondFlip(state, nitzUnix, nitzElapsed);

                Reference reference = freshReference(nitzElapsed);
                if (reference != null) {
                    long referenceUncertainty = reference.baseUncertaintyMillis
                        + driftBudgetMillis(Math.abs(nitzElapsed - reference.elapsedMillis));
                    if (referenceUncertainty <= REFERENCE_MAX_UNCERTAINTY_MILLIS) {
                        long referenceAtNitz = reference.unixMillis
                            + (nitzElapsed - reference.elapsedMillis);
                        // delta: how far the midpoint estimate (U + 500) was from
                        // the reference truth. See the class comment for why the
                        // correction is U + 500 + median(delta), not U + delta.
                        long delta = referenceAtNitz - (nitzUnix + HALF_SECOND_MILLIS);
                        if (Math.abs(delta) <= MAX_OFFSET_SAMPLE_MILLIS) {
                            learn(state, delta);
                        } else {
                            XposedBridge.log(TAG + ": nitz_calibration skip sample, delta out of "
                                + "range: carrier=" + key + " delta=" + delta + " ms");
                        }
                    }
                    // A fresh reference outranks NITZ: learn from this sample,
                    // but pass the raw suggestion on unchanged.
                    publishStatus(lpparam, key, state);
                    return null;
                }

                // No fresh reference: apply the learned correction if valid.
                Long median = validMedian(state);
                if (median == null) {
                    publishStatus(lpparam, key, state);
                    return null;
                }
                long correctedUnix = nitzUnix + HALF_SECOND_MILLIS + median;
                if (correctedUnix < MIN_EPOCH_MILLIS || correctedUnix >= MAX_EPOCH_MILLIS) {
                    publishStatus(lpparam, key, state);
                    return null;
                }
                Object replacement = buildCorrectedSuggestion(lpparam, suggestion,
                    nitzElapsed, correctedUnix);
                if (replacement != null) {
                    XposedBridge.log(TAG + ": nitz_calibration applied: carrier=" + key
                        + " rawUnix=" + nitzUnix + " correctedUnix=" + correctedUnix
                        + " medianDelta=" + median + " ms samples=" + state.deltas.size());
                }
                publishStatus(lpparam, key, state);
                return replacement;
            }
        } catch (Throwable t) {
            // Calibration must never interfere with system_server time detection.
            XposedBridge.log(TAG + ": nitz_calibration failed safely: " + t);
            return null;
        }
    }

    /** Current calibration status for the diagnostics page, in property format. */
    static String statusFor(String key, CarrierState state) {
        if (state == null || state.deltas.isEmpty()) return "uncalibrated|" + key;
        Long median = validMedian(state);
        if (median == null) return "uncalibrated|" + key;
        return "calibrated|" + median + "|" + state.deltas.size() + "|" + key;
    }

    private CarrierState stateFor(String key) {
        CarrierState state = states.get(key);
        if (state != null) return state;
        if (states.size() >= MAX_CARRIER_STATES) {
            // A traveller can accumulate carrier keys; evict the stalest one so
            // the map stays bounded. The evicted key simply relearns on arrival.
            String stalest = null;
            long stalestLearned = Long.MAX_VALUE;
            for (Map.Entry<String, CarrierState> entry : states.entrySet()) {
                if (entry.getValue().lastLearnElapsedMillis < stalestLearned) {
                    stalestLearned = entry.getValue().lastLearnElapsedMillis;
                    stalest = entry.getKey();
                }
            }
            if (stalest != null) states.remove(stalest);
        }
        state = new CarrierState();
        states.put(key, state);
        return state;
    }

    private static void learn(CarrierState state, long delta) {
        state.deltas.addLast(delta);
        while (state.deltas.size() > MAX_SAMPLES_PER_CARRIER) state.deltas.removeFirst();
        state.lastLearnElapsedMillis = SystemClock.elapsedRealtime();
    }

    /**
     * Median of the learned deltas, or null when the learning is not usable:
     * too few samples, samples disagree too much, the learning is older than
     * 24 h, or the median itself is implausibly large. A SIM / carrier change
     * produces a different key, so the old key's learning is simply never
     * looked up again -- no explicit invalidation is needed.
     */
    private static Long validMedian(CarrierState state) {
        if (state == null || state.deltas.size() < MIN_SAMPLES_TO_APPLY) return null;
        if (SystemClock.elapsedRealtime() - state.lastLearnElapsedMillis > MAX_LEARN_AGE_MILLIS) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(state.deltas);
        Collections.sort(sorted);
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        if (max - min > MAX_SPREAD_MILLIS) return null;
        int middle = sorted.size() / 2;
        long median = sorted.size() % 2 == 1
            ? sorted.get(middle)
            : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        if (Math.abs(median) > MAX_APPLY_OFFSET_MILLIS) return null;
        return median;
    }

    /**
     * Best currently available reference for a NITZ sample arriving at
     * nitzElapsed: GNSS first (more accurate), network otherwise. A reference
     * only counts when it is within 60 s of the NITZ sample, and a GNSS
     * reference must also pass the module's 10-minute freshness gate.
     */
    private static Reference freshReference(long nitzElapsed) {
        long gnssElapsed = lastGnssRefElapsedMillis;
        long gnssUnix = lastGnssRefUnixMillis;
        if (gnssElapsed >= 0 && gnssUnix >= 0
                && Math.abs(nitzElapsed - gnssElapsed) <= REFERENCE_MAX_AGE_MILLIS
                && NitzPriorityHook.isGnssSuggestionFresh()) {
            return new Reference(gnssElapsed, gnssUnix,
                GNSS_REFERENCE_BASE_UNCERTAINTY_MILLIS);
        }
        long networkElapsed = lastNetworkRefElapsedMillis;
        long networkUnix = lastNetworkRefUnixMillis;
        if (networkElapsed >= 0 && networkUnix >= 0
                && Math.abs(nitzElapsed - networkElapsed) <= REFERENCE_MAX_AGE_MILLIS) {
            long reported = lastNetworkRefUncertaintyMillis;
            long base = reported >= 0 ? reported : NETWORK_REFERENCE_BASE_UNCERTAINTY_MILLIS;
            return new Reference(networkElapsed, networkUnix, base);
        }
        return null;
    }

    /**
     * Detects a genuine consecutive-second flip (new unix exactly 1000 ms above
     * the previous one for the same carrier) and logs the receipt elapsed as a
     * phase anchor. This is diagnostic only: one anchor cannot beat the median
     * and is never used to adjust a correction.
     */
    private void recordSecondFlip(CarrierState state, long nitzUnix, long nitzElapsed) {
        if (state.lastNitzUnixMillis >= 0
                && nitzUnix - state.lastNitzUnixMillis == 1_000L) {
            state.lastFlipUnixMillis = nitzUnix;
            state.lastFlipElapsedMillis = nitzElapsed;
            if (!flipAnchorLogged) {
                flipAnchorLogged = true;
                XposedBridge.log(TAG + ": nitz_calibration second-flip phase anchor: "
                    + "unix=" + nitzUnix + " elapsed=" + nitzElapsed);
            }
        }
        state.lastNitzUnixMillis = nitzUnix;
    }

    private void publishStatus(XC_LoadPackage.LoadPackageParam lpparam, String key,
            CarrierState state) {
        String value = statusFor(key, state);
        if (value.equals(lastPublishedStatus)) return;
        lastPublishedStatus = value;
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties",
                false, lpparam.classLoader);
            Method set = systemProperties.getDeclaredMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, CALIBRATION_STATUS_PROPERTY, value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nitz_calibration could not publish status: " + t);
        }
    }

    /**
     * Builds a suggestion carrying the corrected unix time. Preferred path: a
     * fresh suggestion through the public constructors (UnixEpochTime(long,
     * long) + TelephonyTimeSuggestion(UnixEpochTime, int)), which works on
     * AOSP 11+; a (long, long, int) constructor is tried for OEM variants.
     * Fallback: mutate the existing UnixEpochTime's final mUnixEpochTimeMillis
     * field via reflection, and return the original suggestion (already
     * corrected in place, so the caller need not swap it). Returns null when
     * every path fails, in which case the caller keeps the original
     * suggestion untouched.
     */
    private static Object buildCorrectedSuggestion(XC_LoadPackage.LoadPackageParam lpparam,
            Object suggestion, long elapsed, long correctedUnix) {
        try {
            Class<?> unixClass = Class.forName(
                "com.android.server.timedetector.UnixEpochTime", false, lpparam.classLoader);
            Object newUnix = unixClass.getConstructor(long.class, long.class)
                .newInstance(elapsed, correctedUnix);
            Class<?> suggestionClass = suggestion.getClass();
            try {
                Constructor<?> constructor = suggestionClass.getConstructor(
                    unixClass, int.class);
                int slot = suggestionSlot(suggestion);
                return constructor.newInstance(newUnix, slot);
            } catch (NoSuchMethodException ignored) {
                // Fall through to the (long, long, int) constructor, then to
                // field mutation.
            }
            try {
                Constructor<?> constructor = suggestionClass.getConstructor(
                    long.class, long.class, int.class);
                int slot = suggestionSlot(suggestion);
                return constructor.newInstance(elapsed, correctedUnix, slot);
            } catch (NoSuchMethodException ignored) {
                // Fall through to field mutation.
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nitz_calibration rebuild failed, trying field mutation: "
                + t);
        }
        return mutateUnixField(suggestion, correctedUnix) ? suggestion : null;
    }

    private static boolean mutateUnixField(Object suggestion, long correctedUnix) {
        try {
            Object unixEpochTime = suggestion.getClass().getMethod("getUnixEpochTime")
                .invoke(suggestion);
            if (unixEpochTime == null) return false;
            Field field = unixEpochTime.getClass().getDeclaredField("mUnixEpochTimeMillis");
            field.setAccessible(true);
            field.setLong(unixEpochTime, correctedUnix);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nitz_calibration field mutation failed: " + t);
            return false;
        }
    }

    /**
     * Carrier key: MCC/MNC of the registered network plus the slot index. The
     * MCC/MNC comes from the gsm.operator.numeric system property, which
     * system_server can read directly; the per-slot suffix is best-effort and
     * degrades to the primary subscription when absent. The slot index is the
     * authoritative part of the key: even when the property is unavailable the
     * key is still "unknown:slotN", so one SIM's learning can never be applied
     * to another SIM's samples. A SIM / carrier change yields a different key
     * and the old key's learning is simply never used again.
     */
    private static String carrierKey(XC_LoadPackage.LoadPackageParam lpparam, int slot) {
        String numeric = systemProperty(lpparam, "gsm.operator.numeric", "");
        if (slot > 0) {
            String slotNumeric = systemProperty(lpparam,
                "gsm.operator.numeric." + slot, "");
            if (!slotNumeric.isEmpty()) numeric = slotNumeric;
        }
        if (numeric.isEmpty()) return "unknown:slot" + slot;
        return numeric + ":slot" + slot;
    }

    private static String systemProperty(XC_LoadPackage.LoadPackageParam lpparam,
            String name, String def) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties",
                false, lpparam.classLoader);
            Method get = systemProperties.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            Object value = get.invoke(null, name, def);
            return value instanceof String ? (String) value : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static long driftBudgetMillis(long ageMillis) {
        if (ageMillis <= 0) return 0L;
        return ageMillis * DRIFT_BUDGET_PPM / 1_000_000L;
    }

    /**
     * Extracts [elapsedRealtimeMillis, unixEpochTimeMillis] from a time
     * suggestion (or a UnixEpochTime itself). The exact AOSP shape varies by
     * version and OEM, so both shapes are tried; anything unrecognised returns
     * null and the caller treats the suggestion as unusable.
     */
    static long[] suggestionTimes(Object suggestion) {
        if (suggestion == null || suggestion instanceof Number) return null;
        try {
            Object unixEpochTime;
            try {
                // UnixEpochTime itself exposes both getters directly.
                Object elapsed = suggestion.getClass()
                    .getMethod("getElapsedRealtimeMillis").invoke(suggestion);
                Object unix = suggestion.getClass()
                    .getMethod("getUnixEpochTimeMillis").invoke(suggestion);
                if (elapsed instanceof Number && unix instanceof Number) {
                    return new long[]{((Number) elapsed).longValue(),
                        ((Number) unix).longValue()};
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // Not a UnixEpochTime; try the TimeSuggestion shape below.
            }
            unixEpochTime = suggestion.getClass().getMethod("getUnixEpochTime")
                .invoke(suggestion);
            if (unixEpochTime == null) return null;
            Object elapsed = unixEpochTime.getClass()
                .getMethod("getElapsedRealtimeMillis").invoke(unixEpochTime);
            Object unix = unixEpochTime.getClass()
                .getMethod("getUnixEpochTimeMillis").invoke(unixEpochTime);
            if (!(elapsed instanceof Number) || !(unix instanceof Number)) return null;
            return new long[]{((Number) elapsed).longValue(), ((Number) unix).longValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static int suggestionSlot(Object suggestion) {
        if (suggestion == null) return -1;
        try {
            Object slot = suggestion.getClass().getMethod("getSlotIndex").invoke(suggestion);
            if (slot instanceof Number) return ((Number) slot).intValue();
        } catch (Throwable ignored) {
            // Slot unknown; the carrier key falls back to "unknown:slot-1".
        }
        return -1;
    }

    /**
     * Reported uncertainty of a network suggestion, when the build carries it
     * (mUncertaintyMillis, as seen in dumpsys time_detector). -1 when unknown.
     */
    private static long suggestionUncertaintyMillis(Object suggestion) {
        if (suggestion == null) return -1;
        try {
            Object value = suggestion.getClass().getMethod("getUncertaintyMillis")
                .invoke(suggestion);
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Throwable ignored) {
            // No getter; try the field directly.
        }
        try {
            Field field = suggestion.getClass().getDeclaredField("mUncertaintyMillis");
            field.setAccessible(true);
            Object value = field.get(suggestion);
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Throwable ignored) {
            // Unknown; the caller uses the base estimate.
        }
        return -1;
    }

    private static final class Reference {
        final long elapsedMillis;
        final long unixMillis;
        final long baseUncertaintyMillis;

        Reference(long elapsedMillis, long unixMillis, long baseUncertaintyMillis) {
            this.elapsedMillis = elapsedMillis;
            this.unixMillis = unixMillis;
            this.baseUncertaintyMillis = baseUncertaintyMillis;
        }
    }

    private static final class CarrierState {
        final Deque<Long> deltas = new ArrayDeque<>();
        long lastLearnElapsedMillis = -1L;
        long lastNitzUnixMillis = -1L;
        long lastFlipUnixMillis = -1L;
        long lastFlipElapsedMillis = -1L;
    }
}
