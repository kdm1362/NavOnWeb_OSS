import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val enableNativeOpenAuto = providers.gradleProperty("enableNativeOpenAuto")
    .orNull
    .equals("true", ignoreCase = true)
val enablePremiumProjectionBench = providers.gradleProperty("enablePremiumProjectionBench")
    .orNull
    .equals("true", ignoreCase = true)
val playInternalTestBuild = providers.gradleProperty("playInternalTestBuild")
    .orNull
    .equals("true", ignoreCase = true)
val publicSourceRepositoryUrl = "https://github.com/kdm1362/NavOnWeb_OSS"
val sourceCodeUrl = providers.gradleProperty("sourceCodeUrl")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: publicSourceRepositoryUrl
require(sourceCodeUrl.isEmpty() || sourceCodeUrl.matches(Regex("https://[^\\s]{1,500}"))) {
    "sourceCodeUrl must be an HTTPS URL"
}
val immutablePublicSourceUrl = Regex(
    "^https://github\\.com/kdm1362/NavOnWeb_OSS/(?:" +
        "tree/v[0-9][A-Za-z0-9._-]{0,119}-source|commit/[0-9a-fA-F]{40})$",
)

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun projectSetting(name: String): String =
    providers.gradleProperty(name).orNull?.trim()
        ?: localProperties.getProperty(name)?.trim().orEmpty()

val supabaseUrl = projectSetting("supabaseUrl").removeSuffix("/")
val supabasePublishableKey = projectSetting("supabasePublishableKey")
val premiumProductId = projectSetting("premiumProductId")
    .ifBlank { "navonweb_premium" }
require(premiumProductId.matches(Regex("[a-z][a-z0-9._-]{0,149}"))) {
    "premiumProductId must be a valid Google Play one-time product ID"
}
val premiumPurchaseOptionId = projectSetting("premiumPurchaseOptionId")
    .ifBlank { "premium-access" }
require(premiumPurchaseOptionId.matches(Regex("[a-z][a-z0-9._-]{0,149}"))) {
    "premiumPurchaseOptionId must be a valid Google Play one-time purchase option ID"
}
require(supabaseUrl.isEmpty() || supabaseUrl.matches(Regex("https://[a-z0-9-]{1,80}\\.supabase\\.co"))) {
    "supabaseUrl must be an HTTPS Supabase project URL"
}
require(
    supabasePublishableKey.isEmpty() ||
        supabasePublishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]{20,200}")),
) {
    "supabasePublishableKey must be a Supabase publishable key; secret and service-role keys are forbidden"
}

val cloudBrowserPageUrl = projectSetting("cloudBrowserPageUrl").removeSuffix("/")
val cloudSignalingWebSocketUrl = projectSetting("cloudSignalingWebSocketUrl").removeSuffix("/")
fun validCloudEndpoint(value: String, expectedScheme: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals(expectedScheme, ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
}.getOrDefault(false)
require(cloudBrowserPageUrl.isEmpty() || validCloudEndpoint(cloudBrowserPageUrl, "https")) {
    "cloudBrowserPageUrl must be an HTTPS origin without credentials, query, fragment, or path"
}
require(
    cloudSignalingWebSocketUrl.isEmpty() ||
        validCloudEndpoint(cloudSignalingWebSocketUrl, "wss"),
) {
    "cloudSignalingWebSocketUrl must be a WSS origin without credentials, query, fragment, or path"
}
require(cloudBrowserPageUrl.isEmpty() == cloudSignalingWebSocketUrl.isEmpty()) {
    "cloudBrowserPageUrl and cloudSignalingWebSocketUrl must be configured together"
}

val generatedLegalAssets = layout.buildDirectory.dir("generated/legal-assets")
val prepareLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) {
        rename { "GPL-3.0.txt" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(generatedLegalAssets)
}

fun String.asBuildConfigStringLiteral(): String =
    "\"" +
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t") +
        "\""

val sha256Regex = Regex("[0-9A-Fa-f]{64}")
val productionCredentialManifestPath = providers
    .gradleProperty("productionCredentialManifestPath")
    .orNull
    ?.trim()
    .orEmpty()
val productionCredentialManifestSha256 = providers
    .gradleProperty("productionCredentialManifestSha256")
    .orNull
    ?.trim()
    .orEmpty()
val productionAasdkIdentityLeafSha256 = providers
    .gradleProperty("productionAasdkIdentityLeafSha256")
    .orNull
    ?.trim()
    .orEmpty()
val productionAasdkIdentityAnchorSha256 = providers
    .gradleProperty("productionAasdkIdentityAnchorSha256")
    .orNull
    ?.trim()
    .orEmpty()
val productionAasdkIdentityConfigured =
    productionAasdkIdentityLeafSha256.isNotEmpty() ||
        productionAasdkIdentityAnchorSha256.isNotEmpty()
val productionAasdkIdentityFullyConfigured =
    productionAasdkIdentityLeafSha256.matches(sha256Regex) &&
        productionAasdkIdentityAnchorSha256.matches(sha256Regex)
require(!productionAasdkIdentityConfigured || productionAasdkIdentityFullyConfigured) {
    "Production AASDK identity pins must contain leaf and anchor SHA-256 hashes"
}

val playUploadKeystorePath = providers
    .environmentVariable("NAVONWEB_PLAY_UPLOAD_KEYSTORE")
    .orNull
    ?.trim()
    .orEmpty()
val playUploadPassword = providers
    .environmentVariable("NAVONWEB_PLAY_UPLOAD_PASSWORD")
    .orNull
    .orEmpty()
val playUploadKeyAlias = providers
    .environmentVariable("NAVONWEB_PLAY_UPLOAD_KEY_ALIAS")
    .orNull
    ?.trim()
    ?.ifBlank { null }
    ?: "navonweb-upload"
val playUploadSigningConfigured =
    playUploadKeystorePath.isNotEmpty() || playUploadPassword.isNotEmpty()
require(
    !playUploadSigningConfigured ||
        (playUploadKeystorePath.isNotEmpty() && playUploadPassword.isNotEmpty()),
) {
    "Play upload signing requires both NAVONWEB_PLAY_UPLOAD_KEYSTORE and " +
        "NAVONWEB_PLAY_UPLOAD_PASSWORD"
}
val playUploadKeystoreFile = playUploadKeystorePath
    .takeIf { it.isNotEmpty() }
    ?.let(::file)
    ?.canonicalFile
playUploadKeystoreFile?.let { keyStore ->
    require(keyStore.isFile) {
        "Play upload keystore is not a regular file"
    }
    val projectPath = rootProject.projectDir.canonicalFile.path.trimEnd('\\', '/')
    val keyStorePath = keyStore.path
    require(
        !keyStorePath.equals(projectPath, ignoreCase = true) &&
            !keyStorePath.startsWith("$projectPath${File.separator}", ignoreCase = true),
    ) {
        "Play upload keystore must be stored outside the project directory"
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }
}

if (
    playUploadSigningConfigured && !playInternalTestBuild
) {
    require(productionCredentialManifestPath.isNotEmpty()) {
        "Play-signed release builds require the verified production credential manifest snapshot"
    }
    require(productionCredentialManifestSha256.matches(sha256Regex)) {
        "Play-signed release builds require the verified production credential manifest SHA-256"
    }
    val manifestFile = file(productionCredentialManifestPath).canonicalFile
    require(manifestFile.isFile && manifestFile.length() in 1..65536) {
        "The production credential manifest snapshot must be a small regular file"
    }
    val actualManifestSha256 = sha256Hex(manifestFile)
    require(actualManifestSha256.equals(productionCredentialManifestSha256, ignoreCase = true)) {
        "The production credential manifest snapshot SHA-256 does not match the verified digest"
    }
}

android {
    namespace = "com.pebble.tecomheadunit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eigenkodex.navonweb"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.8-p0"

        buildConfigField(
            "String",
            "PREMIUM_PRODUCT_ID",
            premiumProductId.asBuildConfigStringLiteral(),
        )
        buildConfigField(
            "String",
            "PREMIUM_PURCHASE_OPTION_ID",
            premiumPurchaseOptionId.asBuildConfigStringLiteral(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        if (enableNativeOpenAuto) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
                    arguments += listOf("-DOPENAUTO_RUNTIME_LINKED=OFF")
                }
            }

            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    signingConfigs {
        if (playUploadSigningConfigured) {
            create("playUpload") {
                storeFile = requireNotNull(playUploadKeystoreFile)
                storePassword = playUploadPassword
                keyAlias = playUploadKeyAlias
                keyPassword = playUploadPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "NATIVE_OPENAUTO_ENABLED", enableNativeOpenAuto.toString())
            buildConfigField("boolean", "ALLOW_DEV_HEAD_UNIT_CREDENTIALS", "true")
            buildConfigField("String", "AASDK_IDENTITY_LEAF_SHA256", "\"\"")
            buildConfigField("String", "AASDK_IDENTITY_ANCHOR_SHA256", "\"\"")
            buildConfigField("String", "SOURCE_CODE_URL", sourceCodeUrl.asBuildConfigStringLiteral())
            buildConfigField("String", "SUPABASE_URL", supabaseUrl.asBuildConfigStringLiteral())
            buildConfigField(
                "String",
                "SUPABASE_PUBLISHABLE_KEY",
                supabasePublishableKey.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "CLOUD_BROWSER_PAGE_URL",
                cloudBrowserPageUrl.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "CLOUD_SIGNALING_WEBSOCKET_URL",
                cloudSignalingWebSocketUrl.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "boolean",
                "ENABLE_PREMIUM_PROJECTION_BENCH",
                enablePremiumProjectionBench.toString(),
            )
        }
        release {
            if (playUploadSigningConfigured) {
                if (playInternalTestBuild) {
                    require(
                        !productionAasdkIdentityConfigured &&
                            productionCredentialManifestPath.isEmpty() &&
                            productionCredentialManifestSha256.isEmpty(),
                    ) {
                        "Internal Play test builds must not contain production AASDK identity inputs"
                    }
                } else {
                    require(productionAasdkIdentityFullyConfigured) {
                        "Play-signed release builds require the deployment credential gate and all " +
                            "production AASDK identity pins"
                    }
                }
                signingConfig = signingConfigs.getByName("playUpload")
            }
            buildConfigField("boolean", "NATIVE_OPENAUTO_ENABLED", "false")
            buildConfigField("boolean", "ALLOW_DEV_HEAD_UNIT_CREDENTIALS", "false")
            buildConfigField(
                "String",
                "AASDK_IDENTITY_LEAF_SHA256",
                productionAasdkIdentityLeafSha256.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "AASDK_IDENTITY_ANCHOR_SHA256",
                productionAasdkIdentityAnchorSha256.asBuildConfigStringLiteral(),
            )
            buildConfigField("String", "SOURCE_CODE_URL", sourceCodeUrl.asBuildConfigStringLiteral())
            buildConfigField("String", "SUPABASE_URL", supabaseUrl.asBuildConfigStringLiteral())
            buildConfigField(
                "String",
                "SUPABASE_PUBLISHABLE_KEY",
                supabasePublishableKey.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "CLOUD_BROWSER_PAGE_URL",
                cloudBrowserPageUrl.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "CLOUD_SIGNALING_WEBSOCKET_URL",
                cloudSignalingWebSocketUrl.asBuildConfigStringLiteral(),
            )
            // Release never honors the debug bench override. Premium comes from verified Play
            // ownership only.
            buildConfigField("boolean", "ENABLE_PREMIUM_PROJECTION_BENCH", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    if (enableNativeOpenAuto) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.getByName("main").assets.srcDir(generatedLegalAssets)

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val verifyReleaseSourceCodeUrl by tasks.registering {
    group = "verification"
    description = "Requires release builds to link to an immutable public source tag or commit."
    inputs.property("sourceCodeUrl", sourceCodeUrl)

    doLast {
        require(immutablePublicSourceUrl.matches(sourceCodeUrl)) {
            "Release builds require -PsourceCodeUrl=$publicSourceRepositoryUrl/tree/<immutable-tag> " +
                "or $publicSourceRepositoryUrl/commit/<full-commit>."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSourceCodeUrl)
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.play.billing)
    // Billing's Play Services graph still exposes Fragment 1.1.0. Activity Result APIs require
    // Fragment 1.3.0+, so pin the current stable AndroidX Fragment release explicitly.
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
