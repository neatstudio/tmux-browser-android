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
    interface Callback {
        void onChecking(boolean checking);
        void onMessage(String message);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean checkInProgress;
    private boolean downloadInProgress;

    UpdateManager(Activity activity, SharedPreferences prefs, Callback callback) {
        this.activity = activity;
        this.prefs = prefs;
        this.callback = callback;
    }

    void check(boolean userInitiated) {
        if (checkInProgress) {
            postMessage("Update check already running");
            return;
        }
        String manifestUrl = getUpdateManifestUrl();
        checkInProgress = true;
        callback.onChecking(true);
        postMessage("Checking update from " + hostLabel(manifestUrl) + "...");
        executor.execute(() -> {
            try {
                ReleaseInfo info = fetchReleaseInfo(manifestUrl);
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    postMessage("Already up to date: " + BuildConfig.VERSION_NAME);
                    return;
                }
                postMessage("Update found: " + info.versionName);
                activity.runOnUiThread(() -> showUpdateDialog(info));
            } catch (Exception error) {
                postMessage(userInitiated ? "Update check failed: " + error.getMessage() : null);
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
        HttpURLConnection connection = (HttpURLConnection) new URL(info.apkUrl).openConnection();
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
        return apk;
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
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(settingsIntent);
            Toast.makeText(activity, "Allow installs, then run update again", Toast.LENGTH_LONG).show();
            postMessage("Install permission required");
            return;
        }

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
}
