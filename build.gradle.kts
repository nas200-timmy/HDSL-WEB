// HDSL-web — the HDSL domain layer behind a web server, shipped as one jar.
//
// The domain sources under `org.jackhuang.hmcl.dsh/setting/util` are copied
// from the HDSL desktop build and de-JavaFX-ed (see docs/plan.md); their
// package names and copyright headers stay as GPLv3 requires. This build keeps
// only what a headless server needs: no JavaFX, no JFoenix, no JNA, no
// nanohttpd.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    application
    alias(libs.plugins.shadow)
}

group = "org.jackhuang.hmcl"

// A release takes its version from `-PreleaseVersion=1.2.3`; anything else is
// a development build. HDSL-web is not versioned from git tags the way the
// desktop build is — the jar name just needs to be stable.
version = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0-dev"

application {
    mainClass = "org.jackhuang.hmcl.web.Main"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.gson)
    implementation(libs.jetbrains.annotations)
    implementation(libs.errorprone.annotations)
    implementation(libs.kala.compress.zip)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.ar)
    implementation(libs.kala.encoding.detector)
    implementation(libs.xz)
    implementation(libs.weburl)
    implementation(libs.uuid.tools)

    // Phase 1 server stack: embedded Jetty 12 (EE10 = Jakarta Servlet 6),
    // snakeyaml-engine for server.yaml, Bouncy Castle bcpkix for PEM parsing
    // and self-signed certificate generation. Phase 2 adds the WebSocket
    // modules: jakarta-server for the /ws gateway, core server + client for
    // the /i/* upgrade relay.
    implementation(libs.jetty.server)
    implementation(libs.jetty.ee10.servlet)
    implementation(libs.jetty.ee10.websocket.jakarta.server)
    implementation(libs.jetty.websocket.jetty.server)
    implementation(libs.jetty.websocket.jetty.client)
    implementation(libs.snakeyaml.engine)
    implementation(libs.bcpkix)

    // Jetty speaks slf4j; this binding forwards it to java.util.logging, the
    // same sink HDSL-web's own logging story is built on. Server code itself
    // logs through org.jackhuang.hmcl.util.logging.Logger, never slf4j.
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // A bound on every test, so a machine that cannot run one fails in minutes instead of holding
    // the runner. The name matters — `junit.jupiter.execution.timeout.default` is the key JUnit
    // reads, and `…method.default` is not a key at all, which is a bound nobody reads.
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
    // The launcher keeps its state in one per-user home, and a test that writes
    // settings must not write into the one the user is running. Tests get a home
    // of their own inside the build tree, which is also what makes them able to
    // assert on what was persisted.
    systemProperty("hdsl.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // The shared home survives an interrupted run (a killed test JVM leaves its
    // instances behind, and the next run's `crud-one` becomes `crud-one-2`).
    // Start every run from an empty one; TestSupport additionally wipes it
    // before every server boot for in-run isolation.
    doFirst {
        delete(layout.buildDirectory.dir("test-home"))
    }

    // A fake `pnpm` ahead of the real one on the test PATH (see
    // src/test/resources/fake-pnpm/pnpm): it stubs out the registry download
    // of a harness install — and only while a test has written the marker
    // file — so pack installs can run end-to-end offline. Everything else is
    // delegated to the real pnpm.
    val fakePnpmBin = layout.buildDirectory.dir("fake-pnpm-bin")
    doFirst {
        val dir = fakePnpmBin.get().asFile
        dir.mkdirs()
        val script = File(dir, "pnpm")
        project.file("src/test/resources/fake-pnpm/pnpm").copyTo(script, overwrite = true)
        script.setExecutable(true)
    }
    environment("PATH", fakePnpmBin.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
    environment("HDSL_FAKE_PNPM_MARKER", layout.buildDirectory.file("fake-pnpm.enabled").get().asFile.absolutePath)
}

// --------------------------------------------------------------- resources ---
// HMCL generates this list at build time. HDSL did the same, and HDSL-web
// keeps the task so the language list can never drift from the .properties
// files actually shipped.
val generateLanguageList by tasks.registering {
    val langDir = layout.projectDirectory.dir("src/main/resources/assets/lang")
    val outputDir = layout.buildDirectory.dir("generated/languageList")

    inputs.dir(langDir)
    outputs.dir(outputDir)

    doLast {
        val tags = sortedSetOf<String>()
        langDir.asFile.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("I18N") && name.endsWith(".properties")) {
                val tag = name.removePrefix("I18N").removeSuffix(".properties").removePrefix("_")
                tags.add(if (tag.isEmpty()) "en" else tag.replace('_', '-'))
            }
        }
        val target = outputDir.get().dir("assets/lang").file("languages.json").asFile
        target.parentFile.mkdirs()
        target.writeText(tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"" + it + "\"" })
    }
}

sourceSets.main {
    resources.srcDir(generateLanguageList)
}

// ------------------------------------------------------------------ web UI ---
// The panel SPA lives in web/ (Vite + React). Its dist is synced into the
// resources tree before packaging so the fat jar serves it. Without npm the
// tree keeps whatever it currently holds, so Java-only builds still work.
val webDir = layout.projectDirectory.dir("web")
val npmAvailable = System.getenv("PATH")?.split(File.pathSeparator)?.any { File(it, "npm").canExecute() } == true

val buildWeb by tasks.registering(Exec::class) {
    onlyIf { npmAvailable && webDir.file("package.json").asFile.isFile }
    group = "build"
    description = "Builds the panel SPA (npm run build in web/)."
    workingDir(webDir)
    commandLine("npm", "run", "build")

    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("package.json"), webDir.file("index.html"))
    outputs.dir(webDir.dir("dist"))

    doFirst { logger.lifecycle("Building panel SPA with npm…") }
    doLast { if (!npmAvailable) logger.warn("npm not on PATH; serving the web tree as it stands") }
}

val syncWebDist by tasks.registering(Sync::class) {
    dependsOn(buildWeb)
    from(webDir.dir("dist"))
    into(layout.projectDirectory.dir("src/main/resources/web"))
}

tasks.named("processResources") {
    dependsOn(syncWebDist)
}

// ------------------------------------------------------------- toolchain -----
java {
    // HDSL-web requires JDK 21+. We do not pin a toolchain so the build works
    // with whichever >= 21 JDK the developer has active.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Report every error instead of stopping at the default 100.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

// The application plugin is used for its `run` task only. The launcher ships
// as a single executable shadow jar, so the distribution tasks are switched
// off rather than taught to agree about the jar this build disables.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("startScripts") { enabled = false }
tasks.named("installDist") { enabled = false }

// ------------------------------------------------------------------ fat jar --
// The server ships as a single self-contained jar: `java -jar hdsl-web.jar`.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("hdsl-web")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Dependency signatures do not survive merging, and a stale one makes the
    // JVM refuse to start.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    manifest {
        attributes(
            "Main-Class" to "org.jackhuang.hmcl.web.Main",
            "Implementation-Title" to "HDSL-web",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
