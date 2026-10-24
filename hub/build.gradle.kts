import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

/**
 * Every folder `cases/<id>/` with a `case.json` is bundled into the app (see docs/NEW_CASE.md for the layout).
 * The build copies the content into the assets the phone serves and writes `data/cases/index.json`, the list of
 * cases the lobby shows, so adding a case needs no code.
 */
val caseDirs = rootDir.resolve("cases").listFiles { f -> f.resolve("case.json").isFile }!!.sortedBy { it.name }
val genDir = layout.buildDirectory.dir("generated/caseAssets")

val syncCases by tasks.registering {
    val out = genDir.get().asFile
    inputs.files(caseDirs)
    inputs.dir(rootDir.resolve("cases"))
    outputs.dir(out)
    doLast {
        out.deleteRecursively()
        val index = StringBuilder("[")
        for (dir in caseDirs) {
            val id = dir.name
            @Suppress("UNCHECKED_CAST")
            val case = groovy.json.JsonSlurper().parse(dir.resolve("case.json"), "UTF-8") as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val meta = (case["meta"] as? Map<String, Any?>) ?: emptyMap()
            val data = out.resolve("data/cases/$id")
            val web = out.resolve("web/games/$id")
            copy { from(dir.resolve("case.json")); into(data) }
            copy { from(dir.resolve("documents")); into(data.resolve("doc")) }
            copy { from(dir.resolve("photos")) { include("*.jpg") }; into(data.resolve("photo")) }
            if (dir.resolve("avatars").isDirectory) copy { from(dir.resolve("avatars")); into(web.resolve("avatars")) }
            if (dir.resolve("rules.html").isFile) copy { from(dir.resolve("rules.html")); into(web) }
            web.mkdirs()
            web.resolve("game.js").writeText("PG.caseKit('$id');\n")
            web.resolve("game.css").writeText("[data-theme=$id] { --accent: ${meta["accent"] ?: "#c9a34e"}; --on-accent: ${meta["on_accent"] ?: "#1a1508"}; }\n")
            index.append(if (index.length > 1) "," else "").append(
                groovy.json.JsonOutput.toJson(mapOf("id" to id, "title" to case["title"], "card" to (meta["card"] ?: ""), "emoji" to (meta["emoji"] ?: "🕵️")))
            )
        }
        out.resolve("data/cases").mkdirs()
        out.resolve("data/cases/index.json").writeText(index.append("]").toString(), Charsets.UTF_8)
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
    sourceSets.getByName("main").assets.srcDir(genDir)

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


// the generated assets must exist before anything reads the asset folders (merging, lint)
tasks.matching { it.name != "syncCases" && (it.name.endsWith("Assets") || it.name.contains("lint", ignoreCase = true)) }
    .configureEach { dependsOn(syncCases) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":casekit"))
}
