import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi") version "2.0.0-alpha02"
}
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val fdroidBuild = providers.gradleProperty("fdroid").map(String::toBoolean).getOrElse(false)
val unsignedRelease = providers.gradleProperty("unsignedRelease").map(String::toBoolean).getOrElse(false)
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")
android {
    namespace = "net.yexe.anicome"
    compileSdk = 36
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    signingConfigs {
        getByName("debug") { enableV1Signing = true; enableV2Signing = true }
        if (releaseKeystore != null) create("distribution") {
            storeFile = file(releaseKeystore)
            storeType = "PKCS12"
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            enableV2Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (unsignedRelease || releaseKeystore == null) null else signingConfigs.getByName("distribution")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    defaultConfig { applicationId = "net.yexe.anicome"; minSdk = 26; targetSdk = 36; versionCode = appVersion.getProperty("versionCode").toInt(); versionName = appVersion.getProperty("versionName"); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("targetAbi").orNull?.let { require(it in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")); listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig { buildConfigField("boolean", "FDROID", fdroidBuild.toString()) }
    if (fdroidBuild) sourceSets.getByName("release").manifest.srcFile("src/fdroid/AndroidManifest.xml")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register<Exec>("auditTranslations") {
    workingDir(rootProject.projectDir)
    commandLine("python", rootProject.file("scripts/checks/audit-translations.py").absolutePath)
    inputs.dir("src/main/java")
    inputs.dir("src/main/assets/i18n")
    inputs.file(rootProject.file("scripts/checks/audit-translations.py"))
    outputs.file(rootProject.file("artifacts/translation-audit.json"))
}
tasks.named("check") { dependsOn("auditTranslations") }

dependencies {
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.navigation3:navigation3-runtime:1.0.0")
    implementation("androidx.navigation3:navigation3-ui:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("io.insert-koin:koin-androidx-compose:4.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach { forkEvery = 1 }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
