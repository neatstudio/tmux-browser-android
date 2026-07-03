package com.neatstudio.tmuxandroid;

final class ReleaseInfo {
    final int versionCode;
    final String versionName;
    final String apkUrl;
    final String sha256;
    final String releasePageUrl;

    ReleaseInfo(int versionCode, String versionName, String apkUrl, String sha256, String releasePageUrl) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.releasePageUrl = releasePageUrl;
    }
}

