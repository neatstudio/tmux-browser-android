import java.util.Properties

plugins {
    id("com.android.application")
}

val repoSlug = providers.gradleProperty("repoSlug")
    .orElse("neatstudio/tmux-android")
val defaultServerUrl = providers.gradleProperty("defaultServerUrl")
    .orElse("http://100.89.0.116:3000")
val defaultUpdateUrl = providers.gradleProperty("defaultUpdateUrl")
    .orElse("https://github.com/${repoSlug.get()}/releases/latest/download/latest.json")
val defaultGiteaUpdateUrl = providers.gradleProperty("defaultGiteaUpdateUrl")
    .orElse("https://gitea.neatcn.com/tmux/tmux-browser-android/releases/latest/download/latest.json")

val signingProps = Properties()
val signingFile = rootProject.file("signing.properties")
if (signingFile.exists()) {
    signingFile.inputStream().use(signingProps::load)
}

android {
    namespace = "com.neatstudio.tmuxandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neatstudio.tmuxandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("0.1.0").get()

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${defaultServerUrl.get()}\"")
        buildConfigField("String", "DEFAULT_UPDATE_URL", "\"${defaultUpdateUrl.get()}\"")
        buildConfigField("String", "DEFAULT_GITEA_UPDATE_URL", "\"${defaultGiteaUpdateUrl.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingProps.getProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
}
