plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lito.planify.dashboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lito.planify.dashboard"
        minSdk = 29 // XORO CPF 10PF: Android 10
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // El RTC del marco no conserva la hora: cualquier fecha anterior a la compilación es falsa.
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["cleartext"] = "false"
            signingConfig = signingConfigs.getByName("debug") // Instalación local por ADB, como la app
        }
        debug {
            // El calendario de la demo (scripts/demo.sh) se sirve por HTTP desde el PC
            manifestPlaceholders["cleartext"] = "true"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.airbnb.android:lottie:6.7.1")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    // iCal de Google Calendar: lectura y repeticiones (RRULE). Jackson solo hace falta para jCal, que no se usa.
    implementation("net.sf.biweekly:biweekly:0.6.8") { exclude(group = "com.fasterxml.jackson.core") }

    testImplementation("junit:junit:4.13.2")
}
