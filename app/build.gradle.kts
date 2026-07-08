import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.toBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val openAiApiKeyFromLocal = localProperties.getProperty("OPENAI_API_KEY").orEmpty()
val openAiApiKeyFromEnv = providers.environmentVariable("OPENAI_API_KEY").orNull.orEmpty()
val openAiApiKey = openAiApiKeyFromLocal.ifBlank { openAiApiKeyFromEnv }

fun propertyOrEnv(name: String): String {
    return localProperties.getProperty(name).orEmpty()
        .ifBlank { providers.environmentVariable(name).orNull.orEmpty() }
        .ifBlank { providers.gradleProperty(name).orNull.orEmpty() }
        .ifBlank { windowsPersistentEnvironmentVariable(name) }
}

fun windowsPersistentEnvironmentVariable(name: String): String {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return ""
    }

    return runCatching {
        val script = """
            ${'$'}value = [Environment]::GetEnvironmentVariable('$name', 'User')
            if ([string]::IsNullOrWhiteSpace(${'$'}value)) {
                ${'$'}value = [Environment]::GetEnvironmentVariable('$name', 'Machine')
            }
            [Console]::Out.Write(${'$'}value)
        """.trimIndent()
        providers.exec {
            commandLine("powershell", "-NoProfile", "-Command", script)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
}

val jiandaoYunApiKey = propertyOrEnv("JIANDAOYUN_API_KEY")
val jiandaoYunAppId = propertyOrEnv("JIANDAOYUN_APP_ID")
val jiandaoYunEntryId = propertyOrEnv("JIANDAOYUN_ENTRY_ID")
val jiandaoYunBaseUrl = propertyOrEnv("JIANDAOYUN_BASE_URL")
    .ifBlank { "https://api.jiandaoyun.com/api" }

android {
    namespace = "com.example.deepchatdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.deepchatdemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENAI_API_KEY", openAiApiKey.toBuildConfigString())
        buildConfigField("String", "OPENAI_BASE_URL", "https://trancloud.net".toBuildConfigString())
        buildConfigField("String", "OPENAI_MODEL", "gpt-5.5".toBuildConfigString())
        buildConfigField("String", "OPENAI_REASONING_EFFORT", "xhigh".toBuildConfigString())
        buildConfigField("String", "JIANDAOYUN_API_KEY", jiandaoYunApiKey.toBuildConfigString())
        buildConfigField("String", "JIANDAOYUN_APP_ID", jiandaoYunAppId.toBuildConfigString())
        buildConfigField("String", "JIANDAOYUN_ENTRY_ID", jiandaoYunEntryId.toBuildConfigString())
        buildConfigField("String", "JIANDAOYUN_BASE_URL", jiandaoYunBaseUrl.toBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".next"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
}
