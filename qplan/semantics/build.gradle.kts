plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
    id("me.champeau.jmh")
}

dependencies {
    implementation(project(":model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testFixturesImplementation(project(":arbitrary"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testFixturesImplementation(kotlin("test-junit5"))

    testImplementation(project(":arbitrary"))
    testImplementation(testFixtures(project(":model")))
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit5"))

    add("jmhImplementation", sourceSets["testFixtures"].output)
}

configurations.named("jmhImplementation") {
    extendsFrom(configurations["testFixturesImplementation"])
}

val resolverBenchmarkQueryCount =
    providers.gradleProperty("resolverBenchmarkQueryCount").orElse("100")
val resolverBenchmarkQuerySeed =
    providers.gradleProperty("resolverBenchmarkQuerySeed").orElse("1")
val resolverBenchmarkLoopCount =
    providers.gradleProperty("resolverBenchmarkLoopCount").orElse("1")
val correctResolutionBenchmarkInputCount =
    providers.gradleProperty("correctResolutionBenchmarkInputCount").orElse("50")
val correctResolutionBenchmarkQuerySeed =
    providers.gradleProperty("correctResolutionBenchmarkQuerySeed").orElse("1")
val correctResolutionBenchmarkLoopCount =
    providers.gradleProperty("correctResolutionBenchmarkLoopCount").orElse("1")
val correctResolutionProfileOutput =
    providers
        .gradleProperty("correctResolutionProfileOutput")
        .map { configured -> file(configured) }
        .orElse(
            layout.buildDirectory
                .file("reports/resolver-benchmarks/correct-resolution.jfr")
                .map { regularFile -> regularFile.asFile },
        )
val propertyTestBenchmarkLoopCount =
    providers.gradleProperty("propertyTestBenchmarkLoopCount").orElse("1")
val propertyTestProfileOutput =
    providers
        .gradleProperty("propertyTestProfileOutput")
        .map { configured -> file(configured) }
        .orElse(
            layout.buildDirectory
                .file("reports/resolver-benchmarks/property-test.jfr")
                .map { regularFile -> regularFile.asFile },
        )
val resolver26OverheadProfileOutput =
    providers
        .gradleProperty("resolver26OverheadProfileOutput")
        .map { configured -> file(configured) }
        .orElse(
            layout.buildDirectory
                .file("reports/resolver-benchmarks/resolver26-overhead.jfr")
                .map { regularFile -> regularFile.asFile },
        )
val resolverBenchmarkCorpusSeed =
    providers.gradleProperty("resolverBenchmarkCorpusSeed").orElse("1")
val resolverBenchmarkCorpusSize =
    providers.gradleProperty("resolverBenchmarkCorpusSize").orElse("10:5:10")
val resolverBenchmarkCorpusDirectory =
    layout.projectDirectory.dir("src/jmh/resources/semantics/benchmark/current-profile")
val resolverBenchmarkQueriesFile =
    resolverBenchmarkCorpusDirectory.file("queries.json")

tasks.register<JavaExec>("generateResolverBenchmarkCorpus") {
    group = "benchmark"
    description = "Searches generated schema/registry pairs and writes the overhead benchmark corpus."
    dependsOn("testFixturesClasses")
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("semantics.benchmark.ResolverBenchmarkCorpusSearch")
    maxHeapSize = "4g"
    inputs.property("seed", resolverBenchmarkCorpusSeed)
    inputs.property("size", resolverBenchmarkCorpusSize)
    inputs.property("queryCount", resolverBenchmarkQueryCount)
    inputs.property("querySeed", resolverBenchmarkQuerySeed)
    outputs.dir(resolverBenchmarkCorpusDirectory)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                resolverBenchmarkCorpusDirectory.asFile.absolutePath,
                resolverBenchmarkCorpusSeed.get(),
                resolverBenchmarkCorpusSize.get(),
                resolverBenchmarkQueryCount.get(),
                resolverBenchmarkQuerySeed.get(),
            )
    }
}

tasks.register<JavaExec>("generateResolverBenchmarkQueries") {
    group = "benchmark"
    description = "Snapshots the exact query batch for the resolver overhead benchmarks."
    dependsOn("testFixturesClasses")
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("semantics.benchmark.ResolverBenchmarkQueryCorpusWriter")
    inputs.file(resolverBenchmarkCorpusDirectory.file("schema.graphqls"))
    inputs.file(resolverBenchmarkCorpusDirectory.file("registry.json"))
    inputs.property("queryCount", resolverBenchmarkQueryCount)
    inputs.property("querySeed", resolverBenchmarkQuerySeed)
    outputs.file(resolverBenchmarkQueriesFile)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                resolverBenchmarkCorpusDirectory.file("schema.graphqls").asFile.absolutePath,
                resolverBenchmarkCorpusDirectory.file("registry.json").asFile.absolutePath,
                resolverBenchmarkQueriesFile.asFile.absolutePath,
                resolverBenchmarkQueryCount.get(),
                resolverBenchmarkQuerySeed.get(),
            )
    }
}

val propertyTestBenchmarkCorpusDirectory =
    layout.projectDirectory.dir("src/jmh/resources/semantics/benchmark/property-test")

tasks.register<JavaExec>("generatePropertyTestBenchmarkCorpus") {
    group = "benchmark"
    description = "Snapshots the historical Resolver26 property-test benchmark case."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("semantics.resolver26.PropertyTestBenchmarkCorpusWriter")
    maxHeapSize = "4g"
    outputs.dir(propertyTestBenchmarkCorpusDirectory)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                propertyTestBenchmarkCorpusDirectory.asFile.absolutePath,
            )
    }
}

fun registerResolverBenchmarkTask(
    resolver: String,
    benchmark: String,
) {
    val taskName = "${resolver}${benchmark.replaceFirstChar(Char::uppercaseChar)}Benchmark"
    tasks.register<JavaExec>(taskName) {
        group = "benchmark"
        description = "Runs the $benchmark JMH benchmark for $resolver."
        val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
        dependsOn(benchmarkJar)
        classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
        mainClass.set("org.openjdk.jmh.Main")
        inputs.property("loopCount", resolverBenchmarkLoopCount)
        outputs.upToDateWhen { false }
        val statisticsFile =
            layout.buildDirectory.file(
                "reports/resolver-benchmarks/$taskName-statistics.txt",
            )

        doFirst {
            val reportArguments =
                if (benchmark == "overhead") {
                    val reportFile = statisticsFile.get().asFile
                    reportFile.delete()
                    listOf(
                        "-jvmArgsAppend",
                        "-DresolverBenchmarkReportFile=${reportFile.absolutePath}",
                    )
                } else {
                    emptyList()
                }
            args =
                listOf(
                    "semantics\\.$resolver\\.ResolverBenchmark\\.$benchmark",
                    "-p",
                    "loopCount=${resolverBenchmarkLoopCount.get()}",
                ) + reportArguments
        }
        doLast {
            if (benchmark == "overhead") {
                println()
                print(statisticsFile.get().asFile.readText())
            }
        }
    }
}

listOf("resolver26").forEach { resolver ->
    registerResolverBenchmarkTask(resolver, "full")
    registerResolverBenchmarkTask(resolver, "overhead")
}

tasks.register<JavaExec>("resolver26OverheadProfile") {
    group = "benchmark"
    description = "Profiles only a measured Resolver26 fixed-corpus overhead iteration with JFR."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("loopCount", resolverBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        val profileFile = resolver26OverheadProfileOutput.get().absoluteFile
        profileFile.parentFile.mkdirs()
        profileFile.delete()
        args =
            listOf(
                "semantics\\.resolver26\\.ResolverBenchmark\\.overhead",
                "-p",
                "loopCount=${resolverBenchmarkLoopCount.get()}",
                "-wi",
                "1",
                "-i",
                "1",
                "-f",
                "1",
                "-jvmArgsAppend",
                "-Dresolver26OverheadProfileOutput=${profileFile.absolutePath} " +
                    "-XX:FlightRecorderOptions=stackdepth=256",
            )
    }

    doLast {
        println()
        println("Resolver26 overhead JFR: ${resolver26OverheadProfileOutput.get().absolutePath}")
    }
}

tasks.register<JavaExec>("correctResolutionBenchmark") {
    group = "benchmark"
    description = "Benchmarks correctResolution over a prepared fixed corpus."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("inputCount", correctResolutionBenchmarkInputCount)
    inputs.property("querySeed", correctResolutionBenchmarkQuerySeed)
    inputs.property("loopCount", correctResolutionBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                "semantics\\.correctresolution\\.CorrectResolutionBenchmark\\.correctResolution",
                "-p",
                "inputCount=${correctResolutionBenchmarkInputCount.get()}",
                "-p",
                "querySeed=${correctResolutionBenchmarkQuerySeed.get()}",
                "-p",
                "loopCount=${correctResolutionBenchmarkLoopCount.get()}",
            )
    }
}

tasks.register<JavaExec>("correctResolutionProfile") {
    group = "benchmark"
    description = "Profiles only a measured correctResolution iteration with JFR."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("inputCount", correctResolutionBenchmarkInputCount)
    inputs.property("querySeed", correctResolutionBenchmarkQuerySeed)
    inputs.property("loopCount", correctResolutionBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        val profileFile = correctResolutionProfileOutput.get().absoluteFile
        profileFile.parentFile.mkdirs()
        profileFile.delete()
        args =
            listOf(
                "semantics\\.correctresolution\\.CorrectResolutionBenchmark\\.correctResolution",
                "-p",
                "inputCount=${correctResolutionBenchmarkInputCount.get()}",
                "-p",
                "querySeed=${correctResolutionBenchmarkQuerySeed.get()}",
                "-p",
                "loopCount=${correctResolutionBenchmarkLoopCount.get()}",
                "-wi",
                "1",
                "-i",
                "1",
                "-f",
                "1",
                "-jvmArgsAppend",
                "-DcorrectResolutionProfileOutput=${profileFile.absolutePath}",
            )
    }

    doLast {
        println()
        println("CorrectResolution JFR: ${correctResolutionProfileOutput.get().absolutePath}")
    }
}

tasks.register<JavaExec>("propertyTestBenchmark") {
    group = "benchmark"
    description = "Benchmarks one frozen Resolver26 property-test case and all of its oracles."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("loopCount", propertyTestBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                "semantics\\.resolver26\\.PropertyTestBenchmark\\.propertyTest",
                "-p",
                "loopCount=${propertyTestBenchmarkLoopCount.get()}",
            )
    }
}

tasks.register<JavaExec>("propertyTestProfile") {
    group = "benchmark"
    description = "Profiles one measured frozen Resolver26 property-test case with JFR."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("loopCount", propertyTestBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        val profileFile = propertyTestProfileOutput.get().absoluteFile
        profileFile.parentFile.mkdirs()
        profileFile.delete()
        args =
            listOf(
                "semantics\\.resolver26\\.PropertyTestBenchmark\\.propertyTest",
                "-p",
                "loopCount=${propertyTestBenchmarkLoopCount.get()}",
                "-wi",
                "1",
                "-i",
                "1",
                "-f",
                "1",
                "-jvmArgsAppend",
                "-DpropertyTestProfileOutput=${profileFile.absolutePath} " +
                    "-XX:FlightRecorderOptions=stackdepth=256",
            )
    }

    doLast {
        println()
        println("Property-test JFR: ${propertyTestProfileOutput.get().absolutePath}")
    }
}

val stressResolverNames =
    listOf(
        "resolver03",
        "resolver08",
        "resolver23",
        "resolver26",
    )

fun resolverStressTestClass(resolverName: String): String =
    if (resolverName == "resolver26") {
        "semantics.resolver26.ResolverStressTest"
    } else {
        "semantics.resolvers.$resolverName.ResolverStressTest"
    }

val configuredResolutionThreadCount =
    providers
        .gradleProperty("viaduct.resolution.threadcount")
        .orElse(providers.systemProperty("viaduct.resolution.threadcount"))
        .orElse(providers.environmentVariable("viaduct.resolution.threadcount"))

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val resolutionThreadCount =
        configuredResolutionThreadCount.orElse(
            if (name == "resolver26MultithreadedStress") "100" else "1",
        )
    inputs.property("viaduct.resolution.threadcount", resolutionThreadCount)

    doFirst {
        val configured = resolutionThreadCount.get()
        require(configured.toIntOrNull()?.let { threadCount -> threadCount > 0 } == true) {
            "viaduct.resolution.threadcount must be a positive integer: $configured"
        }
        systemProperty("viaduct.resolution.threadcount", configured)
    }
}

tasks.test {
    maxHeapSize = "2g"
    filter {
        stressResolverNames.forEach { resolverName ->
            excludeTestsMatching(resolverStressTestClass(resolverName))
        }
        excludeTestsMatching("semantics.resolver26.ResolverBroadStressTest")
        excludeTestsMatching("semantics.resolver26.ResolverBroadStressCampaignTest")
        excludeTestsMatching("semantics.resolver26.ResolverMultithreadedStressTest")
    }
}

tasks.register<JavaExec>("materializeGeneratorConfigs") {
    group = "verification"
    description = "Materializes complete Resolver26 generator-profile JSON files."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("semantics.propertytest.GeneratorConfigMaterializer")
    val outputDirectory =
        providers
            .gradleProperty("generatorConfigOutput")
            .map(::file)
            .orElse(layout.projectDirectory.dir("src/test/resources").asFile)
    doFirst {
        args(outputDirectory.get().absolutePath)
    }
    outputs.dir(
        layout.projectDirectory.dir(
            "src/test/resources/semantics/property-tests/generator-configs",
        ),
    )
    outputs.upToDateWhen { false }
}

val propertyTestLauncherJar =
    tasks.register<Jar>("propertyTestLauncherJar") {
        dependsOn("testClasses")
        archiveClassifier.set("property-test-launcher")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets["main"].output)
        from(sourceSets["testFixtures"].output)
        from(sourceSets["test"].output)
        manifest {
            attributes["Main-Class"] =
                "semantics.propertytest.PropertyTestRoundLauncher"
        }
    }

val propertyTestLauncherScripts =
    tasks.register<CreateStartScripts>("propertyTestLauncherScripts") {
        dependsOn(propertyTestLauncherJar)
        applicationName = "property-test-round"
        mainClass.set("semantics.propertytest.PropertyTestRoundLauncher")
        outputDir = layout.buildDirectory.dir("property-test-launcher/scripts").get().asFile
        classpath =
            files(
                propertyTestLauncherJar,
                configurations["testRuntimeClasspath"].filter(File::isFile),
            )
        defaultJvmOpts = listOf("-Xmx2g")
    }

tasks.register<Sync>("installPropertyTestRoundLauncher") {
    group = "verification"
    description = "Installs the direct-JVM property-test round launcher."
    dependsOn(propertyTestLauncherScripts)
    into(layout.buildDirectory.dir("install/property-test-round"))
    from(propertyTestLauncherScripts) {
        into("bin")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from(propertyTestLauncherJar)
    from(configurations["testRuntimeClasspath"].filter(File::isFile))
    eachFile {
        if (relativePath.segments.firstOrNull() != "bin") {
            relativePath = RelativePath(true, "lib", name)
        }
    }
}

val resolverPropertySeed =
    providers
        .gradleProperty("resolverPropertySeed")
        .orElse(providers.systemProperty("resolver.property.seed"))
        .orElse(providers.environmentVariable("RESOLVER_PROPERTY_SEED"))

tasks.test {
    inputs.property(
        "resolverPropertySeed",
        resolverPropertySeed.orElse("unseeded"),
    )
    outputs.upToDateWhen { resolverPropertySeed.orNull == null }

    doFirst {
        resolverPropertySeed.orNull?.let { configured ->
            configured.toLongOrNull()
                ?: throw GradleException(
                    "Set resolverPropertySeed, resolver.property.seed, or " +
                        "RESOLVER_PROPERTY_SEED to a Long: $configured",
                )
            systemProperty("resolver.property.seed", configured)
            systemProperty("kotest.proptest.default.seed", configured)
        }
    }
}

val resolverPropertyProfiles =
    mapOf(
        "empty-object-fragment" to
            "generated empty object fragment worlds resolve correctly",
        "node" to "generated node worlds resolve correctly",
        "sometimes-passive" to "generated sometimes-passive fields resolve correctly",
        "object-fragment" to
            "generated object fragment worlds without variables resolve correctly",
        "query-fragment" to
            "generated query fragment worlds resolve correctly",
        "object-fragment-from-argument" to
            "generated object fragment worlds with fromArgument resolve correctly",
        "resolver26-field-checker-success" to "generated successful field checker worlds resolve correctly",
        "resolver26-field-checker-denial" to "generated denying field checker worlds resolve correctly",
        "resolver26-field-checker-mixed" to "generated mixed field checker worlds resolve correctly",
        "resolver26-field-checker-passive" to "generated passive field checker worlds resolve correctly",
        "resolver26-field-checker-root-reference" to "generated root-reference field checker worlds resolve correctly",
        "resolver23-field-checker-success" to
            "generated successful field checker worlds resolve correctly",
        "resolver23-field-checker-denial" to
            "generated denying field checker worlds resolve correctly",
        "resolver23-field-checker-mixed" to
            "generated mixed field checker worlds resolve correctly",
        "resolver23-field-checker-passive" to
            "generated passive field checker worlds resolve correctly",
        "resolver23-field-checker-root-reference" to
            "generated root-reference field checker worlds resolve correctly",
        "object-fragment-from-object-field" to
            "generated object fragment worlds with fromObjectField resolve correctly",
        "mixed-variables" to
            "generated mixed resolver variable worlds resolve correctly",
        "resolver26-broad-stress" to
            "broad full-feature worlds resolve correctly",
        "resolver26-broad-descendant-variables" to
            "broad full-feature worlds resolve correctly",
        "resolver26-broad-nullable-errors" to
            "broad full-feature worlds resolve correctly",
        "resolver26-broad-symbolic-identity" to
            "broad full-feature worlds resolve correctly",
        "resolver26-broad-multiple-owners" to
            "broad full-feature worlds resolve correctly",
        "resolver26-root-field-references" to
            "root field reference focused randomized worlds resolve correctly",
        "feature-interaction" to "generated full feature interactions resolve correctly",
        "resolver03-construction-witness" to
            "generated construction witness is exact minimal and permutation invariant",
    )
val resolverPropertyReplayClass = providers.gradleProperty("resolverPropertyClass")
val resolverPropertyReplayProfile = providers.gradleProperty("resolverPropertyProfile")
val resolverPropertyReplayCase =
    providers.gradleProperty("resolverPropertyCase").orElse("all")
val resolverPropertyReplaySize = providers.gradleProperty("resolverPropertySize")

tasks.register<org.gradle.api.tasks.testing.Test>("resolverPropertyReplay") {
    group = "verification"
    description = "Replays one generated resolver profile or S:R:Q case."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }

    doFirst {
        val className =
            resolverPropertyReplayClass.orNull
                ?: throw GradleException("Set -PresolverPropertyClass=<fully-qualified-class>")
        require(className.matches(Regex("""[A-Za-z_$][A-Za-z0-9_.$]*"""))) {
            "resolverPropertyClass must be a fully qualified JVM class name: $className"
        }
        val profile =
            resolverPropertyReplayProfile.orNull
                ?: throw GradleException(
                    "Set -PresolverPropertyProfile=<profile>; profiles=" +
                        resolverPropertyProfiles.keys.sorted().joinToString(),
                )
        val method =
            resolverPropertyProfiles[profile]
                ?: throw GradleException(
                    "Unknown resolverPropertyProfile $profile; profiles=" +
                        resolverPropertyProfiles.keys.sorted().joinToString(),
                )
        val seed =
            resolverPropertySeed.orNull
                ?: throw GradleException("Set -PresolverPropertySeed=<long>")
        seed.toLongOrNull()
            ?: throw GradleException("resolverPropertySeed must be a Long: $seed")
        val case = resolverPropertyReplayCase.get()
        require(
            case.equals("all", ignoreCase = true) ||
                case.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertyCase must be all or S:R:Q with positive integers: $case"
        }
        val size = resolverPropertyReplaySize.orNull
        require(
            size == null ||
                size.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertySize must have S:R:Q form with positive integers: $size"
        }

        filter.includeTestsMatching("$className.$method")
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.profile", profile)
        systemProperty("resolver.property.case", case)
        size?.let { systemProperty("resolver.property.size", it) }
    }
}

fun registerResolverStressTask(resolverName: String) {
    val displayName = resolverName.replaceFirstChar(Char::uppercase)
    val environmentPrefix = resolverName.uppercase()
    val cases =
        providers.environmentVariable("${environmentPrefix}_STRESS_CASES").orElse("10000")
    val seed =
        providers
            .gradleProperty("${resolverName}StressSeed")
            .orElse(providers.systemProperty("$resolverName.stress.seed"))
            .orElse(providers.environmentVariable("${environmentPrefix}_STRESS_SEED"))

    tasks.register<org.gradle.api.tasks.testing.Test>("${resolverName}Stress") {
        group = "verification"
        description = "Runs the seeded $displayName deep stress property."
        maxHeapSize = "2g"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching(resolverStressTestClass(resolverName))
        }
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
        }

        doFirst {
            val configuredSeed =
                seed.orNull
                    ?: throw GradleException(
                        "Set -P${resolverName}StressSeed=<long>, " +
                            "-D$resolverName.stress.seed=<long>, or " +
                            "${environmentPrefix}_STRESS_SEED=<long>",
                    )
            systemProperty("$resolverName.stress.cases", cases.get())
            systemProperty("$resolverName.stress.seed", configuredSeed)
        }
    }
}

stressResolverNames.forEach(::registerResolverStressTask)

val resolver23FieldCheckerStressSeed =
    providers
        .gradleProperty("resolver23FieldCheckerStressSeed")
        .orElse(providers.systemProperty("resolver23.field.checker.stress.seed"))
        .orElse(providers.environmentVariable("RESOLVER23_FIELD_CHECKER_STRESS_SEED"))
val resolver23FieldCheckerStressProfile =
    providers
        .gradleProperty("resolver23FieldCheckerStressProfile")
        .orElse(providers.systemProperty("resolver23.field.checker.stress.profile"))
        .orElse(providers.environmentVariable("RESOLVER23_FIELD_CHECKER_STRESS_PROFILE"))
        .orElse("success")
val resolver23FieldCheckerStressProfiles =
    mapOf(
        "success" to
            Pair(
                "resolver23-field-checker-success",
                "generated successful field checker worlds resolve correctly",
            ),
        "denial" to
            Pair(
                "resolver23-field-checker-denial",
                "generated denying field checker worlds resolve correctly",
            ),
    )

tasks.register<org.gradle.api.tasks.testing.Test>("resolver23FieldCheckerStress") {
    group = "verification"
    description = "Runs 2,500 generated Resolver23 field-checker cases for one replayable profile."
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed =
            resolver23FieldCheckerStressSeed.orNull
                ?: throw GradleException(
                    "Set -Presolver23FieldCheckerStressSeed=<long>, " +
                        "-Dresolver23.field.checker.stress.seed=<long>, or " +
                        "RESOLVER23_FIELD_CHECKER_STRESS_SEED=<long>",
                )
        seed.toLongOrNull()
            ?: throw GradleException("resolver23FieldCheckerStressSeed must be a Long: $seed")
        val selectedProfile = resolver23FieldCheckerStressProfile.get()
        val (profile, method) =
            resolver23FieldCheckerStressProfiles[selectedProfile]
                ?: throw GradleException(
                    "Unknown resolver23FieldCheckerStressProfile $selectedProfile; profiles=" +
                        resolver23FieldCheckerStressProfiles.keys.sorted().joinToString(),
                )
        filter.includeTestsMatching(
            "semantics.resolvers.resolver23.ResolverGeneratedTest.$method",
        )
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.profile", profile)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.size", "50:5:10")
    }
}

val resolver26BroadStressSize =
    providers
        .gradleProperty("resolver26BroadStressSize")
        .orElse(providers.systemProperty("resolver26.broad.stress.size"))
        .orElse(providers.environmentVariable("RESOLVER26_BROAD_STRESS_SIZE"))
val resolver26BroadStressSeed =
    providers
        .gradleProperty("resolver26BroadStressSeed")
        .orElse(providers.systemProperty("resolver26.broad.stress.seed"))
        .orElse(providers.environmentVariable("RESOLVER26_BROAD_STRESS_SEED"))
val resolver26BroadStressProfile =
    providers
        .gradleProperty("resolver26BroadStressProfile")
        .orElse(providers.systemProperty("resolver26.broad.stress.profile"))
        .orElse(providers.environmentVariable("RESOLVER26_BROAD_STRESS_PROFILE"))
        .orElse("balanced")
val resolver26BroadStressProfiles =
    mapOf(
        "balanced" to Pair("resolver26-broad-stress", "10:20:50"),
        "descendant-variables" to
            Pair("resolver26-broad-descendant-variables", "40:25:10"),
        "nullable-errors" to Pair("resolver26-broad-nullable-errors", "10:20:50"),
        "symbolic-identity" to Pair("resolver26-broad-symbolic-identity", "10:20:50"),
        "multiple-owners" to Pair("resolver26-broad-multiple-owners", "10:50:20"),
    )

val resolver26ParentFocusedSeed =
    providers
        .gradleProperty("resolver26ParentFocusedSeed")
        .orElse(providers.systemProperty("resolver26.parent.focused.seed"))
        .orElse(providers.environmentVariable("RESOLVER26_PARENT_FOCUSED_SEED"))
        .orElse("2026090403")

tasks.register<org.gradle.api.tasks.testing.Test>("resolver26ParentFocused") {
    group = "verification"
    description = "Runs four 250-case slices of the parent-focused Resolver26 property."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "semantics.resolver26.ResolverBroadStressTest." +
                "parent focused randomized worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed = resolver26ParentFocusedSeed.get()
        seed.toLongOrNull()
            ?: throw GradleException("resolver26ParentFocusedSeed must be a Long: $seed")
        systemProperty("resolver26.broad.stress.seed", seed)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", "resolver26-parent-fields")
        systemProperty("resolver.property.size", "40:5:5")
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

val resolver26RootFieldReferenceFocusedSeed =
    providers
        .gradleProperty("resolver26RootFieldReferenceFocusedSeed")
        .orElse(providers.systemProperty("resolver26.root.field.reference.focused.seed"))
        .orElse(providers.environmentVariable("RESOLVER26_ROOT_FIELD_REFERENCE_FOCUSED_SEED"))
        .orElse("2026091001")

tasks.register<org.gradle.api.tasks.testing.Test>("resolver26RootFieldReferenceFocused") {
    group = "verification"
    description = "Runs the hard-coverage root-field-reference Resolver26 property."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "semantics.resolver26.ResolverBroadStressTest." +
                "root field reference focused randomized worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed = resolver26RootFieldReferenceFocusedSeed.get()
        seed.toLongOrNull()
            ?: throw GradleException(
                "resolver26RootFieldReferenceFocusedSeed must be a Long: $seed",
            )
        systemProperty("resolver26.broad.stress.seed", seed)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", "resolver26-root-field-references")
        systemProperty("resolver.property.size", "10:5:5")
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("resolver26BroadStress") {
    group = "verification"
    description = "Runs every case in a seeded broad Resolver26 generated product."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "semantics.resolver26.ResolverBroadStressTest." +
                "broad full-feature worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val profile = resolver26BroadStressProfile.get()
        val profileConfiguration =
            resolver26BroadStressProfiles[profile]
                ?: throw GradleException(
                    "Unknown resolver26BroadStressProfile $profile; profiles=" +
                        resolver26BroadStressProfiles.keys.sorted().joinToString(),
                )
        val size = resolver26BroadStressSize.orNull ?: profileConfiguration.second
        require(size.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*"""))) {
            "resolver26BroadStressSize must have S:R:Q form with positive integers: $size"
        }
        val seed =
            resolver26BroadStressSeed.orNull
                ?: throw GradleException(
                    "Set -Presolver26BroadStressSeed=<long>, " +
                        "-Dresolver26.broad.stress.seed=<long>, or " +
                        "RESOLVER26_BROAD_STRESS_SEED=<long>",
                )
        seed.toLongOrNull()
            ?: throw GradleException("resolver26BroadStressSeed must be a Long: $seed")

        systemProperty("resolver26.broad.stress.profile", profile)
        systemProperty("resolver26.broad.stress.size", size)
        systemProperty("resolver26.broad.stress.seed", seed)
        systemProperty("resolver.property.size", size)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", profileConfiguration.first)
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

val resolver26BroadStressCampaignRound =
    providers
        .gradleProperty("resolver26BroadStressCampaignRound")
        .orElse(providers.systemProperty("resolver26.broad.campaign.round"))
val resolver26BroadStressCampaignProfile =
    providers
        .gradleProperty("resolver26BroadStressCampaignProfile")
        .orElse(providers.systemProperty("resolver26.broad.campaign.profile"))
val resolver26BroadStressCampaignProfiles = resolver26BroadStressProfiles.keys

tasks.register<org.gradle.api.tasks.testing.Test>("resolver26BroadStressCampaign") {
    group = "verification"
    description = "Runs one recorded five-profile Resolver26 broad-stress campaign round."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver26.ResolverBroadStressCampaignTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val round =
            resolver26BroadStressCampaignRound.orNull
                ?: throw GradleException(
                    "Set -Presolver26BroadStressCampaignRound=<1..100>",
                )
        val roundNumber =
            round.toIntOrNull()
                ?: throw GradleException(
                    "resolver26BroadStressCampaignRound must be an integer: $round",
                )
        require(roundNumber in 1..100) {
            "resolver26BroadStressCampaignRound must be in 1..100: $round"
        }
        val profile = resolver26BroadStressCampaignProfile.orNull
        require(profile == null || profile in resolver26BroadStressCampaignProfiles) {
            "Unknown resolver26BroadStressCampaignProfile $profile; profiles=" +
                resolver26BroadStressCampaignProfiles.sorted().joinToString()
        }
        val case = resolverPropertyReplayCase.get()
        require(
            case.equals("all", ignoreCase = true) ||
                case.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertyCase must be all or S:R:Q with positive integers: $case"
        }
        require(case.equals("all", ignoreCase = true) || profile != null) {
            "Set -Presolver26BroadStressCampaignProfile=<profile> for coordinate replay"
        }

        systemProperty("resolver26.broad.campaign.round", round)
        profile?.let {
            systemProperty("resolver26.broad.campaign.profile", it)
        }
        systemProperty("resolver.property.case", case)
    }
}

val resolver26MultithreadedStressSize =
    providers
        .gradleProperty("resolver26MultithreadedStressSize")
        .orElse("campaign")
val resolver26MultithreadedStressRounds =
    providers
        .gradleProperty("resolver26MultithreadedStressRounds")
        .orElse("1")

tasks.register<org.gradle.api.tasks.testing.Test>("resolver26MultithreadedStress") {
    group = "verification"
    description = "Runs each Resolver26 request on a fixed multithreaded dispatcher."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("semantics.resolver26.ResolverMultithreadedStressTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        systemProperty(
            "resolver26.multithreaded.size",
            resolver26MultithreadedStressSize.get(),
        )
        systemProperty(
            "resolver26.multithreaded.rounds",
            resolver26MultithreadedStressRounds.get(),
        )
    }
}

// Runtime field checks have their own replayable workload; checker-free broad campaigns stay unchanged.
tasks.register<org.gradle.api.tasks.testing.Test>("resolver26FieldCheckerStress") {
    group = "verification"
    description = "Runs generated Resolver26 runtime field checks with exact checker application accounting."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    doFirst {
        val seed = providers.gradleProperty("resolver26FieldCheckerStressSeed").get().toLong()
        val profile = "resolver26-field-checker-" + providers.gradleProperty("resolver26FieldCheckerStressProfile").orElse("success").get()
        val method = resolverPropertyProfiles[profile] ?: throw GradleException("Unknown field-checker profile $profile")
        filter.includeTestsMatching("semantics.resolver26.FieldCheckerGeneratedTest.$method")
        systemProperty("resolver.property.profile", profile)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.size", providers.gradleProperty("resolver26FieldCheckerStressSize").orElse("50:5:10").get())
    }
}
