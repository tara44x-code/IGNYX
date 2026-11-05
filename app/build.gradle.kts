import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ignyx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ignyx.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Boot layout, exFAT and the Ventoy installer (pure Kotlin, tested on the JVM)
    implementation(project(":core"))
    // Ventoy ships its images xz-compressed
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Fonts (SIL Open Font License) are fetched from the Google Fonts repository
// on first build, so the repo stays small. They land in res/font/.
// ---------------------------------------------------------------------------
val fontsDir = layout.projectDirectory.dir("src/main/res/font").asFile
val downloadFonts by tasks.registering {
    val fonts = mapOf(
        "chakra_petch_medium.ttf" to "https://github.com/google/fonts/raw/main/ofl/chakrapetch/ChakraPetch-Medium.ttf",
        "chakra_petch_bold.ttf" to "https://github.com/google/fonts/raw/main/ofl/chakrapetch/ChakraPetch-Bold.ttf",
        "cairo_variable.ttf" to "https://github.com/google/fonts/raw/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf",
    )
    val dir = fontsDir
    outputs.dir(dir)
    doLast {
        dir.mkdirs()
        fonts.forEach { (name, url) ->
            val target = File(dir, name)
            if (!target.exists() || target.length() < 10_000) {
                println("Downloading font $name")
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadFonts) }
