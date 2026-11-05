package com.example.nitzpriority;

import android.os.Handler;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bridges the companion button to the already-running GNSS time service. */
final class GnssTriggerBridge {
    private static final String TAG = "NitzPriority";
    private static final long PROPERTY_POLL_INTERVAL_MILLIS = 1_000L;
    private static final long IDLE_PROPERTY_POLL_INTERVAL_MILLIS = 5_000L;
    private static final long GNSS_RECOVERY_WINDOW_MILLIS = 5 * 60_000L;
    private static final long GNSS_REARM_DELAY_MILLIS = 15_000L;
    private static final int GNSS_MAX_REARM_ATTEMPTS = 18;
    static final String TRIGGER_PROPERTY = "debug.nitz.gnss";
    static final String GNSS_RESULT_PROPERTY = "debug.nitz.gnss_result";
    static final String GNSS_CANCEL_PROPERTY = "debug.nitz.gnss_cancel";
    static final String FORCE_TIME_PROPERTY = "debug.nitz.force_time";
    static final String FORCE_RESULT_PROPERTY = "debug.nitz.force_result";
    private static final long MIN_FORCE_TIME_MILLIS = 1_577_836_800_000L; // 2020-01-01
    private static final long MAX_FORCE_TIME_MILLIS = 4_102_444_800_000L; // 2100-01-01

    private final AtomicBoolean triggerPending = new AtomicBoolean(false);
    private volatile ClassLoader systemServerClassLoader;
    private volatile Object gnssTimeUpdateService;
    private volatile Handler foregroundHandler;
    private volatile String lastTriggerValue = "";
    private volatile String lastForceTimeValue = "";
    private volatile Method setSystemProperty;
    private String lastCancelValue = "";
    private String activeRecoveryToken = "";
    private long activeRecoveryBaselineElapsedMillis = -1L;
    private long activeRecoveryDeadlineElapsedMillis = -1L;
    private int recoveryAttempts;
    private boolean rearmScheduled;

    void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        systemServerClassLoader = lpparam.classLoader;
        try {
            Class<?> lifecycleClass = Class.forName(
                "com.android.server.timedetector.GnssTimeUpdateService$Lifecycle",
                false, lpparam.classLoader);
            Set<?> hooks = XposedBridge.hookAllMethods(lifecycleClass, "onStart",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Field serviceField = findField(param.thisObject.getClass(), "mService");
                            serviceField.setAccessible(true);
                            Object service = serviceField.get(param.thisObject);
                            if (service != null) {
                                gnssTimeUpdateService = service;
                                XposedBridge.log(TAG + ": GNSS service instance captured");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": could not capture GNSS service safely: " + t);
                        }
                    }
                });
            XposedBridge.log(TAG + ": GNSS lifecycle trigger hook count=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": GNSS lifecycle hook unavailable: " + t);
        }

        installStaleSampleRecoveryHook(lpparam.classLoader);
        installPropertyCallback(lpparam.classLoader);
    }

    private void installStaleSampleRecoveryHook(ClassLoader classLoader) {
        try {
            Class<?> serviceClass = Class.forName(
                "com.android.server.timedetector.GnssTimeUpdateService", false, classLoader);
            Set<?> hooks = XposedBridge.hookAllMethods(serviceClass, "handleLocationAvailable",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            handlePassiveLocationTimeResult(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": stale GNSS recovery hook failed safely: " + t);
                        }
                    }
                });
            XposedBridge.log(TAG + ": GNSS stale-sample recovery hook count="
                + (hooks == null ? 0 : hooks.size()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": GNSS stale-sample recovery hook unavailable: " + t);
        }
    }

    private void installPropertyCallback(ClassLoader classLoader) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties", false,
                classLoader);
            final Method getProperty = systemProperties.getDeclaredMethod(
                "get", String.class, String.class);
            getProperty.setAccessible(true);
            final Method setProperty = systemProperties.getDeclaredMethod(
                "set", String.class, String.class);
            setProperty.setAccessible(true);
            setSystemProperty = setProperty;
            Method addCallback = systemProperties.getDeclaredMethod("addChangeCallback",
                Runnable.class);
            addCallback.setAccessible(true);
            Object initial = getProperty.invoke(null, TRIGGER_PROPERTY, "");
            lastTriggerValue = initial == null ? "" : initial.toString();
            Object initialForce = getProperty.invoke(null, FORCE_TIME_PROPERTY, "");
            lastForceTimeValue = initialForce == null ? "" : initialForce.toString();
            Object initialCancel = getProperty.invoke(null, GNSS_CANCEL_PROPERTY, "");
            lastCancelValue = initialCancel == null ? "" : initialCancel.toString();

            Runnable callback = new Runnable() {
                @Override
                public void run() {
                    try {
                        Object valueObject = getProperty.invoke(null, TRIGGER_PROPERTY, "");
                        String value = valueObject == null ? "" : valueObject.toString();
                        if (!value.isEmpty()) {
                            boolean changed;
                            synchronized (GnssTriggerBridge.this) {
                                changed = !value.equals(lastTriggerValue);
                                if (changed) lastTriggerValue = value;
                            }
                            if (changed) {
                                XposedBridge.log(TAG + ": manual GNSS trigger received");
                                queueOneShotGnssRequest(value, setProperty);
                            }
                        }

                        Object forceObject = getProperty.invoke(null, FORCE_TIME_PROPERTY, "");
                        String forceValue = forceObject == null ? "" : forceObject.toString();
                        if (!forceValue.isEmpty()) {
                            boolean changed;
                            synchronized (GnssTriggerBridge.this) {
                                changed = !forceValue.equals(lastForceTimeValue);
                                if (changed) lastForceTimeValue = forceValue;
                            }
                            if (changed) {
                                XposedBridge.log(TAG + ": force-time request received");
                                handleForceTimeRequest(forceValue, setProperty);
                            }
                        }

                        Object cancelObject = getProperty.invoke(null, GNSS_CANCEL_PROPERTY, "");
                        String cancelValue = cancelObject == null
                            ? "" : cancelObject.toString();
                        boolean cancelChanged;
                        synchronized (GnssTriggerBridge.this) {
                            cancelChanged = !cancelValue.equals(lastCancelValue);
                            if (cancelChanged) lastCancelValue = cancelValue;
                        }
                        if (cancelChanged && !cancelValue.isEmpty()) {
                            cancelRecoverySession(cancelValue);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": trigger property callback failed safely: " + t);
                    }
                }
            };
            addCallback.invoke(null, callback);
            XposedBridge.log(TAG + ": trigger property watcher installed: " + TRIGGER_PROPERTY);
            Handler handler = getForegroundHandler();
            if (handler != null) {
                Runnable fallbackPoll = new Runnable() {
                    @Override
                    public void run() {
                        callback.run();
                        // Fast cadence only while a request may be in flight; otherwise the
                        // property change callback is the primary trigger and polling is backup.
                        long delay = hasActiveSession()
                            ? PROPERTY_POLL_INTERVAL_MILLIS
                            : IDLE_PROPERTY_POLL_INTERVAL_MILLIS;
                        handler.postDelayed(this, delay);
                    }
                };
                handler.postDelayed(fallbackPoll, PROPERTY_POLL_INTERVAL_MILLIS);
                XposedBridge.log(TAG + ": trigger property polling fallback started");
            } else {
                XposedBridge.log(TAG + ": trigger property polling fallback unavailable");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": trigger property watcher unavailable: " + t);
        }
    }

    private synchronized boolean hasActiveSession() {
        return triggerPending.get() || !activeRecoveryToken.isEmpty();
    }

    private void queueOneShotGnssRequest(final String token, final Method setProperty) {
        if (!triggerPending.compareAndSet(false, true)) {
            XposedBridge.log(TAG + ": GNSS trigger ignored; request already pending");
            writeGnssResult(setProperty, token, "request_already_pending");
            return;
        }
        Handler handler = getForegroundHandler();
        if (handler == null || !handler.post(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    beginRecoverySession(token);
                    result = armOneShotRequest();
                    XposedBridge.log(TAG + ": GNSS trigger result: " + result);
                    if (!isListenerArmed(result)) {
                        endRecoverySession(token, "initial_listener_arm_failed", false);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": GNSS trigger failed safely: " + t);
                    result = "bridge_exception";
                    endRecoverySession(token, "initial_listener_arm_failed", false);
                } finally {
                    triggerPending.set(false);
                }
                writeGnssResult(setProperty, token, result);
            }
        })) {
            triggerPending.set(false);
            XposedBridge.log(TAG + ": could not queue GNSS trigger on FgThread");
            writeGnssResult(setProperty, token, "queue_failed");
        }
    }

    private void beginRecoverySession(String token) {
        long now = SystemClock.elapsedRealtime();
        long baseline = now;
        try {
            // The companion token is elapsedRealtimeNanos captured at button press.
            baseline = Long.parseLong(token) / 1_000_000L;
        } catch (Throwable ignored) {
            // Keep the server-side baseline if a future client uses a non-numeric token.
        }
        synchronized (this) {
            activeRecoveryToken = token;
            activeRecoveryBaselineElapsedMillis = baseline;
            activeRecoveryDeadlineElapsedMillis = baseline + GNSS_RECOVERY_WINDOW_MILLIS;
            recoveryAttempts = 0;
            rearmScheduled = false;
        }
        XposedBridge.log(TAG + ": bounded GNSS stale-sample recovery started; baseline="
            + baseline + " deadline=" + (baseline + GNSS_RECOVERY_WINDOW_MILLIS));
    }

    private void handlePassiveLocationTimeResult(final Object service) {
        final long now = SystemClock.elapsedRealtime();
        final long sampleElapsed = readLastSuggestedElapsedMillis(service);
        String token;
        String terminalStatus = null;
        int attempt = 0;
        synchronized (this) {
            if (activeRecoveryToken.isEmpty()) return;
            token = activeRecoveryToken;
            if (now >= activeRecoveryDeadlineElapsedMillis) {
                terminalStatus = "rearm_window_expired";
                activeRecoveryToken = "";
                rearmScheduled = false;
            } else if (sampleElapsed > activeRecoveryBaselineElapsedMillis) {
                terminalStatus = "fresh_sample_observed";
                activeRecoveryToken = "";
                rearmScheduled = false;
            } else if (recoveryAttempts >= GNSS_MAX_REARM_ATTEMPTS) {
                terminalStatus = "rearm_limit_reached";
                activeRecoveryToken = "";
                rearmScheduled = false;
            } else if (rearmScheduled) {
                return;
            } else {
                rearmScheduled = true;
                attempt = ++recoveryAttempts;
            }
        }

        if (terminalStatus != null) {
            XposedBridge.log(TAG + ": GNSS recovery stopped: " + terminalStatus
                + " sampleElapsed=" + sampleElapsed);
            publishGnssResult(token, terminalStatus);
            return;
        }

        final int scheduledAttempt = attempt;
        Handler handler = getForegroundHandler();
        if (handler == null) {
            endRecoverySession(token, "rearm_handler_unavailable", true);
            return;
        }
        boolean posted = handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performRecoveryRearm(token, scheduledAttempt, service);
            }
        }, GNSS_REARM_DELAY_MILLIS);
        if (!posted) {
            endRecoverySession(token, "rearm_queue_failed", true);
        } else {
            XposedBridge.log(TAG + ": stale GNSS time sample; queued passive listener re-arm "
                + scheduledAttempt + "/" + GNSS_MAX_REARM_ATTEMPTS
                + " after " + GNSS_REARM_DELAY_MILLIS + " ms");
            publishGnssResult(token, "stale_sample_rearm_scheduled_" + scheduledAttempt);
        }
    }

    private void performRecoveryRearm(String token, int attempt, Object service) {
        String terminalStatus = null;
        synchronized (this) {
            if (!token.equals(activeRecoveryToken)) return;
            rearmScheduled = false;
            if (SystemClock.elapsedRealtime() >= activeRecoveryDeadlineElapsedMillis) {
                activeRecoveryToken = "";
                terminalStatus = "rearm_window_expired";
            }
        }
        if (terminalStatus != null) {
            publishGnssResult(token, terminalStatus);
            return;
        }

        long sampleElapsed = readLastSuggestedElapsedMillis(service);
        synchronized (this) {
            if (!token.equals(activeRecoveryToken)) return;
            if (sampleElapsed > activeRecoveryBaselineElapsedMillis) {
                activeRecoveryToken = "";
                terminalStatus = "fresh_sample_observed";
            }
        }
        if (terminalStatus != null) {
            publishGnssResult(token, terminalStatus);
            XposedBridge.log(TAG + ": fresh GNSS sample appeared before re-arm; recovery stopped");
            return;
        }

        try {
            String result = armOneShotRequest();
            if (isListenerArmed(result)) {
                XposedBridge.log(TAG + ": stale-sample passive listener re-armed; attempt="
                    + attempt + " result=" + result);
                publishGnssResult(token, "stale_sample_rearmed_" + attempt);
            } else {
                endRecoverySession(token, "rearm_failed_" + result, true);
                XposedBridge.log(TAG + ": GNSS passive listener re-arm failed: " + result);
            }
        } catch (Throwable t) {
            endRecoverySession(token, "rearm_exception", true);
            XposedBridge.log(TAG + ": GNSS passive listener re-arm failed safely: " + t);
        }
    }

    private void cancelRecoverySession(String token) {
        synchronized (this) {
            if (token == null || !token.equals(activeRecoveryToken)) return;
        }
        XposedBridge.log(TAG + ": GNSS recovery cancellation received from companion app");
        endRecoverySession(token, "rearm_cancelled", true);
    }

    private void endRecoverySession(String token, String status, boolean publish) {
        boolean ended = false;
        synchronized (this) {
            if (token != null && token.equals(activeRecoveryToken)) {
                activeRecoveryToken = "";
                rearmScheduled = false;
                ended = true;
            }
        }
        if (ended && publish) publishGnssResult(token, status);
    }

    private void publishGnssResult(String token, String result) {
        Method setProperty = setSystemProperty;
        if (setProperty != null && token != null && !token.isEmpty()) {
            writeGnssResult(setProperty, token, result);
        }
    }

    private static boolean isListenerArmed(String result) {
        return "passive_listener_armed".equals(result)
            || "existing_listener_restarted_and_rearmed".equals(result);
    }

    private static long readLastSuggestedElapsedMillis(Object service) {
        try {
            Field suggestionField = findField(service.getClass(), "mLastSuggestedGnssTime");
            suggestionField.setAccessible(true);
            Object suggestion = suggestionField.get(service);
            if (suggestion == null) return -1L;

            Method getter = findNoArgMethod(suggestion.getClass(), "getElapsedRealtimeMillis");
            if (getter != null) {
                getter.setAccessible(true);
                Object result = getter.invoke(suggestion);
                if (result instanceof Number) return ((Number) result).longValue();
            }

            Field elapsedField = findField(suggestion.getClass(), "mElapsedRealtimeMillis");
            elapsedField.setAccessible(true);
            Object result = elapsedField.get(suggestion);
            return result instanceof Number ? ((Number) result).longValue() : -1L;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not read last GNSS suggestion timestamp: " + t);
            return -1L;
        }
    }

    private static void writeGnssResult(Method setProperty, String token, String result) {
        try {
            setProperty.invoke(null, GNSS_RESULT_PROPERTY, token + ":" + result);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not publish GNSS trigger result: " + t);
        }
    }

    private Handler getForegroundHandler() {
        Handler cached = foregroundHandler;
        if (cached != null) return cached;
        try {
            Class<?> fgThread = Class.forName("com.android.server.FgThread", false,
                systemServerClassLoader);
            Method getHandler = fgThread.getDeclaredMethod("getHandler");
            getHandler.setAccessible(true);
            Object result = getHandler.invoke(null);
            if (result instanceof Handler) {
                foregroundHandler = (Handler) result;
                return foregroundHandler;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not access FgThread handler: " + t);
        }
        return null;
    }

    private void handleForceTimeRequest(String request, final Method setProperty) {
        String[] parts = request.split(":", 2);
        if (parts.length != 2 || parts[0].isEmpty()) {
            XposedBridge.log(TAG + ": malformed force-time request rejected");
            return;
        }
        final String token = parts[0];
        final long targetMillis;
        try {
            targetMillis = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            writeForceResult(setProperty, token, false);
            return;
        }
        if (targetMillis < MIN_FORCE_TIME_MILLIS || targetMillis > MAX_FORCE_TIME_MILLIS) {
            XposedBridge.log(TAG + ": force-time target rejected by date bounds");
            writeForceResult(setProperty, token, false);
            return;
        }

        Handler handler = getForegroundHandler();
        if (handler == null || !handler.post(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    Class<?> systemClock = Class.forName("android.os.SystemClock", false,
                        systemServerClassLoader);
                    Method setCurrentTimeMillis = systemClock.getDeclaredMethod(
                        "setCurrentTimeMillis", long.class);
                    setCurrentTimeMillis.setAccessible(true);
                    Object result = setCurrentTimeMillis.invoke(null, targetMillis);
                    success = !(result instanceof Boolean) || ((Boolean) result).booleanValue();
                    XposedBridge.log(TAG + ": confirmed force-time request result=" + success);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": force-time request failed safely: " + t);
                }
                writeForceResult(setProperty, token, success);
            }
        })) {
            writeForceResult(setProperty, token, false);
        }
    }

    private static void writeForceResult(Method setProperty, String token, boolean success) {
        try {
            setProperty.invoke(null, FORCE_RESULT_PROPERTY,
                token + (success ? ":OK" : ":FAIL"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not publish force-time result: " + t);
        }
    }

    private String armOneShotRequest() throws Exception {
        Object service = gnssTimeUpdateService;
        if (service == null) return "service_not_started";

        Field lockField = findField(service.getClass(), "mLock");
        Field locationListenerField = findField(service.getClass(), "mLocationListener");
        Field locationManagerField = findField(service.getClass(), "mLocationManager");
        Method startMethod = findNoArgMethod(service.getClass(), "startGnssListeningInternal");
        if (startMethod == null) return "startGnssListeningInternal_unavailable";

        lockField.setAccessible(true);
        locationListenerField.setAccessible(true);
        locationManagerField.setAccessible(true);
        startMethod.setAccessible(true);

        Object lock = lockField.get(service);
        Object locationManager = locationManagerField.get(service);
        if (lock == null || locationManager == null) return "service_state_unavailable";

        synchronized (lock) {
            Object oldListener = locationListenerField.get(service);
            if (oldListener != null) {
                Method removeUpdates = findRemoveUpdatesMethod(
                    locationManager.getClass(), oldListener);
                if (removeUpdates == null) return "remove_location_listener_unavailable";
                removeUpdates.setAccessible(true);
                removeUpdates.invoke(locationManager, oldListener);
                locationListenerField.set(service, null);
            }

            Object started = startMethod.invoke(service);
            if (started instanceof Boolean && !((Boolean) started).booleanValue()) {
                return "gps_provider_unavailable";
            }
            Object newListener = locationListenerField.get(service);
            if (newListener == null) return "listener_restart_not_confirmed";
            return oldListener == null
                ? "passive_listener_armed"
                : "existing_listener_restarted_and_rearmed";
        }
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findRemoveUpdatesMethod(Class<?> type, Object listener) {
        for (Method method : type.getMethods()) {
            if ("removeUpdates".equals(method.getName())
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isInstance(listener)) {
                return method;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
