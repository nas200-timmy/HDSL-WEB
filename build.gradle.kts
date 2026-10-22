// HDSL-web — the HDSL domain layer behind a web server, shipped as one jar.
//
// The domain sources under `org.jackhuang.hmcl.dsh/setting/util` are copied
// from the HDSL desktop build and de-JavaFX-ed (see docs/plan.md); their
// package names and copyright headers stay as GPLv3 requires. This build keeps
// only what a headless server needs: no JavaFX, no JFoenix, no nanohttpd.
// JNA is the single native bridge kept: it backs the PTY glue of the web
// terminal (org.jackhuang.hmcl.web.terminal.pty, see docs/web-shell.md).

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

// The minimal-image variant: `-PnoSsh` drops the SSH terminal channel —
// its classes and the MINA/eddsa jars — from the fat jar. HdslServer meets
// the absence reflectively (SshBridge), so the runtime degrades to "the
// channel does not exist", not to a crash.
//
// `-PnoSsh` carries no value, which Gradle hands over as the empty string;
// `String::toBoolean` would read that as false and silently ship the full
// jar, so presence alone means on. `-PnoSsh=false` still turns it off.
val noSsh = providers.gradleProperty("noSsh")
    .map { it.isEmpty() || it.toBoolean() }
    .getOrElse(false)

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

    // The PTY glue behind the web terminal (docs/web-shell.md): JNA is the
    // only native bridge in this build, and the terminal is present in every
    // variant — it is not part of the -PnoSsh switch.
    implementation(libs.jna)

    // The SSH terminal channel: embedded Apache MINA SSHD server (docs/ssh-terminal-channel.md).
    // sshd-core also carries the SshClient the integration tests connect with.
    // Both the channel and its dependencies are absent from a `-PnoSsh`
    // build: the minimal image carries no MINA/eddsa at all (docs §M4). The
    // sources still compile against them (compileOnly), and the test suite
    // still runs with them — only the shipped jar loses them.
    if (noSsh) {
        compileOnly(libs.mina.sshd)
        compileOnly(libs.eddsa)
        testImplementation(libs.mina.sshd)
        testImplementation(libs.eddsa)
    } else {
        implementation(libs.mina.sshd)
        // Ed25519 for the channel above. sshd-common declares this dependency
        // OPTIONAL, so Gradle never resolves it transitively — without it every
        // Ed25519 path (authorized_keys verification, the EdDSA host key) fails
        // at runtime with "EdDSA provider not supported".
        implementation(libs.eddsa)
    }

    // Jetty speaks slf4j; this binding forwards it to java.util.logging, the
    // same sink HDSL-web's own logging story is built on. Server code itself
    // logs through org.jackhuang.hmcl.util.logging.Logger, never slf4j.
    runtimeOnly(libs.slf4j.jdk14)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 全套用例跑在一个 fork 的 JVM 里，而 Gradle 的默认上限是 512m——本机峰值在
    // 256–384m 之间（256m 会 OOM），正好卡在默认值边上，于是 CI 上（核数不同、GC 行为
    // 不同）翻过去成了 `java.lang.OutOfMemoryError: Java heap space`。给一个有富余的界：
    // 跑得动就还是几分钟跑完，跑不动时也仍然是「堆不够」而不是随机红。
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        // Failure output carries the full exception, assertion message included: without it a
        // red test in CI logs only its line number, and the reason is unrecoverable.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
        dir.deleteRecursively()
        dir.mkdirs()
        // The whole fixture directory, not just the POSIX script: its Windows entry
        // point is `pnpm.cmd`, and on Windows a bare `pnpm` names nothing. Without the
        // `.cmd` the launcher's own executable resolution would skip this directory and
        // find the real pnpm further down PATH — so the tests would reach the network
        // instead of the stub, which is the one thing this fixture exists to prevent.
        project.file("src/test/resources/fake-pnpm").copyRecursively(dir, overwrite = true)
        File(dir, "pnpm").setExecutable(true)
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

// Which `npm` to run is a platform question, and getting it wrong fails in a way that
// reads like a Gradle bug rather than a missing tool. A Node.js installation on Windows
// ships three files under one name: `npm` (a POSIX shell script), `npm.cmd` (the real
// shim) and `npm.ps1`. The extensionless one is a *file*, so `canExecute()` is true, but
// it is not an executable image — `CreateProcess` answers "not a valid Win32
// application" (error 193) while a bare `npm` is not found at all (error 2). The
// extension is therefore chosen by platform rather than left to the name.
val npmExecutable: String? = run {
    val names = if (File.separatorChar == '\\') listOf("npm.cmd", "npm.exe", "npm") else listOf("npm")
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.filter { it.isNotBlank() }
        ?.flatMap { dir -> names.asSequence().map { File(dir, it) } }
        ?.firstOrNull { it.isFile }
        ?.absolutePath
}
val npmAvailable = npmExecutable != null

val buildWeb by tasks.registering(Exec::class) {
    onlyIf { npmAvailable && webDir.file("package.json").asFile.isFile }
    group = "build"
    description = "Builds the panel SPA (npm run build in web/)."
    workingDir(webDir)
    commandLine(npmExecutable ?: "npm", "run", "build")

    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("package.json"), webDir.file("index.html"))
    outputs.dir(webDir.dir("dist"))

    doFirst { logger.lifecycle("Building panel SPA with npm…") }
    doLast { if (!npmAvailable) logger.warn("npm not on PATH; serving the web tree as it stands") }
}

// buildWeb 的 onlyIf 会在没有 npm 时静默跳过；这时若连上一次的 dist 都没有，
// 打出来的 jar 必然缺前端、运行时每个页面都回 "not found"（Windows 裸部署实测踩过：
// 装了 JDK、没装 Node，shadowJar 33 秒"成功"，跑起来只剩光秃秃的 not found）。
// 所以单独设一个无输入输出的哨兵任务——这种任务永不 up-to-date、必定执行
// （doFirst 挂在 Sync 上会被 up-to-date 短路，实测不咬人）。
val checkWebDist by tasks.registering {
    doLast {
        if (!npmAvailable && !webDir.dir("dist").asFile.isDirectory) {
            throw GradleException(
                "web/ has no dist/ and npm is not on PATH: this jar would ship without the web frontend. " +
                    "Install Node.js (which provides npm) and rebuild, or download a release jar instead."
            )
        }
    }
}

val syncWebDist by tasks.registering(Sync::class) {
    dependsOn(buildWeb, checkWebDist)
    from(webDir.dir("dist"))
    into(layout.projectDirectory.dir("src/main/resources/web"))
}

tasks.named("processResources") {
    dependsOn(syncWebDist)
}

// ------------------------------------------------------------- toolchain -----
java {
    // HDSL-web requires JDK 17+. We do not pin a toolchain so the build works
    // with whichever >= 17 JDK the developer has active.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Compile against the Java 17 API even when the build runs on 21+: the jar
    // must start on a 17 JVM, and `--release` is what makes a 21-only API a
    // compile error here rather than an UnsupportedClassVersionError there.
    options.release.set(17)
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
    // The -PnoSsh variant ships no SSH terminal channel: the channel's own
    // classes and MINA/eddsa stay out of the image. HdslServer meets the
    // absence through SshBridge (reflection), so nothing else changes.
    if (noSsh) {
        exclude("org/jackhuang/hmcl/web/ssh/**")
        exclude("org/apache/sshd/**")
        exclude("net/i2p/**")
    }
    manifest {
        attributes(
            "Main-Class" to "org.jackhuang.hmcl.web.Main",
            "Implementation-Title" to "HDSL-web",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
