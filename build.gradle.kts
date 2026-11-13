plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "tricatch.oehostsapp"
version = "0.1.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Runs on any JDK 21+ (the embedded oe-proxy uses virtual threads); IntelliJ users already have one.
tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

dependencies {
    // 0.3.0: setBindAddresses (the reverse proxy on a network card plus 127.0.0.1).
    implementation("io.github.tricatch:oe-proxy:0.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")
    implementation("com.formdev:flatlaf:3.8")
    implementation("com.formdev:flatlaf-extras:3.8")
    implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
    // Forward proxy (same version as oeHub).
    implementation("io.github.littleproxy:littleproxy:2.9.1")
    // QR code on the phone setup page (pure Java, no native parts).
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Writes the app version and the resolved version of every runtime library into the jar
// (/oehostsapp-build.properties), so the About page shows the versions actually bundled - the
// merged shadow jar keeps no per-library manifests, and an IDE run has no manifest at all.
val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val buildInfo by tasks.registering {
    val appVersion = project.version.toString()
    val deps = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
        val seen = sortedSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        val visited = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!visited.add(c)) continue
            val m = c.moduleVersion
            // group/name: a ':' would end the key in a .properties file.
            if (c != root && m != null) seen.add("dep.${m.group}/${m.name}=${m.version}")
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { queue.add(it.selected) }
        }
        seen.joinToString("\n")
    }
    inputs.property("version", appVersion)
    inputs.property("deps", deps)
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("oehostsapp-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$appVersion\n${deps.get()}\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }

// oeHosts-Agent (the javaagent that maps hosts from oeHosts share URLs) ships inside the jar as
// /oehosts-agent.jar; the app copies it next to itself at start (HostsAgent), so installing or
// updating the app installs or updates the agent too. Its jar is built from the sibling
// ../oeHostsAgent checkout (an included build); without the checkout the app has no agent to offer.
val agentBuild = gradle.includedBuilds.find { it.name == "oeHostsAgent" }
val bundleAgent by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/agent"))
    if (agentBuild != null) {
        val script = agentBuild.projectDir.resolve("build.gradle.kts").readText()
        val agentVersion = Regex("""(?m)^version\s*=\s*"([^"]+)"""").find(script)?.groupValues?.get(1)
            ?: error("no version in ../oeHostsAgent/build.gradle.kts")
        dependsOn(agentBuild.task(":jar"))
        from(agentBuild.projectDir.resolve("build/libs/oehosts-agent-$agentVersion.jar")) {
            rename { "oehosts-agent.jar" }
        }
    }
}
sourceSets.main { resources.srcDir(bundleAgent) }

tasks.jar {
    manifest {
        attributes("Main-Class" to "tricatch.oehostsapp.OeHostsApp", "Implementation-Version" to project.version)
    }
}

/**
 * META-INF/licenses/<library>/ for the jar: the LICENSE/NOTICE files each dependency jar carries,
 * plus src/main/licenses/ for the libraries whose jars carry none (Netty, LittleProxy, SnakeYAML,
 * ...). When a dependency is added, check its jar for license files and add a folder under
 * src/main/licenses/ if it has none.
 */
val thirdPartyLicenses = tasks.register<Sync>("thirdPartyLicenses") {
    into(layout.buildDirectory.dir("third-party-licenses"))
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
    configurations.runtimeClasspath.get().incoming.artifacts.artifacts.forEach { a ->
        val lib = when (val id = a.id.componentIdentifier) {
            is ModuleComponentIdentifier -> id.module
            is ProjectComponentIdentifier -> id.projectName
            else -> a.file.nameWithoutExtension
        }
        from(zipTree(a.file)) {
            include("META-INF/*LICENSE*", "META-INF/*NOTICE*")
            eachFile { path = "META-INF/licenses/$lib/$name" }
        }
    }
    from("src/main/licenses") { into("META-INF/licenses") }
}

tasks.shadowJar {
    archiveFileName = "oehostsapp.jar"
    manifest {
        attributes("Main-Class" to "tricatch.oehostsapp.OeHostsApp", "Implementation-Version" to project.version)
    }
    // Signed dependencies (BouncyCastle) would fail signature checks once merged into one jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    // Every library ships its own META-INF/LICENSE and NOTICE; merged into one jar they would
    // overwrite each other, so they go into per-library folders instead (thirdPartyLicenses).
    exclude("META-INF/*LICENSE*", "META-INF/*NOTICE*")
    from(thirdPartyLicenses)
    from("LICENSE")
}

// Desktop (Swing) app.
tasks.register<JavaExec>("run") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "tricatch.oehostsapp.OeHostsApp"
}
