plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

// Reuse the platform-independent code as-is; Android classes are stubbed in src/main/kotlin/android.
sourceSets.main {
    kotlin.srcDir("../core/src/main/java")
    kotlin.srcDir("../casekit/src/main/java")
    kotlin.srcDir("../hub/src/main/java")
    kotlin.exclude(
        "com/sokolovskiy/party/MainActivity.kt",
        "com/sokolovskiy/party/PartyService.kt",
        "com/sokolovskiy/party/update/**",
        "com/sokolovskiy/detective/DetectiveApp.kt",
        "com/sokolovskiy/detective/SemanticManager.kt",
    )
}

application { mainClass.set("com.sokolovskiy.party.dev.DevMainKt") }

dependencies {
    val ktor = "3.1.3"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.json:json:20240303")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

tasks.named("run") { dependsOn(":hub:syncCases") }

dependencies { testImplementation("junit:junit:4.13.2") }
tasks.withType<Test> { workingDir = rootDir }

// the case from the console, as the players see it (for the blind playtester): see PlayCli.kt
tasks.register<JavaExec>("play") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sokolovskiy.party.dev.PlayCliKt")
    workingDir = rootDir
}
