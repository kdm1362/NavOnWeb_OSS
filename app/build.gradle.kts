import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
import java.net.URI
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64

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
    "^https://github\\.com/kdm1362/NavOnWeb_OSS/tree/(?:" +
        "v[0-9][A-Za-z0-9._-]{0,119}-source|[0-9a-fA-F]{40})$",
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
val reviewPromoApiUrl = projectSetting("reviewPromoApiUrl")
val reviewPromoEs256KeyId = projectSetting("reviewPromoEs256KeyId")
val reviewPromoEs256PublicKeyDerBase64 = projectSetting("reviewPromoEs256PublicKeyDerBase64")
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
fun validReviewPromoApiUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        !uri.rawPath.isNullOrBlank() &&
        uri.rawPath != "/"
}.getOrDefault(false)
require(reviewPromoApiUrl.isEmpty() || validReviewPromoApiUrl(reviewPromoApiUrl)) {
    "reviewPromoApiUrl must be an HTTPS endpoint without credentials, query, or fragment"
}
require(
    reviewPromoEs256KeyId.isEmpty() ||
        reviewPromoEs256KeyId.matches(Regex("[A-Za-z0-9._-]{1,64}")),
) {
    "reviewPromoEs256KeyId must be a bounded public key identifier"
}
require(
    reviewPromoEs256PublicKeyDerBase64.isEmpty() ||
        reviewPromoEs256PublicKeyDerBase64.matches(Regex("[A-Za-z0-9+/]{80,512}={0,2}")),
) {
    "reviewPromoEs256PublicKeyDerBase64 must be a base64 X.509 P-256 public key"
}
val reviewPromoConfigComplete =
    reviewPromoApiUrl.isNotEmpty() &&
        reviewPromoEs256KeyId.isNotEmpty() &&
        reviewPromoEs256PublicKeyDerBase64.isNotEmpty()
require(
    reviewPromoConfigComplete ||
        (
            reviewPromoApiUrl.isEmpty() &&
                reviewPromoEs256KeyId.isEmpty() &&
                reviewPromoEs256PublicKeyDerBase64.isEmpty()
            ),
) {
    "Review promotion verification must configure API URL and ES256 key ID/public key together"
}

// AASDK identity PEM files are optional external release-build inputs. Their contents are never
// stored in this repository. When supplied, Gradle embeds them into the generated release
// BuildConfig, so generated sources and build outputs must remain untracked.
val bundledAasdkCertificatePemPath = providers
    .gradleProperty("bundledAasdkCertificatePemPath")
    .orNull
    ?.trim()
    .orEmpty()
val bundledAasdkPrivateKeyPemPath = providers
    .gradleProperty("bundledAasdkPrivateKeyPemPath")
    .orNull
    ?.trim()
    .orEmpty()
val bundledAasdkCredentialConfigured =
    bundledAasdkCertificatePemPath.isNotEmpty() || bundledAasdkPrivateKeyPemPath.isNotEmpty()
require(
    !bundledAasdkCredentialConfigured ||
        (bundledAasdkCertificatePemPath.isNotEmpty() && bundledAasdkPrivateKeyPemPath.isNotEmpty()),
) {
    "Bundled AASDK identity requires both certificate and private-key PEM paths"
}
fun readExternalPem(
    configuredPath: String,
    beginMarker: String,
    endMarker: String,
    label: String,
): String {
    if (configuredPath.isEmpty()) return ""
    val source = file(configuredPath).canonicalFile
    require(source.isFile && source.length() in 1..65536) {
        "$label must be a small regular PEM file"
    }
    val projectPath = rootProject.projectDir.canonicalFile.path.trimEnd('\\', '/')
    require(
        !source.path.equals(projectPath, ignoreCase = true) &&
            !source.path.startsWith("$projectPath${File.separator}", ignoreCase = true),
    ) {
        "$label must remain outside the public source tree"
    }
    val bytes = source.readBytes()
    return try {
        val text = bytes.toString(StandardCharsets.US_ASCII)
        require(text.contains(beginMarker) && text.contains(endMarker)) {
            "$label is not the expected PEM type"
        }
        text
    } finally {
        bytes.fill(0)
    }
}

val bundledAasdkCertificatePem = readExternalPem(
    bundledAasdkCertificatePemPath,
    "-----BEGIN CERTIFICATE-----",
    "-----END CERTIFICATE-----",
    "Bundled AASDK certificate",
)
val bundledAasdkPrivateKeyPem = readExternalPem(
    bundledAasdkPrivateKeyPemPath,
    "-----BEGIN PRIVATE KEY-----",
    "-----END PRIVATE KEY-----",
    "Bundled AASDK private key",
)

fun pemDerSha256(pem: String, beginMarker: String, endMarker: String): String {
    if (pem.isEmpty()) return ""
    val bodyStart = pem.indexOf(beginMarker).takeIf { it >= 0 }?.plus(beginMarker.length)
        ?: throw IllegalArgumentException("Missing PEM begin marker")
    val bodyEnd = pem.indexOf(endMarker, bodyStart).takeIf { it >= bodyStart }
        ?: throw IllegalArgumentException("Missing PEM end marker")
    val compactBody = buildString(bodyEnd - bodyStart) {
        pem.substring(bodyStart, bodyEnd).forEach { character ->
            if (!character.isWhitespace()) append(character)
        }
    }
    val der = Base64.getDecoder().decode(compactBody)
    return try {
        MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    } finally {
        der.fill(0)
    }
}

val generatedLegalAssets = layout.buildDirectory.dir("generated/legal-assets")
val prepareLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) {
        rename { "GPL-3.0.txt" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("third_party/licenses")) {
        into("third_party/licenses")
    }
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
val aasdkIdentityLeafSha256 = projectSetting("aasdkIdentityLeafSha256")
val aasdkIdentityAnchorSha256 = projectSetting("aasdkIdentityAnchorSha256")
val aasdkIdentityPinsConfigured =
    aasdkIdentityLeafSha256.isNotEmpty() || aasdkIdentityAnchorSha256.isNotEmpty()
val aasdkIdentityPinsComplete =
    aasdkIdentityLeafSha256.matches(sha256Regex) &&
        aasdkIdentityAnchorSha256.matches(sha256Regex)
require(!aasdkIdentityPinsConfigured || aasdkIdentityPinsComplete) {
    "AASDK identity pins must provide both leaf and anchor SHA-256 hashes"
}
require(!bundledAasdkCredentialConfigured || aasdkIdentityPinsComplete) {
    "Bundled AASDK PEM inputs require leaf and anchor SHA-256 properties"
}
if (bundledAasdkCredentialConfigured) {
    require(
        pemDerSha256(
            bundledAasdkCertificatePem,
            "-----BEGIN CERTIFICATE-----",
            "-----END CERTIFICATE-----",
        ).equals(aasdkIdentityLeafSha256, ignoreCase = true),
    ) {
        "Bundled AASDK certificate does not match aasdkIdentityLeafSha256"
    }
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

android {
    namespace = "com.pebble.tecomheadunit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eigenkodex.navonweb"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.1.13-p0"

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
            // Keep debug and release installations independent on the same device.
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            buildConfigField("boolean", "NATIVE_OPENAUTO_ENABLED", enableNativeOpenAuto.toString())
            buildConfigField("boolean", "ALLOW_DEV_HEAD_UNIT_CREDENTIALS", "true")
            buildConfigField("String", "AASDK_IDENTITY_LEAF_SHA256", "\"\"")
            buildConfigField("String", "AASDK_IDENTITY_ANCHOR_SHA256", "\"\"")
            buildConfigField("String", "BUNDLED_AASDK_CERTIFICATE_PEM", "\"\"")
            buildConfigField("String", "BUNDLED_AASDK_PRIVATE_KEY_PEM", "\"\"")
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
                "String",
                "REVIEW_PROMO_API_URL",
                reviewPromoApiUrl.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "REVIEW_PROMO_ES256_KEY_ID",
                reviewPromoEs256KeyId.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "REVIEW_PROMO_ES256_PUBLIC_KEY_DER_BASE64",
                reviewPromoEs256PublicKeyDerBase64.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "boolean",
                "ENABLE_PREMIUM_PROJECTION_BENCH",
                enablePremiumProjectionBench.toString(),
            )
        }
        release {
            // Package native symbol tables in the AAB so Google Play can symbolicate native
            // crashes. The bundled WebRTC/AndroidX native dependencies do not publish DWARF
            // source data, so FULL would only increase output size without restoring file/line
            // information for those libraries.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (playUploadSigningConfigured) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
            buildConfigField("boolean", "NATIVE_OPENAUTO_ENABLED", "false")
            buildConfigField("boolean", "ALLOW_DEV_HEAD_UNIT_CREDENTIALS", "false")
            buildConfigField(
                "String",
                "AASDK_IDENTITY_LEAF_SHA256",
                aasdkIdentityLeafSha256.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "AASDK_IDENTITY_ANCHOR_SHA256",
                aasdkIdentityAnchorSha256.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "BUNDLED_AASDK_CERTIFICATE_PEM",
                bundledAasdkCertificatePem.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "BUNDLED_AASDK_PRIVATE_KEY_PEM",
                bundledAasdkPrivateKeyPem.asBuildConfigStringLiteral(),
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
            buildConfigField(
                "String",
                "REVIEW_PROMO_API_URL",
                reviewPromoApiUrl.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "REVIEW_PROMO_ES256_KEY_ID",
                reviewPromoEs256KeyId.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "REVIEW_PROMO_ES256_PUBLIC_KEY_DER_BASE64",
                reviewPromoEs256PublicKeyDerBase64.asBuildConfigStringLiteral(),
            )
            // Release never honors the local bench override. Premium access comes from a verified
            // purchase or a remotely signed entitlement.
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
    description = "Checks that release builds reference an immutable public source revision."
    inputs.property("sourceCodeUrl", sourceCodeUrl)

    doLast {
        require(immutablePublicSourceUrl.matches(sourceCodeUrl)) {
            "Release builds require -PsourceCodeUrl=" +
                "$publicSourceRepositoryUrl/tree/v0.1.13-p0-source " +
                "(or the full 40-character source commit)."
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
    // Android's org.json implementation is a platform stub in local JVM tests. This test-only
    // implementation exercises signed entitlement parsing without changing the app runtime.
    testImplementation("org.json:json:20240303")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
