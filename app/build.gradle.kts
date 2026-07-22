import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import java.util.Properties

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val missingValues: ListProperty<String>

    @TaskAction
    fun validateSigningConfiguration() {
        val missing = missingValues.get()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is required. Configure app/release-signing.properties " +
                    "or HIGHTAC_RELEASE_* environment variables. Missing: " +
                    missing.joinToString()
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun String.toBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun localCloudValue(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name).orEmpty().trim().ifBlank { defaultValue }
}

val releaseSigningPropertiesFile = project.file("release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String {
    return releaseSigningProperties.getProperty(propertyName).orEmpty().trim()
        .ifBlank { providers.environmentVariable(environmentName).orNull.orEmpty().trim() }
}

val releaseStoreFileValue = releaseSigningValue(
    propertyName = "storeFile",
    environmentName = "HIGHTAC_RELEASE_STORE_FILE"
)
val releaseStorePassword = releaseSigningValue(
    propertyName = "storePassword",
    environmentName = "HIGHTAC_RELEASE_STORE_PASSWORD"
)
val releaseKeyAlias = releaseSigningValue(
    propertyName = "keyAlias",
    environmentName = "HIGHTAC_RELEASE_KEY_ALIAS"
)
val releaseKeyPassword = releaseSigningValue(
    propertyName = "keyPassword",
    environmentName = "HIGHTAC_RELEASE_KEY_PASSWORD"
)
val releaseStoreFile = releaseStoreFileValue.takeIf(String::isNotBlank)?.let(project::file)
val missingReleaseSigningValues = buildList {
    if (releaseStoreFileValue.isBlank()) add("storeFile / HIGHTAC_RELEASE_STORE_FILE")
    if (releaseStoreFileValue.isNotBlank() && releaseStoreFile?.isFile != true) {
        add("existing release keystore file")
    }
    if (releaseStorePassword.isBlank()) add("storePassword / HIGHTAC_RELEASE_STORE_PASSWORD")
    if (releaseKeyAlias.isBlank()) add("keyAlias / HIGHTAC_RELEASE_KEY_ALIAS")
    if (releaseKeyPassword.isBlank()) add("keyPassword / HIGHTAC_RELEASE_KEY_PASSWORD")
}
val hasReleaseSigning = missingReleaseSigningValues.isEmpty()

android {
    namespace = "com.example.deepchatdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.durovamotor.hightacai"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "OPENAI_BASE_URL",
            localCloudValue("HIGHTAC_OPENAI_BASE_URL").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            localCloudValue("HIGHTAC_OPENAI_API_KEY").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "OPENAI_MODEL",
            localCloudValue("HIGHTAC_OPENAI_MODEL", "gpt-5.5").toBuildConfigString()
        )
        buildConfigField("String", "OPENAI_REASONING_EFFORT", "xhigh".toBuildConfigString())
        buildConfigField(
            "String",
            "JIANDAOYUN_BASE_URL",
            localCloudValue("HIGHTAC_JIANDAOYUN_BASE_URL").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "JIANDAOYUN_API_KEY",
            localCloudValue("HIGHTAC_JIANDAOYUN_API_KEY").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "JIANDAOYUN_APP_ID",
            localCloudValue("HIGHTAC_JIANDAOYUN_APP_ID").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "JIANDAOYUN_ENTRY_ID",
            localCloudValue("HIGHTAC_JIANDAOYUN_ENTRY_ID").toBuildConfigString()
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".next"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = releaseStoreFile
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // Keep the field-test package exactly stable so adb install -r retains its approved token.
        variant.applicationId.set("com.example.deepchatdemo.next")
    }
}

val validateReleaseSigning by tasks.registering(ValidateReleaseSigningTask::class) {
    group = "verification"
    description = "Fails release builds unless a complete HighTac signing configuration is present."
    missingValues.set(missingReleaseSigningValues)
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateReleaseSigning)
    }
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation(libs.coil.compose)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
}
