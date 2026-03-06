import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    java
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = providers.gradleProperty("description").get()

val awesomeDebugBuild =
    providers
        .gradleProperty("awesome.debug")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

val generatedBuildFlagsDir = layout.buildDirectory.dir("generated/source/buildFlags/main/kotlin")
val generateBuildFlags by
    tasks.registering {
        group = "build setup"
        description = "Generates build-time flags for Awesome MCP."
        inputs.property("awesome.debug", awesomeDebugBuild)

        val outputFile = generatedBuildFlagsDir.get().asFile.resolve("net/portswigger/mcp/core/BuildFlags.kt")
        outputs.file(outputFile)

        doLast {
            val debugEnabled = awesomeDebugBuild.get()
            outputFile.parentFile.mkdirs()
            outputFile.writeText(
                """
                package net.portswigger.mcp.core

                object BuildFlags {
                    const val DEBUG_BUILD: Boolean = $debugEnabled
                }
                """.trimIndent() + "\n",
            )
        }
    }

val testsLogFile = layout.projectDirectory.file("tests_log.txt").asFile
val testsLogLock = Any()
var testsLogInitialized = false

dependencies {
    compileOnly(libs.burp.montoya.api)

    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk)

    testImplementation(libs.bundles.test.framework)
    testImplementation(libs.bundles.ktor.test)
    testImplementation(libs.burp.montoya.api)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("java.toolchain.version").get().toInt()))
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("java.toolchain.version").get().toInt()))
    }

    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                providers.gradleProperty("java.toolchain.version").get(),
            ),
        )
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
        )
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildFlagsDir)
    }
}

application {
    mainClass.set("net.portswigger.mcp.ExtensionBase")
}

ktlint {
    android.set(false)
    ignoreFailures.set(false)
    outputToConsole.set(true)
    filter {
        exclude("**/build/**")
    }
}

tasks {
    named("compileKotlin") {
        dependsOn(generateBuildFlags)
    }

    matching { task ->
        task.name.startsWith("runKtlint") && task.name.endsWith("OverMainSourceSet")
    }.configureEach {
        dependsOn(generateBuildFlags)
    }

    val testSourceSet = sourceSets.getByName("test")

    fun Test.configureDetailedTraceToFile(resetFile: Boolean) {
        notCompatibleWithConfigurationCache("Detailed test output listeners write runtime trace to tests_log.txt")
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")

        fun appendTestLog(line: String) {
            synchronized(testsLogLock) {
                testsLogFile.appendText(line + System.lineSeparator())
            }
        }

        doFirst {
            synchronized(testsLogLock) {
                testsLogFile.parentFile?.mkdirs()
                if (resetFile || !testsLogInitialized) {
                    testsLogFile.writeText("")
                    testsLogInitialized = true
                }
                testsLogFile.appendText(
                    "=== $name run started ${Instant.now()} ===" + System.lineSeparator(),
                )
            }
        }

        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }

        addTestListener(
            object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {
                    if (suite.parent == null) {
                        appendTestLog("[suite:start] ${suite.name}")
                    }
                }

                override fun afterSuite(
                    suite: TestDescriptor,
                    result: TestResult,
                ) {
                    if (suite.parent == null) {
                        appendTestLog(
                            "[suite:done] ${suite.name} result=${result.resultType} tests=${result.testCount} " +
                                "passed=${result.successfulTestCount} failed=${result.failedTestCount} " +
                                "skipped=${result.skippedTestCount}",
                        )
                    }
                }

                override fun beforeTest(testDescriptor: TestDescriptor) {
                    appendTestLog("[test:start] ${testDescriptor.className}#${testDescriptor.name}")
                }

                override fun afterTest(
                    testDescriptor: TestDescriptor,
                    result: TestResult,
                ) {
                    appendTestLog(
                        "[test:done] ${testDescriptor.className}#${testDescriptor.name} " +
                            "result=${result.resultType} durationMs=${result.endTime - result.startTime}",
                    )
                }
            },
        )

        addTestOutputListener(
            object : TestOutputListener {
                override fun onOutput(
                    descriptor: TestDescriptor,
                    event: TestOutputEvent,
                ) {
                    val source = descriptor.className?.let { "$it#${descriptor.name}" } ?: descriptor.name
                    val lines =
                        event.message
                            .lineSequence()
                            .map { it.trimEnd() }
                            .filter { it.isNotBlank() }
                            .toList()
                    lines.forEach { line ->
                        appendTestLog("[output:${event.destination}] $source :: $line")
                    }
                }
            },
        )

        doLast {
            appendTestLog("=== $name run finished ${Instant.now()} ===")
        }
    }

    test {
        configureDetailedTraceToFile(resetFile = true)
        // Keep unit test profile clean: integration/live suites are run via dedicated Gradle tasks.
        exclude("**/*IntegrationTest.class")
    }

    val runLiveIntegrationTests =
        providers.provider {
            val byGradleProperty =
                providers.gradleProperty("awesome.live").orNull?.equals("true", ignoreCase = true) == true
            val bySystemProperty = !providers.systemProperty("awesome.mcp.live.url").orNull.isNullOrBlank()
            val byEnvironmentVariable = !providers.environmentVariable("AWESOME_MCP_LIVE_URL").orNull.isNullOrBlank()
            byGradleProperty || bySystemProperty || byEnvironmentVariable
        }

    fun Test.propagateLiveMcpProperties() {
        val keys =
            listOf(
                "awesome.mcp.live.url",
                "awesome.mcp.live.transport",
                "awesome.mcp.live.collaborator",
                "awesome.mcp.live.scope.include_prefix",
                "awesome.mcp.live.scope.exclude_prefix",
            )
        keys.forEach { key ->
            providers.systemProperty(key).orNull?.let { value ->
                systemProperty(key, value)
            }
        }
    }

    register<Test>("integrationTest") {
        group = "verification"
        description =
            "Runs integration tests (*IntegrationTest) excluding LiveBurpMcpIntegrationTest unless -Pawesome.live=true."
        configureDetailedTraceToFile(resetFile = false)
        propagateLiveMcpProperties()
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        systemProperty("file.encoding", "UTF-8")
        include("**/*IntegrationTest.class")
        if (!runLiveIntegrationTests.get()) {
            exclude("**/LiveBurpMcpIntegrationTest.class")
        }
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    register<Test>("liveBurpTest") {
        group = "verification"
        description = "Runs only live Burp integration test (requires running Burp with Awesome MCP enabled)."
        configureDetailedTraceToFile(resetFile = false)
        propagateLiveMcpProperties()
        useJUnitPlatform()
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        systemProperty("file.encoding", "UTF-8")
        include("**/LiveBurpMcpIntegrationTest.class")
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveFileName.set("burp-awesome-mcp.jar")
        archiveClassifier.set("")
        mergeServiceFiles()

        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version,
                    "Implementation-Vendor" to "vvvvvvvvvvel",
                    "Built-By" to System.getProperty("user.name"),
                    "Built-Date" to Instant.now().toString(),
                    "Built-JDK" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")} ${
                        System.getProperty("java.vm.version")
                    })",
                    "Created-By" to "Gradle ${gradle.gradleVersion}",
                ),
            )
        }

        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/INDEX.LIST")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/NOTICE*")
        exclude("META-INF/LICENSE*")
        exclude("module-info.class")

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    build {
        dependsOn(shadowJar)
    }

    register("buildPlugin") {
        group = "build"
        description = "Builds only plugin fat JAR (shadowJar), without running tests."
        dependsOn("clean", "shadowJar")
    }

    register("buildWithTests") {
        group = "build"
        description = "Builds plugin JAR and runs regular test suite."
        dependsOn("clean", "test", "shadowJar")
    }

    register("buildWithIntegrationTests") {
        group = "build"
        description = "Builds plugin JAR and runs integration tests (see integrationTest and -Pawesome.live=true)."
        dependsOn("clean", "integrationTest", "shadowJar")
    }

    register("lintAndFormat") {
        group = "verification"
        description = "Runs Kotlin formatter and linter (ktlintFormat + ktlintCheck)."
        dependsOn("ktlintFormat")
        finalizedBy("ktlintCheck")
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.wrapper {
    gradleVersion = "9.2.0"
    distributionType = Wrapper.DistributionType.BIN
}
