val sourceSets = extensions.getByType<SourceSetContainer>()

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
val resolutionOverheadProfileOutput =
    providers
        .gradleProperty("resolutionOverheadProfileOutput")
        .map { configured -> file(configured) }
        .orElse(
            layout.buildDirectory
                .file("reports/resolver-benchmarks/resolution-overhead.jfr")
                .map { regularFile -> regularFile.asFile },
        )
val resolverBenchmarkCorpusSeed =
    providers.gradleProperty("resolverBenchmarkCorpusSeed").orElse("1")
val resolverBenchmarkCorpusSize =
    providers.gradleProperty("resolverBenchmarkCorpusSize").orElse("10:5:10")
val resolverBenchmarkCorpusDirectory =
    layout.projectDirectory.dir("src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile")
val resolverBenchmarkQueriesFile =
    resolverBenchmarkCorpusDirectory.file("queries.json")

tasks.register<JavaExec>("generateResolverBenchmarkCorpus") {
    group = "benchmark"
    description = "Searches generated schema/registry pairs and writes the overhead benchmark corpus."
    dependsOn("supportClasses")
    classpath = sourceSets["support"].runtimeClasspath
    mainClass.set("viaduct.engine.runtime2.benchmark.ResolverBenchmarkCorpusSearch")
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
    dependsOn("supportClasses")
    classpath = sourceSets["support"].runtimeClasspath
    mainClass.set("viaduct.engine.runtime2.benchmark.ResolverBenchmarkQueryCorpusWriter")
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
    layout.projectDirectory.dir("src/jmh/resources/viaduct/engine/runtime2/benchmark/property-test")

tasks.register<JavaExec>("generatePropertyTestBenchmarkCorpus") {
    group = "benchmark"
    description = "Snapshots the historical Resolution property-test benchmark case."
    dependsOn("supportClasses")
    classpath = sourceSets["support"].runtimeClasspath + files(sourceSets["test"].resources.srcDirs)
    mainClass.set("viaduct.engine.runtime2.resolution.PropertyTestBenchmarkCorpusWriter")
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
                    "viaduct\\.engine\\.runtime2\\.$resolver\\.ResolverBenchmark\\.$benchmark",
                    "-p",
                    "loopCount=${resolverBenchmarkLoopCount.get()}",
                ) + reportArguments
        }
        doLast {
            if (benchmark == "overhead") {
                logger.lifecycle("")
                logger.lifecycle(statisticsFile.get().asFile.readText())
            }
        }
    }
}

listOf("resolution").forEach { resolver ->
    registerResolverBenchmarkTask(resolver, "full")
    registerResolverBenchmarkTask(resolver, "overhead")
}

tasks.register<JavaExec>("resolutionOverheadProfile") {
    group = "benchmark"
    description = "Profiles only a measured Resolution fixed-corpus overhead iteration with JFR."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("loopCount", resolverBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        val profileFile = resolutionOverheadProfileOutput.get().absoluteFile
        profileFile.parentFile.mkdirs()
        profileFile.delete()
        args =
            listOf(
                "viaduct\\.engine\\.runtime2\\.resolution\\.ResolverBenchmark\\.overhead",
                "-p",
                "loopCount=${resolverBenchmarkLoopCount.get()}",
                "-wi",
                "1",
                "-i",
                "1",
                "-f",
                "1",
                "-jvmArgsAppend",
                "-DresolutionOverheadProfileOutput=${profileFile.absolutePath} " +
                    "-XX:FlightRecorderOptions=stackdepth=256",
            )
    }

    doLast {
        logger.lifecycle("")
        logger.lifecycle("Resolution overhead JFR: ${resolutionOverheadProfileOutput.get().absolutePath}")
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
                "viaduct\\.engine\\.runtime2\\.correctresolution\\.CorrectResolutionBenchmark\\.correctResolution",
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
                "viaduct\\.engine\\.runtime2\\.correctresolution\\.CorrectResolutionBenchmark\\.correctResolution",
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
        logger.lifecycle("")
        logger.lifecycle("CorrectResolution JFR: ${correctResolutionProfileOutput.get().absolutePath}")
    }
}

tasks.register<JavaExec>("propertyTestBenchmark") {
    group = "benchmark"
    description = "Benchmarks one frozen Resolution property-test case and all of its oracles."
    val benchmarkJar = tasks.named<org.gradle.jvm.tasks.Jar>("jmhJar")
    dependsOn(benchmarkJar)
    classpath = files(benchmarkJar.flatMap { jar -> jar.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    inputs.property("loopCount", propertyTestBenchmarkLoopCount)
    outputs.upToDateWhen { false }

    doFirst {
        args =
            listOf(
                "viaduct\\.engine\\.runtime2\\.resolution\\.PropertyTestBenchmark\\.propertyTest",
                "-p",
                "loopCount=${propertyTestBenchmarkLoopCount.get()}",
            )
    }
}

tasks.register<JavaExec>("propertyTestProfile") {
    group = "benchmark"
    description = "Profiles one measured frozen Resolution property-test case with JFR."
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
                "viaduct\\.engine\\.runtime2\\.resolution\\.PropertyTestBenchmark\\.propertyTest",
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
        logger.lifecycle("")
        logger.lifecycle("Property-test JFR: ${propertyTestProfileOutput.get().absolutePath}")
    }
}

val stressResolverNames =
    listOf(
        "resolver03",
        "resolver08",
        "resolver23",
        "resolution",
    )

fun resolverStressTestClass(resolverName: String): String =
    if (resolverName == "resolution") {
        "viaduct.engine.runtime2.resolution.ResolverStressTest"
    } else {
        "viaduct.engine.runtime2.resolvers.$resolverName.ResolverStressTest"
    }

val configuredResolutionThreadCount =
    providers
        .gradleProperty("viaduct.resolution.threadcount")
        .orElse(providers.systemProperty("viaduct.resolution.threadcount"))
        .orElse(providers.environmentVariable("viaduct.resolution.threadcount"))

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val resolutionThreadCount =
        configuredResolutionThreadCount.orElse(
            if (name == "resolutionMultithreadedStress") "100" else "1",
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

tasks.named<Test>("test") {
    maxHeapSize = "2g"
    maxParallelForks =
        providers.gradleProperty("runtime2TestForks")
            .map { configured ->
                requireNotNull(configured.toIntOrNull()?.takeIf { it > 0 }) {
                    "runtime2TestForks must be a positive integer: $configured"
                }
            }
            .orElse(
                provider {
                    val runtime = Runtime.getRuntime()
                    val cpuLimit = (runtime.availableProcessors() / 2).coerceAtLeast(1)
                    val totalMemory =
                        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                            ?.totalMemorySize ?: Long.MAX_VALUE
                    // Reserve the Gradle heap and budget 2 GiB heap plus 1 GiB native memory per fork.
                    val memoryLimit = ((totalMemory - runtime.maxMemory()).coerceAtLeast(0L) / (3L shl 30)).coerceIn(1L, 4L).toInt()
                    minOf(4, cpuLimit, memoryLimit)
                },
            ).get()
    filter {
        stressResolverNames.forEach { resolverName ->
            excludeTestsMatching(resolverStressTestClass(resolverName))
        }
        excludeTestsMatching("viaduct.engine.runtime2.resolution.ResolverBroadStressTest")
        excludeTestsMatching("viaduct.engine.runtime2.resolution.ResolverBroadStressCampaignTest")
        excludeTestsMatching("viaduct.engine.runtime2.resolution.ResolverMultithreadedStressTest")
    }
}

tasks.register<JavaExec>("materializeGeneratorConfigs") {
    group = "verification"
    description = "Materializes complete Resolution generator-profile JSON files."
    dependsOn("supportClasses")
    classpath = sourceSets["support"].runtimeClasspath + files(sourceSets["test"].resources.srcDirs)
    mainClass.set("viaduct.engine.runtime2.propertytest.GeneratorConfigMaterializer")
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
            "src/test/resources/viaduct/engine/runtime2/property-tests/generator-configs",
        ),
    )
    outputs.upToDateWhen { false }
}

val propertyTestLauncherJar =
    tasks.register<Jar>("propertyTestLauncherJar") {
        dependsOn("supportClasses")
        archiveClassifier.set("property-test-launcher")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets["main"].output)
        from(sourceSets["support"].output)
        from(sourceSets["test"].resources)
        manifest {
            attributes["Main-Class"] =
                "viaduct.engine.runtime2.propertytest.PropertyTestRoundLauncher"
        }
    }

val propertyTestLauncherScripts =
    tasks.register<CreateStartScripts>("propertyTestLauncherScripts") {
        dependsOn(propertyTestLauncherJar)
        applicationName = "property-test-round"
        mainClass.set("viaduct.engine.runtime2.propertytest.PropertyTestRoundLauncher")
        outputDir = layout.buildDirectory.dir("property-test-launcher/scripts").get().asFile
        classpath =
            files(
                propertyTestLauncherJar,
                configurations["supportRuntimeClasspath"].filter(File::isFile),
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
    from(configurations["supportRuntimeClasspath"].filter(File::isFile))
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

tasks.named<Test>("test") {
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
        "resolution-field-checker-success" to "generated successful field checker worlds resolve correctly",
        "resolution-field-checker-denial" to "generated denying field checker worlds resolve correctly",
        "resolution-field-checker-mixed" to "generated mixed field checker worlds resolve correctly",
        "resolution-field-checker-passive" to "generated passive field checker worlds resolve correctly",
        "resolution-field-checker-root-reference" to "generated root-reference field checker worlds resolve correctly",
        "resolution-type-checker-success" to "generated successful type checker worlds resolve correctly",
        "resolution-type-checker-denial" to "generated denying type checker worlds resolve correctly",
        "resolution-type-checker-mixed" to "generated mixed type checker worlds resolve correctly",
        "resolver23-type-checker-success" to
            "generated successful type checker worlds resolve correctly",
        "resolver23-type-checker-denial" to
            "generated denying type checker worlds resolve correctly",
        "resolver23-type-checker-mixed" to
            "generated mixed type checker worlds resolve correctly",
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
        "resolution-broad-stress" to
            "broad full-feature worlds resolve correctly",
        "resolution-broad-descendant-variables" to
            "broad full-feature worlds resolve correctly",
        "resolution-broad-nullable-errors" to
            "broad full-feature worlds resolve correctly",
        "resolution-broad-symbolic-identity" to
            "broad full-feature worlds resolve correctly",
        "resolution-broad-multiple-owners" to
            "broad full-feature worlds resolve correctly",
        "resolution-root-field-references" to
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

// Keep the field-only entry point and add a default campaign spanning both checker kinds.
val resolver23CheckerStressProfiles =
    mapOf(
        "success" to ("resolver23-field-checker-success" to "generated successful field checker worlds resolve correctly"),
        "denial" to ("resolver23-field-checker-denial" to "generated denying field checker worlds resolve correctly"),
        "mixed" to ("resolver23-field-checker-mixed" to "generated mixed field checker worlds resolve correctly"),
        "type-success" to ("resolver23-type-checker-success" to "generated successful type checker worlds resolve correctly"),
        "type-denial" to ("resolver23-type-checker-denial" to "generated denying type checker worlds resolve correctly"),
        "type-mixed" to ("resolver23-type-checker-mixed" to "generated mixed type checker worlds resolve correctly"),
    )

fun registerResolver23CheckerStressTask(
    taskName: String,
    propertyStem: String,
    defaultProfile: String
) {
    val systemStem = if (propertyStem == "resolver23FieldCheckerStress") "resolver23.field.checker.stress" else "resolver23.access.checker.stress"
    val environmentStem = if (propertyStem == "resolver23FieldCheckerStress") "RESOLVER23_FIELD_CHECKER_STRESS" else "RESOLVER23_ACCESS_CHECKER_STRESS"

    fun setting(name: String) =
        providers.gradleProperty("$propertyStem$name")
            .orElse(providers.systemProperty("$systemStem.${name.lowercase()}"))
            .orElse(providers.environmentVariable("${environmentStem}_${name.uppercase()}"))
    val seedSetting = setting("Seed")
    val profileSetting = setting("Profile").orElse(defaultProfile)
    val sizeSetting = setting("Size").orElse("50:5:10")
    tasks.register<org.gradle.api.tasks.testing.Test>(taskName) {
        group = "verification"
        description = "Runs replayable Resolver23 access-check profiles (2,500 cases per profile by default)."
        maxHeapSize = "2g"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
        doFirst {
            val seed = seedSetting.orNull ?: throw GradleException("Set -P${propertyStem}Seed=<long>")
            seed.toLongOrNull() ?: throw GradleException("${propertyStem}Seed must be a Long: $seed")
            val selected = profileSetting.get()
            val profiles = if (selected == "all") {
                resolver23CheckerStressProfiles.values
            } else {
                listOf(
                    resolver23CheckerStressProfiles[selected] ?: throw GradleException(
                        "Unknown ${propertyStem}Profile $selected; profiles=all," + resolver23CheckerStressProfiles.keys.joinToString(),
                    )
                )
            }
            profiles.forEach { (_, method) ->
                filter.includeTestsMatching("viaduct.engine.runtime2.resolvers.resolver23.ResolverGeneratedTest.$method")
            }
            systemProperty("resolver.property.seed", seed)
            systemProperty("kotest.proptest.default.seed", seed)
            if (selected == "all") {
                systemProperties.remove("resolver.property.profile")
            } else {
                systemProperty("resolver.property.profile", profiles.single().first)
            }
            systemProperty("resolver.property.case", "all")
            systemProperty("resolver.property.size", sizeSetting.get())
        }
    }
}

registerResolver23CheckerStressTask("resolver23FieldCheckerStress", "resolver23FieldCheckerStress", "success")
registerResolver23CheckerStressTask("resolver23AccessCheckerStress", "resolver23AccessCheckerStress", "all")

val resolutionBroadStressSize =
    providers
        .gradleProperty("resolutionBroadStressSize")
        .orElse(providers.systemProperty("resolution.broad.stress.size"))
        .orElse(providers.environmentVariable("RESOLUTION_BROAD_STRESS_SIZE"))
val resolutionBroadStressSeed =
    providers
        .gradleProperty("resolutionBroadStressSeed")
        .orElse(providers.systemProperty("resolution.broad.stress.seed"))
        .orElse(providers.environmentVariable("RESOLUTION_BROAD_STRESS_SEED"))
val resolutionBroadStressProfile =
    providers
        .gradleProperty("resolutionBroadStressProfile")
        .orElse(providers.systemProperty("resolution.broad.stress.profile"))
        .orElse(providers.environmentVariable("RESOLUTION_BROAD_STRESS_PROFILE"))
        .orElse("balanced")
val resolutionBroadStressProfiles =
    mapOf(
        "balanced" to Pair("resolution-broad-stress", "10:20:50"),
        "descendant-variables" to
            Pair("resolution-broad-descendant-variables", "40:25:10"),
        "nullable-errors" to Pair("resolution-broad-nullable-errors", "10:20:50"),
        "symbolic-identity" to Pair("resolution-broad-symbolic-identity", "10:20:50"),
        "multiple-owners" to Pair("resolution-broad-multiple-owners", "10:50:20"),
    )

val resolutionParentFocusedSeed =
    providers
        .gradleProperty("resolutionParentFocusedSeed")
        .orElse(providers.systemProperty("resolution.parent.focused.seed"))
        .orElse(providers.environmentVariable("RESOLUTION_PARENT_FOCUSED_SEED"))
        .orElse("2026090403")

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionParentFocused") {
    group = "verification"
    description = "Runs four 250-case slices of the parent-focused Resolution property."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "viaduct.engine.runtime2.resolution.ResolverBroadStressTest." +
                "parent focused randomized worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed = resolutionParentFocusedSeed.get()
        seed.toLongOrNull()
            ?: throw GradleException("resolutionParentFocusedSeed must be a Long: $seed")
        systemProperty("resolution.broad.stress.seed", seed)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", "resolution-parent-fields")
        systemProperty("resolver.property.size", "40:5:5")
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

val resolutionRootFieldReferenceFocusedSeed =
    providers
        .gradleProperty("resolutionRootFieldReferenceFocusedSeed")
        .orElse(providers.systemProperty("resolution.root.field.reference.focused.seed"))
        .orElse(providers.environmentVariable("RESOLUTION_ROOT_FIELD_REFERENCE_FOCUSED_SEED"))
        .orElse("2026091001")

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionRootFieldReferenceFocused") {
    group = "verification"
    description = "Runs the hard-coverage root-field-reference Resolution property."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "viaduct.engine.runtime2.resolution.ResolverBroadStressTest." +
                "root field reference focused randomized worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val seed = resolutionRootFieldReferenceFocusedSeed.get()
        seed.toLongOrNull()
            ?: throw GradleException(
                "resolutionRootFieldReferenceFocusedSeed must be a Long: $seed",
            )
        systemProperty("resolution.broad.stress.seed", seed)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", "resolution-root-field-references")
        systemProperty("resolver.property.size", "10:5:5")
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionBroadStress") {
    group = "verification"
    description = "Runs every case in a seeded broad Resolution generated product."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(
            "viaduct.engine.runtime2.resolution.ResolverBroadStressTest." +
                "broad full-feature worlds resolve correctly",
        )
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val profile = resolutionBroadStressProfile.get()
        val profileConfiguration =
            resolutionBroadStressProfiles[profile]
                ?: throw GradleException(
                    "Unknown resolutionBroadStressProfile $profile; profiles=" +
                        resolutionBroadStressProfiles.keys.sorted().joinToString(),
                )
        val size = resolutionBroadStressSize.orNull ?: profileConfiguration.second
        require(size.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*"""))) {
            "resolutionBroadStressSize must have S:R:Q form with positive integers: $size"
        }
        val seed =
            resolutionBroadStressSeed.orNull
                ?: throw GradleException(
                    "Set -PresolutionBroadStressSeed=<long>, " +
                        "-Dresolution.broad.stress.seed=<long>, or " +
                        "RESOLUTION_BROAD_STRESS_SEED=<long>",
                )
        seed.toLongOrNull()
            ?: throw GradleException("resolutionBroadStressSeed must be a Long: $seed")

        systemProperty("resolution.broad.stress.profile", profile)
        systemProperty("resolution.broad.stress.size", size)
        systemProperty("resolution.broad.stress.seed", seed)
        systemProperty("resolver.property.size", size)
        systemProperty("resolver.property.seed", seed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.profile", profileConfiguration.first)
        systemProperty("kotest.proptest.default.seed", seed)
    }
}

val resolutionBroadStressCampaignRound =
    providers
        .gradleProperty("resolutionBroadStressCampaignRound")
        .orElse(providers.systemProperty("resolution.broad.campaign.round"))
val resolutionBroadStressCampaignProfile =
    providers
        .gradleProperty("resolutionBroadStressCampaignProfile")
        .orElse(providers.systemProperty("resolution.broad.campaign.profile"))
val resolutionBroadStressCampaignProfiles = resolutionBroadStressProfiles.keys

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionBroadStressCampaign") {
    group = "verification"
    description = "Runs one recorded five-profile Resolution broad-stress campaign round."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("viaduct.engine.runtime2.resolution.ResolverBroadStressCampaignTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        val round =
            resolutionBroadStressCampaignRound.orNull
                ?: throw GradleException(
                    "Set -PresolutionBroadStressCampaignRound=<1..100>",
                )
        val roundNumber =
            round.toIntOrNull()
                ?: throw GradleException(
                    "resolutionBroadStressCampaignRound must be an integer: $round",
                )
        require(roundNumber in 1..100) {
            "resolutionBroadStressCampaignRound must be in 1..100: $round"
        }
        val profile = resolutionBroadStressCampaignProfile.orNull
        require(profile == null || profile in resolutionBroadStressCampaignProfiles) {
            "Unknown resolutionBroadStressCampaignProfile $profile; profiles=" +
                resolutionBroadStressCampaignProfiles.sorted().joinToString()
        }
        val case = resolverPropertyReplayCase.get()
        require(
            case.equals("all", ignoreCase = true) ||
                case.matches(Regex("""[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*""")),
        ) {
            "resolverPropertyCase must be all or S:R:Q with positive integers: $case"
        }
        require(case.equals("all", ignoreCase = true) || profile != null) {
            "Set -PresolutionBroadStressCampaignProfile=<profile> for coordinate replay"
        }

        systemProperty("resolution.broad.campaign.round", round)
        profile?.let {
            systemProperty("resolution.broad.campaign.profile", it)
        }
        systemProperty("resolver.property.case", case)
    }
}

val resolutionMultithreadedStressSize =
    providers
        .gradleProperty("resolutionMultithreadedStressSize")
        .orElse("campaign")
val resolutionMultithreadedStressRounds =
    providers
        .gradleProperty("resolutionMultithreadedStressRounds")
        .orElse("1")

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionMultithreadedStress") {
    group = "verification"
    description = "Runs broad and mixed field/type-checker Resolution profiles on a fixed multithreaded dispatcher."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("viaduct.engine.runtime2.resolution.ResolverMultithreadedStressTest")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }

    doFirst {
        // The inherited checker contracts retain the same seed/size controls as type-checker stress.
        val checkerSeed = providers.gradleProperty("resolutionTypeCheckerStressSeed").orElse("2026093001").get().toLong()
        systemProperty("resolver.property.seed", checkerSeed)
        systemProperty("kotest.proptest.default.seed", checkerSeed)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.size", providers.gradleProperty("resolutionTypeCheckerStressSize").orElse("50:5:10").get())
        systemProperty(
            "resolution.multithreaded.size",
            resolutionMultithreadedStressSize.get(),
        )
        systemProperty(
            "resolution.multithreaded.rounds",
            resolutionMultithreadedStressRounds.get(),
        )
    }
}

// Runtime field checks have their own replayable workload; checker-free broad campaigns stay unchanged.
tasks.register<org.gradle.api.tasks.testing.Test>("resolutionFieldCheckerStress") {
    group = "verification"
    description = "Runs generated Resolution runtime field checks with exact checker application accounting."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    doFirst {
        val seed = providers.gradleProperty("resolutionFieldCheckerStressSeed").get().toLong()
        val profile = "resolution-field-checker-" + providers.gradleProperty("resolutionFieldCheckerStressProfile").orElse("success").get()
        val method = resolverPropertyProfiles[profile] ?: throw GradleException("Unknown field-checker profile $profile")
        filter.includeTestsMatching("viaduct.engine.runtime2.resolution.FieldCheckerGeneratedTest.$method")
        systemProperty("resolver.property.profile", profile)
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.size", providers.gradleProperty("resolutionFieldCheckerStressSize").orElse("50:5:10").get())
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("resolutionTypeCheckerStress") {
    group = "verification"
    description = "Runs generated Resolution runtime type checks with exact checker application accounting."
    maxHeapSize = "2g"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    doFirst {
        val seed = providers.gradleProperty("resolutionTypeCheckerStressSeed").get().toLong()
        val selected = providers.gradleProperty("resolutionTypeCheckerStressProfile").orElse("all").get()
        val profiles = if (selected == "all") listOf("success", "denial", "mixed") else listOf(selected)
        profiles.forEach { suffix ->
            val profile = "resolution-type-checker-$suffix"
            val method = resolverPropertyProfiles[profile] ?: throw GradleException("Unknown type-checker profile $profile")
            filter.includeTestsMatching("viaduct.engine.runtime2.resolution.TypeCheckerGeneratedTest.$method")
        }
        if (selected == "all") {
            systemProperties.remove("resolver.property.profile")
        } else {
            systemProperty("resolver.property.profile", "resolution-type-checker-$selected")
        }
        systemProperty("resolver.property.case", "all")
        systemProperty("resolver.property.seed", seed)
        systemProperty("kotest.proptest.default.seed", seed)
        systemProperty("resolver.property.size", providers.gradleProperty("resolutionTypeCheckerStressSize").orElse("50:5:10").get())
    }
}
