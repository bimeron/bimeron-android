plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release inputs. Each value is read from a Gradle property (-P, ~/.gradle/gradle.properties,
// ORG_GRADLE_PROJECT_<name>) or, failing that, from an environment variable of the same name.
// Secrets and keystores must never be committed; see docs/RELEASE.md.
fun releaseInput(name: String): String? =
    (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

// versionName is MAJOR.MINOR.PATCH (gradle.properties: bimeron.versionName).
// versionCode = MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + BUILD, where BUILD (0..99,
// BIMERON_VERSION_BUILD) is reserved for re-publishing the same versionName. It grows strictly
// with the semantic version, so upgrade-in-place never sees a lower code.
val bimeronVersionName: String = releaseInput("bimeron.versionName") ?: error("bimeron.versionName is not set")
val bimeronVersionCode: Int = run {
    val match = Regex("""(0|[1-9]\d{0,2})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(bimeronVersionName)
        ?: error("bimeron.versionName must be MAJOR.MINOR.PATCH with MAJOR<=999, MINOR/PATCH<=99: $bimeronVersionName")
    val (major, minor, patch) = match.destructured
    val build = releaseInput("BIMERON_VERSION_BUILD")?.let { value ->
        value.toIntOrNull()?.takeIf { it in 0..99 } ?: error("BIMERON_VERSION_BUILD must be 0..99: $value")
    } ?: 0
    major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + build
}

val releaseSigningNames = listOf("BIMERON_KEYSTORE_FILE", "BIMERON_KEYSTORE_PASSWORD", "BIMERON_KEY_ALIAS", "BIMERON_KEY_PASSWORD")
val releaseSigning: Map<String, String> = releaseSigningNames.mapNotNull { name -> releaseInput(name)?.let { name to it } }.toMap()
val requireReleaseSigning: Boolean = when (val value = releaseInput("BIMERON_REQUIRE_RELEASE_SIGNING")?.lowercase()) {
    null, "false", "0" -> false
    "true", "1" -> true
    else -> error("BIMERON_REQUIRE_RELEASE_SIGNING must be true/1 or false/0: $value")
}
if (releaseSigning.isNotEmpty() && releaseSigning.size != releaseSigningNames.size) {
    error("Incomplete release signing: missing ${releaseSigningNames - releaseSigning.keys}")
}
if (requireReleaseSigning && releaseSigning.isEmpty()) {
    error("BIMERON_REQUIRE_RELEASE_SIGNING is set but ${releaseSigningNames.joinToString()} are not set")
}

// Languages packaged in the APK. aapt2 drops every other library translation (Compose/Material ship ~80).
// AGP 8.7 has no androidResources.localeFilters (added in AGP 8.9), so this goes to resourceConfigurations.
// The per-app language list is hand-written in res/xml/locales_config.xml; LocaleConfigTest fails when its
// languages differ from these filters.
val appLocaleFilters = listOf("en", "ru")

android {
    namespace = "org.bimeron.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.bimeron.app"
        minSdk = 26
        targetSdk = 34
        versionCode = bimeronVersionCode
        versionName = bimeronVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += appLocaleFilters
    }
    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getValue("BIMERON_KEYSTORE_FILE")).also {
                    check(it.isFile) { "BIMERON_KEYSTORE_FILE does not exist: $it" }
                }
                storePassword = releaseSigning.getValue("BIMERON_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("BIMERON_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("BIMERON_KEY_PASSWORD")
                // minSdk 26: APK Signature Scheme v2 + v3 (v3 allows future key rotation); v1/JAR is not needed.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without release credentials the release APK is signed with the local debug key so that
            // assembleRelease stays runnable on CI and developer machines. Such an APK must not be
            // published: it cannot upgrade a real release install. BIMERON_REQUIRE_RELEASE_SIGNING=true
            // turns the fallback into a configuration error.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Android 13+ per-app language settings use the hand-written android:localeConfig="@xml/locales_config"
    // (regional tags en-US, ru-RU). AGP must not generate a second one.
    androidResources { generateLocaleConfig = false }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Keep libgojni.so stored uncompressed and 16 KiB page-aligned in the APK (zipalign -P 16).
        jniLibs.useLegacyPackaging = false
    }
    // Robolectric renders Compose semantics for JVM accessibility checks (A11y*Test).
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // LocaleConfigTest compares the effective filters (not the source text) with locales_config.xml.
        unitTests.all { it.systemProperty("bimeron.resourceConfigurations", defaultConfig.resourceConfigurations.sorted().joinToString(",")) }
    }
}

dependencies {
    implementation(files("libs/bimeron-core.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

if (releaseSigning.isEmpty()) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package")) }) {
            logger.warn("WARNING: release build is signed with the DEBUG key (BIMERON_KEYSTORE_* not set). Do not publish it.")
        }
    }
}
