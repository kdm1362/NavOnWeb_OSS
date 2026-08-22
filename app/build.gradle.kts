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
val playInternalTestBuild = providers.gradleProperty("playInternalTestBuild")
    .orNull
    .equals("true", ignoreCase = true)
val playReleaseCandidateBuild = providers.gradleProperty("playReleaseCandidateBuild")
    .orNull
    .equals("true", ignoreCase = true)
val playOssSyncPendingWarning = providers.gradleProperty("playOssSyncPendingWarning")
    .orNull
    .equals("true", ignoreCase = true)
require(!(playInternalTestBuild && playReleaseCandidateBuild)) {
    "playInternalTestBuild and playReleaseCandidateBuild are mutually exclusive"
}
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
val onPremTestMode = projectSetting("onPremTestMode").equals("true", ignoreCase = true)
val onPremTestOrigin = projectSetting("onPremTestOrigin").removeSuffix("/")
fun validOnPremTestOrigin(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.port == 8443 &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
}.getOrDefault(false)
require(!onPremTestMode || validOnPremTestOrigin(onPremTestOrigin)) {
    "onPremTestOrigin must be an HTTPS origin on port 8443"
}
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
require(
    supabaseUrl.isEmpty() ||
        supabaseUrl.matches(Regex("https://[a-z0-9-]{1,80}\\.supabase\\.co")) ||
        (onPremTestMode && supabaseUrl == onPremTestOrigin),
) {
    "supabaseUrl must be an HTTPS Supabase project URL"
}
require(
    supabasePublishableKey.isEmpty() ||
        supabasePublishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]{20,200}")),
) {
    "supabasePublishableKey must be a client-safe publishable key"
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
if (onPremTestMode) {
    val expectedWssOrigin = onPremTestOrigin.replaceFirst("https://", "wss://")
    require(cloudBrowserPageUrl == onPremTestOrigin) {
        "on-prem browser origin must match onPremTestOrigin"
    }
    require(cloudSignalingWebSocketUrl == expectedWssOrigin) {
        "on-prem signaling origin must match onPremTestOrigin"
    }
    require(reviewPromoApiUrl == "$onPremTestOrigin/api/review-promo/verify") {
        "on-prem review promo endpoint must use the fixed test origin"
    }
}
gradle.taskGraph.whenReady {
    if (onPremTestMode) {
        require(allTasks.none { task -> task.name.contains("release", ignoreCase = true) }) {
            "onPremTestMode is restricted to the isolated QA/debug build"
        }
    }
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

// Original Android Auto PEM files remain external build inputs and are not copied into staged
// source. Release BuildConfig generation intentionally writes the exportable pair into generated
// source and DEX; exact staging cleanup is mandatory, and credential bytes must never be logged.
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

val PUBLIC_DEVELOPMENT_AASDK_CERTIFICATE_DER_SHA256 =
    "1C0E0EF9E672DD1A63AB4D61AFAA8996A57CA7AA966D1922B97D8F9385EA3C35"
val PUBLIC_DEVELOPMENT_AASDK_PRIVATE_KEY_PKCS8_DER_SHA256 =
    "08E86E4DE51208BFA0E8164D99C50DAF7F5BFCFA3FE2067DC912CF83F2E99A25"

if (bundledAasdkCredentialConfigured && playInternalTestBuild) {
    require(
        pemDerSha256(
            bundledAasdkCertificatePem,
            "-----BEGIN CERTIFICATE-----",
            "-----END CERTIFICATE-----",
        ) == PUBLIC_DEVELOPMENT_AASDK_CERTIFICATE_DER_SHA256,
    ) {
        "Internal-test AASDK certificate does not match the pinned public development identity"
    }
    require(
        pemDerSha256(
            bundledAasdkPrivateKeyPem,
            "-----BEGIN PRIVATE KEY-----",
            "-----END PRIVATE KEY-----",
        ) == PUBLIC_DEVELOPMENT_AASDK_PRIVATE_KEY_PKCS8_DER_SHA256,
    ) {
        "Internal-test AASDK key does not match the pinned public development identity"
    }
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
require(!playUploadSigningConfigured || reviewPromoConfigComplete) {
    "Play-signed builds must retain remote review promotion verification configuration"
}
val playProductionDeploymentBuild =
    playUploadSigningConfigured && !playInternalTestBuild && !playReleaseCandidateBuild
require(
    !bundledAasdkCredentialConfigured ||
        playInternalTestBuild ||
        playProductionDeploymentBuild,
) {
    "Bundled AASDK identity inputs require an internal-test or production deployment build"
}
require(!playOssSyncPendingWarning || playProductionDeploymentBuild) {
    "playOssSyncPendingWarning is reserved for an explicitly verified production deployment build"
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

if (playProductionDeploymentBuild) {
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
    require(bundledAasdkCredentialConfigured) {
        "Production deployment builds require the verified bundled AASDK certificate and private key"
    }
    require(
        pemDerSha256(
            bundledAasdkCertificatePem,
            "-----BEGIN CERTIFICATE-----",
            "-----END CERTIFICATE-----",
        ).equals(productionAasdkIdentityLeafSha256, ignoreCase = true),
    ) {
        "Bundled production AASDK certificate does not match the verified manifest leaf identity"
    }
}

android {
    namespace = "com.eigenkodex.navonweb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eigenkodex.navonweb"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.1.20-p0"

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
            // Keep the locally installed QA build isolated from the Play-signed package so
            // release-candidate device tests never require uninstalling or overwriting user data.
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
            // The public source tree never ships a way to grant premium without a verified
            // entitlement, so this stays off in debug as well as release.
            buildConfigField("boolean", "ENABLE_PREMIUM_PROJECTION_BENCH", "false")
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
                if (playInternalTestBuild) {
                    require(
                        !productionAasdkIdentityConfigured &&
                            productionCredentialManifestPath.isEmpty() &&
                            productionCredentialManifestSha256.isEmpty() &&
                            bundledAasdkCredentialConfigured,
                    ) {
                        "Internal Play test builds require only the pinned public-development AASDK identity"
                    }
                } else {
                    if (playReleaseCandidateBuild) {
                        require(
                            !productionAasdkIdentityConfigured &&
                                productionCredentialManifestPath.isEmpty() &&
                                productionCredentialManifestSha256.isEmpty() &&
                                !bundledAasdkCredentialConfigured,
                        ) {
                            "Play release-candidate builds report missing production AASDK identity " +
                                "and must not package substitute identity material"
                        }
                    } else {
                        require(
                            productionAasdkIdentityFullyConfigured &&
                                bundledAasdkCredentialConfigured,
                        ) {
                            "Play-signed release builds require the deployment credential gate and all " +
                                "production AASDK identity inputs"
                        }
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
            // Release never honors the debug bench override. Premium comes from verified Play
            // ownership or a remote-verified, signed, process-only review promotion grant.
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
        // Kotlin tooling metadata is only consumed by build tooling, never by the app at
        // runtime; drop it from the APK/AAB. DebugProbesKt.bin is deliberately kept: the
        // Android Studio coroutine debugger loads it from the debug APK's resources.
        resources.excludes += "kotlin-tooling-metadata.json"
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
    inputs.property("playInternalTestBuild", playInternalTestBuild)
    inputs.property("playReleaseCandidateBuild", playReleaseCandidateBuild)
    inputs.property("playOssSyncPendingWarning", playOssSyncPendingWarning)

    doLast {
        if (!immutablePublicSourceUrl.matches(sourceCodeUrl)) {
            val message =
                "Release builds require -PsourceCodeUrl=$publicSourceRepositoryUrl/tree/<immutable-tag> " +
                    "or $publicSourceRepositoryUrl/commit/<full-commit>."
            if (playInternalTestBuild || playReleaseCandidateBuild || playOssSyncPendingWarning) {
                logger.warn("$message Continuing the explicitly warning-only Play release flow.")
            } else {
                throw GradleException(message)
            }
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
    // Compiles the packaged baseline profile (src/main/baseline-prof.txt) ahead of time at
    // install, so first-launch Compose/startup code runs AOT-compiled instead of interpreted.
    // AndroidX libraries' AAR-shipped profiles are merged and compiled through the same path.
    implementation(libs.androidx.profileinstaller)

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
