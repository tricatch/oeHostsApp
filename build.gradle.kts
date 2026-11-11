plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "tricatch.oehubapp"
version = "0.1.0"

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
    implementation("io.github.tricatch:oe-proxy:0.1.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")
    implementation("com.formdev:flatlaf:3.8")
    implementation("com.formdev:flatlaf-extras:3.8")
    implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
    // Forward proxy (same version as oeHub).
    implementation("io.github.littleproxy:littleproxy:2.9.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Writes the app version and the resolved version of every runtime library into the jar
// (/oehubapp-build.properties), so the About page shows the versions actually bundled - the
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
        val f = buildInfoDir.get().file("oehubapp-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$appVersion\n${deps.get()}\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }

tasks.jar {
    manifest {
        attributes("Main-Class" to "tricatch.oehubapp.OeHubApp", "Implementation-Version" to project.version)
    }
}

tasks.shadowJar {
    archiveFileName = "oehubapp.jar"
    manifest {
        attributes("Main-Class" to "tricatch.oehubapp.OeHubApp", "Implementation-Version" to project.version)
    }
    // Signed dependencies (BouncyCastle) would fail signature checks once merged into one jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

// Desktop (Swing) app.
tasks.register<JavaExec>("run") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "tricatch.oehubapp.OeHubApp"
}
