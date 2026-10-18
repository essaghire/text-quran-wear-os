plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val uploadSigningEnvironment = listOf(
    "TEXTQURAN_UPLOAD_STORE_FILE",
    "TEXTQURAN_UPLOAD_STORE_PASSWORD",
    "TEXTQURAN_UPLOAD_KEY_ALIAS",
    "TEXTQURAN_UPLOAD_KEY_PASSWORD"
)
val uploadSigningValues = uploadSigningEnvironment.map { name ->
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
}
val hasUploadSigning = uploadSigningValues.all { it != null }
val releaseBundleRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "bundleRelease"
}
if (releaseBundleRequested && !hasUploadSigning) {
    error(
        "A Play upload key is required for bundleRelease. Set " +
            uploadSigningEnvironment.joinToString(", ") + " in the build environment."
    )
}

android {
    namespace = "com.essaghir.textquran"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.essaghir.textquran"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("release") {
            if (hasUploadSigning) {
                storeFile = file(uploadSigningValues[0]!!)
                storePassword = uploadSigningValues[1]
                keyAlias = uploadSigningValues[2]
                keyPassword = uploadSigningValues[3]
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
