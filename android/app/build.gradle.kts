import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    // Room schema snapshots are checked in so future migrations have a reference.
    arg("room.schemaLocation", "$projectDir/schemas")
}

val stageCoreSources = tasks.register<Copy>("stageCoreSources") {
    from(project.file("../../tools/pdf-probe/src/pdfprobe")) {
        include("Model.java", "QuestionParser.java", "AnswerTableParser.java")
    }
    into(layout.buildDirectory.dir("generated/core-java/pdfprobe"))
}

android {
    namespace = "com.offlinequiz.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinequiz.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 31
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Single source of truth for pure parser rules; desktop-only classes stay out.
            java.srcDir(layout.buildDirectory.dir("generated/core-java"))
        }
        getByName("androidTest") {
            // MigrationTestHelper needs the exported schema history.
            assets.srcDir("$projectDir/schemas")
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

tasks.named("preBuild").configure { dependsOn(stageCoreSources) }
tasks.withType<JavaCompile>().configureEach { dependsOn(stageCoreSources) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Room 2.8.5 is the current stable release; KSP matches Kotlin 2.2.21.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Instrumentation loads serialization-core from the target app first.
    debugRuntimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    // Room's schema serializers need the default interface method introduced in 1.8.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
