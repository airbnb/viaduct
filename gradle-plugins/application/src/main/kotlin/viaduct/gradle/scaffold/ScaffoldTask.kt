package viaduct.gradle.scaffold

import java.io.File
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import viaduct.codegen.st.STContents

/**
 * Gradle task that scaffolds a new Viaduct project.
 *
 * Usage:
 * ```
 * ./gradlew scaffold -PpackagePrefix=com.example.myapp -PoutputDir=./my-app
 * ```
 */
abstract class ScaffoldTask : DefaultTask() {
    @get:Input
    abstract val packagePrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Optional
    @get:Input
    abstract val viaductVersion: Property<String>

    init {
        group = "viaduct"
        description = "Scaffold a new Viaduct project"
        gradleVersion.convention("8.12")
    }

    /**
     * Gets the Viaduct plugin version from the JAR manifest.
     * Returns null if the manifest is not available.
     */
    private fun getPluginVersion(): String? {
        val manifest = ScaffoldTask::class.java.getResourceAsStream("/META-INF/MANIFEST.MF")
            ?: return null
        val props = java.util.jar.Manifest(manifest)
        return props.mainAttributes.getValue("Implementation-Version")
    }

    companion object {
        private val AGENT_DOCS = listOf(
            "batch.md",
            "field-resolver.md",
            "mutations.md",
            "node-type.md",
            "query-resolver.md",
            "relationships.md",
            "scopes.md"
        )
    }

    @TaskAction
    fun scaffold() {
        val prefix = packagePrefix.get()
        require(prefix.isNotBlank()) { "packagePrefix must not be blank" }
        require(prefix.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$"))) {
            "packagePrefix must be a valid Java package name (e.g., com.example.myapp)"
        }

        // Resolve viaductVersion at execution time if not explicitly set
        val resolvedViaductVersion = if (viaductVersion.isPresent) {
            viaductVersion.get()
        } else {
            getPluginVersion()
                ?: error("Cannot determine Viaduct version. Specify -PviaductVersion=<version>")
        }

        val model = ProjectModel(
            packagePrefix = prefix,
            gradleVersion = gradleVersion.get(),
            viaductVersion = resolvedViaductVersion
        )

        val outDir = outputDir.get().asFile
        logger.lifecycle("Scaffolding Viaduct project to: {}", outDir.absolutePath)
        logger.lifecycle("Package prefix: {}", prefix)

        // Create directory structure
        val srcDir = File(outDir, "src/main/kotlin/${model.packagePath}")
        val ktorPluginsDir = File(srcDir, "ktorplugins")
        val resolversDir = File(srcDir, "resolvers")
        val resourcesDir = File(outDir, "src/main/resources")
        val schemaDir = File(outDir, "src/main/viaduct/schema")

        listOf(srcDir, ktorPluginsDir, resolversDir, resourcesDir, schemaDir).forEach { it.mkdirs() }

        // Generate source files using STContents
        STContents(Templates.mainKt, model).write(File(srcDir, "Main.kt"))
        STContents(Templates.contentNegotiationKt, model).write(File(ktorPluginsDir, "ContentNegotiation.kt"))
        STContents(Templates.routingKt, model).write(File(ktorPluginsDir, "Routing.kt"))
        STContents(Templates.schemaGraphqls, model).write(File(schemaDir, "schema.graphqls"))
        STContents(Templates.greetingResolverKt, model).write(File(resolversDir, "GreetingResolver.kt"))
        STContents(Templates.applicationConf, model).write(File(resourcesDir, "application.conf"))
        STContents(Templates.buildGradleKts, model).write(File(outDir, "build.gradle.kts"))
        STContents(Templates.settingsGradleKts, model).write(File(outDir, "settings.gradle.kts"))
        STContents(Templates.gradleProperties, model).write(File(outDir, "gradle.properties"))

        // Install Viaduct skill documentation (creates AGENTS.md and .viaduct/agents/)
        installViaductDocs(outDir, model.projectName)

        // Generate Gradle wrapper
        generateGradleWrapper(outDir, model.gradleVersion)

        logger.lifecycle("")
        logger.lifecycle("Project scaffolded successfully!")
        logger.lifecycle("")
        logger.lifecycle("Next steps:")
        logger.lifecycle("  cd {}", outDir.name)
        logger.lifecycle("  ./gradlew run")
        logger.lifecycle("")
        logger.lifecycle("Then open http://localhost:8080/graphiql in your browser")
    }

    private fun generateGradleWrapper(
        projectDir: File,
        gradleVersion: String
    ) {
        logger.lifecycle("Generating Gradle wrapper (version {})...", gradleVersion)

        // Check if gradle is available
        val gradleExecutable = findGradleExecutable()
        if (gradleExecutable == null) {
            logger.warn("Gradle not found in PATH. Skipping wrapper generation.")
            logger.warn("Run 'gradle wrapper --gradle-version {}' manually in the project directory.", gradleVersion)
            return
        }

        val processBuilder = ProcessBuilder(
            gradleExecutable,
            "wrapper",
            "--gradle-version",
            gradleVersion
        )
        processBuilder.directory(projectDir)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            logger.warn("Failed to generate Gradle wrapper (exit code: {})", exitCode)
            logger.warn(output)
            logger.warn("Run 'gradle wrapper --gradle-version {}' manually in the project directory.", gradleVersion)
        } else {
            logger.info("Gradle wrapper generated successfully")
        }
    }

    private fun findGradleExecutable(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradleName = if (isWindows) "gradle.bat" else "gradle"

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val gradle = File(dir, gradleName)
            if (gradle.exists() && gradle.canExecute()) {
                return gradle.absolutePath
            }
        }

        return null
    }

    /**
     * Installs Viaduct skill documentation from bundled resources.
     * Creates AGENTS.md and .viaduct/agents/ directory with skill docs.
     */
    private fun installViaductDocs(
        projectDir: File,
        projectName: String
    ) {
        logger.lifecycle("Installing Viaduct skill documentation...")

        // Create .viaduct/agents/ directory
        val agentsDir = File(projectDir, ".viaduct/agents")
        agentsDir.mkdirs()

        // Copy agent docs from resources
        for (doc in AGENT_DOCS) {
            val resource = ScaffoldTask::class.java.getResourceAsStream("/scaffold/agents/$doc")
            if (resource != null) {
                File(agentsDir, doc).writeText(resource.bufferedReader().readText())
                logger.info("  Installed {}", doc)
            } else {
                logger.warn("  Missing resource: {}", doc)
            }
        }

        // Generate AGENTS.md from template
        val template = ScaffoldTask::class.java.getResourceAsStream("/scaffold/AGENTS.md.template")
        if (template != null) {
            val content = template.bufferedReader().readText()
                .replace("\$projectName\$", projectName)
            File(projectDir, "AGENTS.md").writeText(content)
            logger.info("  Created AGENTS.md")

            // Create CLAUDE.md as symlink to AGENTS.md
            val claudeMd = File(projectDir, "CLAUDE.md").toPath()
            Files.createSymbolicLink(claudeMd, File("AGENTS.md").toPath())
            logger.info("  Created CLAUDE.md -> AGENTS.md symlink")
        }

        // Add .viaduct/agents/ to .gitignore
        val gitignore = File(projectDir, ".gitignore")
        val gitignoreEntry = ".viaduct/agents/"
        if (gitignore.exists()) {
            val content = gitignore.readText()
            if (!content.contains(gitignoreEntry)) {
                gitignore.appendText("\n# Viaduct docs (generated)\n$gitignoreEntry\n")
            }
        } else {
            gitignore.writeText("# Viaduct docs (generated)\n$gitignoreEntry\n")
        }
    }
}
