import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("androidx.baselineprofile") }
val nextTvSigningFile = rootProject.file(".signing/signing.properties")
val nextTvSigning = Properties().apply { if(nextTvSigningFile.exists())nextTvSigningFile.inputStream().use { load(it) } }
android {
 signingConfigs {
  create("Next TV") {
   if(nextTvSigningFile.exists()) {
    storeFile = rootProject.file(nextTvSigning.getProperty("storeFile"))
    storePassword = nextTvSigning.getProperty("storePassword")
    keyAlias = nextTvSigning.getProperty("keyAlias")
    keyPassword = nextTvSigning.getProperty("keyPassword")
    storeType = "PKCS12"
   }
  }
 }
 namespace = "com.nexttv"
 compileSdk = 35
 defaultConfig { testInstrumentationRunner = providers.gradleProperty("perfRunner").orNull ?: "com.nexttv.AuditFixInstrumentation"; applicationId = "com.nexttv"; minSdk = 23; targetSdk = 35; versionCode = 100; versionName = "1.4.0" }
 compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true; buildConfig = true }
 buildTypes {
  getByName("debug") { if(nextTvSigningFile.exists())signingConfig=signingConfigs.getByName("Next TV") }
  getByName("release") {
   isMinifyEnabled = true
   isShrinkResources = true
   if(nextTvSigningFile.exists())signingConfig = signingConfigs.getByName("Next TV")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }
 }
}
baselineProfile { automaticGenerationDuringBuild = false }
dependencies {
 add("baselineProfile",project(":baselineprofile"))
 implementation("androidx.tv:tv-material:1.1.0")
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
 implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
 implementation("com.google.zxing:core:3.5.3")
 testImplementation("junit:junit:4.13.2")
 implementation(platform("androidx.compose:compose-bom:2026.02.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.media3:media3-exoplayer:1.9.4")
 implementation("androidx.media3:media3-exoplayer-hls:1.9.4")
 implementation("androidx.media3:media3-ui-compose:1.9.4")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
