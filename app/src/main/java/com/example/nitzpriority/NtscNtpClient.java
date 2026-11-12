package com.example.nitzpriority;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Independent NTP reference client for the National Time Service Center
 * (NTSC, 中国科学院国家授时中心).
 *
 * <p>Display only: this class never sets the system clock and its result is
 * never fed into the automatic time detector or the force-jump flow. It
 * directly queries the NTSC public NTP service over UDP so the diagnostics
 * page has a real NTSC reference instead of re-labelling the generic
 * Android network (NTP) suggestion as "Beijing time".
 *
 * <p>Accuracy honesty: the result is shown in milliseconds, but that is
 * display resolution, not 1&nbsp;ms absolute accuracy. Public-internet NTP
 * typically carries 10-30&nbsp;ms of uncertainty from asymmetric network
 * delay, which is why every result carries its own uncertainty
 * ({@code delay / 2}) and the UI must show it next to the offset.
 */
final class NtscNtpClient {
    private static final String TAG = "NitzPriority";

    /**
     * NTSC public NTP hosts, tried in order. Domains only; the one hard-coded
     * IP ({@link #NTSC_DIRECT_IP}) is a last-resort fallback below, not a host.
     * ntp1.ntsc.ac.cn is deliberately absent: it has no DNS record at all
     * (verified on device 2026-11-12: ntp.ntsc.ac.cn resolves, ntp1 does not),
     * and the official NTSC announcement only ever published ntp.ntsc.ac.cn.
     * ntp2/ntp3 are kept for now, unverified; a dead name only costs one fast
     * DNS failure and can no longer kill the whole query.
     */
    static final String[] NTSC_HOSTS = {
        "ntp.ntsc.ac.cn",
        "ntp2.ntsc.ac.cn",
        "ntp3.ntsc.ac.cn",
    };
    /**
     * NTSC official IP (published alongside ntp.ntsc.ac.cn). Used only when
     * at least one NTSC domain failed DNS: with DNS broken, resolving any
     * name is hopeless, but a literal IP needs no DNS at all. Never tried
     * when every name resolved but the server simply did not answer — the IP
     * is the same server and would time out the same way, so the remaining
     * budget is kept for the pool fallback instead.
     */
    static final String NTSC_DIRECT_IP = "210.72.145.44";
    /** Fallback when every NTSC host is unreachable. Not an NTSC source. */
    static final String FALLBACK_HOST = "cn.pool.ntp.org";

    static final int NTP_PORT = 123;
    static final int NTP_PACKET_SIZE = 48;
    /** NTP epoch (1900-01-01) to Unix epoch (1970-01-01) offset, in seconds. */
    static final long NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L;
    static final long MIN_EPOCH_MILLIS = 1_577_836_800_000L; // 2020-01-01 UTC
    static final long MAX_EPOCH_MILLIS = 4_102_444_800_000L; // 2100-01-01 UTC

    static final int SOCKET_TIMEOUT_MILLIS = 3_000;
    static final int SAMPLES_PER_SERVER = 5;
    static final int DISCARDED_WORST_SAMPLES = 2;
    static final int MIN_VALID_SAMPLES = 3;
    static final int MAX_CONSECUTIVE_TIMEOUTS_PER_SERVER = 2;
    /** Whole query (all servers) must finish inside this budget. */
    static final long QUERY_DEADLINE_MILLIS = 15_000;
    /** A cached result younger than this is reused instead of querying again. */
    static final long CACHE_TTL_MILLIS = 60_000;
    /** A result older than this is shown as expired, never as fresh. */
    static final long RESULT_MAX_AGE_MILLIS = 600_000;
    /** Samples with an implausible offset (e.g. a broken server clock) are dropped. */
    static final long MAX_PLAUSIBLE_OFFSET_MILLIS = 86_400_000L;

    private static final Object LOCK = new Object();
    private static Result cachedResult;
    private static long cachedAtElapsed = -1L;

    private NtscNtpClient() {
    }

    /** One NTP query result. Immutable; {@code lastGood} is only set on failures. */
    static final class Result {
        final boolean success;
        /** Host that actually answered, e.g. "ntp2.ntsc.ac.cn". Null on failure. */
        final String server;
        /** True when the answering host is the pool fallback, not NTSC. */
        final boolean fallback;
        /**
         * True when the answer came from the NTSC direct-IP fallback
         * ({@link #NTSC_DIRECT_IP}). The server field then holds the IP, and
         * the UI must say "NTSC IP direct" rather than imply a name resolved.
         */
        final boolean directIp;
        /** Median offset: server time minus phone time, in ms. Positive = phone lags. */
        final long offsetMillis;
        /** Uncertainty budget: median round-trip delay / 2, in ms. */
        final long uncertaintyMillis;
        /** Median round-trip delay of the kept samples, in ms. */
        final long medianRttMillis;
        /** Samples kept for the median (3 when the query fully succeeded). */
        final int sampleCount;
        /** Samples attempted on the answering server (5 when fully sampled). */
        final int samplesAttempted;
        /** Elapsed-realtime timestamp of the query, for age display. */
        final long queryElapsedRealtimeMillis;
        /** Failure reason in plain language, null on success. */
        final String error;
        /** Most recent successful result, if any and still within max age. */
        final Result lastGood;

        Result(boolean success, String server, boolean fallback, boolean directIp,
                long offsetMillis,
                long uncertaintyMillis, long medianRttMillis, int sampleCount,
                int samplesAttempted, long queryElapsedRealtimeMillis, String error,
                Result lastGood) {
            this.success = success;
            this.server = server;
            this.fallback = fallback;
            this.directIp = directIp;
            this.offsetMillis = offsetMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.medianRttMillis = medianRttMillis;
            this.sampleCount = sampleCount;
            this.samplesAttempted = samplesAttempted;
            this.queryElapsedRealtimeMillis = queryElapsedRealtimeMillis;
            this.error = error;
            this.lastGood = lastGood;
        }

        long ageMillis(long nowElapsed) {
            return nowElapsed - queryElapsedRealtimeMillis;
        }

        boolean isExpired(long nowElapsed) {
            return ageMillis(nowElapsed) > RESULT_MAX_AGE_MILLIS;
        }
    }

    /**
     * Queries the NTSC NTP service and returns the median of 5 samples with
     * the 2 worst-RTT samples discarded. Must be called off the main thread:
     * it does DNS and UDP I/O. Never throws.
     *
     * @param forceRefresh true (manual refresh) bypasses the 60&nbsp;s cache;
     *                     the 5-sample median is still performed.
     */
    static Result query(boolean forceRefresh) {
        synchronized (LOCK) {
            long nowElapsed = SystemClock.elapsedRealtime();
            if (!forceRefresh && cachedResult != null && cachedAtElapsed >= 0
                    && nowElapsed - cachedAtElapsed < CACHE_TTL_MILLIS) {
                return cachedResult;
            }
        }
        Result fresh;
        try {
            fresh = queryFresh();
        } catch (Throwable t) {
            fresh = failure("查询异常：" + t.getClass().getSimpleName());
        }
        synchronized (LOCK) {
            if (fresh.success) {
                cachedResult = fresh;
                cachedAtElapsed = fresh.queryElapsedRealtimeMillis;
            }
        }
        return fresh;
    }

    /**
     * Tries, in order: the NTSC domains, the NTSC direct IP (only when at
     * least one domain failed DNS), then the pool fallback. A per-host
     * failure never aborts the query: DNS failure, timeout, and too-few-samples
     * are recorded per host and the next host is tried. Only when every name
     * failed DNS does the result say so; a timeout is never reported as a
     * DNS failure.
     */
    private static Result queryFresh() {
        long deadlineElapsed = SystemClock.elapsedRealtime() + QUERY_DEADLINE_MILLIS;
        String lastError = "未知错误";
        int dnsFailures = 0;
        for (String host : NTSC_HOSTS) {
            try {
                Result result = queryServer(host, false, false, deadlineElapsed);
                if (result != null) return result;
                lastError = "服务器 " + host + " 有效样本不足 3 个";
            } catch (UnknownHostException e) {
                // One dead name must not kill the whole query: ntp1.ntsc.ac.cn
                // has no DNS record at all, while the other names may resolve
                // fine. Record it and try the next host.
                dnsFailures++;
                lastError = "服务器 " + host + " DNS 解析失败";
            } catch (SocketTimeoutException e) {
                lastError = "服务器 " + host + " 超时无响应";
            } catch (Exception e) {
                lastError = "服务器 " + host + " 查询失败：" + e.getMessage();
            }
            if (SystemClock.elapsedRealtime() >= deadlineElapsed) {
                return failure("查询超时（超过 " + (QUERY_DEADLINE_MILLIS / 1000)
                    + " 秒），" + lastError);
            }
        }
        // Direct-IP fallback. A literal IP needs no DNS, so it is the one
        // thing that can still work when name resolution is broken. It is
        // only attempted when DNS actually failed somewhere above: when every
        // name resolved but the server never answered, the IP is the same
        // server and would time out the same way.
        if (dnsFailures > 0 && SystemClock.elapsedRealtime() < deadlineElapsed) {
            try {
                Result result = queryServer(NTSC_DIRECT_IP, false, true, deadlineElapsed);
                if (result != null) return result;
                lastError = "授时中心 IP 直连 " + NTSC_DIRECT_IP + " 有效样本不足 3 个";
            } catch (UnknownHostException e) {
                // A literal IP never needs DNS; treat it as unreachable.
                lastError = "授时中心 IP 直连 " + NTSC_DIRECT_IP + " 无法访问";
            } catch (SocketTimeoutException e) {
                lastError = "授时中心 IP 直连 " + NTSC_DIRECT_IP + " 超时无响应";
            } catch (Exception e) {
                lastError = "授时中心 IP 直连 " + NTSC_DIRECT_IP + " 查询失败：" + e.getMessage();
            }
            if (SystemClock.elapsedRealtime() >= deadlineElapsed) {
                return failure("查询超时（超过 " + (QUERY_DEADLINE_MILLIS / 1000)
                    + " 秒），" + lastError);
            }
        }
        try {
            Result result = queryServer(FALLBACK_HOST, true, false, deadlineElapsed);
            if (result != null) return result;
            lastError = "服务器 " + FALLBACK_HOST + " 有效样本不足 3 个";
        } catch (UnknownHostException e) {
            dnsFailures++;
            lastError = "服务器 " + FALLBACK_HOST + " DNS 解析失败";
        } catch (SocketTimeoutException e) {
            lastError = "服务器 " + FALLBACK_HOST + " 超时无响应";
        } catch (Exception e) {
            lastError = "服务器 " + FALLBACK_HOST + " 查询失败：" + e.getMessage();
        }
        if (dnsFailures == NTSC_HOSTS.length + 1) {
            return failure("DNS 解析失败：全部服务器域名均无法解析，请检查网络");
        }
        return failure("全部服务器无有效响应；" + lastError);
    }

    /**
     * Samples one host up to {@link #SAMPLES_PER_SERVER} times. Returns a
     * successful result, or null when this host cannot produce enough valid
     * samples (the caller then tries the next host). Throws
     * {@link UnknownHostException} / {@link SocketTimeoutException} so the
     * caller can tell DNS failure apart from plain timeouts. {@code host} may
     * be a domain or a literal IP; the sampling, median, and validation logic
     * is identical either way.
     */
    private static Result queryServer(String host, boolean fallback, boolean directIp,
            long deadlineElapsed)
            throws Exception {
        InetAddress address = InetAddress.getByName(host);
        List<Sample> valid = new ArrayList<>();
        int attempted = 0;
        int consecutiveTimeouts = 0;
        while (attempted < SAMPLES_PER_SERVER
                && SystemClock.elapsedRealtime() < deadlineElapsed) {
            attempted++;
            try {
                Sample sample = singleExchange(address);
                consecutiveTimeouts = 0;
                if (sample != null) valid.add(sample);
            } catch (SocketTimeoutException e) {
                consecutiveTimeouts++;
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS_PER_SERVER) break;
            }
        }
        if (valid.size() < MIN_VALID_SAMPLES) return null;
        // Drop the worst-RTT samples, then take the median of what remains.
        Collections.sort(valid, (a, b) -> Long.compare(a.rttMillis, b.rttMillis));
        int keep = valid.size() - DISCARDED_WORST_SAMPLES;
        if (keep < MIN_VALID_SAMPLES) keep = MIN_VALID_SAMPLES;
        List<Sample> kept = valid.subList(0, keep);
        double medianOffset = medianOffset(kept);
        long medianRtt = medianRtt(kept);
        return new Result(true, host, fallback, directIp, Math.round(medianOffset),
            Math.max(0L, medianRtt / 2), medianRtt, kept.size(), attempted,
            SystemClock.elapsedRealtime(), null, null);
    }

    /** One NTP request/response exchange. Returns null for implausible replies. */
    private static Sample singleExchange(InetAddress address) throws Exception {
        byte[] request = new byte[NTP_PACKET_SIZE];
        // LI = 0 (no warning), VN = 4, Mode = 3 (client).
        request[0] = 0x23;
        long t1 = System.currentTimeMillis();
        writeTimestamp(request, 40, t1);
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(address, NTP_PORT);
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[NTP_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            long t4 = System.currentTimeMillis();
            if (packet.getLength() < NTP_PACKET_SIZE) return null;
            int mode = response[0] & 0x07;
            int stratum = response[1] & 0xFF;
            if (mode != 4 || stratum < 1 || stratum > 15) return null;
            long t2 = readTimestamp(response, 32);
            long t3 = readTimestamp(response, 40);
            if (t2 == 0 || t3 == 0) return null;
            if (t2 < MIN_EPOCH_MILLIS || t2 > MAX_EPOCH_MILLIS
                    || t3 < MIN_EPOCH_MILLIS || t3 > MAX_EPOCH_MILLIS) return null;
            // Standard NTP formulae; t1/t4 are client wall time, t2/t3 server time.
            double offset = ((t2 - t1) + (t3 - t4)) / 2.0;
            long delay = (t4 - t1) - (t3 - t2);
            if (delay < 0 || delay > SOCKET_TIMEOUT_MILLIS) return null;
            if (Math.abs(offset) > MAX_PLAUSIBLE_OFFSET_MILLIS) return null;
            return new Sample(offset, delay);
        } finally {
            socket.close();
        }
    }

    private static double medianOffset(List<Sample> samples) {
        List<Double> offsets = new ArrayList<>();
        for (Sample sample : samples) offsets.add(sample.offsetMillis);
        Collections.sort(offsets);
        return offsets.get(offsets.size() / 2);
    }

    private static long medianRtt(List<Sample> samples) {
        List<Long> rtts = new ArrayList<>();
        for (Sample sample : samples) rtts.add(sample.rttMillis);
        Collections.sort(rtts);
        return rtts.get(rtts.size() / 2);
    }

    private static Result failure(String error) {
        Result lastGood = null;
        synchronized (LOCK) {
            if (cachedResult != null && cachedResult.success
                    && !cachedResult.isExpired(SystemClock.elapsedRealtime())) {
                lastGood = cachedResult;
            }
        }
        return new Result(false, null, false, false, 0, -1, -1, 0, 0,
            SystemClock.elapsedRealtime(), error, lastGood);
    }

    private static void writeTimestamp(byte[] buffer, int offset, long unixMillis) {
        long seconds = unixMillis / 1000 + NTP_UNIX_EPOCH_OFFSET_SECONDS;
        long fraction = (unixMillis % 1000) * 4_294_967_296L / 1000;
        buffer[offset] = (byte) (seconds >> 24);
        buffer[offset + 1] = (byte) (seconds >> 16);
        buffer[offset + 2] = (byte) (seconds >> 8);
        buffer[offset + 3] = (byte) seconds;
        buffer[offset + 4] = (byte) (fraction >> 24);
        buffer[offset + 5] = (byte) (fraction >> 16);
        buffer[offset + 6] = (byte) (fraction >> 8);
        buffer[offset + 7] = (byte) fraction;
    }

    private static long readTimestamp(byte[] buffer, int offset) {
        long seconds = ((buffer[offset] & 0xFFL) << 24)
            | ((buffer[offset + 1] & 0xFFL) << 16)
            | ((buffer[offset + 2] & 0xFFL) << 8)
            | (buffer[offset + 3] & 0xFFL);
        long fraction = ((buffer[offset + 4] & 0xFFL) << 24)
            | ((buffer[offset + 5] & 0xFFL) << 16)
            | ((buffer[offset + 6] & 0xFFL) << 8)
            | (buffer[offset + 7] & 0xFFL);
        // Round to the nearest millisecond instead of flooring: a plain
        // floor would bias every decoded server timestamp down by up to 1 ms.
        return (seconds - NTP_UNIX_EPOCH_OFFSET_SECONDS) * 1000
            + (fraction * 1000 + 2_147_483_648L) / 4_294_967_296L;
    }

    private static final class Sample {
        final double offsetMillis;
        final long rttMillis;

        Sample(double offsetMillis, long rttMillis) {
            this.offsetMillis = offsetMillis;
            this.rttMillis = rttMillis;
        }
    }
}
