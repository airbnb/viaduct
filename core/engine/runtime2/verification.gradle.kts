import java.util.zip.ZipFile

val sourceSets = extensions.getByType<SourceSetContainer>()
val main = sourceSets["main"]
val testFixtures = sourceSets["testFixtures"]
val jmh = sourceSets["jmh"]

val checkPackageLayers = tasks.register("checkPackageLayers") {
    group = "verification"
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val rootPackage = "viaduct.engine.runtime2"
        val reference = Regex("""\bviaduct\.engine\.runtime2\.([\w.]+)""")
        val errors = sources.flatMap { source ->
            val text = source.readText()
            val owner = Regex("""(?m)^package ([\w.]+)""").find(text)!!.groupValues[1]
            val forbidden = when {
                owner.startsWith("$rootPackage.model") -> listOf("resolution", "execution", "bootstrap", "resolvers", "correctresolution", "contract", "benchmark")
                owner.startsWith("$rootPackage.schema") -> listOf("resolution", "execution", "bootstrap", "resolvers", "correctresolution", "contract", "benchmark")
                owner.startsWith("$rootPackage.resolution.framework") -> listOf("execution", "bootstrap", "resolvers", "correctresolution", "contract", "benchmark")
                owner.startsWith("$rootPackage.resolution") -> listOf("execution", "bootstrap", "resolvers", "correctresolution", "contract", "benchmark")
                else -> emptyList()
            }
            reference.findAll(text).map { it.groupValues[1] }.filter { dependency ->
                forbidden.any { dependency == it || dependency.startsWith("$it.") } ||
                    (
                        owner.startsWith(
                            "$rootPackage.resolution.framework"
                        ) && dependency.startsWith("resolution.") && dependency != "resolution.framework" && !dependency.startsWith("resolution.framework.")
                    )
            }.map { "${source.relativeTo(projectDir)} -> $rootPackage.$it" }.toList()
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }
}

val checkProductionBoundary = tasks.register("checkProductionBoundary") {
    group = "verification"
    val jar = tasks.named<Jar>("jar")
    val inventory = layout.buildDirectory.file("reports/test-fixtures-output-inventory.txt")
    dependsOn(jar, testFixtures.classesTaskName, "jmhClasses")
    inputs.files(jar.flatMap { it.archiveFile }, testFixtures.output)
    outputs.file(inventory)
    outputs.upToDateWhen { false }
    doLast {
        val productionFiles = main.compileClasspath.files + main.runtimeClasspath.files
        check(productionFiles.intersect(testFixtures.output.files).isEmpty()) { "Production consumes test fixture output" }
        check(jmh.compileClasspath.files.intersect(sourceSets["test"].output.files).isEmpty()) { "JMH consumes test output" }
        check(jmh.runtimeClasspath.files.intersect(sourceSets["test"].output.files).isEmpty()) { "JMH runtime consumes test output" }
        check(configurations.none { it.isCanBeConsumed && it.name.startsWith("testFixtures") }) {
            "Development fixtures must not be published"
        }
        listOf("compileClasspath", "runtimeClasspath", "testFixturesCompileClasspath", "testFixturesRuntimeClasspath").forEach { name ->
            val components = configurations[name].incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            check(components.none { it.group.startsWith("org.junit") }) { "$name contains JUnit" }
            if (!name.startsWith("testFixtures")) {
                check(components.none { it.name.startsWith("kotest-property") || it.name.startsWith("kotest-assertions") || it.group == "org.openjdk.jmh" || it.name.startsWith("kotlin-test") }) {
                    "$name contains development-only dependencies"
                }
                check(
                    configurations[name].incoming.artifacts.artifacts.none { artifact ->
                        artifact.variant.capabilities.any { it.name.endsWith("-test-fixtures") }
                    }
                ) { "$name contains exported test fixtures" }
            }
        }
        val testFixtureEntries = testFixtures.output.files.filter(File::isDirectory).flatMap { directory ->
            directory.walkTopDown().filter(File::isFile).map { it.relativeTo(directory).invariantSeparatorsPath }.toList()
        }.toSortedSet()
        ZipFile(jar.get().archiveFile.get().asFile).use { archive ->
            val leaked = archive.entries().asSequence().map { it.name }.filter { it in testFixtureEntries }.toList()
            check(leaked.isEmpty()) { "Test fixture output in production JAR: $leaked" }
        }
        inventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(testFixtureEntries.joinToString("\n", postfix = "\n"))
        }
        logger.lifecycle("Runtime2 production boundary verified; ${testFixtureEntries.size} test fixture entries excluded.")
    }
}

tasks.named("check") { dependsOn(checkPackageLayers, checkProductionBoundary) }
