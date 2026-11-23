import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.doppelisk.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.doppelisk.app"
        minSdk = 27
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    // Release signing lives outside the repository: the keystore path and its
    // passwords are read from ~/.gradle/gradle.properties, so a cloned tree
    // never carries the key. On a machine without them the release APK is left
    // unsigned instead of failing the build.
    val releaseStore = providers.gradleProperty("DOPPELISK_STORE_FILE")
        .orNull
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = providers.gradleProperty("DOPPELISK_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("DOPPELISK_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("DOPPELISK_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // The hook engine is reached reflectively by the framework, and the
            // profile codecs are serialized by name. Shrinking is left off until
            // there is a keep-rule set that is actually tested against a hooked
            // process; a smaller APK is not worth a module that silently stops
            // applying.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            if (releaseStore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        // This project is built on a 5.4 GiB phone. Lint's release analysis is
        // the single largest consumer in the build and gets the daemon killed
        // after Kotlin and dex have already taken their share. Keep the checks
        // available as their own task (`gradle :app:lintRelease`) and run them
        // on a desktop before tagging; `assembleRelease` must stay repeatable
        // on the build machine.
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hook"))
    implementation(project(":daemon"))

    // The framework provides the Xposed API at runtime; the module class has to
    // compile against it but must not bundle it.
    compileOnly("io.github.libxposed:api:101.0.1")

    // The manager talks to the framework through the service protocol, so this
    // one is bundled.
    implementation("io.github.libxposed:service:101.0.0")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phone numbers have to look like the country's numbering plan, not like a
    // random string of digits. libphonenumber supplies the plan (mobile number
    // examples, validation and E.164 formatting) for every region.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.32")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
