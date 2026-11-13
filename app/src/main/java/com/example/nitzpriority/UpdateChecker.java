package com.example.nitzpriority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Checks GitHub's rolling {@code latest} release for a newer APK.
 *
 * <p>The comparison is the release asset's {@code updated_at} against this
 * build's embedded build time (see {@code app_build_time_millis} in
 * build.gradle). A plain "asset is newer than build time" check would
 * false-positive on a build's own asset: the asset is uploaded a few
 * minutes after the build finishes, so it is always newer than the build
 * time of the build it came from. {@link #NEWER_MARGIN_MILLIS} (5 min)
 * covers the usual build + upload gap (~2-3 min); a genuinely newer
 * build is uploaded hours (or at least tens of minutes) later, so it
 * still shows up. Two pushes within 5 minutes of each other are the one
 * case this can miss; the next push fixes it.
 *
 * <p>Unauthenticated GitHub API (60 requests/hour per IP) is enough: the
 * check only runs when the user taps "检查更新". No token, no account.
 */
public final class UpdateChecker {
    private UpdateChecker() {
    }

    public static final String OWNER = "xx2901318208";
    public static final String REPO = "Satellite-Time-Synchronization";
    public static final String RELEASE_API_URL =
        "https://api.github.com/repos/" + OWNER + "/" + REPO
            + "/releases/tags/latest";
    public static final String DIRECT_APK_URL =
        "https://github.com/" + OWNER + "/" + REPO
            + "/releases/download/latest/app-release.apk";
    public static final String APK_ASSET_NAME = "app-release.apk";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final long NEWER_MARGIN_MILLIS = 5 * 60 * 1_000L;

    /** Result of one update check; {@link #error} is non-null on failure. */
    public static final class Result {
        public final boolean updateAvailable;
        public final String downloadUrl;
        public final long releaseUpdatedAtMillis;
        public final long assetSizeBytes;
        public final String error;

        private Result(boolean updateAvailable, String downloadUrl,
                long releaseUpdatedAtMillis, long assetSizeBytes, String error) {
            this.updateAvailable = updateAvailable;
            this.downloadUrl = downloadUrl;
            this.releaseUpdatedAtMillis = releaseUpdatedAtMillis;
            this.assetSizeBytes = assetSizeBytes;
            this.error = error;
        }

        static Result error(String error) {
            return new Result(false, DIRECT_APK_URL, -1L, -1L, error);
        }
    }

    /**
     * Synchronous network call: run off the UI thread. {@code buildTimeMillis}
     * is this APK's embedded build time; 0 (unknown, e.g. a build before the
     * field existed) means any release counts as newer.
     */
    public static Result check(long buildTimeMillis) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_API_URL).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty(
                "User-Agent", "Satellite-Time-Synchronization-App");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return Result.error("GitHub 返回 HTTP " + status);
            }
            JSONObject release = new JSONObject(readAll(connection.getInputStream()));
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                return Result.error("发布中没有附件");
            }
            JSONObject apk = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset != null && APK_ASSET_NAME.equals(asset.optString("name"))) {
                    apk = asset;
                    break;
                }
            }
            if (apk == null) {
                return Result.error("发布中没有 app-release.apk");
            }
            String updatedAt = apk.optString("updated_at", "");
            if (updatedAt.isEmpty()) {
                return Result.error("附件缺少更新时间");
            }
            long releaseMillis = Instant.parse(updatedAt).toEpochMilli();
            String downloadUrl = apk.optString("browser_download_url", "");
            if (downloadUrl.isEmpty()) {
                downloadUrl = DIRECT_APK_URL;
            }
            long size = apk.optLong("size", -1L);
            boolean newer = releaseMillis > buildTimeMillis + NEWER_MARGIN_MILLIS;
            return new Result(newer, downloadUrl, releaseMillis, size, null);
        } catch (Throwable t) {
            String message = t.getMessage();
            return Result.error(t.getClass().getSimpleName()
                + (message == null ? "" : ": " + message));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
