plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.kotlin.compose)
    id("com.diffplug.spotless") version "8.1.0"
    alias(libs.plugins.androidx.room)
}

spotless {
    kotlin {
        target("**/*.kt")
        // ktfmt() // Google style, no config
        ktfmt().kotlinlangStyle() // 4-space indents
    }
}

android {
    namespace = "app.zornslemma.dayfile"
    compileSdk {
        version = release(37)
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "app.zornslemma.dayfile"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )

            // No custom keep rules are needed; the reflection-surface audit is in
            // src/main/keepRules/rules.keep, which is where to add one if that changes.

            // The debug key is only so that switching between debug and release builds stays
            // frictionless during development. It is not the key anything is published under.
            //
            // Published builds are signed by hand, in Android Studio via Build -> Generate
            // Signed Bundle/APK, because a keystore password cannot live in a build script.
            // That keystore is a personal one and cannot be rotated: Android does not allow an
            // installed app to be updated by a version signed with a different key, so changing
            // it later means every user reinstalls. (A fork is a separate app with its own key,
            // not a replacement for this one.)
            //
            // So a released binary has no second signer able to vouch for it, and the only thing
            // tying it to the source anyone can read is that rebuilding from the release commit
            // reproduces it. That is what SPEC.md's "must build reproducibly" is for, and why
            // non-determinism in the build is a problem rather than a nuisance.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.reorderable)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
}
