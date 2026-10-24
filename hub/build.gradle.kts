import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

/**
 * The cases bundled into the app: id -> folder with `content/case.json`, `documents/html/`, `assets/photos/` and
 * optional `avatars/`, plus the theme. The stairs case still lives in the repository root (its authoring project).
 */
class CaseSrc(val id: String, val dir: File, val avatars: File, val accent: String, val onAccent: String)
val cases = listOf(
    CaseSrc("stairs", rootDir, rootDir.resolve("app/static/avatars"), "#c9a34e", "#1a1508"),
)

val genDir = layout.buildDirectory.dir("generated/caseAssets")

val syncCases by tasks.registering {
    val out = genDir.get().asFile
    inputs.files(cases.map { it.dir.resolve("content/case.json") })
    outputs.dir(out)
    doLast {
        out.deleteRecursively()
        for (c in cases) {
            val data = out.resolve("data/cases/${c.id}")
            val web = out.resolve("web/games/${c.id}")
            copy {
                from(c.dir.resolve("content/case.json")); into(data)
            }
            copy { from(c.dir.resolve("documents/html")); into(data.resolve("doc")) }
            copy { from(c.dir.resolve("assets/photos")) { include("*.jpg") }; into(data.resolve("photo")) }
            if (c.avatars.isDirectory) copy { from(c.avatars); into(web.resolve("avatars")) }
            web.mkdirs()
            web.resolve("game.js").writeText("PG.caseKit('${c.id}');\n")
            web.resolve("game.css").writeText("[data-theme=${c.id}] { --accent: ${c.accent}; --on-accent: ${c.onAccent}; }\n")
        }
    }
}

android {
    namespace = "com.sokolovskiy.detective"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sokolovskiy.detective"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Fixed key committed to the repo so every new APK installs over the old one.
        create("detective") {
            storeFile = file("detective.keystore")
            storePassword = "detective"
            keyAlias = "detective"
            keyPassword = "detective"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("detective") }
        debug { signingConfig = signingConfigs.getByName("detective") }
    }
    sourceSets.getByName("main").assets.srcDir(files(genDir).builtBy(syncCases))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        jniLibs { useLegacyPackaging = true }
    }
}


kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":casekit"))
}
