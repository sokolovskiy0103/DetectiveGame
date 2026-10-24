import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sokolovskiy.party"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.activity:activity-ktx:1.9.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val ktor = "3.1.3"
    api("io.ktor:ktor-server-core:$ktor")
    api("io.ktor:ktor-server-cio:$ktor")
    api("io.ktor:ktor-server-websockets:$ktor")
    api("io.ktor:ktor-client-core:$ktor")
    api("io.ktor:ktor-client-cio:$ktor")
    api("io.ktor:ktor-client-websockets:$ktor")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
