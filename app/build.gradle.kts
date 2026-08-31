plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.verisonder.sonderlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.verisonder.sonderlock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // No test runner is declared and no androidTest source set exists. Everything
        // in the crypto layer is plain JVM code and is tested as such; adding an
        // instrumentation harness before there is anything on screen to instrument
        // would only slow every build down.
    }

    signingConfigs {
        create("release") {
            // Supplied by CI from repository secrets. Absent locally, which is why the
            // release build type falls back to the debug key below rather than failing.
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                // PKCS12 keystores ignore a separate key password: it always equals the
                // store password. Setting them differently fails at packageRelease with
                // "Given final block not properly padded", which reads like a code bug.
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("RELEASE_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // The vector test reads the shipped wordlist off disk rather than a
                // copy, so it has to know where it is standing.
                it.workingDir = project.projectDir
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }

    packaging {
        resources {
            // Bouncy Castle ships signature files that collide when merged.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
