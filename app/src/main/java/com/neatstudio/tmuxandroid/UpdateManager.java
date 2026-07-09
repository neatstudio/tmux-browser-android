package com.neatstudio.tmuxandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateManager {
    private static final String PREF_PENDING_INSTALL_APK = "pending_install_apk";
    private static final int NETWORK_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1200L;

    interface Callback {
        void onChecking(boolean checking);
        void onMessage(String message);
    }

    private interface ReleaseUrlPicker {
        String pick(ReleaseInfo info);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean checkInProgress;
    private volatile boolean downloadInProgress;
    private File pendingInstallApk;

    UpdateManager(Activity activity, SharedPreferences prefs, Callback callback) {
        this.activity = activity;
        this.prefs = prefs;
        this.callback = callback;
    }

    void check(boolean userInitiated) {
        checkWithFallback(userInitiated);
    }

    void checkSelected(boolean userInitiated) {
        startUpdateCheck(
                userInitiated,
                "selected source",
                new String[]{getUpdateManifestUrl()}
        );
    }

    void checkGitea(boolean userInitiated) {
        startUpdateCheck(
                userInitiated,
                "Gitea",
                new String[]{BuildConfig.DEFAULT_GITEA_UPDATE_URL}
        );
    }

    void checkGithub(boolean userInitiated) {
        startUpdateCheck(
                userInitiated,
                "GitHub",
                new String[]{BuildConfig.DEFAULT_GITHUB_UPDATE_URL}
        );
    }

    void checkPreview(boolean userInitiated) {
        startUpdateCheck(
                userInitiated,
                "Preview",
                new String[]{BuildConfig.DEFAULT_PREVIEW_UPDATE_URL}
        );
    }

    void checkWithFallback(boolean userInitiated) {
        startUpdateCheck(
                userInitiated,
                "Gitea, then GitHub",
                new String[]{BuildConfig.DEFAULT_GITEA_UPDATE_URL, BuildConfig.DEFAULT_GITHUB_UPDATE_URL}
        );
    }

    private void startUpdateCheck(boolean userInitiated, String label, String[] manifestUrls) {
        if (checkInProgress) {
            postMessage("Update check already running");
            return;
        }
        checkInProgress = true;
        callback.onChecking(true);
        postMessage("Checking update: " + label + "...");
        executor.execute(() -> {
            Exception lastError = null;
            try {
                for (int index = 0; index < manifestUrls.length; index++) {
                    String manifestUrl = manifestUrls[index];
                    try {
                        postMessage("Checking " + hostLabel(manifestUrl) + "...");
                        ReleaseInfo info = fetchReleaseInfo(manifestUrl);
                        if (info.versionCode <= BuildConfig.VERSION_CODE) {
                            postMessage("Already up to date: " + BuildConfig.VERSION_NAME + " from " + hostLabel(manifestUrl));
                            return;
                        }
                        postMessage("Update found: " + info.versionName + " from " + hostLabel(manifestUrl));
                        activity.runOnUiThread(() -> showUpdateDialog(info));
                        return;
                    } catch (Exception error) {
                        lastError = error;
                        if (index + 1 < manifestUrls.length) {
                            postMessage(hostLabel(manifestUrl) + " failed; trying " + hostLabel(manifestUrls[index + 1]) + "...");
                        }
                    }
                }
                if (lastError != null) {
                    throw lastError;
                }
                throw new IllegalStateException("No update sources configured");
            } catch (Exception finalError) {
                postMessage(userInitiated ? "Update check failed: " + finalError.getMessage() : null);
            } finally {
                checkInProgress = false;
                activity.runOnUiThread(() -> callback.onChecking(false));
            }
        });
    }

    void openApkDownload() {
        openSelectedReleaseUrl("APK", info -> info.apkUrl);
    }

    void openReleasePage() {
        openSelectedReleaseUrl("Release page", info -> info.releasePageUrl);
    }

    private void openSelectedReleaseUrl(String label, ReleaseUrlPicker picker) {
        if (checkInProgress) {
            postMessage("Update check already running");
            return;
        }
        String manifestUrl = getUpdateManifestUrl();
        checkInProgress = true;
        callback.onChecking(true);
        postMessage("Resolving " + label + " from " + hostLabel(manifestUrl) + "...");
        executor.execute(() -> {
            try {
                ReleaseInfo info = fetchReleaseInfo(manifestUrl);
                String url = picker.pick(info);
                if (url == null || url.trim().isEmpty()) {
                    throw new IllegalStateException(label + " URL is missing");
                }
                activity.runOnUiThread(() -> openExternalUrl(url.trim(), label));
            } catch (Exception error) {
                postMessage(label + " failed: " + error.getMessage());
            } finally {
                checkInProgress = false;
                activity.runOnUiThread(() -> callback.onChecking(false));
            }
        });
    }

    private String getUpdateManifestUrl() {
        String url = prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL);
        if (url == null || url.trim().isEmpty()) {
            return BuildConfig.DEFAULT_UPDATE_URL;
        }
        return url.trim();
    }

    private String hostLabel(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }

    private ReleaseInfo fetchReleaseInfo(String manifestUrl) throws Exception {
        String json = readText(manifestUrl);
        JSONObject root = new JSONObject(json);
        if (root.has("assets") && root.has("tag_name")) {
            return fetchReleaseInfoFromReleaseApi(root);
        }
        return new ReleaseInfo(
                root.getInt("versionCode"),
                root.optString("versionName", ""),
                root.getString("apkUrl"),
                root.optString("sha256", ""),
                root.optString("releasePageUrl", "")
        );
    }

    private ReleaseInfo fetchReleaseInfoFromReleaseApi(JSONObject release) throws Exception {
        JSONArray assets = release.getJSONArray("assets");
        String manifestUrl = findAssetUrl(assets, "latest.json");
        String apkUrl = findAssetUrl(assets, "tmux-android.apk");
        if (manifestUrl.isEmpty()) {
            throw new IllegalStateException("Release has no latest.json");
        }
        ReleaseInfo info = fetchReleaseInfo(manifestUrl);
        if (apkUrl.isEmpty()) {
            return info;
        }
        return new ReleaseInfo(
                info.versionCode,
                info.versionName,
                apkUrl,
                info.sha256,
                release.optString("html_url", info.releasePageUrl)
        );
    }

    private String findAssetUrl(JSONArray assets, String name) {
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null || !name.equals(asset.optString("name"))) {
                continue;
            }
            String url = asset.optString("browser_download_url", "");
            if (!url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    private String readText(String url) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= NETWORK_ATTEMPTS; attempt++) {
            try {
                return readTextOnce(url);
            } catch (Exception error) {
                lastError = error;
                if (attempt < NETWORK_ATTEMPTS) {
                    postMessage("Retrying " + hostLabel(url) + " (" + (attempt + 1) + "/" + NETWORK_ATTEMPTS + ")...");
                    waitBeforeRetry();
                }
            }
        }
        throw lastError == null ? new IllegalStateException("Request failed") : lastError;
    }

    private String readTextOnce(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            byte[] bytes = readAllBytes(input);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private void showUpdateDialog(ReleaseInfo info) {
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Install " + info.versionName + " now?")
                .setNegativeButton("Later", null)
                .setPositiveButton("Install", (dialog, which) -> downloadAndInstall(info))
                .show();
    }

    private void downloadAndInstall(ReleaseInfo info) {
        if (downloadInProgress) {
            postMessage("Update download already running");
            return;
        }
        downloadInProgress = true;
        callback.onChecking(true);
        postMessage("Preparing " + info.versionName + "...");
        executor.execute(() -> {
            try {
                File apk = downloadApk(info);
                if (!info.sha256.isEmpty()) {
                    postMessage("Verifying APK...");
                    String actual = sha256(apk);
                    if (!actual.equalsIgnoreCase(info.sha256)) {
                        throw new IllegalStateException("APK SHA-256 mismatch");
                    }
                }
                activity.runOnUiThread(() -> installApk(apk));
            } catch (Exception error) {
                postMessage("Update download failed: " + error.getMessage());
            } finally {
                downloadInProgress = false;
                activity.runOnUiThread(() -> callback.onChecking(false));
            }
        });
    }

    private File downloadApk(ReleaseInfo info) throws Exception {
        File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create update cache");
        }
        File apk = new File(dir, "tmux-android-" + info.versionCode + ".apk");
        if (isCachedApkValid(apk, info)) {
            postMessage("Using downloaded APK");
            return apk;
        }

        postMessage("Downloading " + info.versionName + "...");
        File partial = new File(dir, "tmux-android-" + info.versionCode + ".apk.tmp");
        if (partial.exists()) {
            partial.delete();
        }
        if (apk.exists()) {
            apk.delete();
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= NETWORK_ATTEMPTS; attempt++) {
            try {
                downloadApkOnce(info.apkUrl, partial);
                if (!partial.renameTo(apk)) {
                    throw new IllegalStateException("Cannot finalize APK download");
                }
                return apk;
            } catch (Exception error) {
                lastError = error;
                partial.delete();
                if (attempt < NETWORK_ATTEMPTS) {
                    postMessage("Retrying APK download (" + (attempt + 1) + "/" + NETWORK_ATTEMPTS + ")...");
                    waitBeforeRetry();
                }
            }
        }
        throw lastError == null ? new IllegalStateException("APK download failed") : lastError;
    }

    private void downloadApkOnce(String apkUrl, File apk) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(60000);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    private boolean isCachedApkValid(File apk, ReleaseInfo info) throws Exception {
        if (!apk.exists() || apk.length() <= 0) {
            return false;
        }
        if (info.sha256.isEmpty()) {
            return true;
        }
        return sha256(apk).equalsIgnoreCase(info.sha256);
    }

    private void installApk(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallApk = apk;
            prefs.edit().putString(PREF_PENDING_INSTALL_APK, apk.getAbsolutePath()).apply();
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(settingsIntent);
            Toast.makeText(activity, "Allow installs, then return to continue", Toast.LENGTH_LONG).show();
            postMessage("Install permission required");
            return;
        }
        pendingInstallApk = null;
        prefs.edit().remove(PREF_PENDING_INSTALL_APK).apply();

        Uri apkUri = UpdateFileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                apk
        );
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            postMessage("Opened Android package installer");
        } catch (ActivityNotFoundException error) {
            postMessage("No package installer found");
        }
    }

    private void openExternalUrl(String url, String label) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            postMessage("Opened " + label);
        } catch (ActivityNotFoundException error) {
            postMessage("No app can open " + label);
        }
    }

    void resumePendingInstall() {
        File apk = pendingInstallApk;
        if (apk == null) {
            String path = prefs.getString(PREF_PENDING_INSTALL_APK, "");
            if (path != null && !path.isEmpty()) {
                apk = new File(path);
                pendingInstallApk = apk;
            }
        }
        if (apk == null) {
            return;
        }
        if (!apk.exists() || apk.length() <= 0) {
            pendingInstallApk = null;
            prefs.edit().remove(PREF_PENDING_INSTALL_APK).apply();
            postMessage("Downloaded APK is no longer available");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        postMessage("Continuing APK install...");
        installApk(apk);
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            builder.append(String.format(Locale.US, "%02x", item));
        }
        return builder.toString();
    }

    private void postMessage(String message) {
        if (message == null) {
            return;
        }
        activity.runOnUiThread(() -> callback.onMessage(message));
    }

    private void waitBeforeRetry() throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MS);
    }
}
