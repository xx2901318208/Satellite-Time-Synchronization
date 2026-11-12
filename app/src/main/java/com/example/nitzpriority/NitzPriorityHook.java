package com.example.nitzpriority;

import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NitzPriorityHook implements IXposedHookLoadPackage {
    private static final String TAG = "NitzPriority";
    // Origin constants are package-visible so NitzCalibrator can reuse the
    // same values instead of defining its own copies.
    static final int ORIGIN_TELEPHONY = 1;
    static final int ORIGIN_NETWORK = 3;
    static final int ORIGIN_GNSS = 4;
    static final int AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS = 1000;
    // Per-origin automatic clock-update thresholds (ms). AOSP's
    // mSystemClockUpdateThresholdMillis is a single global value with no
    // per-origin field, so these are applied in the decision hook
    // (hookThresholdDecisionPath), keyed by which suggestion's UnixEpochTime is
    // actually being decided on. The global value above stays as the fallback
    // for unknown origins and stale GNSS suggestions, and remains user-tunable
    // via debug.nitz.clock_threshold_config.
    //
    // Rationale for the fixed values: GNSS 20 ms is aggressive and only valid
    // for a fresh sample (see thresholdForOrigin); NTP 50 ms is the realistic
    // floor for public-network round-trip asymmetry; NITZ 2000 ms because NITZ
    // is whole-second quantised and must never drive fine adjustment.
    //
    // Trade-off, stated plainly: a 20 ms GNSS threshold means GNSS sample
    // jitter will cause more frequent small step adjustments than a 1000 ms
    // threshold did. That is the price of millisecond-level accuracy the user
    // explicitly asked for. GNSS was 10 ms; changed to 20 ms on 2026-11-12
    // (one constant, no other change) because the 10 ms threshold stepped the
    // clock too often in real-device use.
    static final int GNSS_CLOCK_UPDATE_THRESHOLD_MILLIS = 20;
    static final int NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS = 50;
    static final int TELEPHONY_CLOCK_UPDATE_THRESHOLD_MILLIS = 2_000;
    static final String THRESHOLD_STATUS_PROPERTY = "debug.nitz.clock_threshold";
    static final String THRESHOLD_CONFIG_PROPERTY = "debug.nitz.clock_threshold_config";
    // Written by the hook as soon as it loads in system_server, so the
    // diagnostics page can tell whether the module is actually active
    // instead of guessing from the threshold status. hook_active is "1"
    // when the hook entry ran; gnss_service is "present" when the
    // GnssTimeUpdateService class exists on this ROM, "absent" otherwise.
    static final String HOOK_ACTIVE_PROPERTY = "debug.nitz.hook_active";
    static final String GNSS_SERVICE_PROPERTY = "debug.nitz.gnss_service";
    // System-NTP (network time) server override, opt-in, default OFF. The
    // app writes NTP_SWITCH_PROPERTY ("1"/"0") via root; the hook reads it
    // fresh on every NTP refresh, so flipping the switch needs no reboot
    // (once the module itself is active) and takes effect at the next poll.
    // NTP_STATUS_PROPERTY reports whether the hook installed ("armed" or
    // "unavailable:<detail>"); NTP_SERVER_PROPERTY reports the host that
    // actually answered the last successful system NTP refresh.
    static final String NTP_NTSC_HOST = "ntp.ntsc.ac.cn";
    static final String NTP_FALLBACK_HOST = "time.android.com";
    static final String NTP_SWITCH_PROPERTY = "debug.nitz.ntp_ntsc_enabled";
    static final String NTP_STATUS_PROPERTY = "debug.nitz.ntp_status";
    static final String NTP_SERVER_PROPERTY = "debug.nitz.ntp_server";
    // AOSP config_ntpTimeout default (5 s). The override config below keeps
    // the same per-server timeout as the device default.
    private static final int NTP_TIMEOUT_MILLIS = 5_000;
    private static final String NTP_PRIMARY_URI = "ntp://ntp.ntsc.ac.cn";
    private static final String NTP_FALLBACK_URI = "ntp://time.android.com";
    // Cached override config [ntp://ntp.ntsc.ac.cn, ntp://time.android.com],
    // built once at install. Null when the Android 14+ NtpConfig shape is
    // absent (pre-14, or a ROM that removed it); the forceRefresh hook then
    // does nothing and the status reports unavailable.
    private static volatile Object cachedNtpConfig;
    private static volatile boolean ntpTestConfigApplied;
    private static volatile String lastPublishedNtpStatus = "";
    private static volatile String lastPublishedNtpServer = "";
    private static volatile boolean ntpOverrideLogged;
    static final int MIN_CLOCK_UPDATE_THRESHOLD_MILLIS = 100;
    static final int MAX_CLOCK_UPDATE_THRESHOLD_MILLIS = 2_000;
    // A GNSS-first ordering is only valid while GNSS suggestions are actually arriving.
    // 10 minutes matches the diagnostics page's "recent GNSS sample" window.
    private static final long GNSS_SUGGESTION_MAX_AGE_MILLIS = 10L * 60 * 1000;
    private static volatile long lastGnssSuggestionElapsedMillis = -1;
    // Latest suggestion (UnixEpochTime elapsedRealtimeMillis) seen per origin, so
    // the decision hook can identify which origin's time is being decided on by
    // matching the UnixEpochTime it receives. Only the latest value per origin is
    // kept: within one origin AOSP always decides on its newest suggestion, so an
    // older value failing to match is fail-closed (falls back to the global value).
    private static volatile long lastTelephonySuggestionElapsedMillis = -1;
    private static volatile long lastNetworkSuggestionElapsedMillis = -1;
    private static volatile boolean gnssStaleFallbackLogged;
    // Effective threshold for the decision currently running on this thread.
    // setSystemClockAndConfidenceUnderWakeLock reads the threshold through
    // getSystemClockUpdateThresholdMillis(), so the getter hook must return the
    // same per-origin value inside a decision and the global value outside one.
    // Set in the decision hook's beforeHookedMethod, cleared in its
    // afterHookedMethod (which Xposed also runs when the method throws).
    private static final ThreadLocal<Integer> decisionThresholdOverride =
        new ThreadLocal<>();
    private static volatile int lastLoggedDecisionOrigin = -2;
    private static volatile int lastLoggedDecisionThreshold = -1;

    private final NitzSampleMonitor sampleMonitor = new NitzSampleMonitor();
    private final NitzCalibrator nitzCalibrator = new NitzCalibrator();
    private final GnssTriggerBridge gnssTriggerBridge = new GnssTriggerBridge();
    private boolean gnssRuntimeEnabled;
    private volatile int originalClockUpdateThresholdMillis = -1;
    private volatile String lastPublishedThresholdStatus = "";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Android framework / system_server is loaded under package name "android".
        if (!"android".equals(lpparam.packageName)) return;

        // Publish the hook-active marker first: the diagnostics page reads
        // it to tell whether this module is actually loaded in system_server.
        // It must not depend on the GNSS service class existing, so it is
        // written before any other hook runs.
        publishHookActive(lpparam);
        // Install the resource override before SystemServer reads it to decide whether to start
        // GnssTimeUpdateService. Do not manually construct or start the service.
        gnssRuntimeEnabled = enableGnssResourceIfSupported(lpparam);
        publishGnssServicePresence(lpparam);
        gnssTriggerBridge.install(lpparam);
        hookOriginPriorities(lpparam);
        hookSystemClockUpdateThreshold(lpparam);
        hookThresholdDecisionPath(lpparam);
        hookTelephonySamples(lpparam);
        hookGnssSuggestions(lpparam);
        hookNetworkSuggestions(lpparam);
        hookSystemNtp(lpparam);
        XposedBridge.log(TAG + ": loaded in android process; gnssRuntimeEnabled="
            + gnssRuntimeEnabled);
    }

    private boolean enableGnssResourceIfSupported(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findClass("com.android.server.timedetector.GnssTimeUpdateService$Lifecycle", lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": GNSS service class unavailable; leaving GNSS disabled: " + t);
            return false;
        }

        try {
            Class<?> boolResources = findClass("com.android.internal.R$bool", lpparam);
            Field resourceField = boolResources.getField("config_enableGnssTimeUpdateService");
            final int targetResourceId = resourceField.getInt(null);
            if (targetResourceId == 0) {
                XposedBridge.log(TAG + ": GNSS enable resource ID is zero; leaving GNSS disabled");
                return false;
            }

            Class<?> resourcesClass = findClass("android.content.res.Resources", lpparam);
            Set<?> hooks = XposedBridge.hookAllMethods(resourcesClass, "getBoolean",
                new XC_MethodHook() {
                    private boolean logged;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length != 1
                                    || !(param.args[0] instanceof Number)
                                    || ((Number) param.args[0]).intValue() != targetResourceId) {
                                return;
                            }
                            boolean original = Boolean.TRUE.equals(param.getResult());
                            param.setResult(Boolean.TRUE);
                            if (!logged) {
                                logged = true;
                                XposedBridge.log(TAG + ": overrode only "
                                    + "config_enableGnssTimeUpdateService resource id=0x"
                                    + Integer.toHexString(targetResourceId)
                                    + " original=" + original + " -> true");
                            }
                        } catch (Throwable t) {
                            // A resource hook failure must not escape into SystemServer.
                            XposedBridge.log(TAG + ": GNSS resource override failed safely: " + t);
                        }
                    }
                });
            if (hooks == null || hooks.isEmpty()) {
                XposedBridge.log(TAG + ": Resources.getBoolean hook unavailable; leaving GNSS disabled");
                return false;
            }
            XposedBridge.log(TAG + ": GNSS service class present; exact resource hook count="
                + hooks.size());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unable to arm GNSS resource override; leaving GNSS disabled: "
                + t);
            return false;
        }
    }

    private void hookOriginPriorities(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Primary hook: affects the configuration as it is assembled.
        try {
            XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.ServiceConfigAccessorImpl", lpparam),
                "getOriginPriorities",
                new PriorityHook("ServiceConfigAccessorImpl.getOriginPriorities",
                    gnssRuntimeEnabled)
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": primary priority hook failed: " + t);
        }

        // Fallback hook: affects callers reading the final ConfigurationInternal value.
        try {
            XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.ConfigurationInternal", lpparam),
                "getAutoOriginPriorities",
                new PriorityHook("ConfigurationInternal.getAutoOriginPriorities",
                    gnssRuntimeEnabled)
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fallback priority hook failed: " + t);
        }
    }

    private void hookSystemClockUpdateThreshold(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        publishThresholdStatus(lpparam, "unavailable");
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.ConfigurationInternal", lpparam),
                "getSystemClockUpdateThresholdMillis",
                new XC_MethodHook() {
                    private boolean logged;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Inside a decision, return the same per-origin threshold the
                        // decision hook wrote to the field; the strategy reads the
                        // threshold through this getter, so returning the global value
                        // here would silently defeat the per-origin decision. Outside
                        // a decision (dumpsys, other readers) there is no current
                        // suggestion, so the global value is the honest answer.
                        Integer override = decisionThresholdOverride.get();
                        if (override != null) {
                            param.setResult(override);
                            return;
                        }
                        Object result = param.getResult();
                        if (!(result instanceof Number)) return;
                        int original = ((Number) result).intValue();
                        int configured = configuredClockUpdateThreshold(lpparam);
                        param.setResult(configured);
                        if (!logged) {
                            logged = true;
                            XposedBridge.log(TAG + ": automatic system-clock update threshold "
                                + original + " -> " + configured + " ms");
                        }
                        markThresholdApplied(lpparam, original, configured);
                    }
                }
            );
            if (hooks == null || hooks.isEmpty()) {
                XposedBridge.log(TAG + ": system-clock threshold hook unavailable");
                return;
            }
            publishThresholdStatus(lpparam, "armed");
            XposedBridge.log(TAG + ": system-clock threshold hook armed; default="
                + AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS + " ms, configurable range="
                + MIN_CLOCK_UPDATE_THRESHOLD_MILLIS + ".."
                + MAX_CLOCK_UPDATE_THRESHOLD_MILLIS + " ms; hook count=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": system-clock threshold hook failed safely: " + t);
        }
    }

    // Writes one system property from system_server. Failures are logged
    // and swallowed: a status property must never break the hooks.
    // Static so both the instance publish methods (hook_active,
    // gnss_service, threshold status) and the static NTP publish methods
    // share this one helper.
    private static void setSystemProperty(XC_LoadPackage.LoadPackageParam lpparam, String name,
            String value) {
        try {
            // Static helper shared by instance and static publishers, so the
            // lookup must not go through the instance findClass(): same
            // Class.forName call that findClass wraps.
            Class<?> systemProperties = Class.forName(
                "android.os.SystemProperties", false, lpparam.classLoader);
            Method set = systemProperties.getDeclaredMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, name, value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not set system property " + name + ": " + t);
        }
    }

    private void publishHookActive(XC_LoadPackage.LoadPackageParam lpparam) {
        setSystemProperty(lpparam, HOOK_ACTIVE_PROPERTY, "1");
    }

    private void publishGnssServicePresence(XC_LoadPackage.LoadPackageParam lpparam) {
        setSystemProperty(lpparam, GNSS_SERVICE_PROPERTY,
            gnssRuntimeEnabled ? "present" : "absent");
    }

    private void publishThresholdStatus(XC_LoadPackage.LoadPackageParam lpparam, String value) {
        // The decision path can run often; only write the status property when it changes.
        if (value.equals(lastPublishedThresholdStatus)) return;
        lastPublishedThresholdStatus = value;
        setSystemProperty(lpparam, THRESHOLD_STATUS_PROPERTY, value);
    }

    private int configuredClockUpdateThreshold(XC_LoadPackage.LoadPackageParam lpparam) {
        int value = AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS;
        try {
            Class<?> systemProperties = findClass("android.os.SystemProperties", lpparam);
            Method getInt = systemProperties.getDeclaredMethod(
                "getInt", String.class, int.class);
            getInt.setAccessible(true);
            value = (Integer) getInt.invoke(null, THRESHOLD_CONFIG_PROPERTY,
                AUTO_CLOCK_UPDATE_THRESHOLD_MILLIS);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unable to read threshold setting; using default: " + t);
        }
        return Math.max(MIN_CLOCK_UPDATE_THRESHOLD_MILLIS,
            Math.min(MAX_CLOCK_UPDATE_THRESHOLD_MILLIS, value));
    }

    private void hookThresholdDecisionPath(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.TimeDetectorStrategyImpl", lpparam),
                "setSystemClockAndConfidenceUnderWakeLock",
                new XC_MethodHook() {
                    private boolean logged;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // Per-origin threshold: the time being decided on is
                            // identified by matching its UnixEpochTime against the
                            // latest suggestion recorded per origin. Anything that
                            // cannot be matched (unknown origin, external/manual
                            // suggestion, reflection failure) falls back to the
                            // global configured value -- never to 20 ms.
                            int configured = configuredClockUpdateThreshold(lpparam);
                            long decisionElapsed = param.args != null && param.args.length > 0
                                ? extractSuggestionElapsed(param.args[0]) : -1L;
                            int origin = originForDecisionElapsed(decisionElapsed);
                            int effective = thresholdForOrigin(origin, configured);
                            decisionThresholdOverride.set(effective);
                            logDecisionThreshold(origin, effective, configured);
                            Field configField = param.thisObject.getClass()
                                .getDeclaredField("mCurrentConfigurationInternal");
                            configField.setAccessible(true);
                            Object configuration = configField.get(param.thisObject);
                            if (configuration == null) return;

                            Field thresholdField = configuration.getClass()
                                .getDeclaredField("mSystemClockUpdateThresholdMillis");
                            thresholdField.setAccessible(true);
                            int original = thresholdField.getInt(configuration);
                            if (original != effective) {
                                thresholdField.setInt(configuration, effective);
                            }
                            int observed = thresholdField.getInt(configuration);
                            if (observed == effective) {
                                // The status property keeps reporting the global
                                // fallback (configured) so the diagnostics page's
                                // "hook active" check (active == configured) stays
                                // meaningful; the per-origin effective value is in
                                // the Xposed log, not in this property.
                                markThresholdApplied(lpparam, original, configured);
                                if (!logged) {
                                    logged = true;
                                    XposedBridge.log(TAG + ": decision-path clock threshold field "
                                        + original + " -> " + observed + " ms");
                                }
                            } else {
                                XposedBridge.log(TAG + ": decision-path threshold verification failed; "
                                    + "observed=" + observed);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": decision-path threshold adjustment failed safely: "
                                + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // The override is only valid inside this decision call;
                        // clear it so later getSystemClockUpdateThresholdMillis()
                        // reads (dumpsys, other components) see the global value
                        // again. Xposed runs after hooks even when the method
                        // throws, so a decision failure cannot leak the override.
                        decisionThresholdOverride.remove();
                    }
                }
            );
            if (hooks == null || hooks.isEmpty()) {
                XposedBridge.log(TAG + ": decision-path threshold hook unavailable");
                return;
            }
            XposedBridge.log(TAG + ": decision-path threshold hook count=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": decision-path threshold hook failed safely: " + t);
        }
    }

    // Which origin's suggestion is being decided on, by matching the decision's
    // UnixEpochTime against the latest suggestion recorded per origin. Returns -1
    // when nothing matches: unknown origin, an external/manual suggestion, or a
    // suggestion that arrived before this module loaded.
    private static int originForDecisionElapsed(long decisionElapsed) {
        if (decisionElapsed < 0) return -1;
        if (decisionElapsed == lastGnssSuggestionElapsedMillis) return ORIGIN_GNSS;
        if (decisionElapsed == lastNetworkSuggestionElapsedMillis) return ORIGIN_NETWORK;
        if (decisionElapsed == lastTelephonySuggestionElapsedMillis) return ORIGIN_TELEPHONY;
        return -1;
    }

    // Effective threshold for one decision. GNSS only gets its 20 ms threshold
    // while its suggestion is fresh (10 minutes); a stale GNSS suggestion must
    // not enjoy the aggressive threshold and falls back to the global value.
    private static int thresholdForOrigin(int origin, int globalFallback) {
        switch (origin) {
            case ORIGIN_GNSS:
                return isGnssSuggestionFresh()
                    ? GNSS_CLOCK_UPDATE_THRESHOLD_MILLIS : globalFallback;
            case ORIGIN_NETWORK:
                return NETWORK_CLOCK_UPDATE_THRESHOLD_MILLIS;
            case ORIGIN_TELEPHONY:
                return TELEPHONY_CLOCK_UPDATE_THRESHOLD_MILLIS;
            default:
                return globalFallback;
        }
    }

    private static String originName(int origin) {
        switch (origin) {
            case ORIGIN_GNSS: return "gnss";
            case ORIGIN_NETWORK: return "network";
            case ORIGIN_TELEPHONY: return "telephony";
            default: return "unknown";
        }
    }

    // One Xposed log line per (origin, effective threshold) change, so the
    // actually applied per-origin threshold is visible without spamming the log
    // on every decision.
    private static void logDecisionThreshold(int origin, int effective, int configured) {
        if (origin == lastLoggedDecisionOrigin && effective == lastLoggedDecisionThreshold) {
            return;
        }
        lastLoggedDecisionOrigin = origin;
        lastLoggedDecisionThreshold = effective;
        XposedBridge.log(TAG + ": per-origin threshold: origin=" + originName(origin)
            + " effective=" + effective + " ms (global fallback=" + configured + " ms)");
    }

    private void markThresholdApplied(XC_LoadPackage.LoadPackageParam lpparam, int original,
            int configured) {
        if (original != configured
                && originalClockUpdateThresholdMillis < 0) {
            originalClockUpdateThresholdMillis = original;
        }
        int sourceValue = originalClockUpdateThresholdMillis >= 0
            ? originalClockUpdateThresholdMillis : original;
        publishThresholdStatus(lpparam,
            configured + ":" + sourceValue);
    }

    private void hookTelephonySamples(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.TimeDetectorStrategyImpl", lpparam),
                "suggestTelephonyTime",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                            XposedBridge.log(TAG + ": sample monitor ignored empty telephony suggestion");
                            return;
                        }
                        try {
                            XposedBridge.log(TAG + ": NITZ sample "
                                + sampleMonitor.observe(param.args[0]));
                        } catch (Throwable t) {
                            // Diagnostics must never interfere with system_server time detection.
                            XposedBridge.log(TAG + ": sample monitor failed safely: " + t);
                        }
                        // Record the suggestion's elapsed time so the decision hook can
                        // recognise a telephony time being decided on (2000 ms threshold).
                        recordSuggestionElapsed(ORIGIN_TELEPHONY, param.args[0]);
                        // NITZ calibration: learn this carrier's fixed bias from a
                        // fresh GNSS / NTP reference, or apply the learned bias
                        // when no fresh reference exists. A non-null return is a
                        // replacement suggestion carrying the corrected unix time;
                        // swapping it into the live argument array is how the
                        // correction reaches the strategy. Any failure leaves the
                        // original suggestion untouched (fail-closed).
                        try {
                            Object replacement = nitzCalibrator.onTelephonySuggestion(
                                lpparam, param.args[0]);
                            if (replacement != null && replacement != param.args[0]) {
                                param.args[0] = replacement;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": nitz calibration failed safely: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": telephony sample monitor hooks=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": telephony sample monitor hook unavailable: " + t);
        }
    }

    private void hookGnssSuggestions(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.TimeDetectorStrategyImpl", lpparam),
                "suggestGnssTime",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object suggestion = param.args != null && param.args.length > 0
                            ? param.args[0] : null;
                        XposedBridge.log(TAG + ": GNSS time suggestion reached time_detector: "
                            + String.valueOf(suggestion));
                        recordSuggestionElapsed(ORIGIN_GNSS, suggestion);
                    }
                }
            );
            XposedBridge.log(TAG + ": GNSS suggestion monitor hooks=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": GNSS suggestion monitor hook unavailable: " + t);
        }
    }

    // Records network (NTP) suggestions so the decision hook can recognise a
    // network time being decided on (50 ms threshold). Diagnostic-only: a failure
    // here only costs the per-origin match, which falls back to the global value.
    private void hookNetworkSuggestions(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Set<?> hooks = XposedBridge.hookAllMethods(
                findClass("com.android.server.timedetector.TimeDetectorStrategyImpl", lpparam),
                "suggestNetworkTime",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object suggestion = param.args != null && param.args.length > 0
                            ? param.args[0] : null;
                        recordSuggestionElapsed(ORIGIN_NETWORK, suggestion);
                    }
                }
            );
            XposedBridge.log(TAG + ": network suggestion monitor hooks=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": network suggestion monitor hook unavailable: " + t);
        }
    }

    // System-NTP server override (opt-in, default OFF).
    //
    // How the system gets its network time on Android 14+ (AOSP):
    // NetworkTimeUpdateService polls NtpTrustedTime.forceRefresh(), which
    // reads its server list fresh on every refresh from getNtpConfig():
    // Settings.Global.NTP_SERVER first, else the config_ntpServers string
    // array resource (AOSP default: ntp://time.android.com), and tries each
    // server in order, sticking with the last one that answered. The device
    // in question (ColorOS, Android 16) demonstrably uses this path: its
    // dumpsys time_detector network suggestions carry
    // mNtpServerSocketAddress=pool.ntp.org/..., i.e. the modern
    // NtpTrustedTime.TimeResult shape, with ColorOS's own server list
    // (pool.ntp.org instead of time.android.com).
    //
    // Three layers, all defensive (each failure only logs and skips that
    // layer; a failure never touches system_server behaviour):
    //
    // 1. Primary: hook NtpTrustedTime.forceRefresh() and, when the switch is
    //    on, install an in-memory override config [ntp://ntp.ntsc.ac.cn,
    //    ntp://time.android.com] via the framework's own
    //    setServerConfigForTests() entry point. The framework's own
    //    try-in-order algorithm then provides the fallback: NTSC first,
    //    time.android.com in the same poll if NTSC does not answer, and
    //    stickiness to whichever answered last. When the switch is off, the
    //    override is cleared so the system goes back to its own
    //    Settings/resource config at the next poll. In-memory only: a
    //    reboot clears it even if the switch property is left on.
    // 2. Secondary: hook Resources.getStringArray() for
    //    config_ntpServers (exact resource ID only) and return the same
    //    two-URI list when the switch is on. This covers the config path if
    //    the test-config route is bypassed; it is a no-op when
    //    Settings.Global.NTP_SERVER is set, because the setting takes
    //    priority over the resource in getNtpConfigInternal().
    // 3. Secondary (legacy, pre-14): hook Resources.getString() for
    //    config_ntpServer (exact ID only) and return ntp.ntsc.ac.cn when
    //    the switch is on. On Android 14+ this resource does not exist and
    //    the layer simply does not install.
    //
    // ColorOS caveat, stated plainly: if ColorOS replaced NtpTrustedTime /
    // NetworkTimeUpdateService with its own NTP client, none of these layers
    // fires and the status property reports "unavailable". The diagnostics
    // page always shows the server the system NTP actually hit (parsed from
    // dumpsys time_detector), so a non-working hook is visible as "switch
    // on, still pool.ntp.org" rather than a fake success. The hook never
    // claims success it cannot see.
    private void hookSystemNtp(final XC_LoadPackage.LoadPackageParam lpparam) {
        boolean anyArmed = false;
        String firstFailure = "";

        cachedNtpConfig = buildNtpOverrideConfig(lpparam);
        if (cachedNtpConfig == null) {
            firstFailure = "NtpConfig shape unavailable";
            XposedBridge.log(TAG + ": system NTP override unavailable: " + firstFailure
                + " (pre-14 NtpTrustedTime, or ROM removed it)");
        }

        try {
            Class<?> resourcesClass = findClass("android.content.res.Resources", lpparam);
            final int serversId = resourceId(lpparam, "array", "config_ntpServers");
            if (serversId != 0) {
                Set<?> hooks = XposedBridge.hookAllMethods(resourcesClass, "getStringArray",
                    new XC_MethodHook() {
                        private boolean logged;

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length != 1
                                        || !(param.args[0] instanceof Number)
                                        || ((Number) param.args[0]).intValue() != serversId) {
                                    return;
                                }
                                if (!ntpNtscEnabled(lpparam)) return;
                                param.setResult(new String[]{NTP_PRIMARY_URI, NTP_FALLBACK_URI});
                                if (!logged) {
                                    logged = true;
                                    XposedBridge.log(TAG + ": system NTP servers override: "
                                        + "config_ntpServers -> [ntp://ntp.ntsc.ac.cn, "
                                        + "ntp://time.android.com]");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                    + ": system NTP servers override failed safely: " + t);
                            }
                        }
                    });
                if (hooks != null && !hooks.isEmpty()) {
                    anyArmed = true;
                    XposedBridge.log(TAG + ": system NTP config_ntpServers hook armed");
                }
            }
        } catch (Throwable t) {
            if (firstFailure.isEmpty()) firstFailure = "config_ntpServers: " + t;
            XposedBridge.log(TAG + ": system NTP config_ntpServers hook unavailable: " + t);
        }

        try {
            Class<?> resourcesClass = findClass("android.content.res.Resources", lpparam);
            final int serverId = resourceId(lpparam, "string", "config_ntpServer");
            if (serverId != 0) {
                Set<?> hooks = XposedBridge.hookAllMethods(resourcesClass, "getString",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length != 1
                                        || !(param.args[0] instanceof Number)
                                        || ((Number) param.args[0]).intValue() != serverId) {
                                    return;
                                }
                                if (!ntpNtscEnabled(lpparam)) return;
                                param.setResult(NTP_NTSC_HOST);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                    + ": system NTP server override failed safely: " + t);
                            }
                        }
                    });
                if (hooks != null && !hooks.isEmpty()) {
                    anyArmed = true;
                    XposedBridge.log(TAG + ": system NTP config_ntpServer hook armed");
                }
            }
        } catch (Throwable t) {
            if (firstFailure.isEmpty()) firstFailure = "config_ntpServer: " + t;
            XposedBridge.log(TAG + ": system NTP config_ntpServer hook unavailable: " + t);
        }

        try {
            Class<?> ntpClass = findClass("android.util.NtpTrustedTime", lpparam);
            Set<?> hooks = XposedBridge.hookAllMethods(ntpClass, "forceRefresh",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject == null) return;
                            if (!ntpNtscEnabled(lpparam)) {
                                // Switch off: clear an override this module
                                // set earlier, so the next poll uses the
                                // device's own config again. Only clear what
                                // this module set; never touch a test config
                                // set by someone else. (Only ever our own:
                                // see the flag.)
                                if (ntpTestConfigApplied) {
                                    clearNtpTestConfig(param.thisObject);
                                    ntpTestConfigApplied = false;
                                }
                                return;
                            }
                            if (cachedNtpConfig == null) return;
                            applyNtpTestConfig(param.thisObject, cachedNtpConfig);
                            ntpTestConfigApplied = true;
                            if (!ntpOverrideLogged) {
                                ntpOverrideLogged = true;
                                XposedBridge.log(TAG + ": system NTP override active: "
                                    + "NtpTrustedTime now tries ntp.ntsc.ac.cn first, "
                                    + "time.android.com as fallback");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": system NTP override failed safely: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof Boolean)
                                    || !((Boolean) result).booleanValue()) {
                                return;
                            }
                            if (param.thisObject == null) return;
                            String host = readLastSuccessfulNtpHost(param.thisObject);
                            if (host != null && !host.isEmpty()) {
                                publishNtpServer(lpparam, host);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": system NTP last-hit publish failed safely: " + t);
                        }
                    }
                });
            if (hooks != null && !hooks.isEmpty()) {
                anyArmed = true;
                XposedBridge.log(TAG + ": system NTP NtpTrustedTime.forceRefresh hook armed; "
                    + "hooks=" + hooks.size());
            } else if (firstFailure.isEmpty()) {
                firstFailure = "forceRefresh hook empty";
            }
        } catch (Throwable t) {
            if (firstFailure.isEmpty()) firstFailure = "NtpTrustedTime: " + t;
            XposedBridge.log(TAG + ": system NTP NtpTrustedTime hook unavailable: " + t);
        }

        publishNtpStatus(lpparam, anyArmed ? "armed"
            : "unavailable" + (firstFailure.isEmpty() ? "" : ":" + firstFailure));
    }

    // Builds [ntp://ntp.ntsc.ac.cn, ntp://time.android.com] as an
    // android.util.NtpTrustedTime$NtpConfig via reflection. Returns null
    // when the Android 14+ shape is absent; the caller then reports
    // unavailable instead of pretending the override works.
    private static Object buildNtpOverrideConfig(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> configClass = Class.forName(
                "android.util.NtpTrustedTime$NtpConfig", false, lpparam.classLoader);
            java.lang.reflect.Constructor<?> ctor = configClass.getConstructor(
                List.class, java.time.Duration.class);
            List<java.net.URI> uris = new ArrayList<>(2);
            uris.add(new java.net.URI(NTP_PRIMARY_URI));
            uris.add(new java.net.URI(NTP_FALLBACK_URI));
            return ctor.newInstance(uris, java.time.Duration.ofMillis(NTP_TIMEOUT_MILLIS));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not build NTP override config: " + t);
            return null;
        }
    }

    private static void applyNtpTestConfig(Object ntpTrustedTime, Object ntpConfig)
            throws Exception {
        ntpTrustedTime.getClass().getMethod("setServerConfigForTests",
            Class.forName("android.util.NtpTrustedTime$NtpConfig", false,
                ntpTrustedTime.getClass().getClassLoader()))
            .invoke(ntpTrustedTime, ntpConfig);
    }

    private static void clearNtpTestConfig(Object ntpTrustedTime) {
        try {
            ntpTrustedTime.getClass().getMethod("setServerConfigForTests",
                Class.forName("android.util.NtpTrustedTime$NtpConfig", false,
                    ntpTrustedTime.getClass().getClassLoader()))
                .invoke(ntpTrustedTime, new Object[]{null});
        } catch (Throwable t) {
            // The method's own contract says passing null clears the test
            // value, but a ROM may reject it; fall back to clearing the
            // field directly. Either failure just leaves the override in
            // place until reboot, and is logged, never fatal.
            try {
                java.lang.reflect.Field field =
                    findFieldRecursive(ntpTrustedTime.getClass(), "mNtpConfigForTests");
                field.setAccessible(true);
                field.set(ntpTrustedTime, null);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": could not clear NTP test config: " + t2);
            }
        }
    }

    // Host that answered the last successful system NTP refresh, from the
    // framework's own mLastSuccessfulNtpServerUri. Null when the field is
    // absent (older shape) or no refresh has succeeded yet.
    private static String readLastSuccessfulNtpHost(Object ntpTrustedTime) {
        try {
            java.lang.reflect.Field field = findFieldRecursive(
                ntpTrustedTime.getClass(), "mLastSuccessfulNtpServerUri");
            field.setAccessible(true);
            Object uri = field.get(ntpTrustedTime);
            if (uri == null) return null;
            if (uri instanceof java.net.URI) {
                String host = ((java.net.URI) uri).getHost();
                if (host != null && !host.isEmpty()) return host;
            }
            return uri.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // Walks superclasses: getDeclaredField on the concrete class cannot see
    // a field declared on an abstract base (mLastSuccessfulNtpServerUri
    // lives on NtpTrustedTime; the instance is NtpTrustedTimeImpl).
    private static java.lang.reflect.Field findFieldRecursive(Class<?> clazz, String name)
            throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // Exact resource ID for one R field, or 0 when the ROM does not have it
    // (e.g. config_ntpServers does not exist before Android 14).
    private static int resourceId(XC_LoadPackage.LoadPackageParam lpparam,
            String innerClass, String fieldName) {
        try {
            Class<?> resClass = Class.forName(
                "com.android.internal.R$" + innerClass, false, lpparam.classLoader);
            return resClass.getField(fieldName).getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // Reads the switch fresh on every call (same fail-closed pattern as the
    // threshold config): missing property or read failure means OFF.
    private static boolean ntpNtscEnabled(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> systemProperties = Class.forName(
                "android.os.SystemProperties", false, lpparam.classLoader);
            Method getInt = systemProperties.getDeclaredMethod(
                "getInt", String.class, int.class);
            getInt.setAccessible(true);
            return ((Integer) getInt.invoke(null, NTP_SWITCH_PROPERTY, 0)) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void publishNtpStatus(XC_LoadPackage.LoadPackageParam lpparam, String value) {
        if (value.equals(lastPublishedNtpStatus)) return;
        lastPublishedNtpStatus = value;
        setSystemProperty(lpparam, NTP_STATUS_PROPERTY, value);
    }

    private static void publishNtpServer(XC_LoadPackage.LoadPackageParam lpparam, String value) {
        if (value.equals(lastPublishedNtpServer)) return;
        lastPublishedNtpServer = value;
        setSystemProperty(lpparam, NTP_SERVER_PROPERTY, value);
    }

    // Extracts the elapsedRealtimeMillis of a time suggestion (or a UnixEpochTime
    // itself, which is what the decision method receives) via reflection. The
    // exact AOSP shape varies by version and OEM, so every shape is tried and
    // anything unrecognised returns -1, which the caller treats as "no match"
    // and falls back to the global threshold. A bare Number is only trusted when
    // it can actually be an elapsedRealtime value: a unix-epoch millis value
    // simply never matches a recorded elapsed value, so it also falls back.
    private static long extractSuggestionElapsed(Object suggestion) {
        if (suggestion == null) return -1;
        if (suggestion instanceof Number) return ((Number) suggestion).longValue();
        try {
            // UnixEpochTime itself exposes getElapsedRealtimeMillis() directly.
            try {
                Object elapsed = suggestion.getClass()
                    .getMethod("getElapsedRealtimeMillis").invoke(suggestion);
                if (elapsed instanceof Number) return ((Number) elapsed).longValue();
            } catch (NoSuchMethodException ignored) {
                // Not a UnixEpochTime; try the TimeSuggestion shape below.
            }
            Object unixEpochTime = suggestion.getClass().getMethod("getUnixEpochTime")
                .invoke(suggestion);
            if (unixEpochTime == null) return -1;
            Object elapsed = unixEpochTime.getClass()
                .getMethod("getElapsedRealtimeMillis").invoke(unixEpochTime);
            if (!(elapsed instanceof Number)) return -1;
            return ((Number) elapsed).longValue();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not read suggestion elapsed time: " + t);
            return -1;
        }
    }

    // Records when the latest suggestion of one origin was taken, from its
    // UnixEpochTime. Only a monotonically newer timestamp advances the marker,
    // so a re-delivered old suggestion cannot extend freshness or shadow a
    // newer one. Any reflection failure is diagnostic-only and must never
    // interfere with system_server time detection.
    private static void recordSuggestionElapsed(int origin, Object suggestion) {
        long sampleElapsed = extractSuggestionElapsed(suggestion);
        if (sampleElapsed <= 0) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        // Reject non-positive or implausibly future timestamps (2s tolerance for
        // cross-thread sampling skew); elapsedRealtime is monotonic, so a value far
        // in the future cannot be a genuine sample.
        if (sampleElapsed > nowElapsed + 2_000) return;
        switch (origin) {
            case ORIGIN_GNSS:
                if (sampleElapsed > lastGnssSuggestionElapsedMillis) {
                    lastGnssSuggestionElapsedMillis = sampleElapsed;
                    gnssStaleFallbackLogged = false;
                }
                break;
            case ORIGIN_TELEPHONY:
                if (sampleElapsed > lastTelephonySuggestionElapsedMillis) {
                    lastTelephonySuggestionElapsedMillis = sampleElapsed;
                }
                break;
            case ORIGIN_NETWORK:
                if (sampleElapsed > lastNetworkSuggestionElapsedMillis) {
                    lastNetworkSuggestionElapsedMillis = sampleElapsed;
                }
                break;
            default:
                break;
        }
        // Feed the calibrator's reference store: GNSS and network suggestions
        // are the only references NITZ can learn from. Telephony suggestions
        // are never references.
        if (origin == ORIGIN_GNSS || origin == ORIGIN_NETWORK) {
            try {
                NitzCalibrator.recordReference(origin, suggestion);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nitz calibration reference failed safely: " + t);
            }
        }
    }

    // Package-visible so NitzCalibrator reuses the same 10-minute freshness
    // gate instead of defining its own copy.
    static boolean isGnssSuggestionFresh() {
        long last = lastGnssSuggestionElapsedMillis;
        if (last < 0) return false;
        long age = SystemClock.elapsedRealtime() - last;
        return age >= 0 && age < GNSS_SUGGESTION_MAX_AGE_MILLIS;
    }

    private Class<?> findClass(String name, XC_LoadPackage.LoadPackageParam p)
            throws ClassNotFoundException {
        return Class.forName(name, false, p.classLoader);
    }

    private static int[] prioritized(int[] original, boolean gnssEnabled) {
        if (original == null || original.length == 0) return original;
        if (!gnssEnabled) {
            // GNSS service unavailable: respect the AOSP default ordering (network before
            // telephony) instead of forcing NITZ ahead of NTP. P0-3: the old telephonyFirst()
            // rewrite is removed; no reordering happens on this path.
            return original;
        }
        if (!isGnssSuggestionFresh()) {
            // GNSS enabled but no fresh suggestion within 10 minutes: fall back to the AOSP
            // default ordering instead of advertising a stale GNSS-first priority. Log once
            // per stale episode; the flag resets when a newer suggestion arrives.
            if (!gnssStaleFallbackLogged) {
                gnssStaleFallbackLogged = true;
                XposedBridge.log(TAG + ": gnss_stale_fallback: no fresh GNSS suggestion within "
                    + (GNSS_SUGGESTION_MAX_AGE_MILLIS / 60_000)
                    + " min; keeping AOSP default origin priorities " + format(original));
            }
            return original;
        }
        return gnssThenTelephony(original);
    }

    private static int[] gnssThenTelephony(int[] original) {
        List<Integer> result = new ArrayList<>(original.length + 1);
        boolean hasGnss = false;
        for (int value : original) {
            if (value == ORIGIN_GNSS) hasGnss = true;
        }

        // When the GNSS service is present and its enable resource is hooked, make GNSS the
        // highest origin. Add it if the OEM resource array omitted it; Android will still fall
        // back to the lower-priority origins when no valid GNSS suggestion exists.
        if (!hasGnss) result.add(ORIGIN_GNSS);
        for (int value : original) if (value == ORIGIN_GNSS) result.add(value);
        for (int value : original) if (value == ORIGIN_TELEPHONY) result.add(value);
        for (int value : original) if (value == ORIGIN_NETWORK) result.add(value);
        for (int value : original) {
            if (value != ORIGIN_GNSS && value != ORIGIN_TELEPHONY && value != ORIGIN_NETWORK) {
                result.add(value);
            }
        }

        int[] adjusted = toArray(result);
        if (same(original, adjusted)) return original;
        XposedBridge.log(TAG + ": GNSS-first origin priorities " + format(original)
            + " -> " + format(adjusted));
        return adjusted;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static boolean same(int[] left, int[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) if (left[i] != right[i]) return false;
        return true;
    }

    private static String format(int[] values) {
        if (values == null) return "null";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        return out.append(']').toString();
    }

    private static final class PriorityHook extends XC_MethodHook {
        private final String method;
        private final boolean gnssEnabled;
        private boolean unchangedLogged;

        PriorityHook(String method, boolean gnssEnabled) {
            this.method = method;
            this.gnssEnabled = gnssEnabled;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object value = param.getResult();
            if (!(value instanceof int[])) return;
            int[] original = (int[]) value;
            int[] adjusted = prioritized(original, gnssEnabled);
            if (adjusted != original) {
                param.setResult(adjusted);
            } else if (!unchangedLogged) {
                unchangedLogged = true;
                XposedBridge.log(TAG + ": " + method + " priorities unchanged " + format(original));
            }
        }
    }
}
